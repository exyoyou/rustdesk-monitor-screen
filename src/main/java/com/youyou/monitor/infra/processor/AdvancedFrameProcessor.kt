package youyou.monitor.screen.infra.processor

import android.graphics.Bitmap
import youyou.monitor.screen.core.domain.model.ImageFrame
import youyou.monitor.screen.core.domain.repository.ConfigRepository
import youyou.monitor.screen.core.domain.repository.StorageRepository
import youyou.monitor.screen.core.matcher.TemplateMatcherManager
import youyou.monitor.screen.infra.logger.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.imgproc.Imgproc
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 高级帧处理器 - 迁移自 ScreenMonitor.kt
 */
class AdvancedFrameProcessor(
    private val configRepository: ConfigRepository,
    private val storageRepository: StorageRepository,
    private val templateMatcherManager: TemplateMatcherManager
) {
    private val TAG = "AdvancedFrameProcessor"

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @Volatile
    private var cachedConfig: youyou.monitor.screen.core.domain.model.MonitorConfig? = null

    init {
        scope.launch {
            try {
                configRepository.getConfigFlow().collect { config ->
                    cachedConfig = config
                    Log.d(TAG, "配置已更新：每秒检测次数=${config.detectPerSecond}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "收集配置时出错：${e.message}", e)
            }
        }
    }

    private val lastDetectTime = AtomicLong(0L)
    private val lastForceSaveTime = AtomicLong(0L)
    private val lastFrameSignature = AtomicLong(0L)
    private val lastMatchTime = AtomicLong(0L)
    private val frameCallCount = AtomicLong(0L)
    private val lastLogTime = AtomicLong(0L)
    private val isProcessing = AtomicBoolean(false)
    private var running = true

    val isBusy: Boolean
        get() = isProcessing.get()

    private val timestampFormat = ThreadLocal.withInitial {
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    }

    companion object {
        private const val FORCE_SAVE_INTERVAL = 30 * 60 * 1000L
        private const val LOG_INTERVAL = 10000L
        private const val MIN_STDDEV = 5.0
    }

    suspend fun onFrameAvailable(frame: ImageFrame): Boolean = withContext(Dispatchers.IO) {
        val started = tryBeginProcessing(frame)
        if (!started) return@withContext false

        val now = System.currentTimeMillis()
        val cfg = cachedConfig ?: youyou.monitor.screen.core.domain.model.MonitorConfig.default()

        try {
            processFrameInternal(frame, now, cfg)
            true
        } finally {
            isProcessing.set(false)
        }
    }

    fun canProcessNow(): Boolean {
        if (!running) return false
        if (isProcessing.get()) return false
        val config = cachedConfig ?: youyou.monitor.screen.core.domain.model.MonitorConfig.default()
        val interval = if (config.detectPerSecond > 0) 1000 / config.detectPerSecond else 500
        return System.currentTimeMillis() - lastDetectTime.get() >= interval
    }

    suspend fun tryBeginProcessing(frame: ImageFrame): Boolean = withContext(Dispatchers.IO) {
        val count = frameCallCount.incrementAndGet()

        if (isProcessing.get()) {
            if (count % 50L == 0L) {
                Log.d(TAG, "[跳过] 上一帧仍在处理中")
            }
            return@withContext false
        }

        val now = System.currentTimeMillis()

        if (now - lastLogTime.get() > LOG_INTERVAL) {
            Log.i(TAG, "[统计] 总调用次数: $count, 运行中: $running, 正在处理: ${isProcessing.get()}")
            lastLogTime.set(now)
        }

        val config = cachedConfig ?: youyou.monitor.screen.core.domain.model.MonitorConfig.default()
        val interval = if (config.detectPerSecond > 0) 1000 / config.detectPerSecond else 500
        if (now - lastDetectTime.get() < interval) {
            if (count % 100L == 0L) {
                Log.d(TAG, "[跳过] 频率限制: ${now - lastDetectTime.get()}ms < ${interval}ms")
            }
            return@withContext false
        }

        if (!running) {
            Log.w(TAG, "[跳过] 处理器未运行")
            return@withContext false
        }

        val signature = calculateFrameSignature(frame)
        if (signature == null) {
            Log.e(TAG, "[跳过] 签名计算失败")
            return@withContext false
        }

        if (signature == lastFrameSignature.get()) {
            if (count % 50L == 0L) {
                Log.d(TAG, "[跳过] 重复帧 (签名: $signature)")
            }
            return@withContext false
        }

        lastDetectTime.set(now)
        lastFrameSignature.set(signature)
        isProcessing.set(true)

        Log.d(TAG, "[开始] 已获取处理权限: ${frame.width}x${frame.height}, 签名=$signature")
        true
    }

    private fun calculateFrameSignature(frame: ImageFrame): Long? {
        return try {
            val width = frame.width
            val height = frame.height
            val buffer = ByteBuffer.wrap(frame.data)

            val halfWidth = width / 2
            val halfHeight = height / 2
            val lastRow = height - 1
            val lastCol = width - 1

            var sig = 0L
            val pixels = intArrayOf(
                0, halfWidth, lastCol,
                halfHeight * width, halfHeight * width + halfWidth, halfHeight * width + lastCol,
                lastRow * width, lastRow * width + halfWidth, lastRow * width + lastCol
            )

            for (i in pixels.indices) {
                val offset = pixels[i] * 4
                if (offset + 2 < buffer.capacity()) {
                    val r = buffer.get(offset).toInt() and 0xFF
                    val g = buffer.get(offset + 1).toInt() and 0xFF
                    val b = buffer.get(offset + 2).toInt() and 0xFF
                    val gray = (r + g + b) / 3
                    sig = sig or (gray.toLong() shl (i * 7))
                }
            }
            sig
        } catch (e: Exception) {
            Log.e(TAG, "签名计算错误：${e.message}")
            null
        }
    }

    private suspend fun processFrameInternal(
        frame: ImageFrame,
        now: Long,
        config: youyou.monitor.screen.core.domain.model.MonitorConfig
    ) {
        var bmp: Bitmap? = null
        var mat: Mat? = null
        var resized: Mat? = null

        try {
            bmp = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
            val buf = ByteBuffer.wrap(frame.data)
            bmp.copyPixelsFromBuffer(buf)

            mat = Mat()
            Utils.bitmapToMat(bmp, mat)
            Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGBA2GRAY)

            val processedMat = mat

            if (!isValidImage(processedMat)) {
                Log.w(TAG, "帧为空白/单色，跳过")
                return
            }

            if (now - lastForceSaveTime.get() > FORCE_SAVE_INTERVAL) {
                saveBitmap(bmp, "forced")
                lastForceSaveTime.set(now)
                Log.i(TAG, "强制保存有效截图")
            }

            val timeSinceLastMatch = now - lastMatchTime.get()
            if (lastMatchTime.get() > 0 && timeSinceLastMatch < config.matchCooldownMs) {
                Log.d(TAG, "跳过匹配：在冷却期 (${timeSinceLastMatch}ms / ${config.matchCooldownMs}ms)")
                return
            }

            val matchResult = templateMatcherManager.getMatcher().match(processedMat, frame.scale)
            if (matchResult != null) {
                saveBitmap(bmp, matchResult.templateName)
                lastMatchTime.set(now)
                Log.i(TAG, "匹配已保存：${matchResult.templateName}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "processFrame 错误：${e.message}")
        } finally {
            try {
                resized?.release()
            } catch (e: Exception) {
                Log.e(TAG, "释放调整大小的 mat 时出错：${e.message}")
            }

            try {
                mat?.release()
            } catch (e: Exception) {
                Log.e(TAG, "释放 mat 时出错：${e.message}")
            }

            try {
                bmp?.recycle()
            } catch (e: Exception) {
                Log.e(TAG, "回收 bitmap 时出错：${e.message}")
            }
        }
    }

    private fun isValidImage(grayMat: Mat): Boolean {
        var roi: Mat? = null
        var mean: MatOfDouble? = null
        var stddev: MatOfDouble? = null

        return try {
            val startX = (grayMat.cols() * 0.1).toInt()
            val startY = (grayMat.rows() * 0.1).toInt()
            roi = grayMat.submat(
                startY,
                (grayMat.rows() * 0.9).toInt(),
                startX,
                (grayMat.cols() * 0.9).toInt()
            )

            mean = MatOfDouble()
            stddev = MatOfDouble()
            Core.meanStdDev(roi, mean, stddev)

            val stdVal = stddev.get(0, 0)[0]
            val isValid = stdVal >= MIN_STDDEV

            if (!isValid) {
                Log.d(TAG, "无效帧：标准差=$stdVal (太低)")
            }
            isValid
        } catch (e: Exception) {
            Log.e(TAG, "isValidImage 错误：${e.message}")
            false
        } finally {
            roi?.release()
            mean?.release()
            stddev?.release()
        }
    }

    private suspend fun saveBitmap(bmp: Bitmap, templateName: String) {
        try {
            val timestamp = timestampFormat.get()!!.format(Date())
            val nameNoExt = templateName.substringBeforeLast('.')
            val filename = "capture_${nameNoExt}_$timestamp.png"

            val result = storageRepository.saveScreenshot(bmp, filename)
            result.onSuccess {
                Log.i(TAG, "已保存：$it")
            }.onFailure {
                Log.e(TAG, "保存失败：${it.message}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "saveBitmap 错误：${e.message}")
        }
    }

    fun reset() {
        running = true
        lastDetectTime.set(0L)
        lastForceSaveTime.set(0L)
        lastFrameSignature.set(0L)
        lastMatchTime.set(0L)
        frameCallCount.set(0L)
        lastLogTime.set(0L)
        isProcessing.set(false)
        Log.d(TAG, "AdvancedFrameProcessor reset")
    }

    fun shutdown() {
        running = false
        timestampFormat.remove()
        scope.cancel()
        Log.d(TAG, "AdvancedFrameProcessor shutdown")
    }
}

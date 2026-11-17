package youyou.monitor.screen.infra.processor

import android.graphics.Bitmap
import youyou.monitor.screen.core.domain.model.ImageFrame
import youyou.monitor.config.model.MonitorConfig
import youyou.monitor.config.repository.ConfigRepository
import youyou.monitor.sync.storage.StorageRepository
import youyou.monitor.screen.core.matcher.TemplateMatcherManager
import youyou.monitor.logger.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.core.Core
import org.opencv.core.CvType
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

    private var scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @Volatile
    private var cachedConfig: MonitorConfig? = null

    private val scopeLock = Any()
    private var configJob: Job? = null

    init {
        startConfigCollection()
    }

    // shutdown() 会取消 scope，重新启动时必须重建 scope 并重新收集配置，否则 cachedConfig 永远停在旧值
    private fun startConfigCollection() {
        synchronized(scopeLock) {
            if (!scope.isActive) {
                scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            }
            if (configJob?.isActive == true) return
            configJob = scope.launch {
                try {
                    configRepository.getConfigFlow().collect { config ->
                        cachedConfig = config
                        Log.d(TAG, "配置已更新：每秒检测次数=${config.detectPerSecond}, recordEnabled=${config.recordEnabled}")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "收集配置时出错：${e.message}", e)
                }
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
    @Volatile
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
        private const val SIGNATURE_GRID = 16
        private const val FNV_OFFSET = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
        private const val FNV_PRIME = 0x100000001b3L
    }

    suspend fun onFrameAvailable(frame: ImageFrame, signature: Long): Boolean = withContext(Dispatchers.IO) {
        val started = tryBeginProcessing(signature)
        if (!started) return@withContext false

        val now = System.currentTimeMillis()
        val cfg = cachedConfig ?: MonitorConfig.default()

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
        val config = cachedConfig ?: MonitorConfig.default()
        // 总开关关闭时（录屏可能只是因为有人远程查看才开着）不做识别
        if (!config.recordEnabled) return false
        val interval = if (config.detectPerSecond > 0) 1000 / config.detectPerSecond else 500
        return System.currentTimeMillis() - lastDetectTime.get() >= interval
    }

    suspend fun tryBeginProcessing(signature: Long): Boolean = withContext(Dispatchers.IO) {
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

        val config = cachedConfig ?: MonitorConfig.default()
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

        if (signature == lastFrameSignature.get()) {
            if (count % 50L == 0L) {
                Log.d(TAG, "[跳过] 重复帧 (签名: $signature)")
            }
            return@withContext false
        }

        lastDetectTime.set(now)
        lastFrameSignature.set(signature)
        isProcessing.set(true)
        true
    }

    /** 和上次处理过的帧签名相同（画面没变），可以直接跳过 */
    fun isDuplicate(signature: Long): Boolean = signature == lastFrameSignature.get()

    /**
     * 帧签名：在画面上均匀取 SIGNATURE_GRID x SIGNATURE_GRID 个像素的 RGB 做 FNV-1a 哈希。
     * 直接读原始缓冲（绝对位置读取，不改变缓冲的 position），重复帧不必复制整屏数据。
     */
    fun computeSignature(buffer: ByteBuffer, width: Int, height: Int, rowStride: Int): Long {
        var hash = FNV_OFFSET
        val limit = buffer.limit()
        for (gy in 0 until SIGNATURE_GRID) {
            val y = (height - 1) * gy / (SIGNATURE_GRID - 1)
            for (gx in 0 until SIGNATURE_GRID) {
                val x = (width - 1) * gx / (SIGNATURE_GRID - 1)
                val offset = y * rowStride + x * 4
                if (offset + 2 >= limit) continue
                for (c in 0 until 3) {
                    hash = (hash xor (buffer.get(offset + c).toLong() and 0xFF)) * FNV_PRIME
                }
            }
        }
        return hash
    }

    private suspend fun processFrameInternal(
        frame: ImageFrame,
        now: Long,
        config: MonitorConfig
    ) {
        var bmp: Bitmap? = null
        var rgba: Mat? = null
        var mat: Mat? = null

        // 只有需要保存截图时才创建 Bitmap，大多数帧只做识别不保存
        fun bitmap(): Bitmap = bmp ?: Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888).also {
            it.copyPixelsFromBuffer(ByteBuffer.wrap(frame.data, 0, frame.width * frame.height * 4))
            bmp = it
        }

        try {
            // 直接用 RGBA 字节构造 Mat 再转灰度，省掉 Bitmap 和 bitmapToMat 两次整屏复制
            rgba = Mat(frame.height, frame.width, CvType.CV_8UC4)
            rgba.put(0, 0, frame.data, 0, frame.width * frame.height * 4)
            mat = Mat()
            Imgproc.cvtColor(rgba, mat, Imgproc.COLOR_RGBA2GRAY)
            rgba.release()
            rgba = null

            val processedMat = mat

            if (!isValidImage(processedMat)) {
                Log.w(TAG, "帧为空白/单色，跳过")
                return
            }

            if (now - lastForceSaveTime.get() > FORCE_SAVE_INTERVAL) {
                saveBitmap(bitmap(), "forced")
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
                saveBitmap(bitmap(), matchResult.templateName)
                lastMatchTime.set(now)
                Log.i(TAG, "匹配已保存：${matchResult.templateName}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "processFrame 错误：${e.message}")
        } finally {
            try {
                rgba?.release()
            } catch (e: Exception) {
                Log.e(TAG, "释放 rgba mat 时出错：${e.message}")
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
            val filename = "capture_${nameNoExt}_$timestamp.jpg"

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
        startConfigCollection()
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
        isProcessing.set(false)
        timestampFormat.remove()
        synchronized(scopeLock) { scope.cancel() }
        Log.d(TAG, "AdvancedFrameProcessor shutdown")
    }
}

package youyou.monitor.screen

import android.annotation.SuppressLint
import android.content.Context
import youyou.monitor.screen.core.domain.model.ImageFrame
import youyou.monitor.logger.Log
import youyou.monitor.screen.infra.processor.AdvancedFrameProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.component.inject
import org.opencv.android.OpenCVLoader
import java.nio.ByteBuffer

/**
 * 屏幕监控服务（Facade 模式 - 对外统一接口）
 */
class MonitorService private constructor(
    private val context: Context
) : KoinComponent {

    companion object {
        private const val TAG = "MonitorService"

        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: MonitorService? = null

        fun init(context: Context) {
            if (instance == null) {
                synchronized(this) {
                    if (instance == null) {
                        try {
                            Log.init(context)
                            Log.i(TAG, "日志系统初始化完成")

                            if (!OpenCVLoader.initDebug()) {
                                Log.e(TAG, "OpenCV初始化失败！")
                                throw RuntimeException("OpenCV initialization failed")
                            }
                            Log.i(TAG, "OpenCV初始化成功")

                            youyou.monitor.screen.di.initKoin(context)
                            instance = MonitorService(context.applicationContext)

                            Log.i(TAG, "MonitorService初始化成功")
                        } catch (e: Exception) {
                            Log.e(TAG, "初始化失败：${e.message}", e)
                            throw e
                        }
                    }
                }
            }
        }

        fun getInstance(): MonitorService {
            return instance ?: throw IllegalStateException(
                "MonitorService未初始化，请先调用init(context)。"
            )
        }
    }

    private val advancedFrameProcessor: AdvancedFrameProcessor by inject()

    @Volatile
    private var scope: CoroutineScope? = null

    @Volatile
    private var scopeJob: Job? = null

    private val scopeLock = Any()

    private fun getScope(): CoroutineScope {
        val currentScope = scope
        val currentJob = scopeJob
        if (currentScope != null && currentJob != null && currentJob.isActive) {
            return currentScope
        }

        return synchronized(scopeLock) {
            val existingScope = scope
            val existingJob = scopeJob

            if (existingScope != null && existingJob != null && existingJob.isActive) {
                existingScope
            } else {
                val newJob = SupervisorJob()
                val dispatcher = if (isRunning) Dispatchers.Main else Dispatchers.Default
                val newScope = CoroutineScope(dispatcher + newJob)
                scope = newScope
                scopeJob = newJob
                newScope
            }
        }
    }

    @Volatile
    private var isRunning = false

    @Volatile
    private var isProcessingFrame = false

    @Volatile
    private var frameBuffer: ByteArray? = null
    private val frameBufferLock = Any()

    init {
        Log.d(TAG, "MonitorService已初始化")
    }

    fun start() {
        Log.i(TAG, "=== MonitorService.start() 被调用 ===")

        synchronized(this) {
            if (isRunning) {
                Log.w(TAG, "已在运行中，跳过启动")
                return
            }
            isRunning = true
        }

        advancedFrameProcessor.reset()
        Log.d(TAG, "帧处理器已重置")
    }

    fun stop() {
        synchronized(this) {
            if (!isRunning) {
                Log.w(TAG, "已在停止状态，跳过停止操作")
                return
            }
            isRunning = false
        }

        scope?.cancel()
        scope = null
        scopeJob = null

        advancedFrameProcessor.shutdown()

        synchronized(frameBufferLock) {
            frameBuffer = null
        }

        try {
            get<youyou.monitor.screen.core.matcher.TemplateMatcherManager>().release()
        } catch (e: Exception) {
            Log.w(TAG, "释放TemplateMatcherManager失败: ${e.message}")
        }

        Log.shutdown()
    }

    fun onFrameAvailable(buffer: ByteBuffer, width: Int, height: Int, scale: Int = 1) {
        if (!isRunning) return

        if (isProcessingFrame) {
            return
        }

        if (!advancedFrameProcessor.canProcessNow()) return

        isProcessingFrame = true

        val data = try {
            val requiredSize = width * height * 4

            val array = synchronized(frameBufferLock) {
                if (frameBuffer == null || frameBuffer!!.size < requiredSize) {
                    frameBuffer = ByteArray(requiredSize)
                }
                frameBuffer!!
            }

            buffer.position(0)
            buffer.get(array, 0, requiredSize)
            array.copyOf(requiredSize)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy buffer: ${e.message}", e)
            isProcessingFrame = false
            return
        }

        try {
            getScope().launch {
                try {
                    val frame = ImageFrame(width, height, data, scale)
                    advancedFrameProcessor.onFrameAvailable(frame)
                } catch (e: Exception) {
                    Log.e(TAG, "Frame processing failed: ${e.message}", e)
                } finally {
                    isProcessingFrame = false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch frame processing: ${e.message}", e)
            isProcessingFrame = false
        }
    }

    fun getApplicationContext(): Context {
        return context
    }
}

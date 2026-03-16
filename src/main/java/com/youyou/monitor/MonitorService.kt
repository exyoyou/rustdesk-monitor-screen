package youyou.monitor.screen

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import youyou.monitor.screen.core.domain.model.ImageFrame
import youyou.monitor.screen.core.domain.model.MonitorConfig
import youyou.monitor.screen.core.domain.usecase.CleanStorageUseCase
import youyou.monitor.screen.core.domain.usecase.ManageTemplatesUseCase
import youyou.monitor.logger.Log
import youyou.monitor.screen.infra.network.WebDavClient
import youyou.monitor.screen.infra.processor.AdvancedFrameProcessor
import youyou.monitor.screen.core.domain.repository.ConfigRepository
import youyou.monitor.screen.infra.repository.ConfigRepositoryImpl
import youyou.monitor.screen.infra.repository.TemplateRepositoryImpl
import youyou.monitor.screen.infra.task.ScheduledTaskManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

        @Volatile
        private var instance: MonitorService? = null

        private var deviceIdProvider: (() -> String)? = null
        private var notifyRootDirPathProvider: (() -> String)? = null

        fun init(context: Context, deviceIdProvider: (() -> String)? = null) {
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
                            this.deviceIdProvider = deviceIdProvider
                            instance = MonitorService(context.applicationContext)

                            Log.i(
                                TAG,
                                "MonitorService初始化成功 (deviceIdProvider=${if (deviceIdProvider != null) "已提供" else "未提供"})"
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "初始化失败：${e.message}", e)
                            throw e
                        }
                    }
                }
            }
        }

        fun setNotifyRootDirPathProvider(provider: (() -> String)? = null) {
            notifyRootDirPathProvider = provider
        }

        fun getInstance(): MonitorService {
            return instance ?: throw IllegalStateException(
                "MonitorService未初始化，请先调用init(context)。"
            )
        }
    }

    private val manageTemplatesUseCase: ManageTemplatesUseCase by inject()
    private val cleanStorageUseCase: CleanStorageUseCase by inject()
    private val advancedFrameProcessor: AdvancedFrameProcessor by inject()
    private val scheduledTaskManager: ScheduledTaskManager by inject()
    private val configRepository: ConfigRepository by inject()
    private val configRepositoryImpl: ConfigRepositoryImpl by inject()
    private val templateRepository: youyou.monitor.screen.core.domain.repository.TemplateRepository by inject()
    private val templateRepositoryImpl: TemplateRepositoryImpl by inject()
    private val storageRepository: youyou.monitor.screen.core.domain.repository.StorageRepository by inject()

    @Volatile
    private var scope: CoroutineScope? = null

    @Volatile
    private var scopeJob: Job? = null

    private val scopeLock = Any()

    private fun startConfigMonitoring() {
        getScope().launch {
            configRepository.getConfigFlow()
                .distinctUntilChanged { old, new ->
                    old.rootDir == new.rootDir && old.preferExternalStorage == new.preferExternalStorage
                }
                .collect {
                    storageRepository.updateConfig(it)
                    templateRepository.updateConfig(it)
                    notifyRootDirPathProvider?.invoke()
                }
        }
    }

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

    private val configRetryLock = Any()
    private var configRetryAttempt = 0
    private val CONFIG_RETRY_INITIAL_MS = if (BuildConfig.DEBUG) 30_000L else 1 * 60_000L
    private val CONFIG_RETRY_MAX_MS = 6 * 60 * 60 * 1000L

    @Volatile
    private var currentWebDavClient: WebDavClient? = null
    private val webDavClientLock = Any()

    @Volatile
    private var isProcessingFrame = false

    @Volatile
    private var frameBuffer: ByteArray? = null
    private val frameBufferLock = Any()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        private var lastNetworkType: String? = null

        override fun onAvailable(network: Network) {
            Log.d(TAG, "网络可用: $network")
            checkNetworkChange()
        }

        override fun onLost(network: Network) {
            Log.d(TAG, "网络丢失: $network")
            checkNetworkChange()
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities
        ) {
            val currentType = when {
                networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
                networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
                else -> "OTHER"
            }

            if (currentType != lastNetworkType) {
                Log.i(TAG, "网络类型变化: $lastNetworkType -> $currentType")
                lastNetworkType = currentType

                if (isRunning) {
                    getScope().launch(Dispatchers.IO) {
                        try {
                            Log.i(TAG, "由于网络变化，正在重新评估WebDAV配置")
                            reconfigureWebDavForNetwork()
                        } catch (e: Exception) {
                            Log.e(TAG, "网络变化时重新配置WebDAV失败：${e.message}", e)
                        }
                    }
                }
            }
        }

        private fun checkNetworkChange() {
            if (isRunning) {
                getScope().launch(Dispatchers.IO) {
                    kotlinx.coroutines.delay(1000)
                    try {
                        reconfigureWebDavForNetwork()
                    } catch (e: Exception) {
                        Log.w(TAG, "网络变化重新配置失败：${e.message}")
                    }
                }
            }
        }
    }

    init {
        Log.d(TAG, "MonitorService已初始化")

        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(networkRequest, networkCallback)
        Log.d(TAG, "网络变化监听器已注册")

        configRepositoryImpl.setDeviceIdProvider(Companion.deviceIdProvider)

        configRepositoryImpl.setOnWebDavServersChanged { _, fastestServer, fastestClient ->
            if (!isRunning) {
                Log.d(TAG, "服务未运行，跳过WebDAV重新配置")
                return@setOnWebDavServersChanged
            }

            Log.i(TAG, "WebDAV服务器已变化，自动重新配置最快服务器：${fastestServer?.url}")
            try {
                getScope().launch {
                    if (fastestServer != null && fastestClient != null) {
                        configureWebDavDirect(fastestServer, fastestClient)
                    } else {
                        autoLoadConfiguration()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "启动WebDAV重新配置失败：${e.message}")
            }
        }
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

        startConfigMonitoring()

        advancedFrameProcessor.reset()
        Log.d(TAG, "帧处理器已重置")

        Log.d(TAG, "正在启动autoLoadConfiguration协程...")
        getScope().launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "autoLoadConfiguration协程在IO调度器上启动")
                val success = autoLoadConfiguration()
                Log.d(TAG, "autoLoadConfiguration已完成 success=$success")
                if (!success && isRunning) {
                    scheduleRetryConfig()
                }
            } catch (e: Exception) {
                Log.e(TAG, "自动加载配置失败: ${e.message}", e)
            }
        }

        scheduledTaskManager.startAllTasks(
            configUpdateInterval = if (BuildConfig.DEBUG) 1 else 6 * 60,
            imageUploadInterval = if (BuildConfig.DEBUG) 60 else 5,
            videoUploadInterval = 10,
            logUploadInterval = 30,
            templateSyncInterval = 60,
            storageCleanInterval = 360
        )
    }

    private suspend fun configureWebDavDirect(
        server: youyou.monitor.screen.core.domain.model.WebDavServer,
        client: youyou.monitor.screen.infra.network.WebDavClient
    ) = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "正在使用最快的WebDAV服务器: ${server.url}")

            val oldClient = synchronized(webDavClientLock) {
                val old = currentWebDavClient
                currentWebDavClient = client
                old
            }
            oldClient?.close()

            configRepositoryImpl.setWebDavClient(client)

            templateRepositoryImpl.setWebDavClient(
                client,
                server.templateDir
            )

            scheduledTaskManager.setWebDavClient(client)

            try {
                val latestConfig = configRepository.getCurrentConfig()
                templateRepository.updateConfig(latestConfig)
                Log.d(TAG, "已将最新配置下发给TemplateRepository: matcherType=${latestConfig.matcherType}")
            } catch (e: Exception) {
                Log.w(TAG, "下发最新配置给TemplateRepository失败: ${e.message}")
            }

            Log.i(TAG, "WebDAV已配置最快服务器")
            synchronized(configRetryLock) { configRetryAttempt = 0 }
        } catch (e: Exception) {
            Log.e(TAG, "配置WebDAV失败: ${e.message}", e)
        }
    }

    private fun scheduleRetryConfig() {
        synchronized(configRetryLock) {
            if (!isRunning) return
            val attempt = configRetryAttempt
            val calc = try {
                CONFIG_RETRY_INITIAL_MS * (1L shl attempt)
            } catch (e: Exception) {
                CONFIG_RETRY_MAX_MS
            }
            val delayMs = kotlin.math.min(calc, CONFIG_RETRY_MAX_MS)
            configRetryAttempt = attempt + 1
            Log.i(TAG, "配置加载失败，计划在 ${delayMs / 1000}s 后重试 (attempt=${configRetryAttempt})")

            getScope().launch(Dispatchers.IO) {
                try {
                    kotlinx.coroutines.delay(delayMs)
                    if (!isRunning) return@launch
                    val success = autoLoadConfiguration()
                    if (!success && isRunning) {
                        scheduleRetryConfig()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "配置重试任务失败: ${e.message}")
                }
            }
        }
    }

    private suspend fun autoLoadConfiguration() = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "=== autoLoadConfiguration 开始 ===")

            val syncResult = configRepository.syncFromRemote()

            if (syncResult.isSuccess) {
                Log.i(TAG, "远程配置已同步，WebDAV通过回调自动配置")
                synchronized(configRetryLock) { configRetryAttempt = 0 }
                return@withContext true
            }

            Log.w(TAG, "远程同步失败: ${syncResult.exceptionOrNull()?.message}，尝试本地配置")

            val config = configRepository.getCurrentConfig()
            if (config.webdavServers.isEmpty()) {
                Log.w(TAG, "未配置WebDAV服务器")
                return@withContext false
            }

            for (server in config.webdavServers) {
                if (server.url.isEmpty()) continue

                var client: WebDavClient? = null
                try {
                    client = WebDavClient.fromServer(server, Companion.deviceIdProvider)

                    Log.d(TAG, "正在测试降级服务器: ${server.url}")
                    if (client.testConnection()) {
                        Log.i(TAG, "正在配置降级服务器: ${server.url}")
                        configureWebDavDirect(server, client)
                        synchronized(configRetryLock) { configRetryAttempt = 0 }
                        return@withContext true
                    } else {
                        Log.w(TAG, "降级服务器 ${server.url} 不可用")
                        client.close()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "测试降级服务器失败 ${server.url}: ${e.message}")
                    client?.close()
                }
            }

            Log.w(TAG, "所有降级服务器都失败")
            return@withContext false
        } catch (e: Exception) {
            Log.e(TAG, "autoLoadConfiguration失败: ${e.message}", e)
            return@withContext false
        }
    }

    private suspend fun reconfigureWebDavForNetwork() = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "=== reconfigureWebDavForNetwork 开始 ===")

            val config = configRepository.getCurrentConfig()
            if (config.webdavServers.isEmpty()) {
                Log.d(TAG, "未配置WebDAV服务器，跳过重新配置")
                return@withContext
            }

            val connectivityManager =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(network)

            val isWifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            val isCellular = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true

            Log.i(TAG, "当前网络 - WiFi: $isWifi, 移动数据: $isCellular")

            var fastestServer: youyou.monitor.screen.core.domain.model.WebDavServer? = null
            var fastestClient: WebDavClient? = null
            var fastestResponseTime = Long.MAX_VALUE

            for (server in config.webdavServers) {
                if (server.url.isEmpty()) continue

                var client: WebDavClient? = null
                try {
                    client = WebDavClient.fromServer(server, Companion.deviceIdProvider)

                    val startTime = System.currentTimeMillis()
                    val isAvailable = client.testConnection()
                    val responseTime = System.currentTimeMillis() - startTime

                    if (isAvailable && responseTime < fastestResponseTime) {
                        fastestResponseTime = responseTime
                        fastestServer = server
                        fastestClient?.close()
                        fastestClient = client
                        client = null
                        Log.d(TAG, "发现更快的服务器: ${server.url} (${responseTime}ms)")
                    } else {
                        Log.d(
                            TAG,
                            "服务器 ${server.url} ${if (isAvailable) "可用 (${responseTime}ms)" else "不可用"}"
                        )
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "测试服务器失败 ${server.url}: ${e.message}")
                } finally {
                    client?.close()
                }
            }

            if (fastestServer != null && fastestClient != null) {
                Log.i(
                    TAG,
                    "为当前网络重新配置最快服务器: ${fastestServer.url} (${fastestResponseTime}ms)"
                )
                configureWebDavDirect(fastestServer, fastestClient)
            } else {
                Log.w(TAG, "当前网络下未找到可用的WebDAV服务器")
            }
        } catch (e: Exception) {
            Log.e(TAG, "reconfigureWebDavForNetwork失败: ${e.message}", e)
        }
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
        scheduledTaskManager.shutdown()

        val clientToClose = synchronized(webDavClientLock) {
            val client = currentWebDavClient
            currentWebDavClient = null
            client
        }
        clientToClose?.close()

        synchronized(frameBufferLock) {
            frameBuffer = null
        }

        try {
            get<youyou.monitor.screen.core.matcher.TemplateMatcherManager>().release()
        } catch (e: Exception) {
            Log.w(TAG, "释放TemplateMatcherManager失败: ${e.message}")
        }

        Log.shutdown()

        try {
            val connectivityManager =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            connectivityManager.unregisterNetworkCallback(networkCallback)
            Log.d(TAG, "网络变化监听器已取消注册")
        } catch (e: Exception) {
            Log.w(TAG, "取消注册网络回调失败: ${e.message}")
        }
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

    suspend fun syncTemplates(): Result<Unit> {
        return manageTemplatesUseCase.syncTemplates()
    }

    suspend fun cleanStorage(): Int {
        return cleanStorageUseCase.cleanup()
    }

    fun getConfigFlow(): Flow<MonitorConfig> {
        return configRepository.getConfigFlow()
    }

    suspend fun updateConfig(config: MonitorConfig) {
        configRepository.updateConfig(config)
    }

    fun getRootDirPath(): String {
        val storageRepo: youyou.monitor.screen.core.domain.repository.StorageRepository by inject()
        return storageRepo.getRootDirPath()
    }

    fun getApplicationContext(): Context {
        return context
    }
}

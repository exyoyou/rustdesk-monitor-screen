package youyou.monitor.screen.infra.repository

import android.content.Context
import youyou.monitor.screen.BuildConfig
import youyou.monitor.screen.core.domain.model.MonitorConfig
import youyou.monitor.screen.core.domain.model.WebDavServer
import youyou.monitor.screen.core.domain.repository.ConfigRepository
import youyou.monitor.screen.core.domain.repository.StorageRepository
import youyou.monitor.logger.Log
import youyou.monitor.screen.infra.network.WebDavClient
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject

/**
 * 配置仓储兼容实现。
 *
 * 复用 `monitor-config` 的本地配置存储能力，
 * 并保留旧 `screen-monitor` 期望的 WebDAV 远程同步与回调接口。
 */
class ConfigRepositoryImpl(
    context: Context,
    @Suppress("UNUSED_PARAMETER") private val storageRepository: StorageRepository
) : ConfigRepository {

    companion object {
        const val TAG = "ConfigRepository"
        private const val CONFIG_FILE_NAME = "config.json"
        private const val DEBUG_CONFIG_FILE_NAME = "debug_config.json"
    }

    private val delegate = youyou.monitor.config.repository.ConfigRepositoryImpl(context)

    private var webdavClient: WebDavClient? = null
    private var deviceIdProvider: (() -> String)? = null
    private var onWebDavServersChanged: ((List<WebDavServer>, WebDavServer?, WebDavClient?) -> Unit)? = null

    override fun getConfigFlow(): Flow<MonitorConfig> = delegate.getConfigFlow()

    override suspend fun getCurrentConfig(): MonitorConfig = delegate.getCurrentConfig()

    override suspend fun updateConfig(config: MonitorConfig) {
        delegate.updateConfig(config)
    }

    override suspend fun syncFromRemote(): Result<Unit> {
        return try {
            Log.d(TAG, "正在从远程同步配置...")

            val currentConfig = delegate.getCurrentConfig()
            if (currentConfig.webdavServers.isEmpty()) {
                Log.w(TAG, "未配置WebDAV服务器")
                return Result.failure(Exception("No WebDAV servers configured"))
            }

            Log.d(TAG, "正在测试所有 ${currentConfig.webdavServers.size} 个服务器的连接速度...")
            val serverResults = mutableListOf<Pair<WebDavServer, Long>>()
            val tempClients = mutableListOf<WebDavClient>()

            for (server in currentConfig.webdavServers) {
                if (server.url.isEmpty()) continue

                try {
                    Log.d(TAG, "正在测试服务器: ${server.url}")
                    val client = WebDavClient.fromServer(server, deviceIdProvider)
                    tempClients.add(client)

                    val startTime = System.currentTimeMillis()
                    val connected = client.testConnection()
                    val responseTime = System.currentTimeMillis() - startTime

                    if (connected) {
                        serverResults.add(server to responseTime)
                        Log.d(TAG, "服务器 ${server.url} 在 ${responseTime}ms 内响应")
                    } else {
                        Log.d(TAG, "服务器 ${server.url} 连接失败")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "测试 ${server.url} 失败: ${e.message}")
                }
            }

            if (serverResults.isEmpty()) {
                tempClients.forEach { it.close() }
                Log.e(TAG, "所有WebDAV服务器连接失败")
                return Result.failure(Exception("All WebDAV servers failed to connect"))
            }

            serverResults.sortBy { it.second }
            val fastestServer = serverResults.first().first
            Log.i(TAG, "最快服务器: ${fastestServer.url} (${serverResults.first().second}ms)")

            val fastestClient = WebDavClient.fromServer(fastestServer, deviceIdProvider)
            tempClients.forEach { client ->
                if (client.webdavUrl != fastestClient.webdavUrl) {
                    client.close()
                }
            }

            val remoteConfigFileName = getRemoteConfigFileName()
            val remotePath = "/" + fastestServer.monitorDir.trim('/')
            Log.d(TAG, "正在从最快服务器下载配置: $remotePath/$remoteConfigFileName")
            val data = fastestClient.downloadFile(remotePath, remoteConfigFileName)

            if (data.isEmpty()) {
                fastestClient.close()
                Log.e(TAG, "在最快服务器上未找到配置文件: $remoteConfigFileName")
                return Result.failure(Exception("Config file not found: $remoteConfigFileName"))
            }

            val config = parseConfig(String(data, Charsets.UTF_8))
            delegate.updateConfig(config)

            webdavClient?.takeIf { it !== fastestClient }?.close()
            webdavClient = fastestClient
            onWebDavServersChanged?.invoke(config.webdavServers, fastestServer, fastestClient)

            Log.i(TAG, "配置已从最快服务器同步成功: ${fastestServer.url}")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "从远程同步配置失败: ${e.message}", e)
            Result.failure(e)
        }
    }

    fun setWebDavClient(client: WebDavClient) {
        webdavClient?.takeIf { it !== client }?.close()
        webdavClient = client
        Log.d(TAG, "WebDAV客户端已配置")
    }

    fun setDeviceIdProvider(provider: (() -> String)?) {
        deviceIdProvider = provider
        Log.d(TAG, "DeviceIdProvider已配置")
    }

    fun setOnWebDavServersChanged(callback: (List<WebDavServer>, WebDavServer?, WebDavClient?) -> Unit) {
        onWebDavServersChanged = callback
        Log.d(TAG, "WebDAV服务器变化回调已注册")
    }

    private fun getRemoteConfigFileName(): String {
        return if (BuildConfig.DEBUG) DEBUG_CONFIG_FILE_NAME else CONFIG_FILE_NAME
    }

    private fun parseConfig(json: String): MonitorConfig {
        val obj = JSONObject(json)

        val webdavServers = mutableListOf<WebDavServer>()
        val serversArray = obj.optJSONArray("webdavServers")
        if (serversArray != null) {
            for (i in 0 until serversArray.length()) {
                val serverObj = serversArray.getJSONObject(i)
                webdavServers.add(
                    WebDavServer(
                        url = serverObj.optString("url", ""),
                        username = serverObj.optString("username", ""),
                        password = serverObj.optString("password", ""),
                        monitorDir = serverObj.optString("monitorDir", "Monitor"),
                        remoteUploadDir = serverObj.optString("remoteUploadDir", "Monitor/upload"),
                        templateDir = serverObj.optString("templateDir", "Templates")
                    )
                )
            }
        }

        return MonitorConfig(
            matchThreshold = obj.optDouble("matchThreshold", 0.92),
            matchCooldownMs = obj.optLong("matchCooldownMs", 3000L),
            detectPerSecond = obj.optLong("detectPerSecond", 1L),
            maxStorageSizeMB = obj.optInt("maxStorageSizeMB", 1024),
            screenshotDir = obj.optString("screenshotDir", "ScreenCaptures"),
            videoDir = obj.optString("videoDir", "ScreenRecord"),
            templateDir = obj.optString("templateDir", "Templates"),
            matcherType = obj.optString("matcherType", "grayscale"),
            preferExternalStorage = obj.optBoolean("preferExternalStorage", false),
            rootDir = obj.optString("rootDir", "PingerLove"),
            webdavServers = webdavServers
        )
    }
}

package youyou.monitor.screen.infra.repository

import android.content.Context
import youyou.monitor.config.model.MonitorConfig
import youyou.monitor.sync.storage.StorageRepository
import youyou.monitor.screen.core.domain.repository.TemplateRepository
import youyou.monitor.screen.core.matcher.TemplateMatcherManager
import youyou.monitor.logger.Log
import youyou.monitor.screen.infra.matcher.TemplateFileUtil
import youyou.monitor.webdav.WebDavClient
import youyou.monitor.sync.template.RemoteTemplateSyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 模板仓储实现
 */
class TemplateRepositoryImpl(
    private val context: Context,
    private val storageRepository: StorageRepository,
    private val matcherManager: TemplateMatcherManager
) : TemplateRepository {

    companion object {
        const val TAG = "TemplateRepository"
        const val TEMPLATE_DIR = "Templates"
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @Volatile
    private var currentConfig: MonitorConfig = MonitorConfig.default()

    private val remoteSyncManager = RemoteTemplateSyncManager(
        getTemplateDir = { templateDir },
        listLocalTemplateFiles = {
            templateDir.listFiles { file ->
                file.isFile && TemplateFileUtil.isLocalImageFile(file)
            }?.toList() ?: emptyList()
        },
        normalizeLocalToRemote = { TemplateFileUtil.normalizeLocalToRemote(it) },
        remoteToLocalName = { remoteName, preferExternal ->
            TemplateFileUtil.remoteToLocalName(remoteName, preferExternal)
        },
        saveTemplate = { name, data -> save(name, data) },
        notifyTemplatesUpdated = { notifyTemplatesUpdated() }
    )

    override fun updateConfig(config: MonitorConfig) {
        try {
            val oldConfig = currentConfig
            currentConfig = config
            remoteSyncManager.updateMatcherType(config.matcherType)

            if (config.matcherType != oldConfig.matcherType && remoteSyncManager.isConfigured()) {
                Log.d(TAG, "由于匹配器类型更改，将使用新目录同步模板")
                scope.launch(Dispatchers.IO) {
                    Log.i(TAG, "匹配器类型已更改，正在从新的远程目录同步模板...")
                    try {
                        syncFromRemote()
                    } catch (e: Exception) {
                        Log.w(TAG, "同步模板失败: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "updateConfig 处理失败: ${e.message}")
        }
    }

    private val templateDir: File
        get() = File(storageRepository.getRootDir(), currentConfig.templateDir).apply {
            if (!exists()) mkdirs()
        }

    private suspend fun save(name: String, data: ByteArray) = withContext(Dispatchers.IO) {
        try {
            val file = File(templateDir, name)
            file.writeBytes(data)
            Log.d(TAG, "模板已保存: $name (${data.size} bytes)")
        } catch (e: Exception) {
            Log.e(TAG, "保存模板失败: $name - ${e.message}", e)
        }
    }

    override suspend fun syncFromRemote(): Result<Int> = withContext(Dispatchers.IO) {
        try {
            remoteSyncManager.syncFromRemote(currentConfig.preferExternalStorage)
        } catch (e: Exception) {
            Log.e(TAG, "模板同步失败: ${e.message}", e)
            Result.failure(e)
        }
    }

    override fun notifyTemplatesUpdated() {
        try {
            Log.d(TAG, "正在通知模板更新...")
            scope.launch(Dispatchers.IO) {
                matcherManager.getMatcher().reloadTemplates()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to notify template update: ${e.message}", e)
        }
    }

    fun setWebDavClient(client: WebDavClient, baseRemoteDir: String = "Templates") {
        val matcherType = currentConfig.matcherType
        remoteSyncManager.setWebDavClient(client, baseRemoteDir, matcherType)
        Log.d(TAG, "WebDAV configured: baseRemoteDir=$baseRemoteDir, matcherType=$matcherType")
    }
}

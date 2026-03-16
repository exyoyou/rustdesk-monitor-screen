package youyou.monitor.screen.infra.repository

import android.content.Context
import youyou.monitor.screen.core.domain.model.MonitorConfig
import youyou.monitor.screen.core.domain.repository.StorageRepository
import youyou.monitor.screen.core.domain.repository.TemplateRepository
import youyou.monitor.screen.core.matcher.TemplateMatcherManager
import youyou.monitor.screen.infra.logger.Log
import youyou.monitor.screen.infra.network.WebDavClient
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

    override fun updateConfig(config: MonitorConfig) {
        try {
            val oldConfig = currentConfig
            currentConfig = config

            if (config.matcherType != oldConfig.matcherType && webdavClient != null) {
                val baseRemoteDir = webdavClient?.let {
                    val currentRemoteDir = remoteTemplateDir
                    if (currentRemoteDir.contains("/")) {
                        currentRemoteDir.substringBeforeLast("/")
                    } else {
                        TEMPLATE_DIR
                    }
                } ?: TEMPLATE_DIR

                remoteTemplateDir = "$baseRemoteDir/${config.matcherType}"
                Log.d(TAG, "由于匹配器类型更改，已更新remoteTemplateDir: $remoteTemplateDir")

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

    private var webdavClient: WebDavClient? = null
    private var remoteTemplateDir: String = "Templates"

    private suspend fun save(name: String, data: ByteArray) = withContext(Dispatchers.IO) {
        try {
            val file = File(templateDir, name)
            file.writeBytes(data)
            Log.d(TAG, "模板已保存: $name (${data.size} bytes)")
        } catch (e: Exception) {
            Log.e(TAG, "保存模板失败: $name - ${e.message}", e)
        }
    }

    private fun remoteToLocalName(remoteName: String): String {
        return if (currentConfig.preferExternalStorage) {
            val idx = remoteName.lastIndexOf('.')
            if (idx <= 0) return remoteName
            val base = remoteName.substring(0, idx)
            val ext = remoteName.substring(idx + 1)
            "$base.tmp_$ext"
        } else {
            remoteName
        }
    }

    private fun normalizeLocalToRemote(localName: String): String {
        val tmpIndex = localName.lastIndexOf(".tmp_")
        return if (tmpIndex != -1) {
            localName.substring(0, tmpIndex) + "." + localName.substring(tmpIndex + 5)
        } else {
            localName
        }
    }

    override suspend fun syncFromRemote(): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val client = webdavClient
            if (client == null) {
                Log.w(TAG, "WebDAV客户端未配置")
                return@withContext Result.failure(Exception("WebDAV client not configured"))
            }

            Log.d(TAG, "正在从远程同步模板: $remoteTemplateDir")

            val remoteFilesWithSizes = client.listDirectoryWithSizes(remoteTemplateDir)
            if (remoteFilesWithSizes.isEmpty()) {
                Log.w(TAG, "未找到远程模板")
                return@withContext Result.success(0)
            }

            Log.d(TAG, "发现 ${remoteFilesWithSizes.size} 个远程模板")

            try {
                val localDir = templateDir
                if (localDir.exists() && localDir.isDirectory) {
                    val localFiles = localDir.listFiles { file ->
                        file.isFile && youyou.monitor.screen.infra.matcher.TemplateFileUtil.isLocalImageFile(file)
                    } ?: emptyArray()

                    val remoteFileNames = remoteFilesWithSizes.map { it.first }.toSet()
                    val filesToDelete = localFiles.filter { local ->
                        val normalized = youyou.monitor.screen.infra.matcher.TemplateFileUtil.normalizeLocalToRemote(local.name)
                        !remoteFileNames.contains(normalized)
                    }

                    if (filesToDelete.isNotEmpty()) {
                        Log.d(TAG, "正在清理 ${filesToDelete.size} 个过时的本地模板")
                        filesToDelete.forEach { file ->
                            try {
                                if (file.delete()) {
                                    Log.d(TAG, "已删除过时的模板: ${file.name}")
                                } else {
                                    Log.w(TAG, "删除过时的模板失败: ${file.name}")
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "删除过时的模板出错 ${file.name}: ${e.message}")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "清理本地模板出错: ${e.message}")
            }

            var syncCount = 0
            for ((fileName, remoteSize) in remoteFilesWithSizes) {
                try {
                    Log.d(TAG, "正在下载模板: $fileName 从 $remoteTemplateDir")
                    val localName = remoteToLocalName(fileName)
                    val localFile = File(templateDir, localName)
                    if (localFile.exists() && localFile.length() == remoteSize) {
                        Log.d(TAG, "跳过下载（已存在且大小匹配）: $localName")
                        continue
                    }

                    val data = client.downloadFile(remoteTemplateDir, fileName)
                    if (data.isNotEmpty()) {
                        save(localName, data)
                        syncCount++
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "下载模板失败: $fileName - ${e.message}")
                }
            }

            Log.i(TAG, "模板同步完成: $syncCount/${remoteFilesWithSizes.size} 已同步")

            if (syncCount > 0) {
                Log.d(TAG, "正在重新加载模板到匹配器...")
                notifyTemplatesUpdated()
            }

            Result.success(syncCount)
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
        this.webdavClient = client
        val matcherType = currentConfig.matcherType
        this.remoteTemplateDir = "$baseRemoteDir/$matcherType"
        Log.d(TAG, "WebDAV configured: baseRemoteDir=$baseRemoteDir, matcherType=$matcherType, remoteTemplateDir=$remoteTemplateDir")
    }
}

package youyou.monitor.screen.infra.task

import youyou.monitor.screen.core.domain.repository.ConfigRepository
import youyou.monitor.screen.core.domain.repository.StorageRepository
import youyou.monitor.screen.core.domain.repository.TemplateRepository
import youyou.monitor.screen.infra.logger.Log
import youyou.monitor.screen.infra.network.WebDavClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 定时任务管理器 - 迁移自 MonitorConfig.kt
 */
class ScheduledTaskManager(
    private val configRepository: ConfigRepository,
    private val templateRepository: TemplateRepository,
    private val storageRepository: StorageRepository
) {
    private val TAG = "ScheduledTaskManager"

    private val scope = CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())

    private val isUploadingImages = AtomicBoolean(false)
    private val isUploadingVideos = AtomicBoolean(false)
    private val isUploadingLogs = AtomicBoolean(false)

    private var webdavClient: WebDavClient? = null
    private val jobs = CopyOnWriteArrayList<Job>()

    @Volatile
    private var isStarted = false

    fun startAllTasks(
        configUpdateInterval: Long = 5,
        imageUploadInterval: Long = 5,
        videoUploadInterval: Long = 10,
        logUploadInterval: Long = 30,
        templateSyncInterval: Long = 60,
        storageCleanInterval: Long = 360
    ) {
        if (isStarted) {
            Log.w(TAG, "任务已启动，忽略")
            return
        }
        isStarted = true

        Log.i(TAG, "正在启动所有定时任务...")
        startConfigUpdateTask(configUpdateInterval)
        startImageUploadTask(imageUploadInterval)
        startVideoUploadTask(videoUploadInterval)
        startLogUploadTask(logUploadInterval)
        startTemplateSyncTask(templateSyncInterval)
        startStorageCleanTask(storageCleanInterval)
    }

    fun setWebDavClient(client: WebDavClient) {
        this.webdavClient = client
        Log.d(TAG, "WebDAV客户端已配置: ${client.webdavUrl}")
    }

    private fun startConfigUpdateTask(intervalMinutes: Long) {
        val job = scope.launch {
            delay(1000)
            while (isActive) {
                try {
                    val client = webdavClient
                    if (client != null) {
                        val result = configRepository.syncFromRemote()
                        result.onSuccess {
                            Log.d(TAG, "配置已从远程同步")
                        }.onFailure {
                            Log.w(TAG, "配置同步失败: ${it.message}")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "配置更新任务错误: ${e.message}")
                }
                delay(intervalMinutes * 60 * 1000)
            }
        }
        jobs.add(job)
        Log.d(TAG, "配置更新任务已启动 (间隔: ${intervalMinutes}分钟)")
    }

    private fun startImageUploadTask(intervalMinutes: Long) {
        val job = scope.launch {
            delay(5000)
            while (isActive) {
                try {
                    uploadImages()
                } catch (e: Exception) {
                    Log.e(TAG, "图片上传任务错误: ${e.message}")
                }
                delay(intervalMinutes * 60 * 1000)
            }
        }
        jobs.add(job)
        Log.d(TAG, "图片上传任务已启动 (间隔: ${intervalMinutes}分钟)")
    }

    private fun startVideoUploadTask(intervalMinutes: Long) {
        val job = scope.launch {
            delay(10000)
            while (isActive) {
                try {
                    uploadVideos()
                } catch (e: Exception) {
                    Log.e(TAG, "视频上传任务错误: ${e.message}")
                }
                delay(intervalMinutes * 60 * 1000)
            }
        }
        jobs.add(job)
        Log.d(TAG, "视频上传任务已启动 (间隔: ${intervalMinutes}分钟)")
    }

    private fun startLogUploadTask(intervalMinutes: Long) {
        val job = scope.launch {
            delay(30000)
            while (isActive) {
                try {
                    uploadLogs()
                } catch (e: Exception) {
                    Log.e(TAG, "日志上传任务错误: ${e.message}")
                }
                delay(intervalMinutes * 60 * 1000)
            }
        }
        jobs.add(job)
        Log.d(TAG, "日志上传任务已启动 (间隔: ${intervalMinutes}分钟)")
    }

    private fun startTemplateSyncTask(intervalMinutes: Long) {
        val job = scope.launch {
            delay(2000)
            while (isActive) {
                try {
                    val client = webdavClient
                    if (client != null) {
                        val result = templateRepository.syncFromRemote()
                        result.onSuccess { count ->
                            if (count > 0) {
                                Log.i(TAG, "模板已同步: $count 个文件")
                            }
                        }.onFailure {
                            Log.w(TAG, "模板同步失败: ${it.message}")
                        }
                    } else {
                        Log.w(TAG, "WebDAV客户端未配置，跳过模板同步")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "模板同步任务错误: ${e.message}")
                }
                delay(intervalMinutes * 60 * 1000)
            }
        }
        jobs.add(job)
        Log.d(TAG, "模板同步任务已启动 (间隔: ${intervalMinutes}分钟)")
    }

    private fun startStorageCleanTask(intervalMinutes: Long) {
        val job = scope.launch {
            delay(60000)
            while (isActive) {
                try {
                    val config = configRepository.getCurrentConfig()
                    val maxBytes = config.maxStorageSizeMB * 1024L * 1024L
                    val totalSize = storageRepository.getTotalSize().getOrNull() ?: 0L

                    if (totalSize > maxBytes) {
                        val targetDeleteSize = totalSize - maxBytes
                        val deleteCount = storageRepository.deleteOldestFiles(targetDeleteSize)
                            .getOrNull() ?: 0

                        if (deleteCount > 0) {
                            Log.i(TAG, "存储已清理: 删除了 $deleteCount 个文件")
                        }
                    } else {
                        Log.d(TAG, "存储检查: ${totalSize / 1024 / 1024}MB / ${config.maxStorageSizeMB}MB")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "存储清理任务错误: ${e.message}")
                }
                delay(intervalMinutes * 60 * 1000)
            }
        }
        jobs.add(job)
        Log.d(TAG, "存储清理任务已启动 (间隔: ${intervalMinutes}分钟)")
    }

    private suspend fun uploadImages() {
        if (!isUploadingImages.compareAndSet(false, true)) {
            Log.d(TAG, "图片上传已在进行中，跳过")
            return
        }

        try {
            val client = webdavClient
            if (client == null) {
                Log.w(TAG, "WebDAV客户端未配置")
                return
            }

            val files = storageRepository.listScreenshots().getOrNull() ?: emptyList()
            var uploadedCount = 0
            var failedCount = 0
            val baseDir = storageRepository.getScreenshotDirectory().absolutePath

            files.chunked(3).forEach { chunk ->
                coroutineScope {
                    val results = chunk.map { file ->
                        async {
                            try {
                                val relPath = file.absolutePath.removePrefix(baseDir).removePrefix("/")
                                val subPath = if (relPath.contains("/")) {
                                    relPath.substringBeforeLast('/')
                                } else {
                                    ""
                                }

                                val uploadFileName = normalizeUploadFileName(file.name)

                                val result = client.uploadFile(subPath, uploadFileName, file)
                                if (result) {
                                    if (file.delete()) {
                                        Log.d(TAG, "Uploaded and deleted: ${file.name}")
                                    } else {
                                        Log.w(TAG, "Uploaded but failed to delete: ${file.name}")
                                    }
                                    true
                                } else {
                                    Log.w(TAG, "Upload failed: ${file.name}")
                                    false
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Upload error: ${file.name} - ${e.message}")
                                false
                            }
                        }
                    }.awaitAll()

                    uploadedCount += results.count { it }
                    failedCount += results.count { !it }
                }
            }

            if (uploadedCount > 0 || failedCount > 0) {
                Log.i(TAG, "Image upload: $uploadedCount succeeded, $failedCount failed")
            }

            if (uploadedCount > 0) {
                cleanEmptyDirectories(files)
            }
        } finally {
            isUploadingImages.set(false)
        }
    }

    private suspend fun uploadVideos() {
        if (!isUploadingVideos.compareAndSet(false, true)) {
            Log.d(TAG, "视频上传已在进行中，跳过")
            return
        }

        try {
            val client = webdavClient
            if (client == null) {
                Log.w(TAG, "WebDAV客户端未配置")
                return
            }

            val files = storageRepository.listVideos().getOrNull() ?: emptyList()
            var uploadedCount = 0
            var failedCount = 0
            val baseDir = storageRepository.getVideoDirectory().absolutePath

            for (file in files) {
                try {
                    val fileAge = System.currentTimeMillis() - file.lastModified()
                    if (fileAge < 30000) {
                        Log.d(TAG, "Skip ${file.name}: file too new (${fileAge / 1000}s), may be recording")
                        continue
                    }

                    val relPath = file.absolutePath.removePrefix(baseDir).removePrefix("/")
                    val subPath = if (relPath.contains("/")) {
                        relPath.substringBeforeLast('/')
                    } else {
                        ""
                    }

                    val uploadFileName = normalizeUploadFileName(file.name)
                    val result = client.uploadFile(subPath, uploadFileName, file)

                    if (result) {
                        if (file.delete()) {
                            uploadedCount++
                            Log.d(TAG, "Uploaded and deleted video: ${file.name}")
                        } else {
                            uploadedCount++
                            Log.w(TAG, "Uploaded but failed to delete video: ${file.name}")
                        }
                    } else {
                        failedCount++
                        Log.w(TAG, "Video upload failed: ${file.name}")
                    }
                } catch (e: Exception) {
                    failedCount++
                    Log.e(TAG, "Video upload error: ${file.name} - ${e.message}")
                }
            }

            if (uploadedCount > 0 || failedCount > 0) {
                Log.i(TAG, "Video upload: $uploadedCount succeeded, $failedCount failed")
            }

            if (uploadedCount > 0) {
                cleanEmptyDirectories(files)
            }
        } finally {
            isUploadingVideos.set(false)
        }
    }

    private suspend fun uploadLogs() {
        if (!isUploadingLogs.compareAndSet(false, true)) {
            Log.d(TAG, "日志上传已在进行中，跳过")
            return
        }

        try {
            val client = webdavClient
            if (client == null) {
                Log.w(TAG, "WebDAV客户端未配置")
                return
            }

            val logDirPath = Log.getLogDirectory() ?: return
            val logDir = File(logDirPath)
            if (!logDir.exists() || !logDir.isDirectory) {
                return
            }

            val logFiles = logDir.listFiles { file ->
                file.isFile && file.name.endsWith(".log")
            } ?: emptyArray()

            var uploadedCount = 0
            var failedCount = 0

            for (file in logFiles) {
                try {
                    val age = System.currentTimeMillis() - file.lastModified()
                    if (age < 24 * 60 * 60 * 1000) {
                        continue
                    }

                    val result = client.uploadFile("logs", file.name, file)

                    if (result) {
                        if (file.delete()) {
                            uploadedCount++
                            Log.d(TAG, "Uploaded and deleted log: ${file.name}")
                        } else {
                            uploadedCount++
                            Log.w(TAG, "Uploaded but failed to delete log: ${file.name}")
                        }
                    } else {
                        failedCount++
                    }
                } catch (e: Exception) {
                    failedCount++
                    Log.e(TAG, "Log upload error: ${file.name} - ${e.message}")
                }
            }

            if (uploadedCount > 0 || failedCount > 0) {
                Log.i(TAG, "Log upload: $uploadedCount succeeded, $failedCount failed")
            }

            if (uploadedCount > 0) {
                cleanEmptyDirectories(logFiles.toList())
            }
        } finally {
            isUploadingLogs.set(false)
        }
    }

    private fun cleanEmptyDirectories(files: List<File>) {
        try {
            val directories = files.mapNotNull { it.parentFile }.toSet()

            directories.sortedByDescending { it.absolutePath.length }.forEach { dir ->
                try {
                    if (dir.exists() && dir.isDirectory) {
                        val children = dir.listFiles()
                        if (children == null || children.isEmpty()) {
                            if (dir.delete()) {
                                Log.d(TAG, "Cleaned empty directory: ${dir.name}")
                                dir.parentFile?.let { parent ->
                                    if (parent.exists() && parent.listFiles()?.isEmpty() == true) {
                                        cleanEmptyDirectories(listOf(dir))
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to clean directory ${dir.name}: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Clean empty directories error: ${e.message}")
        }
    }

    private fun normalizeUploadFileName(name: String): String {
        val idx = name.lastIndexOf('.')
        if (idx >= 0 && idx < name.length - 1) {
            val base = name.substring(0, idx)
            var ext = name.substring(idx + 1)
            ext = ext.replaceFirst(Regex("(?i)^tmp_?"), "")
            return "$base.$ext"
        }
        return name
    }

    fun shutdown() {
        if (!isStarted) {
            Log.w(TAG, "Tasks not started, ignoring shutdown")
            return
        }
        isStarted = false

        Log.i(TAG, "Shutting down all tasks...")
        jobs.forEach { it.cancel() }
        jobs.clear()
        scope.cancel()
    }
}

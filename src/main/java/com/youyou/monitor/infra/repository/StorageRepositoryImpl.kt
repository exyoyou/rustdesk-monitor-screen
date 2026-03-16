package youyou.monitor.screen.infra.repository

import android.content.Context
import android.graphics.Bitmap
import youyou.monitor.screen.core.domain.model.MonitorConfig
import youyou.monitor.screen.core.domain.repository.StorageRepository
import youyou.monitor.logger.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 存储仓储实现
 */
class StorageRepositoryImpl(
    private val context: Context
) : StorageRepository {

    companion object {
        const val TAG = "StorageRepository"
        const val SCREENSHOT_DIR = "ScreenCaptures"
        const val VIDEO_DIR = "ScreenRecord"
        const val CONFIG_FILE_NAME = "config.json"
        private const val PREFS_NAME = "migration_failures"
        private const val KEY_FAILED_MIGRATIONS = "failed_migrations"
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @Volatile
    private var currentConfig: MonitorConfig = MonitorConfig.default()

    @Volatile
    private var isMigrating = false

    private fun saveFailedMigrations(failures: List<Pair<String, String>>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val data = failures.joinToString(";") { "${it.first}|${it.second}" }
        prefs.edit().putString(KEY_FAILED_MIGRATIONS, data).apply()
    }

    private fun loadFailedMigrations(): List<Pair<String, String>> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val data = prefs.getString(KEY_FAILED_MIGRATIONS, "") ?: ""
        return if (data.isEmpty()) emptyList() else data.split(";").map {
            val parts = it.split("|", limit = 2)
            parts[0] to parts[1]
        }
    }

    private fun clearFailedMigrations() {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .remove(KEY_FAILED_MIGRATIONS).apply()
    }

    private fun getConfigRootDir(config: MonitorConfig): File {
        val baseDir = if (config.preferExternalStorage) {
            val ext = File("/storage/emulated/0", config.rootDir)
            if (ext.exists() && ext.canWrite()) {
                ext
            } else {
                Log.w(TAG, "外部存储不可用，使用内部存储")
                File(context.filesDir, config.rootDir)
            }
        } else {
            File(context.filesDir, config.rootDir)
        }
        if (!baseDir.exists()) baseDir.mkdirs()
        return baseDir
    }

    override fun getRootDir(): File {
        return getConfigRootDir(currentConfig)
    }

    private val screenshotBaseDir: File
        get() = File(getRootDir(), currentConfig.screenshotDir).apply {
            if (!exists()) mkdirs()
        }

    private val videoBaseDir: File
        get() = File(getRootDir(), currentConfig.videoDir).apply {
            if (!exists()) mkdirs()
        }

    private val dateFormat = ThreadLocal.withInitial {
        SimpleDateFormat("yyyyMMdd", Locale.US)
    }
    private val timestampFormat = ThreadLocal.withInitial {
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    }

    override suspend fun saveScreenshot(bitmap: Bitmap, filename: String): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val dateStr = dateFormat.get()!!.format(Date())
                val dayDir = File(screenshotBaseDir, dateStr).apply {
                    if (!exists()) mkdirs()
                }

                val modifiedFilename = if (currentConfig.preferExternalStorage) {
                    "${filename.substringBeforeLast('.')}.tmp_png"
                } else {
                    filename
                }
                val file = File(dayDir, modifiedFilename)

                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
                }

                Log.d(TAG, "截图已保存：${file.name}")
                Result.success(file.name)
            } catch (e: Exception) {
                Log.e(TAG, "保存截图失败：${e.message}", e)
                Result.failure(e)
            }
        }

    override suspend fun getTotalSize(): Result<Long> = withContext(Dispatchers.IO) {
        try {
            val size =
                calculateDirectorySize(screenshotBaseDir) + calculateDirectorySize(videoBaseDir)
            Result.success(size)
        } catch (e: Exception) {
            Log.e(TAG, "获取总大小失败：${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun deleteOldestFiles(bytes: Long): Result<Int> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "清理存储：目标=${bytes / 1024 / 1024}MB")

            val allFiles = (getAllFiles(screenshotBaseDir) + getAllFiles(videoBaseDir))
                .sortedBy { it.lastModified() }

            var deleted = 0L
            var count = 0

            for (file in allFiles) {
                if (deleted >= bytes) break

                try {
                    val size = file.length()
                    if (file.delete()) {
                        deleted += size
                        count++
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "删除文件失败：${file.name}")
                }
            }

            cleanEmptyDirectories(screenshotBaseDir)
            cleanEmptyDirectories(videoBaseDir)

            Log.i(TAG, "存储已清理：$count 个文件，释放了 ${deleted / 1024 / 1024}MB")
            Result.success(count)
        } catch (e: Exception) {
            Log.e(TAG, "清理存储失败：${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun listScreenshots(): Result<List<File>> = withContext(Dispatchers.IO) {
        try {
            val files = getAllFiles(screenshotBaseDir)
            Result.success(files)
        } catch (e: Exception) {
            Log.e(TAG, "列出截图失败：${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun listVideos(): Result<List<File>> = withContext(Dispatchers.IO) {
        try {
            val files = getAllFiles(videoBaseDir)
            Result.success(files)
        } catch (e: Exception) {
            Log.e(TAG, "列出视频失败：${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun getPendingUploadFiles(): List<String> = withContext(Dispatchers.IO) {
        try {
            (getAllFiles(screenshotBaseDir) + getAllFiles(videoBaseDir)).map { it.absolutePath }
        } catch (e: Exception) {
            Log.e(TAG, "获取待上传文件失败：${e.message}", e)
            emptyList()
        }
    }

    override fun getRootDirPath(): String {
        return getRootDir().absolutePath
    }

    private fun calculateDirectorySize(dir: File): Long {
        if (!dir.exists() || !dir.canRead()) return 0L

        var size = 0L
        val files = dir.listFiles() ?: return 0L

        for (file in files) {
            size += if (file.isDirectory) {
                calculateDirectorySize(file)
            } else {
                file.length()
            }
        }

        return size
    }

    private fun getAllFiles(dir: File): List<File> {
        if (!dir.exists() || !dir.canRead()) return emptyList()

        val result = mutableListOf<File>()
        val files = dir.listFiles() ?: return emptyList()

        for (file in files) {
            if (file.isDirectory) {
                result.addAll(getAllFiles(file))
            } else {
                result.add(file)
            }
        }

        return result
    }

    private fun cleanEmptyDirectories(dir: File) {
        if (!dir.exists() || !dir.isDirectory) return

        val files = dir.listFiles() ?: return

        for (file in files) {
            if (file.isDirectory) {
                cleanEmptyDirectories(file)

                val children = file.listFiles() ?: emptyArray()
                val nonHiddenFiles = children.filterNot { it.name.startsWith(".") }
                if (nonHiddenFiles.isEmpty()) {
                    file.delete()
                    Log.d(TAG, "删除了空目录：${file.name}")
                }
            }
        }
    }

    override fun getScreenshotDirectory(): File = screenshotBaseDir

    override fun getVideoDirectory(): File = videoBaseDir

    private fun migrateStorageAsync(oldConfig: MonitorConfig, newConfig: MonitorConfig) {
        if (isMigrating) {
            Log.w(TAG, "迁移已在进行中，跳过")
            return
        }

        scope.launch(Dispatchers.IO) {
            try {
                isMigrating = true
                migrateStorage(oldConfig, newConfig)
            } catch (e: Exception) {
                Log.e(TAG, "迁移失败：${e.message}", e)
            } finally {
                isMigrating = false
            }
        }
    }

    private suspend fun migrateStorage(
        oldConfig: MonitorConfig,
        newConfig: MonitorConfig
    ): Boolean = withContext(Dispatchers.IO) {
        Log.i(TAG, "开始存储迁移...")

        val oldBaseDir = if (oldConfig.preferExternalStorage) {
            val ext = File("/storage/emulated/0", oldConfig.rootDir)
            if (ext.exists() && ext.canWrite()) {
                ext
            } else {
                File(context.filesDir, oldConfig.rootDir)
            }
        } else {
            File(context.filesDir, oldConfig.rootDir)
        }

        val newBaseDir = if (newConfig.preferExternalStorage) {
            File("/storage/emulated/0", newConfig.rootDir)
        } else {
            File(context.filesDir, newConfig.rootDir)
        }
        if (!newBaseDir.exists()) newBaseDir.mkdirs()

        val rootDir = getRootDir()
        manageNomediaFile(rootDir, newConfig.preferExternalStorage)
        if (oldBaseDir.absolutePath == newBaseDir.absolutePath &&
            oldConfig.screenshotDir == newConfig.screenshotDir &&
            oldConfig.videoDir == newConfig.videoDir &&
            oldConfig.templateDir == newConfig.templateDir
        ) {
            Log.d(TAG, "存储路径相同，无需迁移")
            return@withContext true
        }

        var totalMoved = 0
        val allFailedMigrations = mutableListOf<Pair<String, String>>()

        val previousFailures = loadFailedMigrations()
        val retrySuccess = mutableListOf<Pair<String, String>>()
        for ((source, target) in previousFailures) {
            val sourceFile = File(source)
            val targetFile = File(target)
            if (sourceFile.exists() && targetFile.exists()) {
                try {
                    if (sourceFile.delete()) {
                        Log.d(TAG, "删除了重复源文件：${source}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "删除重复源文件失败：${source}", e)
                }
                retrySuccess.add(source to target)
            } else if (sourceFile.exists() && !targetFile.exists()) {
                try {
                    if (sourceFile.renameTo(targetFile)) {
                        Log.d(TAG, "重试迁移成功：${source}")
                        retrySuccess.add(source to target)
                    } else {
                        allFailedMigrations.add(source to target)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "重试迁移失败：${source}", e)
                    allFailedMigrations.add(source to target)
                }
            } else {
                retrySuccess.add(source to target)
            }
        }
        val remainingFailures = previousFailures.filterNot { it in retrySuccess }

        val knownDirs = listOf(
            Triple(oldConfig.screenshotDir, newConfig.screenshotDir, "截图"),
            Triple(oldConfig.videoDir, newConfig.videoDir, "视频"),
            Triple(oldConfig.templateDir, newConfig.templateDir, "模板"),
            Triple("Logs", "Logs", "日志")
        )

        for ((oldName, newName, desc) in knownDirs) {
            val oldDir = File(oldBaseDir, oldName)
            val newDir = File(newBaseDir, newName)
            if (oldDir.exists()) {
                Log.d(TAG, "迁移${desc}目录：从 ${oldDir.absolutePath} 到 ${newDir.absolutePath}")
                val (moved, failed) = migrateDirectory(
                    oldDir,
                    newDir,
                    newConfig.preferExternalStorage
                )
                totalMoved += moved
                allFailedMigrations.addAll(failed)
            }
        }

        val knownOldNames = knownDirs.map { it.first }
        val otherDirs = oldBaseDir.listFiles()?.filter { it.isDirectory && it.name !in knownOldNames }
            ?: emptyList()

        for (oldDir in otherDirs) {
            val newDir = File(newBaseDir, oldDir.name)
            Log.d(TAG, "迁移其他目录：从 ${oldDir.absolutePath} 到 ${newDir.absolutePath}")
            val (moved, failed) = migrateDirectory(oldDir, newDir, newConfig.preferExternalStorage)
            totalMoved += moved
            allFailedMigrations.addAll(failed)
        }

        val totalFailed = allFailedMigrations.size
        Log.i(TAG, "迁移完成：移动=$totalMoved, 失败=$totalFailed")

        val allFailures = remainingFailures + allFailedMigrations
        saveFailedMigrations(allFailures)

        if (totalFailed == 0) {
            clearFailedMigrations()
            cleanupOldDirectory(oldBaseDir)
        }

        totalFailed == 0
    }

    private fun migrateDirectory(
        sourceDir: File,
        targetDir: File,
        preferExternal: Boolean
    ): Pair<Int, List<Pair<String, String>>> {
        if (!sourceDir.exists() || !sourceDir.canRead()) {
            return 0 to emptyList()
        }

        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }

        var movedCount = 0
        val failedMigrations = mutableListOf<Pair<String, String>>()

        val files = sourceDir.listFiles() ?: return 0 to emptyList()

        for (file in files) {
            try {
                if (file.isDirectory) {
                    val targetSubDir = File(targetDir, file.name)
                    val (moved, subFailed) = migrateDirectory(file, targetSubDir, preferExternal)
                    movedCount += moved
                    failedMigrations.addAll(subFailed)
                } else {
                    val targetName = if (preferExternal) {
                        if (file.name.contains("tmp_")) {
                            file.name
                        } else {
                            val lastDotIndex = file.name.lastIndexOf('.')
                            if (lastDotIndex > 0) {
                                file.name.substring(0, lastDotIndex) + ".tmp_" + file.name.substring(lastDotIndex + 1)
                            } else {
                                file.name
                            }
                        }
                    } else {
                        if (file.name.contains("tmp_")) {
                            val lastDotIndex = file.name.lastIndexOf('.')
                            if (lastDotIndex > 0 && file.name.substring(lastDotIndex).startsWith(".tmp_")) {
                                file.name.substring(0, lastDotIndex) + "." + file.name.substring(lastDotIndex + 5)
                            } else {
                                file.name
                            }
                        } else {
                            file.name
                        }
                    }
                    val targetFile = File(targetDir, targetName)

                    if (targetFile.exists() && targetFile.length() == file.length()) {
                        file.delete()
                        Log.d(TAG, "删除了重复文件：${file.name}")
                        movedCount++
                    } else if (file.renameTo(targetFile)) {
                        movedCount++
                        Log.d(TAG, "已移动：${file.name} -> ${targetFile.name}")
                    } else {
                        file.inputStream().use { input ->
                            targetFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        if (file.delete()) {
                            movedCount++
                            Log.d(TAG, "已复制并删除：${file.name} -> ${targetFile.name}")
                        } else {
                            failedMigrations.add(file.absolutePath to targetFile.absolutePath)
                            Log.w(TAG, "复制后删除失败：${file.name}")
                        }
                    }
                }
            } catch (e: Exception) {
                val targetName = if (preferExternal) {
                    if (file.name.contains("tmp_")) {
                        file.name
                    } else {
                        val lastDotIndex = file.name.lastIndexOf('.')
                        if (lastDotIndex > 0) {
                            file.name.substring(0, lastDotIndex) + ".tmp_" + file.name.substring(lastDotIndex + 1)
                        } else {
                            file.name
                        }
                    }
                } else {
                    if (file.name.contains("tmp_")) {
                        val lastDotIndex = file.name.lastIndexOf('.')
                        if (lastDotIndex > 0 && file.name.substring(lastDotIndex).startsWith(".tmp_")) {
                            file.name.substring(0, lastDotIndex) + "." + file.name.substring(lastDotIndex + 5)
                        } else {
                            file.name
                        }
                    } else {
                        file.name
                    }
                }
                val targetFile = File(targetDir, targetName)
                failedMigrations.add(file.absolutePath to targetFile.absolutePath)
                Log.e(TAG, "迁移 ${file.name} 失败：${e.message}")
            }
        }

        return movedCount to failedMigrations
    }

    private fun cleanupOldDirectory(oldBaseDir: File) {
        try {
            if (!oldBaseDir.exists()) return

            cleanEmptyDirectories(oldBaseDir)

            val files = oldBaseDir.listFiles() ?: return
            for (file in files) {
                if (file.name.startsWith(".")) {
                    try {
                        if (file.isDirectory) {
                            file.deleteRecursively()
                        } else {
                            file.delete()
                        }
                        Log.d(TAG, "删除了隐藏文件/目录：${file.name}")
                    } catch (e: Exception) {
                        Log.e(TAG, "删除隐藏文件/目录失败：${file.name}", e)
                    }
                }
            }

            if (oldBaseDir.listFiles()?.isEmpty() == true) {
                if (oldBaseDir.delete()) {
                    Log.i(TAG, "清理了旧目录：${oldBaseDir.absolutePath}")
                } else {
                    Log.w(TAG, "清理旧目录失败：${oldBaseDir.absolutePath}")
                }
            } else {
                val filesLeft = oldBaseDir.listFiles() ?: return
                Log.d(
                    TAG,
                    "保留旧目录：${oldBaseDir.absolutePath} \n文件个数: ${filesLeft.size}, \n    文件名: ${filesLeft.map { it.name }.joinToString(", ")}"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "清理旧目录失败：${e.message}")
        }
    }

    private fun manageNomediaFile(dir: File, create: Boolean) {
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val nomediaFile = File(dir, ".nomedia")
        try {
            if (create) {
                if (!nomediaFile.exists()) {
                    nomediaFile.createNewFile()
                    Log.d(TAG, "创建了 .nomedia 文件：${nomediaFile.absolutePath}")
                }
            } else {
                if (nomediaFile.exists()) {
                    nomediaFile.delete()
                    Log.d(TAG, "删除了 .nomedia 文件：${nomediaFile.absolutePath}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "管理 .nomedia 文件失败：${e.message}", e)
        }
    }

    override fun updateConfig(config: MonitorConfig) {
        val oldConfig = currentConfig
        val hasFailures = loadFailedMigrations().isNotEmpty()
        Log.d(
            TAG,
            "检测到配置变化: preferExternalStorage ${oldConfig.preferExternalStorage} -> ${config.preferExternalStorage}, rootDir ${oldConfig.rootDir} -> ${config.rootDir}, 有失败迁移=${hasFailures}"
        )
        if (config.preferExternalStorage != oldConfig.preferExternalStorage ||
            config.rootDir != oldConfig.rootDir ||
            config.screenshotDir != oldConfig.screenshotDir ||
            config.videoDir != oldConfig.videoDir ||
            config.templateDir != oldConfig.templateDir ||
            hasFailures
        ) {
            Log.i(
                TAG,
                "存储路径已更改或有失败迁移需要重试：优先外部存储=${config.preferExternalStorage}, 根目录=${config.rootDir}, 截图目录=${config.screenshotDir}, 视频目录=${config.videoDir}, 模板目录=${config.templateDir}, 有失败=${hasFailures}"
            )
            Log.updateLogDir { getConfigRootDir(config) }
            migrateStorageAsync(oldConfig, config)
        }
        currentConfig = config
    }
}

package youyou.monitor.screen.infra.matcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import youyou.monitor.screen.core.domain.model.MatchResult
import youyou.monitor.screen.core.domain.repository.ConfigRepository
import youyou.monitor.screen.core.matcher.TemplateMatcher
import youyou.monitor.logger.Log
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * 灰度多尺度模板匹配器
 */
class GrayscaleMultiScaleMatcher(
    private val context: Context,
    private val configRepository: ConfigRepository
) : TemplateMatcher {

    private val TAG = "GrayscaleMultiScaleMatcher"

    private val lock = ReentrantReadWriteLock()
    private var templateGrays: List<Mat> = emptyList()
    private var templateNames: List<String> = emptyList()

    private val coarseScales = floatArrayOf(1.0f, 0.7f, 0.5f)
    private val fineScalesHigh = floatArrayOf(0.95f, 0.9f, 0.85f)
    private val fineScalesLow = floatArrayOf(0.55f, 0.48f, 0.45f)
    private val fineScalesMidLocal = ThreadLocal.withInitial { FloatArray(2) }

    companion object {
        private const val WEAK_MATCH_OFFSET = 0.04
        private const val EARLY_EXIT_OFFSET = 0.20
        private const val MIN_TEMPLATE_SIZE = 30
        private const val MAX_DIMENSION = 3200
    }

    override suspend fun loadTemplates(): Pair<Int, List<String>> {
        val config = configRepository.getCurrentConfig()
        val baseDir = if (config.preferExternalStorage) {
            val ext = File("/storage/emulated/0", config.rootDir)
            if (ext.exists() && ext.canWrite()) {
                ext
            } else {
                File(context.filesDir, config.rootDir)
            }
        } else {
            File(context.filesDir, config.rootDir)
        }

        val templateDir = File(baseDir, config.templateDir).apply {
            if (!exists()) mkdirs()
        }

        Log.d(TAG, "正在从以下位置加载模板: ${templateDir.absolutePath}")

        if (!templateDir.exists() || !templateDir.isDirectory) {
            Log.e(TAG, "未找到模板目录: ${templateDir.absolutePath}")
            return Pair(0, emptyList())
        }

        val files = templateDir.listFiles { f ->
            f.isFile && (f.name.endsWith(".png", ignoreCase = true) ||
                f.name.endsWith(".jpg", ignoreCase = true))
        } ?: emptyArray()

        val bitmaps = mutableListOf<Bitmap>()
        val names = mutableListOf<String>()

        for (file in files) {
            try {
                val bmp = BitmapFactory.decodeFile(file.absolutePath)
                if (bmp != null) {
                    bitmaps.add(bmp)
                    names.add(file.name)
                } else {
                    Log.w(TAG, "解码位图失败: ${file.name}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "加载模板出错 ${file.name}: ${e.message}")
            }
        }

        val newTemplateGrays = bitmaps.mapNotNull { bmp ->
            convertBitmapToGrayMat(bmp)
        }

        val oldTemplateGrays = lock.write {
            val old = templateGrays
            templateGrays = newTemplateGrays
            templateNames = names
            old
        }

        oldTemplateGrays.forEach { it.release() }
        bitmaps.forEach { it.recycle() }

        Log.d(TAG, "已加载 ${newTemplateGrays.size} 个模板: $names")

        return Pair(newTemplateGrays.size, names)
    }

    override suspend fun match(grayMat: Mat, scale: Int): MatchResult? {
        val (templates, names) = lock.read {
            Pair(templateGrays, templateNames)
        }

        if (templates.isEmpty()) {
            Log.w(TAG, "未加载任何模板")
            return null
        }

        val config = configRepository.getCurrentConfig()
        val threshold = config.matchThreshold
        val weakThreshold = threshold - WEAK_MATCH_OFFSET

        for ((idx, tmpl) in templates.withIndex()) {
            val templateName = names.getOrNull(idx) ?: "template$idx"
            val templateStartTime = System.currentTimeMillis()

            var bestScore = Double.NEGATIVE_INFINITY
            var bestScale = 1.0f
            val scaleScores = mutableListOf<Pair<Float, Double>>()

            for (scaleFactor in coarseScales) {
                val scaledWidth = (tmpl.cols() * scaleFactor).toInt()
                val scaledHeight = (tmpl.rows() * scaleFactor).toInt()

                if (scaledWidth > grayMat.cols() || scaledHeight > grayMat.rows()) continue
                if (scaledWidth < MIN_TEMPLATE_SIZE || scaledHeight < MIN_TEMPLATE_SIZE) continue

                val score = matchAtScale(tmpl, grayMat, scaleFactor)
                scaleScores.add(Pair(scaleFactor, score))
                if (score > bestScore) {
                    bestScore = score
                    bestScale = scaleFactor
                }
            }

            if (bestScore < threshold - EARLY_EXIT_OFFSET) {
                if (scaleScores.size == coarseScales.size) {
                    Log.d(TAG, "[$templateName] 跳过细搜索 (粗搜索最佳=${String.format("%.3f", bestScore)} << 阈值)")
                }
                continue
            }

            val fineScales = when {
                bestScale >= 0.9f -> fineScalesHigh
                bestScale >= 0.65f -> {
                    val fineScalesMid = fineScalesMidLocal.get()!!
                    fineScalesMid[0] = bestScale + 0.05f
                    fineScalesMid[1] = bestScale - 0.05f
                    fineScalesMid
                }
                else -> fineScalesLow
            }

            for (scaleFactor in fineScales) {
                if (scaleFactor == bestScale) continue

                val scaledWidth = (tmpl.cols() * scaleFactor).toInt()
                val scaledHeight = (tmpl.rows() * scaleFactor).toInt()

                if (scaledWidth > grayMat.cols() || scaledHeight > grayMat.rows()) continue
                if (scaledWidth < MIN_TEMPLATE_SIZE || scaledHeight < MIN_TEMPLATE_SIZE) continue

                val score = matchAtScale(tmpl, grayMat, scaleFactor)
                scaleScores.add(Pair(scaleFactor, score))
                if (score > bestScore) {
                    bestScore = score
                    bestScale = scaleFactor
                }
            }

            val templateElapsed = System.currentTimeMillis() - templateStartTime

            if (bestScore > threshold - 0.10 && scaleScores.isNotEmpty()) {
                val scoresStr = scaleScores.sortedByDescending { it.second }
                    .take(5)
                    .joinToString(", ") { "${String.format("%.2f", it.first)}=${String.format("%.3f", it.second)}" }
                Log.d(TAG, "[$templateName] ${scaleScores.size} 个尺度在 ${templateElapsed}ms 内完成，最佳: [$scoresStr]")
            }

            if (bestScore >= threshold) {
                Log.i(TAG, "✓ 匹配成功: $templateName (分数=$bestScore, 尺度=${String.format("%.2f", bestScale)}, 阈值=$threshold)")
                return MatchResult(
                    templateName = templateName,
                    score = bestScore,
                    scale = bestScale,
                    timeMs = templateElapsed,
                    isWeak = false
                )
            } else if (bestScore >= weakThreshold) {
                Log.i(TAG, "⚠ 弱匹配: $templateName (分数=$bestScore, 尺度=${String.format("%.2f", bestScale)}, 阈值=$threshold, 差异=${String.format("%.3f", threshold - bestScore)})")
                return MatchResult(
                    templateName = "weak_$templateName",
                    score = bestScore,
                    scale = bestScale,
                    timeMs = templateElapsed,
                    isWeak = true
                )
            }
        }

        Log.d(TAG, "✗ 无匹配 (阈值=$threshold)")
        return null
    }

    override suspend fun reloadTemplates() {
        loadTemplates()
    }

    override fun release() {
        val oldTemplateGrays = lock.write {
            val old = templateGrays
            templateGrays = emptyList()
            templateNames = emptyList()
            old
        }

        oldTemplateGrays.forEach { mat ->
            try {
                mat.release()
            } catch (e: Exception) {
                Log.e(TAG, "释放Mat出错: ${e.message}")
            }
        }

        fineScalesMidLocal.remove()
        Log.d(TAG, "已释放所有模板")
    }

    private fun matchAtScale(template: Mat, image: Mat, scale: Float): Double {
        var scaledTmpl: Mat? = null
        var result: Mat? = null
        return try {
            scaledTmpl = if (scale != 1.0f) {
                val scaledWidth = (template.cols() * scale).toInt()
                val scaledHeight = (template.rows() * scale).toInt()
                Mat().apply {
                    val newSize = Size(scaledWidth.toDouble(), scaledHeight.toDouble())
                    Imgproc.resize(template, this, newSize, 0.0, 0.0, Imgproc.INTER_AREA)
                }
            } else {
                template
            }

            val resultCols = image.cols() - scaledTmpl.cols() + 1
            val resultRows = image.rows() - scaledTmpl.rows() + 1

            if (resultCols <= 0 || resultRows <= 0) {
                return Double.NEGATIVE_INFINITY
            }

            result = Mat(resultRows, resultCols, CvType.CV_32FC1)
            Imgproc.matchTemplate(image, scaledTmpl, result, Imgproc.TM_CCOEFF_NORMED)

            val mm = Core.minMaxLoc(result)
            mm.maxVal
        } catch (e: Exception) {
            Log.e(TAG, "matchAtScale在尺度=${scale}时出错: ${e.message}")
            Double.NEGATIVE_INFINITY
        } finally {
            result?.release()
            if (scale != 1.0f) scaledTmpl?.release()
        }
    }

    private fun convertBitmapToGrayMat(bmp: Bitmap): Mat? {
        var tmp: Mat? = null
        var resized: Mat? = null
        return try {
            tmp = Mat()
            Utils.bitmapToMat(bmp, tmp)

            val result = if (tmp.cols() > MAX_DIMENSION || tmp.rows() > MAX_DIMENSION) {
                val maxDim = maxOf(tmp.cols(), tmp.rows())
                val scale = MAX_DIMENSION.toFloat() / maxDim
                resized = Mat()
                val newSize = Size((tmp.cols() * scale).toDouble(), (tmp.rows() * scale).toDouble())
                Imgproc.resize(tmp, resized, newSize, 0.0, 0.0, Imgproc.INTER_AREA)
                Imgproc.cvtColor(resized, resized, Imgproc.COLOR_RGBA2GRAY)
                resized
            } else {
                Imgproc.cvtColor(tmp, tmp, Imgproc.COLOR_RGBA2GRAY)
                tmp
            }

            if (result === tmp) tmp = null else resized = null
            result
        } catch (e: Exception) {
            Log.e(TAG, "将位图转换为Mat失败: ${e.message}")
            null
        } finally {
            tmp?.release()
            resized?.release()
        }
    }
}

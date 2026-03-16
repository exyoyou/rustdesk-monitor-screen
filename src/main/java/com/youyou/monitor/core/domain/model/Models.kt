package youyou.monitor.screen.core.domain.model

/**
 * 模板匹配结果
 */
data class MatchResult(
    val templateName: String,
    val score: Double,
    val scale: Float,
    val timeMs: Long,
    val isWeak: Boolean = false
)

/**
 * 图像帧数据
 *
 * @param scale 屏幕缩放比例（1=原始分辨率，2=半分辨率，用于性能优化）
 */
data class ImageFrame(
    val width: Int,
    val height: Int,
    val data: ByteArray,
    val scale: Int = 1,
    val timestamp: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ImageFrame
        if (width != other.width) return false
        if (height != other.height) return false
        if (!data.contentEquals(other.data)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + data.contentHashCode()
        return result
    }
}

typealias WebDavServer = youyou.monitor.config.model.WebDavServer
typealias MonitorConfig = youyou.monitor.config.model.MonitorConfig

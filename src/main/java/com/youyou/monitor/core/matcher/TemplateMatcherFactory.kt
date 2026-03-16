package youyou.monitor.screen.core.matcher

import android.content.Context
import android.util.Log
import youyou.monitor.screen.core.domain.repository.ConfigRepository
import youyou.monitor.screen.infra.matcher.GrayscaleMultiScaleMatcher
import youyou.monitor.screen.infra.matcher.SmartGridScaleMatcher

/**
 * 模板匹配器工厂
 * 根据配置动态创建匹配器实例
 */
object TemplateMatcherFactory {
    private val TAG = "TemplateMatcherFactory"
    /**
     * 创建匹配器实例
     * @param matcherType 匹配器类型
     * @param context Android 上下文
     * @param configRepository 配置仓库
     * @return 匹配器实例
     */
    fun createMatcher(
        matcherType: String,
        context: Context,
        configRepository: ConfigRepository
    ): TemplateMatcher {
        return when (matcherType.lowercase()) {
            "grayscale", "grayscalemultiscale" -> {
                Log.d(TAG, "创建匹配器实例：GrayscaleMultiScaleMatcher")
                GrayscaleMultiScaleMatcher(context, configRepository)
            }
            "smart" -> {
                Log.d(TAG, "创建匹配器实例：SmartGridScaleMatcher")
                SmartGridScaleMatcher(context, configRepository)
            }
            else -> {
                Log.d(TAG, "默认使用聊天窗口匹配器 SmartGridScaleMatcher")
                GrayscaleMultiScaleMatcher(context, configRepository)
            }
        }
    }
}

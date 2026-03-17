package youyou.monitor.screen.core.matcher

import android.content.Context
import youyou.monitor.config.model.MonitorConfig
import youyou.monitor.config.repository.ConfigRepository
import youyou.monitor.logger.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * 模板匹配器管理器
 * 负责根据配置动态管理匹配器实例
 */
class TemplateMatcherManager(
    private val context: Context,
    private val configRepository: ConfigRepository
) {
    private val TAG = "TemplateMatcherManager"

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @Volatile
    private var currentMatcher: TemplateMatcher? = null

    @Volatile
    private var currentConfig: MonitorConfig = MonitorConfig.default()

    init {
        configRepository.getConfigFlow()
            .onEach { newConfig ->
                val oldConfig = currentConfig
                if (newConfig.matcherType != oldConfig.matcherType) {
                    Log.i(TAG, "匹配器类型从 ${oldConfig.matcherType} 更改为 ${newConfig.matcherType}，正在重新创建匹配器...")
                    recreateMatcher(newConfig)
                }
                currentConfig = newConfig
            }
            .launchIn(scope)

        recreateMatcher(currentConfig)
    }

    /**
     * 获取当前匹配器
     */
    fun getMatcher(): TemplateMatcher {
        val matcher = currentMatcher
        return if (matcher != null && isMatcherTypeValid(matcher, currentConfig.matcherType)) {
            matcher
        } else {
            synchronized(this) {
                currentMatcher?.let { existing ->
                    if (isMatcherTypeValid(existing, currentConfig.matcherType)) {
                        return existing
                    }
                }

                Log.d(TAG, "为类型创建新匹配器: ${currentConfig.matcherType}")
                val newMatcher = TemplateMatcherFactory.createMatcher(
                    currentConfig.matcherType,
                    context,
                    configRepository
                )
                currentMatcher = newMatcher

                scope.launch(Dispatchers.IO) {
                    try {
                        newMatcher.loadTemplates()
                        Log.d(TAG, "为匹配器加载了模板: ${currentConfig.matcherType}")
                    } catch (e: Exception) {
                        Log.e(TAG, "加载模板失败: ${e.message}", e)
                    }
                }

                newMatcher
            }
        }
    }

    private fun isMatcherTypeValid(matcher: TemplateMatcher, expectedType: String): Boolean {
        return true
    }

    /**
     * 重新创建匹配器
     */
    private fun recreateMatcher(config: MonitorConfig) {
        synchronized(this) {
            try {
                currentMatcher?.release()

                val newMatcher = TemplateMatcherFactory.createMatcher(
                    config.matcherType,
                    context,
                    configRepository
                )

                currentMatcher = newMatcher

                Log.i(TAG, "匹配器切换到: ${config.matcherType}")

                scope.launch(Dispatchers.IO) {
                    try {
                        val (count, names) = newMatcher.loadTemplates()
                        Log.i(TAG, "为 ${config.matcherType} 加载了 $count 个模板: $names")
                    } catch (e: Exception) {
                        Log.e(TAG, "为 ${config.matcherType} 加载模板失败: ${e.message}", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "重新创建匹配器失败: ${e.message}", e)
            }
        }
    }

    /**
     * 释放资源
     */
    fun release() {
        currentMatcher?.release()
        currentMatcher = null
    }
}

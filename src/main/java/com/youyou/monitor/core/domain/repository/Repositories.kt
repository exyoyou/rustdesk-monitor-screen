package youyou.monitor.screen.core.domain.repository

import youyou.monitor.config.model.MonitorConfig

/**
 * 模板仓储接口
 */
interface TemplateRepository {
    /**
     * 从远程同步模板
     */
    suspend fun syncFromRemote(): Result<Int>

    /**
     * 通知模板已更新（触发重新加载）
     */
    fun notifyTemplatesUpdated()

    /**
     * 更新配置（由上层显式调用，例如 MonitorService）
     */
    fun updateConfig(config: MonitorConfig)
}
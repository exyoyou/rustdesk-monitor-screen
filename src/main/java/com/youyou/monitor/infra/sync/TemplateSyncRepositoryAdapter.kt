package youyou.monitor.screen.infra.sync

import youyou.monitor.screen.core.domain.repository.TemplateRepository
import youyou.monitor.sync.repository.TemplateSyncRepository

class TemplateSyncRepositoryAdapter(
    private val delegate: TemplateRepository
) : TemplateSyncRepository {
    override suspend fun syncFromRemote(): Result<Int> = delegate.syncFromRemote()
}

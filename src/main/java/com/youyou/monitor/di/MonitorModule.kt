package youyou.monitor.screen.di

import android.content.Context
import youyou.monitor.config.repository.ConfigRepository
import youyou.monitor.sync.storage.StorageRepository
import youyou.monitor.screen.core.domain.repository.TemplateRepository
import youyou.monitor.screen.core.domain.usecase.CleanStorageUseCase
import youyou.monitor.screen.core.domain.usecase.ManageTemplatesUseCase
import youyou.monitor.screen.core.matcher.TemplateMatcher
import youyou.monitor.screen.core.matcher.TemplateMatcherManager
import youyou.monitor.logger.Log
import youyou.monitor.screen.infra.processor.AdvancedFrameProcessor
import youyou.monitor.screen.infra.repository.TemplateRepositoryImpl
import youyou.monitor.screen.infra.sync.TemplateSyncRepositoryAdapter
import youyou.monitor.sync.config.ConfigRepositoryImpl
import youyou.monitor.sync.storage.StorageRepositoryImpl as SyncStorageRepositoryImpl
import youyou.monitor.sync.repository.StorageSyncRepository
import youyou.monitor.sync.repository.TemplateSyncRepository
import youyou.monitor.sync.task.ScheduledTaskManager
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.error.KoinAppAlreadyStartedException
import org.koin.dsl.module

/**
 * Koin 依赖注入模块
 */
val monitorModule = module {
    single {
        ConfigRepositoryImpl(androidContext(), youyou.monitor.screen.BuildConfig.DEBUG)
    }

    single<ConfigRepository> {
        get<ConfigRepositoryImpl>()
    }

    single<StorageRepository> {
        SyncStorageRepositoryImpl(androidContext())
    }

    single<TemplateMatcherManager> {
        TemplateMatcherManager(androidContext(), get())
    }

    factory<TemplateMatcher> {
        get<TemplateMatcherManager>().getMatcher()
    }

    single {
        TemplateRepositoryImpl(androidContext(), get(), get())
    }

    single<TemplateRepository> {
        get<TemplateRepositoryImpl>()
    }

    single<StorageSyncRepository> {
        get<StorageRepository>()
    }

    single<TemplateSyncRepository> {
        TemplateSyncRepositoryAdapter(get())
    }

    single<AdvancedFrameProcessor> {
        AdvancedFrameProcessor(get(), get(), get<TemplateMatcherManager>())
    }

    single<ScheduledTaskManager> {
        ScheduledTaskManager(get(), get(), get())
    }

    factory { ManageTemplatesUseCase(get()) }
    factory { CleanStorageUseCase(get(), get()) }
}

/**
 * 初始化 Koin（包含日志系统）
 */
fun initKoin(context: Context) {
    if (Log.getCurrentLogFile() == null) {
        Log.init(context)
    }

    try {
        startKoin {
            androidContext(context)
            modules(monitorModule)
        }
        Log.i("MonitorModule", "Koin initialized successfully")
    } catch (e: KoinAppAlreadyStartedException) {
        Log.w("MonitorModule", "Koin already started, skipping initialization")
    }
}

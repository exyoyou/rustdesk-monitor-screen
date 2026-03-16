package youyou.monitor.screen.di

import android.content.Context
import youyou.monitor.screen.core.domain.repository.ConfigRepository
import youyou.monitor.screen.core.domain.repository.StorageRepository
import youyou.monitor.screen.core.domain.repository.TemplateRepository
import youyou.monitor.screen.core.domain.usecase.CleanStorageUseCase
import youyou.monitor.screen.core.domain.usecase.ManageTemplatesUseCase
import youyou.monitor.screen.core.matcher.TemplateMatcher
import youyou.monitor.screen.core.matcher.TemplateMatcherManager
import youyou.monitor.screen.infra.logger.Log
import youyou.monitor.screen.infra.processor.AdvancedFrameProcessor
import youyou.monitor.screen.infra.repository.ConfigRepositoryImpl
import youyou.monitor.screen.infra.repository.StorageRepositoryImpl
import youyou.monitor.screen.infra.repository.TemplateRepositoryImpl
import youyou.monitor.screen.infra.task.ScheduledTaskManager
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.error.KoinAppAlreadyStartedException
import org.koin.dsl.module

/**
 * Koin 依赖注入模块
 */
val monitorModule = module {
    single {
        ConfigRepositoryImpl(androidContext(), get())
    }

    single<ConfigRepository> {
        get<ConfigRepositoryImpl>()
    }

    single<StorageRepository> {
        StorageRepositoryImpl(androidContext())
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

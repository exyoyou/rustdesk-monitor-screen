package com.youyou.monitor.infra.logger

object Log {
    fun d(tag: String, message: String) = youyou.monitor.screen.infra.logger.Log.d(tag, message)
    fun i(tag: String, message: String) = youyou.monitor.screen.infra.logger.Log.i(tag, message)
    fun w(tag: String, message: String) = youyou.monitor.screen.infra.logger.Log.w(tag, message)
    fun e(tag: String, message: String) = youyou.monitor.screen.infra.logger.Log.e(tag, message)
    fun e(tag: String, message: String, throwable: Throwable) =
        youyou.monitor.screen.infra.logger.Log.e(tag, message, throwable)
    fun v(tag: String, message: String) = youyou.monitor.screen.infra.logger.Log.v(tag, message)
    fun init(context: android.content.Context) = youyou.monitor.screen.infra.logger.Log.init(context)
    fun updateLogDir(getRootDir: () -> java.io.File) =
        youyou.monitor.screen.infra.logger.Log.updateLogDir(getRootDir)
    fun forceRotate() = youyou.monitor.screen.infra.logger.Log.forceRotate()
    fun getLogDirectory(): String? = youyou.monitor.screen.infra.logger.Log.getLogDirectory()
    fun getCurrentLogFile(): String? = youyou.monitor.screen.infra.logger.Log.getCurrentLogFile()
    fun shutdown() = youyou.monitor.screen.infra.logger.Log.shutdown()
    fun shutdownCompletely() = youyou.monitor.screen.infra.logger.Log.shutdownCompletely()
}

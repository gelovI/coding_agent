package org.ivangelov.agent.app.util

interface Logger {
    fun info(message: String)
    fun warn(message: String)
    fun error(message: String, throwable: Throwable? = null)
}

class StdoutLogger : Logger {
    override fun info(message: String) {
        println("[INFO] $message")
    }

    override fun warn(message: String) {
        println("[WARN] $message")
    }

    override fun error(message: String, throwable: Throwable?) {
        println("[ERROR] $message")
        throwable?.printStackTrace()
    }
}
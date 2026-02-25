package org.ivangelov.agent.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver

object DbFactory {

    fun create(path: String = "coding-agent.db"): AgentDb {
        val home = System.getProperty("user.home")
        val fullPath = "$home/$path"

        println("DB PATH = $fullPath")

        val driver = JdbcSqliteDriver(
            url = "jdbc:sqlite:$fullPath",
            schema = AgentDb.Schema
        )

        return AgentDb(driver)
    }
}
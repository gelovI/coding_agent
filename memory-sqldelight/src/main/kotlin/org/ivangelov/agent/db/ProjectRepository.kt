package org.ivangelov.agent.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

class ProjectRepository(
    private val db: AgentDb
) {
    private val q = db.projectQueries

    suspend fun list(tenantId: String): List<Project> = withContext(Dispatchers.IO) {
        q.listProjects(tenantId).executeAsList()
    }

    suspend fun get(tenantId: String, id: String): Project? = withContext(Dispatchers.IO) {
        q.getProject(tenantId, id).executeAsOneOrNull()
    }

    suspend fun create(
        tenantId: String,
        name: String,
        rootPath: String?
    ): String = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        q.upsertProject(
            id = id,
            tenantId = tenantId,
            name = name,
            rootPath = rootPath,
            createdAt = now,
            updatedAt = now
        )
        id
    }

    suspend fun upsert(
        tenantId: String,
        id: String,
        name: String,
        rootPath: String?,
        createdAt: Long
    ) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        q.upsertProject(
            id = id,
            tenantId = tenantId,
            name = name,
            rootPath = rootPath,
            createdAt = createdAt,
            updatedAt = now
        )
    }

    suspend fun delete(tenantId: String, id: String) = withContext(Dispatchers.IO) {
        q.deleteProject(tenantId, id)
    }
}
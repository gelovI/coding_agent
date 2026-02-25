package org.ivangelov.agent.tools.code.indexing

data class CodeChunk(
    val id: String,
    val filePath: String,
    val index: Int,
    val total: Int,
    val content: String
)

object CodeChunker {
    fun chunkCode(
        filePath: String,
        content: String,
        maxChunkChars: Int = 1400,
        overlapChars: Int = 150
    ): List<CodeChunk> {
        if (content.isBlank()) return emptyList()

        val rawChunks = mutableListOf<String>()
        var start = 0

        while (start < content.length) {
            val end = (start + maxChunkChars).coerceAtMost(content.length)
            rawChunks += content.substring(start, end)
            if (end == content.length) break
            start = (end - overlapChars).coerceAtLeast(0)
        }

        val total = rawChunks.size
        return rawChunks.mapIndexed { i, chunk ->
            CodeChunk(
                id = "$filePath#$i",
                filePath = filePath,
                index = i,
                total = total,
                content = chunk
            )
        }
    }
}
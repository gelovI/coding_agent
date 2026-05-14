package org.ivangelov.agent.orchestrator.edit

object KotlinBlockExtractor {

    private val composableFunRegex =
        Regex("""@Composable\s+fun\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(""", RegexOption.MULTILINE)

    private val funRegex =
        Regex("""fun\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(""", RegexOption.MULTILINE)

    private val classRegex =
        Regex("""class\s+([A-Za-z_][A-Za-z0-9_]*)\b""", RegexOption.MULTILINE)

    private val propertyRegex =
        Regex("""\b(val|var)\s+([A-Za-z_][A-Za-z0-9_]*)\s*=""", RegexOption.MULTILINE)

    fun extractRelevantBlocks(
        fileContent: String,
        userText: String
    ): List<TargetBlock> {
        val requestedNames = extractRequestedIdentifiers(userText)

        val blocks = mutableListOf<TargetBlock>()

        blocks += extractBlocksFromRegex(
            fileContent = fileContent,
            regex = composableFunRegex,
            kind = BlockKind.COMPOSABLE_FUNCTION,
            requestedNames = requestedNames
        )

        blocks += extractBlocksFromRegex(
            fileContent = fileContent,
            regex = funRegex,
            kind = BlockKind.FUNCTION,
            requestedNames = requestedNames
        )

        blocks += extractBlocksFromRegex(
            fileContent = fileContent,
            regex = classRegex,
            kind = BlockKind.CLASS,
            requestedNames = requestedNames
        )

        blocks += extractPropertyBlocks(
            fileContent = fileContent,
            requestedNames = requestedNames
        )

        return blocks
            .distinctBy { "${it.kind}:${it.identifier}:${it.startOffset}:${it.endOffset}" }
            .sortedBy { it.startOffset }
    }

    private fun extractRequestedIdentifiers(userText: String): Set<String> {
        val ids = mutableSetOf<String>()

        Regex("""\b[A-Z][A-Za-z0-9_]*\b""").findAll(userText).forEach {
            ids += it.value
        }
        Regex("""\b[a-z][A-Za-z0-9_]*\b""").findAll(userText).forEach {
            val v = it.value
            if (v.length > 2) ids += v
        }

        return ids
    }

    private fun extractBlocksFromRegex(
        fileContent: String,
        regex: Regex,
        kind: BlockKind,
        requestedNames: Set<String>
    ): List<TargetBlock> {
        val out = mutableListOf<TargetBlock>()

        regex.findAll(fileContent).forEach { match ->
            val identifier = match.groupValues.getOrNull(1).orEmpty()
            if (requestedNames.isNotEmpty() && identifier !in requestedNames) return@forEach

            val start = match.range.first
            val end = findBalancedBlockEnd(fileContent, start) ?: return@forEach

            out += TargetBlock(
                kind = kind,
                identifier = identifier,
                startOffset = start,
                endOffset = end,
                originalText = fileContent.substring(start, end)
            )
        }

        return out
    }

    private fun extractPropertyBlocks(
        fileContent: String,
        requestedNames: Set<String>
    ): List<TargetBlock> {
        val out = mutableListOf<TargetBlock>()

        propertyRegex.findAll(fileContent).forEach { match ->
            val identifier = match.groupValues.getOrNull(2).orEmpty()
            if (requestedNames.isNotEmpty() && identifier !in requestedNames) return@forEach

            val start = match.range.first
            val end = findLineEnd(fileContent, match.range.last)

            out += TargetBlock(
                kind = BlockKind.PROPERTY,
                identifier = identifier,
                startOffset = start,
                endOffset = end,
                originalText = fileContent.substring(start, end)
            )
        }

        return out
    }

    private fun findLineEnd(content: String, from: Int): Int {
        val idx = content.indexOf('\n', from)
        return if (idx == -1) content.length else idx + 1
    }

    private fun findBalancedBlockEnd(content: String, declarationStart: Int): Int? {
        val openBrace = content.indexOf('{', declarationStart)
        if (openBrace == -1) return null

        var depth = 0
        var i = openBrace

        while (i < content.length) {
            when (content[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i + 1
                }
            }
            i++
        }

        return null
    }
}
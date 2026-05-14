package org.ivangelov.agent.orchestrator.edit

import kotlin.test.Test
import kotlin.test.assertEquals

class EditStrategySelectorTest {

    private val editIntent = EditIntent(
        userText = "ändere die Funktion",
        targetPath = "Example.kt",
        modificationRequested = true
    )

    @Test
    fun smallFileWithoutTargetBlockCanUseFullRewrite() {
        val plan = EditStrategySelector.choose(
            fileContent = "class Example\n",
            intent = editIntent,
            targetBlocks = emptyList()
        )

        assertEquals(EditStrategy.FULL_FILE_REWRITE, plan.strategy)
    }

    @Test
    fun largeFileWithoutTargetBlockNeedsLocalization() {
        val plan = EditStrategySelector.choose(
            fileContent = "x".repeat(20_000),
            intent = editIntent,
            targetBlocks = emptyList()
        )

        assertEquals(EditStrategy.NEEDS_TARGET_LOCALIZATION, plan.strategy)
    }

    @Test
    fun veryLargeTargetBlockNeedsNarrowerLocalization() {
        val block = TargetBlock(
            kind = BlockKind.CLASS,
            identifier = "HugeClass",
            startOffset = 0,
            endOffset = 20_001,
            originalText = "x".repeat(20_001)
        )

        val plan = EditStrategySelector.choose(
            fileContent = block.originalText,
            intent = editIntent,
            targetBlocks = listOf(block)
        )

        assertEquals(EditStrategy.NEEDS_TARGET_LOCALIZATION, plan.strategy)
    }

    @Test
    fun mediumTargetBlockStillUsesBlockRewrite() {
        val block = TargetBlock(
            kind = BlockKind.FUNCTION,
            identifier = "medium",
            startOffset = 0,
            endOffset = 8_000,
            originalText = "x".repeat(8_000)
        )

        val plan = EditStrategySelector.choose(
            fileContent = "x".repeat(40_000),
            intent = editIntent,
            targetBlocks = listOf(block)
        )

        assertEquals(EditStrategy.BLOCK_REWRITE, plan.strategy)
    }
}

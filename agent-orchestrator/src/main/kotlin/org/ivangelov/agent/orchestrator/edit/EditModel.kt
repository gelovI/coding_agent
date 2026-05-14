package org.ivangelov.agent.orchestrator.edit

data class EditIntent(
    val userText: String,
    val targetPath: String,
    val modificationRequested: Boolean,
    val requestedComments: Boolean = false,
    val requestedRefactor: Boolean = false,
    val requestedFix: Boolean = false
)

data class TargetBlock(
    val kind: BlockKind,
    val identifier: String,
    val startOffset: Int,
    val endOffset: Int,
    val originalText: String
)

enum class BlockKind {
    FUNCTION,
    CLASS,
    PROPERTY,
    COMPOSABLE_FUNCTION,
    UNKNOWN
}

data class PlannedBlockEdit(
    val targetBlock: TargetBlock,
    val replacementText: String
)

data class EditPlan(
    val strategy: EditStrategy,
    val targetBlocks: List<TargetBlock> = emptyList(),
    val reason: String? = null
)
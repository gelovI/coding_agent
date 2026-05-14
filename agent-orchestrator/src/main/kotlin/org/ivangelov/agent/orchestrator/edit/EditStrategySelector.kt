package org.ivangelov.agent.orchestrator.edit

object EditStrategySelector {

    private const val SMALL_FILE_FULL_REWRITE_LIMIT = 8_000
    private const val SMALL_FILE_MANY_EDITS_REWRITE_LIMIT = 10_000
    private const val SMALL_LOCAL_BLOCK_LIMIT = 4_000
    private const val LARGE_BLOCK_REWRITE_LIMIT = 16_000

    fun choose(
        fileContent: String,
        intent: EditIntent,
        targetBlocks: List<TargetBlock>
    ): EditPlan {
        val fileLength = fileContent.length

        if (!intent.modificationRequested) {
            return EditPlan(
                strategy = EditStrategy.NEEDS_TARGET_LOCALIZATION,
                reason = "No modification intent detected."
            )
        }

        if (targetBlocks.isEmpty()) {
            return if (fileLength < SMALL_FILE_FULL_REWRITE_LIMIT) {
                EditPlan(
                    strategy = EditStrategy.FULL_FILE_REWRITE,
                    reason = "No target block found, but file is small enough for full rewrite."
                )
            } else {
                EditPlan(
                    strategy = EditStrategy.NEEDS_TARGET_LOCALIZATION,
                    reason = "No target block found in large file."
                )
            }
        }

        if (targetBlocks.size == 1) {
            val block = targetBlocks.first()
            val blockLength = block.originalText.length

            return when {
                blockLength < SMALL_LOCAL_BLOCK_LIMIT -> {
                    EditPlan(
                        strategy = EditStrategy.BLOCK_REWRITE,
                        targetBlocks = targetBlocks,
                        reason = "Single local block edit."
                    )
                }

                blockLength <= LARGE_BLOCK_REWRITE_LIMIT -> {
                    EditPlan(
                        strategy = EditStrategy.BLOCK_REWRITE,
                        targetBlocks = targetBlocks,
                        reason = "Single large block edit, but still safer than full rewrite."
                    )
                }

                else -> {
                    EditPlan(
                        strategy = EditStrategy.NEEDS_TARGET_LOCALIZATION,
                        reason = "Target block is too large for a reliable rewrite; needs a narrower function or section."
                    )
                }
            }
        }

        if (targetBlocks.size in 2..5) {
            return EditPlan(
                strategy = EditStrategy.MULTI_EXACT_REPLACE,
                targetBlocks = targetBlocks,
                reason = "Multiple local block edits."
            )
        }

        return if (fileLength < SMALL_FILE_MANY_EDITS_REWRITE_LIMIT) {
            EditPlan(
                strategy = EditStrategy.FULL_FILE_REWRITE,
                reason = "Too many local edits; full rewrite safer for smaller file."
            )
        } else {
            EditPlan(
                strategy = EditStrategy.NEEDS_TARGET_LOCALIZATION,
                reason = "Too many edits in large file; needs more precise localization."
            )
        }
    }
}

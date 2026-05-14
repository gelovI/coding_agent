package org.ivangelov.agent.orchestrator.edit

object EditStrategySelector {

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
            return if (fileLength < 8_000) {
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
            val smallLocalEdit = block.originalText.length < 4_000

            return if (smallLocalEdit) {
                EditPlan(
                    strategy = EditStrategy.BLOCK_REWRITE,
                    targetBlocks = targetBlocks,
                    reason = "Single local block edit."
                )
            } else {
                EditPlan(
                    strategy = EditStrategy.BLOCK_REWRITE,
                    targetBlocks = targetBlocks,
                    reason = "Single large block edit, but still safer than full rewrite."
                )
            }
        }

        if (targetBlocks.size in 2..5) {
            return EditPlan(
                strategy = EditStrategy.MULTI_EXACT_REPLACE,
                targetBlocks = targetBlocks,
                reason = "Multiple local block edits."
            )
        }

        return if (fileLength < 10_000) {
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
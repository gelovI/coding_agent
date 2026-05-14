package org.ivangelov.agent.orchestrator.edit

enum class EditStrategy {
    SMALL_EXACT_REPLACE,
    MULTI_EXACT_REPLACE,
    FULL_FILE_REWRITE,
    BLOCK_REWRITE,
    NEEDS_TARGET_LOCALIZATION
}
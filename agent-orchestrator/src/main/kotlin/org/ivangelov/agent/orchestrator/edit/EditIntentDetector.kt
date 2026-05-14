package org.ivangelov.agent.orchestrator.edit

object EditIntentDetector {

    fun detect(userText: String, targetPath: String): EditIntent {
        val t = userText.lowercase()

        return EditIntent(
            userText = userText,
            targetPath = targetPath,
            modificationRequested = listOf(
                "ändere", "aendere", "bearbeite", "ergänze", "ergaenze",
                "füge", "fuege", "kommentiere", "kommentar", "kommentare",
                "refactor", "refaktoriere", "verbessere", "fixe", "korrigiere"
            ).any { it in t },
            requestedComments = listOf(
                "kommentiere", "kommentar", "kommentare", "documentation", "dokumentiere"
            ).any { it in t },
            requestedRefactor = listOf(
                "refactor", "refaktoriere", "umbauen", "strukturieren"
            ).any { it in t },
            requestedFix = listOf(
                "fixe", "korrigiere", "bug", "fehler"
            ).any { it in t }
        )
    }
}
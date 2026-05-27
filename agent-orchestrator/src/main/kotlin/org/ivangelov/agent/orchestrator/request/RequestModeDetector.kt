package org.ivangelov.agent.orchestrator.request

object RequestModeDetector {

    fun detect(userText: String): RequestMode {
        return when {
            wantsProjectScaffolding(userText) -> RequestMode.SCAFFOLDING
            wantsFileModification(userText) -> RequestMode.MODIFICATION
            else -> RequestMode.ANALYSIS
        }
    }

    fun wantsFileModification(userText: String): Boolean {
        val t = userText.lowercase()

        return listOf(
            "ändere",
            "aendere",
            "bearbeite",
            "ergänze",
            "ergaenze",
            "füge",
            "fuege",
            "schreibe in",
            "kommentiere",
            "kommentar",
            "kommentare",
            "refactor",
            "refaktoriere",
            "verbessere",
            "patch",
            "fixe",
            "korrigiere",
            "erstelle in",
            "ändere die datei",
            "bearbeite die datei"
        ).any { it in t }
    }

    fun wantsProjectScaffolding(userText: String): Boolean {
        val t = userText.lowercase()

        return listOf(
            "projektstruktur",
            "projekt architektur",
            "architektur erstellen",
            "clean architecture",
            "mvvm struktur",
            "ordnerstruktur",
            "feature struktur",
            "paketstruktur",
            "module struktur",
            "scaffold",
            "grundstruktur",
            "projekt verzeichnis",
            "dateistruktur erstellen",
            "create project structure",
            "setup architecture",
            "project scaffolding"
        ).any { it in t }
    }
}

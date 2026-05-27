package org.ivangelov.agent.orchestrator.request

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RequestModeDetectorTest {

    @Test
    fun detectsScaffoldingBeforeModification() {
        val mode = RequestModeDetector.detect("Bitte erstelle eine Clean Architecture Projektstruktur")

        assertEquals(RequestMode.SCAFFOLDING, mode)
    }

    @Test
    fun detectsModificationRequests() {
        val mode = RequestModeDetector.detect("Bitte aendere die Datei App.kt")

        assertEquals(RequestMode.MODIFICATION, mode)
    }

    @Test
    fun defaultsToAnalysis() {
        val mode = RequestModeDetector.detect("Analysiere die Architektur")

        assertEquals(RequestMode.ANALYSIS, mode)
    }

    @Test
    fun exposesModificationIntentForSmartEditGate() {
        assertTrue(RequestModeDetector.wantsFileModification("refactor App.kt"))
        assertFalse(RequestModeDetector.wantsFileModification("erklaere App.kt"))
    }
}

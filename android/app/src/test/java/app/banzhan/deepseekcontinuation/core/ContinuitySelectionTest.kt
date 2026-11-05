package app.banzhan.deepseekcontinuation.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinuitySelectionTest {
    private fun candidate(
        id: String,
        order: Int,
        vararg features: String,
        branch: String = "chat:main",
        pinned: Boolean = false,
        selected: Boolean = false,
        length: Int = 100,
    ) = ContinuityCandidate(id, order, branch, features.toSet(), pinned, selected, length)

    @Test fun recentRepetitionsLeaveRoomForEarlierDifferentInteractions() {
        val candidates = listOf(
            candidate("early-correction", 1, "cue:CORRECTION", "category:PREFERENCES", length = 900),
            candidate("shared-language", 2, "cue:SHARED_LANGUAGE", "category:RELATIONSHIP"),
            candidate("old-project", 3, "cue:OPEN_THREAD", "category:PROJECT"),
            candidate("project-4", 4, "cue:OPEN_THREAD", "category:PROJECT"),
            candidate("project-5", 5, "cue:OPEN_THREAD", "category:PROJECT"),
            candidate("project-6", 6, "cue:OPEN_THREAD", "category:PROJECT"),
            candidate("project-7", 7, "cue:OPEN_THREAD", "category:PROJECT"),
            candidate("latest-project", 8, "cue:OPEN_THREAD", "category:PROJECT"),
        )

        assertEquals(listOf("early-correction", "shared-language", "latest-project"),
            chooseContinuityCandidates(candidates))
    }

    @Test fun separateSupportedStyleObservationsCanBothBeRepresented() {
        val candidates = listOf(
            candidate("asking-before-advice", 1, "cue:RESPONSE_STYLE", "style:entry-1"),
            candidate("direct-answer-old", 2, "cue:RESPONSE_STYLE", "style:entry-2"),
            candidate("direct-answer-new", 3, "cue:RESPONSE_STYLE", "style:entry-2"),
        )

        assertEquals(listOf("asking-before-advice", "direct-answer-new"),
            chooseContinuityCandidates(candidates, limit = 2))
    }

    @Test fun manualChoiceAndPinTakePriorityOverBroaderAutomaticCoverage() {
        val candidates = listOf(
            candidate("manual", 1, "category:PROJECT", selected = true),
            candidate("pin", 2, "category:PROJECT", pinned = true),
            candidate("different", 3, "cue:CORRECTION", "category:PREFERENCES"),
            candidate("repeat", 4, "category:PROJECT"),
        )

        assertEquals(listOf("manual", "pin", "different"), chooseContinuityCandidates(candidates))
    }

    @Test fun onlyExplicitChoicesMayExceedTheAutomaticLimit() {
        val manual = (1..4).map { candidate("manual-$it", it, selected = true) }
        val pins = (5..8).map { candidate("pin-$it", it, pinned = true) }

        assertEquals(manual.map { it.id }, chooseContinuityCandidates(manual + pins))
        assertEquals(3, chooseContinuityCandidates(pins).size)
        assertEquals(manual.map { it.id }, chooseContinuityCandidates(manual + pins, limit = 0))
        assertTrue(chooseContinuityCandidates(pins, limit = 0).isEmpty())
    }

    @Test fun unrelatedBranchesCannotContributeEvenProtectedCandidates() {
        val local = candidate("local", 1, "cue:CORRECTION")
        val foreign = candidate("foreign", 9, "cue:CORRECTION", "category:OTHER",
            branch = "chat:alternative", pinned = true, selected = true)

        assertEquals(listOf("local"), chooseContinuityCandidates(listOf(local, foreign),
            branchKey = "chat:main"))
        assertTrue(chooseContinuityCandidates(listOf(local, foreign), branchKey = "missing").isEmpty())
    }

    @Test fun coverageOnOneBranchDoesNotSuppressTheSameFeatureOnAnotherBranch() {
        val candidates = listOf(
            candidate("main-first", 1, "cue:PREFERENCE", selected = true),
            candidate("alternative", 2, "cue:PREFERENCE", branch = "chat:alternative"),
            candidate("main-repeat", 3, "cue:PREFERENCE"),
        )

        assertEquals(listOf("main-first", "alternative"), chooseContinuityCandidates(candidates, limit = 2))
    }

    @Test fun selectionIsStableAndFallsBackToRecencyWithoutObservedFeatures() {
        val candidates = listOf(candidate("a", 1), candidate("c", 3, length = 100),
            candidate("b", 3, length = 100), candidate("d", 2))
        val selected = chooseContinuityCandidates(candidates, limit = 2)

        assertEquals(listOf("b", "c"), selected)
        assertEquals(selected, chooseContinuityCandidates(candidates.reversed(), limit = 2))
        assertEquals(selected, chooseContinuityCandidates(candidates + candidates, limit = 2))
        assertFalse(selected.contains("a"))
    }
}

package app.banzhan.deepseekcontinuation.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The real dialog and gestures, with synthetic callbacks and no document/model I/O. */
@RunWith(AndroidJUnit4::class)
class TutorialScrollTest {
    @get:Rule val compose = createComposeRule()

    @Test fun thirdStepCanReachBothEndsAndStillSwipeAtLargeFont() {
        var imports = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                ContinuationTheme {
                    DownloadShareTutorialDialog({}, {}, { imports++ })
                }
            }
        }
        repeat(2) {
            compose.onNodeWithText("下一步").performClick()
            compose.waitForIdle()
        }
        compose.onNodeWithText("3/6").assertIsDisplayed()
        compose.onNodeWithText("选择「系统设置」").performScrollTo().assertIsDisplayed()
        val manual = compose.onNode(hasText("手动选择 ZIP（备用）") and
            hasAnyAncestor(hasTestTag("tutorial-page-2")))
        manual.performScrollTo().assertIsDisplayed()
        val viewport = compose.onNodeWithTag("tutorial-page-2").getUnclippedBoundsInRoot()
        val finalText = manual.getUnclippedBoundsInRoot()
        assertTrue("The final action must fit completely inside the scroll viewport",
            finalText.top >= viewport.top && finalText.bottom <= viewport.bottom)
        manual.performClick()
        compose.runOnIdle { assertEquals(1, imports) }
        compose.onNodeWithText("选择「系统设置」").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("tutorial-page-2").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithText("4/6").assertIsDisplayed()
    }
}

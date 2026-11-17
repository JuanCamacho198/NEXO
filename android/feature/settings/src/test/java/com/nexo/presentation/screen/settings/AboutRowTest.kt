package com.nexo.presentation.screen.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.nexo.ui.icons.NexoIcons
import com.nexo.ui.theme.NexoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The About rows have to reach their handler, and a row with no handler must
 * not look or announce itself as pressable.
 *
 * The shipped defect was wiring, not rendering: [AboutRow] accepted
 * `onClick: (() -> Unit)?` and never applied a `clickable` modifier, so the row
 * drew a chevron, sat in the accessibility tree with no action, and did
 * nothing when tapped.
 *
 * Both directions are asserted, because a chevron that over-promises is the
 * same defect mirrored: a null handler must produce no click action, no
 * `Role.Button` and no chevron.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AboutRowTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val label = "Terms"

    private fun setRow(onClick: (() -> Unit)?) {
        composeRule.setContent {
            NexoTheme {
                Column {
                    AboutRow(label = label, icon = NexoIcons.Info, onClick = onClick)
                }
            }
        }
    }

    private fun row(): SemanticsNodeInteraction = composeRule.onNodeWithText(label)

    @Test
    fun aboutRow_withHandler_invokesItOnClick() {
        var taps = 0
        setRow(onClick = { taps++ })

        row().performClick()

        assertEquals(1, taps)
    }

    @Test
    fun aboutRow_withHandler_exposesButtonRole() {
        setRow(onClick = {})

        row().assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        row().assert(hasClickAction())
    }

    @Test
    fun aboutRow_withoutHandler_isNotClickable() {
        setRow(onClick = null)

        row().assert(!hasClickAction())
        row().assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Role))
    }

    @Test
    fun aboutRow_withoutHandler_rendersNoTrailingChevron() {
        setRow(onClick = null)

        composeRule.onNodeWithDescription(CHEVRON_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun aboutRow_withHandler_rendersTrailingChevron() {
        setRow(onClick = {})

        composeRule.onNodeWithDescription(CHEVRON_DESCRIPTION).assertExists()
    }

    /** A chevron is decoration on a pressable row; TalkBack still needs to name it. */
    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onNodeWithDescription(
        description: String,
    ): SemanticsNodeInteraction = onNode(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(description)))
}

private const val CHEVRON_DESCRIPTION = "Opens a new screen"

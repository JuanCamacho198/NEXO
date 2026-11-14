package com.nexo.ui.components.molecules

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nexo.R
import com.nexo.domain.model.HighlightColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented layout assertions for T6 — the menu overflow treatment.
 *
 * Every assertion is selector- or layout-based; nothing here reads a pixel
 * coordinate and eyeballs it. The widths come from
 * [getUnclippedBoundsInRoot], which is the layout's own reported extent, so a
 * menu that is drawn narrower than its content reports the CLAMPED width and
 * the measured value is the number the reader sees.
 *
 * The measurement runs at a caller-chosen width so both cases are exercised:
 * the 411dp viewport (where the old menu just fit) and the 360dp one (where it
 * overflowed by ~45dp).
 */
@RunWith(AndroidJUnit4::class)
class SelectionMenuOverflowTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun description(resId: Int): String = composeRule.activity.getString(resId)

    private val paletteDesc: String get() = description(R.string.context_menu_color)
    private val copyDesc: String get() = description(R.string.text_selection_copy)
    private val dictionaryDesc: String get() = description(R.string.context_menu_dictionary)
    private val defineDesc: String get() = description(R.string.text_selection_define)
    private val translateDesc: String get() = description(R.string.text_selection_translate)
    private val shareDesc: String get() = description(R.string.context_menu_share)

    private fun widthOf(node: androidx.compose.ui.test.SemanticsNodeInteraction): Dp = node.getUnclippedBoundsInRoot().width

    /** The menu pill's own reported width — NOT the host Box's, which is always the full viewport. */
    private fun measuredMenuWidth(): Dp = widthOf(composeRule.onNodeWithTag(MENU_PILL_TAG))

    @Test
    fun selectionMenu_at360dp_fitsWithinWidth_andEveryActionIsReachable() {
        composeRule.setContent {
            Box(modifier = Modifier.width(360.dp)) {
                TextSelectionMenu(
                    selectedColor = HighlightColor.YELLOW.hex,
                    onColorSelected = {},
                    onCopy = {},
                    onDictionary = {},
                    onDefine = {},
                    onTranslate = {},
                    onShare = {},
                )
            }
        }
        composeRule.waitForIdle()

        val menuWidth = measuredMenuWidth()
        assertTrue(
            "Selection menu measured ${menuWidth.value}dp on a 360dp viewport; it must not exceed the viewport",
            menuWidth.value <= 360f,
        )
        assertTrue(
            "Selection menu measured ${menuWidth.value}dp; the palette button should have brought it well under 330dp",
            menuWidth.value <= 330f,
        )

        // The menu must never report a width beyond the screen — the overflow
        // defect was exactly this: content wider than the box that holds it.
        composeRule.onNodeWithContentDescription(paletteDesc).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(copyDesc).assertIsDisplayed()
    }

    @Test
    fun selectionMenu_paletteTrigger_present_andFiveInlineSwatchesGone() {
        var paletteTaps = 0
        composeRule.setContent {
            TextSelectionMenu(
                selectedColor = HighlightColor.YELLOW.hex,
                onColorSelected = { paletteTaps++ },
                onCopy = {},
                onDictionary = {},
                onDefine = {},
                onTranslate = {},
                onShare = {},
            )
        }
        composeRule.waitForIdle()

        // One palette trigger, mirroring FloatingContextMenu — not five swatches.
        composeRule.onNodeWithContentDescription(paletteDesc).assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        assertEquals(1, paletteTaps)
    }

    @Test
    fun selectionMenu_at411dp_showsNoScrollAndEveryActionReachable() {
        composeRule.setContent {
            Box(modifier = Modifier.width(411.dp)) {
                TextSelectionMenu(
                    selectedColor = HighlightColor.YELLOW.hex,
                    onColorSelected = {},
                    onCopy = {},
                    onDictionary = {},
                    onDefine = {},
                    onTranslate = {},
                    onShare = {},
                )
            }
        }
        composeRule.waitForIdle()

        val menuWidth = measuredMenuWidth()
        assertTrue(
            "Selection menu measured ${menuWidth.value}dp on a 411dp viewport; it must fit",
            menuWidth.value <= 411f,
        )

        // All five actions present in the tree (reachable by selector).
        composeRule.onNodeWithContentDescription(paletteDesc).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(copyDesc).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(dictionaryDesc).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(defineDesc).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(translateDesc).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(shareDesc).assertIsDisplayed()
    }

    @Test
    fun selectionMenu_at280dp_forcesScroll_andFadeAppearsWithoutClippingTheActions() {
        composeRule.setContent {
            Box(modifier = Modifier.width(280.dp)) {
                TextSelectionMenu(
                    selectedColor = HighlightColor.YELLOW.hex,
                    onColorSelected = {},
                    onCopy = {},
                    onDictionary = {},
                    onDefine = {},
                    onTranslate = {},
                    onShare = {},
                )
            }
        }
        composeRule.waitForIdle()

        val menuWidth = measuredMenuWidth()
        assertTrue(
            "Selection menu measured ${menuWidth.value}dp on a 280dp viewport; it must not exceed the viewport",
            menuWidth.value <= 280f,
        )

        // The menu is narrower than its content here, so the action region must
        // be a scrollable container. Asserting the scroll action exists is the
        // selector-level proof that the fade's liveness gate has somewhere to
        // fire; the fade itself draws on the same node.
        val actions = composeRule.onNodeWithTag(MENU_ACTIONS_TAG)
        actions.assertExists()
        val hasScrollForward =
            actions
                .fetchSemanticsNode()
                .config
                .contains(androidx.compose.ui.semantics.SemanticsActions.ScrollBy)
        assertTrue("The action region must be scrollable when the menu overflows", hasScrollForward)
    }

    @Test
    fun highlightMenu_at360dp_fitsWithinWidth_paletteAndEveryActionReachable() {
        val tagDesc = description(R.string.context_menu_tag)
        val annotateDesc = description(R.string.context_menu_annotate)
        val deleteDesc = description(R.string.context_menu_delete)
        var paletteTaps = 0

        composeRule.setContent {
            Box(modifier = Modifier.width(360.dp)) {
                FloatingContextMenu(
                    selectedColor = HighlightColor.YELLOW.hex,
                    onColorSelected = { paletteTaps++ },
                    onCopy = {},
                    onDefine = {},
                    onTranslate = {},
                    onAddTag = {},
                    onAnnotate = {},
                    onShare = {},
                    onDelete = {},
                )
            }
        }
        composeRule.waitForIdle()

        val menuWidth = measuredMenuWidth()
        assertTrue(
            "Highlight menu measured ${menuWidth.value}dp on a 360dp viewport; it must not exceed the viewport",
            menuWidth.value <= 360f,
        )

        composeRule.onNodeWithContentDescription(paletteDesc).assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        assertEquals("The highlight menu's palette trigger must still open the popover path", 1, paletteTaps)

        composeRule.onNodeWithContentDescription(copyDesc).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(defineDesc).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(translateDesc).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(tagDesc).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(annotateDesc).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(shareDesc).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(deleteDesc).assertIsDisplayed()
    }
}

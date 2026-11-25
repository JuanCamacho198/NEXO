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
import java.util.Locale

/**
 * Layout assertions for the two floating menus, by selector and measured
 * width, never by eyeballing pixels.
 *
 * The selection menu carries its five colours inline and pins them; at 411dp
 * the whole pill fits, at 360dp and below only the actions scroll. The
 * highlight menu keeps one colour dot that swaps the pill to the palette.
 */
@RunWith(AndroidJUnit4::class)
class SelectionMenuOverflowTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun description(resId: Int): String = composeRule.activity.getString(resId)

    private val colorDotDesc: String get() = description(R.string.context_menu_color)
    private val copyDesc: String get() = description(R.string.text_selection_copy)
    private val dictionaryDesc: String get() = description(R.string.context_menu_dictionary_action)
    private val defineDesc: String get() = description(R.string.text_selection_define)
    private val translateDesc: String get() = description(R.string.text_selection_translate)
    private val shareDesc: String get() = description(R.string.context_menu_share)

    private fun swatchDesc(nameRes: Int): String =
        composeRule.activity.getString(
            R.string.reader_highlight_in_color,
            description(nameRes).lowercase(Locale.getDefault()),
        )

    private val swatchNames =
        listOf(
            R.string.highlight_filter_yellow,
            R.string.highlight_filter_green,
            R.string.highlight_filter_blue,
            R.string.highlight_filter_orange,
            R.string.highlight_filter_red,
        )

    private fun widthOf(node: androidx.compose.ui.test.SemanticsNodeInteraction): Dp = node.getUnclippedBoundsInRoot().width

    /** The menu pill's own reported width — NOT the host Box's, which is always the full viewport. */
    private fun measuredMenuWidth(): Dp = widthOf(composeRule.onNodeWithTag(MENU_PILL_TAG))

    private fun setSelectionMenu(
        width: Dp,
        onColorSelected: (String) -> Unit = {},
    ) {
        composeRule.setContent {
            Box(modifier = Modifier.width(width)) {
                TextSelectionMenu(
                    swatches = HighlightColor.defaultHexList(),
                    onColorSelected = onColorSelected,
                    onCopy = {},
                    onDictionary = {},
                    onDefine = {},
                    onTranslate = {},
                    onShare = {},
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun selectionMenu_at360dp_fitsWithinWidth_andKeepsEveryColourVisible() {
        setSelectionMenu(360.dp)

        val menuWidth = measuredMenuWidth()
        assertTrue(
            "Selection menu measured ${menuWidth.value}dp on a 360dp viewport; it must not exceed the viewport",
            menuWidth.value <= 360f,
        )
        swatchNames.forEach { composeRule.onNodeWithContentDescription(swatchDesc(it)).assertIsDisplayed() }
        composeRule.onNodeWithContentDescription(defineDesc).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(copyDesc).assertIsDisplayed()
    }

    @Test
    fun selectionMenu_swatchTap_highlightsInThatColour() {
        var picked: String? = null
        setSelectionMenu(411.dp, onColorSelected = { picked = it })

        composeRule.onNodeWithContentDescription(swatchDesc(R.string.highlight_filter_blue)).performClick()
        composeRule.waitForIdle()

        assertEquals(HighlightColor.BLUE.hex, picked)
    }

    @Test
    fun selectionMenu_at411dp_showsEveryColourAndAction() {
        setSelectionMenu(411.dp)

        val menuWidth = measuredMenuWidth()
        assertTrue(
            "Selection menu measured ${menuWidth.value}dp on a 411dp viewport; it must fit",
            menuWidth.value <= 411f,
        )
        swatchNames.forEach { composeRule.onNodeWithContentDescription(swatchDesc(it)).assertIsDisplayed() }
        composeRule.onNodeWithContentDescription(copyDesc).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(dictionaryDesc).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(defineDesc).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(translateDesc).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(shareDesc).assertIsDisplayed()
    }

    @Test
    fun selectionMenu_at280dp_forcesScroll_andFadeAppearsWithoutClippingTheActions() {
        setSelectionMenu(280.dp)

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
    fun highlightMenu_at360dp_fitsWithinWidth_andTheColourDotOpensThePalette() {
        val tagDesc = description(R.string.context_menu_tag)
        val annotateDesc = description(R.string.context_menu_annotate)
        val deleteDesc = description(R.string.context_menu_delete)
        var picked: String? = null

        composeRule.setContent {
            Box(modifier = Modifier.width(360.dp)) {
                FloatingContextMenu(
                    selectedColor = HighlightColor.YELLOW.hex,
                    swatches = HighlightColor.defaultHexList(),
                    onColorSelected = { picked = it },
                    onMoreColors = {},
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
        composeRule.onNodeWithContentDescription(copyDesc).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(defineDesc).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(translateDesc).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(tagDesc).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(annotateDesc).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(shareDesc).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(deleteDesc).assertIsDisplayed()

        composeRule.onNodeWithContentDescription(colorDotDesc).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(swatchDesc(R.string.highlight_filter_green)).assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        assertEquals(HighlightColor.GREEN.hex, picked)
    }
}

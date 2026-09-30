package com.nexo.ui.components.molecules

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nexo.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented Compose tests for [BookContextMenuTrigger], covering the slice-1
 * contracts (WS4a):
 *
 * - the default `showPlanToRead = true` menu exposes the full five shelf actions
 *   (Edit Metadata, Mark as read, Plan to Read, Share file, Remove) plus a divider;
 * - `showPlanToRead = false` omits the "Plan to Read" item (four actions);
 * - tapping Remove invokes `onDelete` exactly once and no other callback;
 * - tapping each remaining item invokes its own callback;
 * - tapping the MoreVert trigger opens the menu and dismissing it closes the menu.
 *
 * These run on a device/emulator (`connectedDebugAndroidTest`); they are not part
 * of the JVM `testDebugUnitTest` suite.
 */
@RunWith(AndroidJUnit4::class)
class BookContextMenuTriggerTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val triggerDescription: String
        get() = composeRule.activity.getString(R.string.context_menu_more)

    private val editLabel: String
        get() = composeRule.activity.getString(R.string.library_menu_edit_metadata)

    private val markCompletedLabel: String
        get() = composeRule.activity.getString(R.string.library_menu_mark_completed)

    private val planToReadLabel: String
        get() = composeRule.activity.getString(R.string.library_menu_mark_plan_to_read)

    private val shareLabel: String
        get() = composeRule.activity.getString(R.string.library_menu_share)

    private val removeLabel: String
        get() = composeRule.activity.getString(R.string.library_menu_remove)

    @Test
    fun trigger_showPlanToReadTrue_showsFiveItems() {
        composeRule.setContent {
            BookContextMenuTrigger(
                showPlanToRead = true,
                onEdit = {},
                onMarkCompleted = {},
                onMarkPlanToRead = {},
                onShare = {},
                onDelete = {},
            )
        }

        openMenu()

        composeRule.onNodeWithText(editLabel).assertIsDisplayed()
        composeRule.onNodeWithText(markCompletedLabel).assertIsDisplayed()
        composeRule.onNodeWithText(planToReadLabel).assertIsDisplayed()
        composeRule.onNodeWithText(shareLabel).assertIsDisplayed()
        composeRule.onNodeWithText(removeLabel).assertIsDisplayed()
    }

    @Test
    fun trigger_showPlanToReadFalse_omitsPlanItem() {
        composeRule.setContent {
            BookContextMenuTrigger(
                showPlanToRead = false,
                onEdit = {},
                onMarkCompleted = {},
                onMarkPlanToRead = {},
                onShare = {},
                onDelete = {},
            )
        }

        openMenu()

        composeRule.onNodeWithText(editLabel).assertIsDisplayed()
        composeRule.onNodeWithText(markCompletedLabel).assertIsDisplayed()
        composeRule.onNodeWithText(shareLabel).assertIsDisplayed()
        composeRule.onNodeWithText(removeLabel).assertIsDisplayed()
        composeRule.onNodeWithText(planToReadLabel).assertDoesNotExist()
    }

    @Test
    fun removeTap_invokesOnDeleteExactlyOnce() {
        var deleteInvocations = 0
        var otherInvocations = 0

        composeRule.setContent {
            BookContextMenuTrigger(
                onEdit = { otherInvocations++ },
                onMarkCompleted = { otherInvocations++ },
                onMarkPlanToRead = { otherInvocations++ },
                onShare = { otherInvocations++ },
                onDelete = { deleteInvocations++ },
            )
        }

        openMenu()
        composeRule.onNodeWithText(removeLabel).performClick()
        composeRule.waitForIdle()

        assertEquals(1, deleteInvocations)
        assertEquals(0, otherInvocations)
    }

    @Test
    fun eachItemTap_invokesItsOwnCallback() {
        var editInvocations = 0
        var markCompletedInvocations = 0
        var markPlanToReadInvocations = 0
        var shareInvocations = 0

        composeRule.setContent {
            BookContextMenuTrigger(
                onEdit = { editInvocations++ },
                onMarkCompleted = { markCompletedInvocations++ },
                onMarkPlanToRead = { markPlanToReadInvocations++ },
                onShare = { shareInvocations++ },
                onDelete = {},
            )
        }

        tapMenuItem(editLabel)
        tapMenuItem(markCompletedLabel)
        tapMenuItem(planToReadLabel)
        tapMenuItem(shareLabel)

        assertEquals(1, editInvocations)
        assertEquals(1, markCompletedInvocations)
        assertEquals(1, markPlanToReadInvocations)
        assertEquals(1, shareInvocations)
    }

    @Test
    fun triggerTap_opensMenu_andDismiss_closesIt() {
        composeRule.setContent {
            BookContextMenuTrigger(
                onEdit = {},
                onMarkCompleted = {},
                onMarkPlanToRead = {},
                onShare = {},
                onDelete = {},
            )
        }

        openMenu()
        composeRule.onNodeWithText(editLabel).assertIsDisplayed()

        Espresso.pressBack()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(editLabel).assertDoesNotExist()
    }

    private fun openMenu() {
        composeRule.onNodeWithContentDescription(triggerDescription).performClick()
        composeRule.waitForIdle()
    }

    private fun tapMenuItem(label: String) {
        openMenu()
        composeRule.onNodeWithText(label).performClick()
        composeRule.waitForIdle()
    }
}

package com.nexo.presentation.screen

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nexo.R
import com.nexo.ui.components.atoms.CoverThumbnail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LibraryScreenCoverImageTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun coverThumbnail_withNullCoverPath_usesFallbackPlaceholder() {
        val states = mutableListOf<String>()
        val contentDescription = composeRule.activity.getString(R.string.library_cover_content_description)

        composeRule.setContent {
            CoverThumbnail(
                coverPath = null,
                onImageState = { state -> states += state::class.simpleName.orEmpty() },
            )
        }

        composeRule.onNodeWithContentDescription(contentDescription).assertIsDisplayed()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            states.any { it == "Empty" || it == "Success" }
        }
    }

    @Test
    fun coverThumbnail_withValidCoverPath_rendersImageSuccessfully() {
        val states = mutableListOf<String>()
        val contentDescription = composeRule.activity.getString(R.string.library_cover_content_description)
        val coverFile = createTestCoverFile()

        composeRule.setContent {
            CoverThumbnail(
                coverPath = coverFile.absolutePath,
                onImageState = { state -> states += state::class.simpleName.orEmpty() },
            )
        }

        composeRule.onNodeWithContentDescription(contentDescription).assertIsDisplayed()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            states.any { it == "Success" }
        }
    }

    @Test
    fun coverThumbnail_withInvalidCoverPath_reportsErrorState() {
        val states = mutableListOf<String>()
        val contentDescription = composeRule.activity.getString(R.string.library_cover_content_description)

        composeRule.setContent {
            CoverThumbnail(
                coverPath = "/tmp/nextpage-does-not-exist-cover.png",
                onImageState = { state -> states += state::class.simpleName.orEmpty() },
            )
        }

        composeRule.onNodeWithContentDescription(contentDescription).assertIsDisplayed()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            states.any { it == "Error" }
        }
    }

    private fun createTestCoverFile(): File {
        val targetFile = File(composeRule.activity.cacheDir, "library-cover-test.png")
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFF4A90E2.toInt())
        targetFile.outputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                "Failed to write test cover bitmap"
            }
        }
        bitmap.recycle()
        return targetFile
    }
}

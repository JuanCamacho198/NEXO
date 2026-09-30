package com.nexo.data.session

import android.content.Context
import android.content.SharedPreferences
import com.nexo.domain.model.ReaderSettings
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for [ReaderPreferences] persistence of [ReaderSettings.customHighlightColors].
 *
 * Uses MockK to mock [SharedPreferences] so we can verify the serialization/
 * deserialization of the JSON array without Robolectric or device.
 */
class ReaderPreferencesCustomColorsTest {
    private fun createMockPrefs(storedValues: Map<String, String> = emptyMap()): ReaderPreferences {
        // Mock SharedPreferences and SharedPreferences.Editor
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        val prefs =
            mockk<SharedPreferences> {
                every { getString(any(), any()) } answers {
                    val key = firstArg<String>()
                    val fallback = secondArg<String?>()
                    storedValues[key] ?: fallback
                }
                every { getInt(any<String>(), any()) } answers {
                    val key = firstArg<String>()
                    storedValues[key]?.toIntOrNull() ?: secondArg()
                }
                every { edit() } returns editor
            }

        // Mock Context to return our fake SharedPreferences
        val context =
            mockk<Context> {
                every { getSharedPreferences(any(), any()) } returns prefs
            }

        return ReaderPreferences(context)
    }

    // ── Load (deserialisation) ──────────────────────────────────────

    @Test
    fun `load with no stored palette returns null customHighlightColors`() {
        val prefs = createMockPrefs(emptyMap())
        val settings = prefs.load()
        assertNull(settings.customHighlightColors)
    }

    @Test
    fun `load with empty JSON array returns empty list`() {
        val prefs = createMockPrefs(mapOf("highlight_palette" to "[]"))
        val settings = prefs.load()
        assertNotNull(settings.customHighlightColors)
        assertEquals(emptyList<String>(), settings.customHighlightColors)
    }

    @Test
    fun `load round-trip 5 color hex values`() {
        val colors = listOf("#4ADE80", "#3B82F6", "#F97316", "#EF4444", "#FACC15")
        val json = """["#4ADE80","#3B82F6","#F97316","#EF4444","#FACC15"]"""
        val prefs = createMockPrefs(mapOf("highlight_palette" to json))
        val settings = prefs.load()
        assertEquals(colors, settings.customHighlightColors)
    }

    // ── Save (serialisation) ────────────────────────────────────────

    @Test
    fun `save with null customHighlightColors removes key`() {
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        val prefs =
            mockk<SharedPreferences> {
                every { edit() } returns editor
                every { getString(any(), any()) } returns null
                every { getInt(any<String>(), any()) } returns 16
            }
        val context =
            mockk<Context> {
                every { getSharedPreferences(any(), any()) } returns prefs
            }
        val rp = ReaderPreferences(context)

        rp.save(ReaderSettings(customHighlightColors = null))

        // Should NOT write the palette key
        verify(exactly = 0) { editor.putString("highlight_palette", any()) }
    }

    @Test
    fun `save with custom colors writes JSON array`() {
        val paletteKey = slot<String>()
        val paletteValue = slot<String>()
        val editor =
            mockk<SharedPreferences.Editor>(relaxed = true) {
                every { putString(capture(paletteKey), capture(paletteValue)) } returns this
                every { putInt(any<String>(), any()) } returns this
            }
        val prefs =
            mockk<SharedPreferences> {
                every { edit() } returns editor
                every { getString(any(), any()) } returns null
                every { getInt(any<String>(), any()) } returns 16
            }
        val context =
            mockk<Context> {
                every { getSharedPreferences(any(), any()) } returns prefs
            }
        val rp = ReaderPreferences(context)

        val colors = listOf("#4ADE80", "#3B82F6", "#F97316", "#EF4444", "#FACC15")
        rp.save(ReaderSettings(customHighlightColors = colors))

        assertEquals("highlight_palette", paletteKey.captured)
        assertEquals("""["#4ADE80","#3B82F6","#F97316","#EF4444","#FACC15"]""", paletteValue.captured)
    }
}

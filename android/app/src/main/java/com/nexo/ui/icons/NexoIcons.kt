package com.nexo.ui.icons

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.ArrowLeft
import androidx.compose.material.icons.automirrored.rounded.ArrowRight
import androidx.compose.material.icons.automirrored.rounded.FormatAlignLeft
import androidx.compose.material.icons.automirrored.rounded.FormatAlignRight
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.automirrored.rounded.Label
import androidx.compose.material.icons.automirrored.rounded.LibraryBooks
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.automirrored.rounded.ShowChart
import androidx.compose.material.icons.automirrored.rounded.StarHalf
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Book
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.FormatAlignCenter
import androidx.compose.material.icons.rounded.FormatAlignJustify
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material.icons.rounded.FormatSize
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.Monitor
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.ShowChart
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Spa
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nexo.presentation.theme.NexoTheme

/**
 * Single source of truth for NEXO app iconography.
 *
 * Sourcing:
 * - The primary glyphs are ported **verbatim** from the user-supplied vector
 *   drawables in `res/drawable/ic_*.xml` via [filledPathFromXml], which parses
 *   the raw `pathData` with `PathParser` — zero manual coordinate transcription.
 * - The remaining icons delegate to the official Material icon set
 *   (`androidx.compose.material:material-icons-extended`), Rounded style, using
 *   the `AutoMirrored` variants for directional glyphs so they mirror in RTL.
 *
 * Every icon is an `ImageVector` whose fills use `SolidColor(Color.Black)`, so
 * `Icon(tint = ...)` colors them uniformly with no hardcoded colors.
 */
object NexoIcons {
    private fun nexoIcon(
        name: String,
        autoMirror: Boolean = false,
        viewportWidth: Float = 24f,
        viewportHeight: Float = 24f,
        block: ImageVector.Builder.() -> ImageVector.Builder,
    ): ImageVector {
        val builder =
            ImageVector.Builder(
                name = "NexoIcons.$name",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = viewportWidth,
                viewportHeight = viewportHeight,
                autoMirror = autoMirror,
            )
        block(builder)
        return builder.build()
    }

    /**
     * Filled path from a raw vector-drawable `pathData` string, parsed verbatim
     * via [PathParser] so arcs/curves survive untouched. Used to port
     * user-uploaded `ic_*.xml` drawables into the icon object with zero manual
     * coordinate transcription. `SolidColor(Color.Black)` so `Icon(tint = ...)`
     * colors it uniformly.
     */
    private fun ImageVector.Builder.filledPathFromXml(pathData: String): ImageVector.Builder =
        addPath(
            pathData = PathParser().parsePathString(pathData).toNodes(),
            fill = SolidColor(Color.Black),
        )

    // ── Icons ported verbatim from user XML drawables ──

    /**
     * Bottom nav "Home" tab. Filled house. Source: `ic_home.xml`, 512 viewport.
     */
    val Home =
        nexoIcon("Home", viewportWidth = 512f, viewportHeight = 512f) {
            filledPathFromXml("M256,319.84c-35.35,0 -64,28.65 -64,64v128h128v-128C320,348.49 291.35,319.84 256,319.84z")
            filledPathFromXml("M362.67,383.84v128H448c35.35,0 64,-28.65 64,-64V253.26c0,-11.08 -4.3,-21.73 -12.01,-29.7l-181.29,-195.99c-31.99,-34.61 -85.98,-36.74 -120.59,-4.75c-1.64,1.52 -3.23,3.1 -4.75,4.75L12.4,223.5C4.45,231.5 -0,242.31 0,253.58v194.26c0,35.35 28.65,64 64,64h85.33v-128c0.4,-58.17 47.37,-105.68 104.07,-107.04C312.01,275.38 362.22,323.7 362.67,383.84z")
            filledPathFromXml("M256,319.84c-35.35,0 -64,28.65 -64,64v128h128v-128C320,348.49 291.35,319.84 256,319.84z")
        }

    /**
     * Bottom nav "Library" tab — bookshelf with two rows of books. Filled.
     * Source: `ic_library.xml`, 24 viewport.
     */
    val Library =
        nexoIcon("Library") {
            filledPathFromXml("m8,12c0,0.552 -0.447,1 -1,1h-2v2c0,0.552 -0.447,1 -1,1s-1,-0.448 -1,-1v-2L1,13c-0.553,0 -1,-0.448 -1,-1s0.447,-1 1,-1h2v-2c0,-0.552 0.447,-1 1,-1s1,0.448 1,1v2h2c0.553,0 1,0.448 1,1ZM3,21c0,1.657 1.343,3 3,3h2v-5L3,19v2ZM3,3v2h5L8,0h-2c-1.657,0 -3,1.343 -3,3ZM10,17h5L15,7h-5v10ZM10,24h2c1.657,0 3,-1.343 3,-3v-2h-5v5ZM20.941,6.091l-5.601,1.064 2.169,10.859 5.601,-1.064 -2.169,-10.859ZM23.965,21.308l-0.479,-2.393 -5.601,1.064 0.48,2.396c0.207,1.084 1.254,1.796 2.338,1.59l1.672,-0.317c1.086,-0.206 1.798,-1.254 1.591,-2.34ZM20.566,4.127l-0.477,-2.5c-0.206,-1.085 -1.253,-1.797 -2.338,-1.591l-1.672,0.317c-0.684,0.13 -1.219,0.595 -1.47,1.193 -0.513,-0.918 -1.482,-1.546 -2.609,-1.546h-2v5h5.967l4.599,-0.874Z")
        }

    /**
     * Bottom nav "Highlights" tab — highlighter marker. Filled.
     * Source: `ic_highlights.xml`, 24 viewport.
     */
    val Highlights =
        nexoIcon("Highlights") {
            filledPathFromXml("M8.02,19c1.78,0 3.48,-0.76 4.7,-2.13l7.1,-8.52c1.65,-1.9 1.55,-4.79 -0.23,-6.58l-0.37,-0.37c-1.78,-1.78 -4.67,-1.88 -6.55,-0.25L4.09,8.31c-1.33,1.18 -2.09,2.88 -2.09,4.66v3.9l-1.56,1.56c-0.59,0.59 -0.59,1.54 0,2.12 0.29,0.29 0.68,0.44 1.06,0.44s0.77,-0.15 1.06,-0.44l1.56,-1.56h3.9ZM6.05,10.59L14.61,3.45c0.72,-0.62 1.81,-0.59 2.49,0.09l0.37,0.37c0.67,0.67 0.71,1.77 0.07,2.51l-7.09,8.5c-0.23,0.25 -0.49,0.47 -0.78,0.64l-4.22,-4.22c0.16,-0.28 0.37,-0.53 0.6,-0.74ZM24,22.5c0,0.83 -0.67,1.5 -1.5,1.5L5.5,24c-0.83,0 -1.5,-0.67 -1.5,-1.5s0.67,-1.5 1.5,-1.5L22.5,21c0.83,0 1.5,0.67 1.5,1.5Z")
        }

    /**
     * Bottom nav "Settings" tab — gear. Filled. Source: `ic_settings.xml`,
     * 512 viewport.
     */
    val Settings =
        nexoIcon("Settings", viewportWidth = 512f, viewportHeight = 512f) {
            filledPathFromXml("M34.28,384c17.65,30.63 56.78,41.15 87.4,23.5c0.02,-0.01 0.04,-0.02 0.06,-0.04l9.49,-5.48c17.92,15.33 38.52,27.22 60.76,35.07V448c0,35.35 28.65,64 64,64s64,-28.65 64,-64v-10.94c22.24,-7.86 42.84,-19.77 60.76,-35.12l9.54,5.5c30.63,17.67 69.79,7.17 87.47,-23.47c17.67,-30.63 7.17,-69.79 -23.47,-87.47l0,0l-9.47,-5.46c4.26,-23.2 4.26,-46.99 0,-70.19l9.47,-5.46c30.63,-17.67 41.14,-56.83 23.47,-87.47c-17.67,-30.63 -56.83,-41.14 -87.47,-23.47l-9.49,5.48C362.86,94.64 342.25,82.77 320,74.94V64c0,-35.35 -28.65,-64 -64,-64s-64,28.65 -64,64v10.94c-22.24,7.86 -42.84,19.77 -60.76,35.12l-9.54,-5.53C91.07,86.86 51.91,97.37 34.24,128s-7.17,69.79 23.47,87.47l0,0l9.47,5.46c-4.26,23.2 -4.26,46.99 0,70.19l-9.47,5.46C27.16,314.3 16.69,353.38 34.28,384zM256,170.67c47.13,0 85.33,38.21 85.33,85.33S303.13,341.33 256,341.33S170.67,303.13 170.67,256S208.87,170.67 256,170.67z")
        }

    /**
     * Bottom nav "Statistics" tab — bar chart. Filled. Source:
     * `ic_statistics.xml`, 24 viewport.
     */
    val Statistics =
        nexoIcon("Statistics") {
            filledPathFromXml("M23,22H5a3,3 0,0 1,-3 -3V1A1,1 0,0 0,0 1V19a5.006,5.006 0,0 0,5 5H23a1,1 0,0 0,0 -2Z")
            filledPathFromXml("M6,20a1,1 0,0 0,1 -1V12a1,1 0,0 0,-2 0v7A1,1 0,0 0,6 20Z")
            filledPathFromXml("M10,10v9a1,1 0,0 0,2 0V10a1,1 0,0 0,-2 0Z")
            filledPathFromXml("M15,13v6a1,1 0,0 0,2 0V13a1,1 0,0 0,-2 0Z")
            filledPathFromXml("M20,9V19a1,1 0,0 0,2 0V9a1,1 0,0 0,-2 0Z")
            filledPathFromXml("M6,9a1,1 0,0 0,0.707 -0.293l3.586,-3.586a1.025,1.025 0,0 1,1.414 0l2.172,2.172a3,3 0,0 0,4.242 0l5.586,-5.586A1,1 0,0 0,22.293 0.293L16.707,5.878a1,1 0,0 1,-1.414 0L13.121,3.707a3,3 0,0 0,-4.242 0L5.293,7.293A1,1 0,0 0,6 9Z")
        }

    /**
     * Search field magnifier. Filled. Source: `ic_search.xml`, 24 viewport.
     */
    val Search =
        nexoIcon("Search") {
            filledPathFromXml("M23.707,22.293l-5.969,-5.969a10.016,10.016 0,1 0,-1.414 1.414l5.969,5.969a1,1 0,0 0,1.414 -1.414ZM10,18a8,8 0,1 1,8 -8A8.009,8.009 0,0 1,10 18Z")
        }

    /**
     * Share — three connected dots. Filled. Source: `ic_share.xml`,
     * 24 viewport.
     */
    val Share =
        nexoIcon("Share") {
            filledPathFromXml("M19.333,14.667a4.66,4.66 0,0 0,-3.839 2.024L8.985,13.752a4.574,4.574 0,0 0,0.005 -3.488l6.5,-2.954a4.66,4.66 0,1 0,-0.827 -2.643,4.633 4.633,0 0,0 0.08,0.786L7.833,8.593a4.668,4.668 0,1 0,-0.015 6.827l6.928,3.128a4.736,4.736 0,0 0,-0.079 0.785,4.667 4.667,0 1,0 4.666,-4.666ZM19.333,2a2.667,2.667 0,1 1,-2.666 2.667A2.669,2.669 0,0 1,19.333 2ZM4.667,14.667A2.667,2.667 0,1 1,7.333 12,2.67 2.67,0 0,1 4.667,14.667ZM19.333,22A2.667,2.667 0,1 1,22 19.333,2.669 2.669,0 0,1 19.333,22Z")
        }

    /**
     * Trash can — lid, body, ribs. Filled. Source: `ic_trash.xml`,
     * 24 viewport.
     */
    val Trash =
        nexoIcon("Trash") {
            filledPathFromXml("M21,4L17.9,4A5.009,5.009 0,0 0,13 0L11,0A5.009,5.009 0,0 0,6.1 4L3,4A1,1 0,0 0,3 6L4,6L4,19a5.006,5.006 0,0 0,5 5h6a5.006,5.006 0,0 0,5 -5L20,6h1a1,1 0,0 0,0 -2ZM11,2h2a3.006,3.006 0,0 1,2.829 2L8.171,4A3.006,3.006 0,0 1,11 2ZM18,19a3,3 0,0 1,-3 3L9,22a3,3 0,0 1,-3 -3L6,6L18,6Z")
            filledPathFromXml("M10,18a1,1 0,0 0,1 -1V11a1,1 0,0 0,-2 0v6A1,1 0,0 0,10 18Z")
            filledPathFromXml("M14,18a1,1 0,0 0,1 -1V11a1,1 0,0 0,-2 0v6A1,1 0,0 0,14 18Z")
        }

    /**
     * Auth route icon — user/account circle. Filled. Source:
     * `ic_account.xml`, 24 viewport.
     */
    val Person =
        nexoIcon("Person") {
            filledPathFromXml("M12,12A6,6 0,1 0,6 6,6.006 6.006,0 0,0 12,12ZM12,2A4,4 0,1 1,8 6,4 4,0 0,1 12,2Z")
            filledPathFromXml("M12,14a9.01,9.01 0,0 0,-9 9,1 1,0 0,0 2,0 7,7 0,0 1,14 0,1 1,0 0,0 2,0A9.01,9.01 0,0 0,12 14Z")
        }

    /**
     * Envelope. Filled. Source: `ic_email.xml`, 24 viewport.
     */
    val Email =
        nexoIcon("Email") {
            filledPathFromXml("M19,1H5A5.006,5.006 0,0 0,0 6V18a5.006,5.006 0,0 0,5 5H19a5.006,5.006 0,0 0,5 -5V6A5.006,5.006 0,0 0,19 1ZM5,3H19a3,3 0,0 1,2.78 1.887l-7.658,7.659a3.007,3.007 0,0 1,-4.244 0L2.22,4.887A3,3 0,0 1,5 3ZM19,21H5a3,3 0,0 1,-3 -3V7.5L8.464,13.96a5.007,5.007 0,0 0,7.072 0L22,7.5V18A3,3 0,0 1,19 21Z")
        }

    /**
     * Bookmark ribbon (outline — default reader bookmark). Filled.
     * Source: `ic_marcador.xml`, 24 viewport.
     */
    val Bookmark =
        nexoIcon("Bookmark") {
            filledPathFromXml("M20.137,24a2.8,2.8 0,0 1,-1.987 -0.835L12,17.051 5.85,23.169a2.8,2.8 0,0 1,-3.095 0.609A2.8,2.8 0,0 1,1 21.154V5A5,5 0,0 1,6 0H18a5,5 0,0 1,5 5V21.154a2.8,2.8 0,0 1,-1.751 2.624A2.867,2.867 0,0 1,20.137 24ZM6,2A3,3 0,0 0,3 5V21.154a0.843,0.843 0,0 0,1.437 0.6h0L11.3,14.933a1,1 0,0 1,1.41 0l6.855,6.819a0.843,0.843 0,0 0,1.437 -0.6V5a3,3 0,0 0,-3 -3Z")
        }

    /**
     * Filled variant of [Bookmark] — used by the reader bookmark toggle
     * animation. Filled. Source: `ic_marcador_relleno.xml`, 24 viewport.
     */
    val BookmarkFilled =
        nexoIcon("BookmarkFilled") {
            filledPathFromXml("M2.849,23.55a2.954,2.954 0,0 0,3.266 -0.644L12,17.053l5.885,5.853a2.956,2.956 0,0 0,2.1 0.881,3.05 3.05,0 0,0 1.17,-0.237A2.953,2.953 0,0 0,23 20.779V5a5.006,5.006 0,0 0,-5 -5H6A5.006,5.006 0,0 0,1 5V20.779A2.953,2.953 0,0 0,2.849 23.55Z")
        }

    /**
     * Hamburger menu — three stacked lines. Filled. Source:
     * `ic_menu_burger.xml`, 512 viewport.
     */
    val MenuBurger =
        nexoIcon("MenuBurger", viewportWidth = 512f, viewportHeight = 512f) {
            filledPathFromXml("M480,224H32c-17.67,0 -32,14.33 -32,32s14.33,32 32,32h448c17.67,0 32,-14.33 32,-32S497.67,224 480,224z")
            filledPathFromXml("M32,138.67h448c17.67,0 32,-14.33 32,-32s-14.33,-32 -32,-32H32c-17.67,0 -32,14.33 -32,32S14.33,138.67 32,138.67z")
            filledPathFromXml("M480,373.33H32c-17.67,0 -32,14.33 -32,32s14.33,32 32,32h448c17.67,0 32,-14.33 32,-32S497.67,373.33 480,373.33z")
        }

    /**
     * Upload — tray with up arrow. Filled. Source: `ic_subir_archivo.xml`,
     * 24 viewport.
     */
    val Upload =
        nexoIcon("Upload") {
            filledPathFromXml("M11.007,2.578 L11,18.016a1,1 0,0 0,1 1h0a1,1 0,0 0,1 -1l0.007,-15.421 2.912,2.913a1,1 0,0 0,1.414 0h0a1,1 0,0 0,0 -1.414L14.122,0.879a3,3 0,0 0,-4.244 0L6.667,4.091a1,1 0,0 0,0 1.414h0a1,1 0,0 0,1.414 0Z")
            filledPathFromXml("M22,17v4a1,1 0,0 1,-1 1H3a1,1 0,0 1,-1 -1V17a1,1 0,0 0,-1 -1H1a1,1 0,0 0,-1 1v4a3,3 0,0 0,3 3H21a3,3 0,0 0,3 -3V17a1,1 0,0 0,-1 -1h0A1,1 0,0 0,22 17Z")
        }

    /**
     * Dismiss/clear X in a circle. Filled. Source: `ic_cross_circle.xml`,
     * 512 viewport.
     */
    val Close =
        nexoIcon("Close", viewportWidth = 512f, viewportHeight = 512f) {
            filledPathFromXml("M256,0C114.61,0 0,114.61 0,256s114.61,256 256,256s256,-114.61 256,-256C511.85,114.68 397.32,0.15 256,0zM341.33,311.19c8.67,7.98 9.23,21.48 1.25,30.14c-7.98,8.67 -21.48,9.23 -30.14,1.25c-0.43,-0.4 -0.85,-0.82 -1.25,-1.25L256,286.17l-55.17,55.17c-8.48,8.19 -21.98,7.95 -30.17,-0.52c-7.98,-8.27 -7.98,-21.37 0,-29.64L225.84,256l-55.17,-55.17c-8.19,-8.48 -7.95,-21.98 0.52,-30.17c8.27,-7.98 21.37,-7.98 29.64,0L256,225.84l55.19,-55.17c7.98,-8.67 21.48,-9.23 30.14,-1.25c8.67,7.98 9.23,21.48 1.25,30.14c-0.4,0.43 -0.82,0.85 -1.25,1.25L286.17,256L341.33,311.19z")
        }

    /**
     * Help — question mark in a circle. Filled. Source:
     * `ic_interrogation.xml`, 24 viewport.
     */
    val Help =
        nexoIcon("Help") {
            filledPathFromXml("M12,0A12,12 0,1 0,24 12,12.013 12.013,0 0,0 12,0ZM12,22A10,10 0,1 1,22 12,10.011 10.011,0 0,1 12,22Z")
            filledPathFromXml("M12.717,5.063A4,4 0,0 0,8 9a1,1 0,0 0,2 0,2 2,0 0,1 2.371,-1.967 2.024,2.024 0,0 1,1.6 1.595,2 2,0 0,1 -1,2.125A3.954,3.954 0,0 0,11 14.257V15a1,1 0,0 0,2 0v-0.743a1.982,1.982 0,0 1,0.93 -1.752,4 4,0 0,0 -1.213,-7.442Z")
            filledPathFromXml("M12,17L12,17A1,1 0,0 1,13 18L13,18A1,1 0,0 1,12 19L12,19A1,1 0,0 1,11 18L11,18A1,1 0,0 1,12 17z")
        }

    // ── Icons delegated to official Material icons (material-icons-extended) ──

    /** Reader route icon. Source: `Icons.AutoMirrored.Rounded.MenuBook`. */
    val BookOpen = Icons.AutoMirrored.Rounded.MenuBook

    /** BookDetail route icon. Source: `Icons.Rounded.Book`. */
    val Book = Icons.Rounded.Book

    /** Vertical overflow menu — three dots. Source: `Icons.Rounded.MoreVert`. */
    val MoreVert = Icons.Rounded.MoreVert

    /** Add plus. Source: `Icons.Rounded.Add`. */
    val Add = Icons.Rounded.Add

    /** Filter funnel — three narrowing lines. Source: `Icons.Rounded.FilterList`. */
    val FilterList = Icons.Rounded.FilterList

    /** Notification bell. Source: `Icons.Rounded.Notifications`. */
    val Notifications = Icons.Rounded.Notifications

    /** Grid of four rounded squares (library view toggle). Source: `Icons.Rounded.GridView`. */
    val GridView = Icons.Rounded.GridView

    /** List rows with left gutter (library view toggle). RTL mirrors. Source: `Icons.AutoMirrored.Rounded.ViewList`. */
    val ViewList = Icons.AutoMirrored.Rounded.ViewList

    /** Clock face with hands. Source: `Icons.Rounded.Schedule`. */
    val Clock = Icons.Rounded.Schedule

    /** Ascending trend line. RTL mirrors. Source: `Icons.AutoMirrored.Rounded.ShowChart`. */
    val ChartLine = Icons.AutoMirrored.Rounded.ShowChart

    /** Three vertical bars on a baseline. Source: `Icons.Rounded.BarChart`. */
    val ChartBar = Icons.Rounded.BarChart

    /** Four-point sparkle star. Source: `Icons.Rounded.AutoAwesome`. */
    val Sparkle = Icons.Rounded.AutoAwesome

    /** Quotation marks — two comma glyphs. Source: `Icons.Rounded.FormatQuote`. */
    val Quote = Icons.Rounded.FormatQuote

    /** Lightbulb — dome, base, filament. Source: `Icons.Rounded.Lightbulb`. */
    val Lightbulb = Icons.Rounded.Lightbulb

    /** Check mark. Source: `Icons.Rounded.Check`. */
    val Check = Icons.Rounded.Check

    /** Error outline — circle with exclamation. Source: `Icons.Rounded.ErrorOutline`. */
    val ErrorOutline = Icons.Rounded.ErrorOutline

    /** Trophy cup. Source: `Icons.Rounded.EmojiEvents`. */
    val Trophy = Icons.Rounded.EmojiEvents

    /** Paint palette — oval with thumb hole and dots. Source: `Icons.Rounded.Palette`. */
    val Palette = Icons.Rounded.Palette

    /** Copy — two overlapping squares. Source: `Icons.Rounded.ContentCopy`. */
    val Copy = Icons.Rounded.ContentCopy

    /** Label tag with hole. RTL mirrors. Source: `Icons.AutoMirrored.Rounded.Label`. */
    val Tag = Icons.AutoMirrored.Rounded.Label

    /** Pencil — diagonal body with tip. Source: `Icons.Rounded.Edit`. */
    val Pencil = Icons.Rounded.Edit

    /** Stacked books. RTL mirrors. Source: `Icons.AutoMirrored.Rounded.LibraryBooks`. */
    val LibraryBooks = Icons.AutoMirrored.Rounded.LibraryBooks

    /** Cloud with down arrow. Source: `Icons.Rounded.CloudDownload`. */
    val CloudDownload = Icons.Rounded.CloudDownload

    /** Monitor and phone (settings devices). Source: `Icons.Rounded.Devices`. */
    val Devices = Icons.Rounded.Devices

    /** Crescent moon. Source: `Icons.Rounded.DarkMode`. */
    val DarkMode = Icons.Rounded.DarkMode

    /** Sun with rays. Source: `Icons.Rounded.LightMode`. */
    val LightMode = Icons.Rounded.LightMode

    /** Brightness auto — sun inside a ring. Source: `Icons.Rounded.BrightnessAuto`. */
    val BrightnessAuto = Icons.Rounded.BrightnessAuto

    /** Globe with meridians. Source: `Icons.Rounded.Language`. */
    val Language = Icons.Rounded.Language

    /** Storage cylinder. Source: `Icons.Rounded.Storage`. */
    val Storage = Icons.Rounded.Storage

    /** Info circle with exclamation. Source: `Icons.Rounded.Info`. */
    val Info = Icons.Rounded.Info

    /** Padlock — closed shackle. Source: `Icons.Rounded.Lock`. */
    val Lock = Icons.Rounded.Lock

    /** Bug with legs and antennae. Source: `Icons.Rounded.BugReport`. */
    val BugReport = Icons.Rounded.BugReport

    /** Sign-out — door with exit arrow. RTL mirrors. Source: `Icons.AutoMirrored.Rounded.Logout`. */
    val SignOut = Icons.AutoMirrored.Rounded.Logout

    /** Smartphone. Source: `Icons.Rounded.Smartphone`. */
    val Smartphone = Icons.Rounded.Smartphone

    /** Desktop monitor with stand. Source: `Icons.Rounded.Monitor`. */
    val Monitor = Icons.Rounded.Monitor

    /** Laptop — screen over base. Source: `Icons.Rounded.Laptop`. */
    val Laptop = Icons.Rounded.Laptop

    /** Chevron (settings row affordance). Source: `Icons.Rounded.ChevronRight`. */
    val ChevronRight = Icons.Rounded.ChevronRight

    /** Back arrow. RTL mirrors. Source: `Icons.AutoMirrored.Rounded.ArrowBack`. */
    val ArrowBack = Icons.AutoMirrored.Rounded.ArrowBack

    /** Sharp right chevron (settings row affordance). RTL mirrors. Source: `Icons.AutoMirrored.Rounded.ArrowRight`. */
    val ArrowRight = Icons.AutoMirrored.Rounded.ArrowRight

    /** Sync — two chasing arrows. Source: `Icons.Rounded.Sync`. */
    val Sync = Icons.Rounded.Sync

    /** Cloud with sync arrows. Source: `Icons.Rounded.CloudSync`. */
    val CloudSync = Icons.Rounded.CloudSync

    /** "Aa" text glyph. Source: `Icons.Rounded.TextFields`. */
    val TextAa = Icons.Rounded.TextFields

    /** Left-aligned text lines. RTL mirrors. Source: `Icons.AutoMirrored.Rounded.FormatAlignLeft`. */
    val AlignLeft = Icons.AutoMirrored.Rounded.FormatAlignLeft

    /** Center-aligned text lines. Source: `Icons.Rounded.FormatAlignCenter`. */
    val AlignCenter = Icons.Rounded.FormatAlignCenter

    /** Right-aligned text lines. RTL mirrors. Source: `Icons.AutoMirrored.Rounded.FormatAlignRight`. */
    val AlignRight = Icons.AutoMirrored.Rounded.FormatAlignRight

    /** Justified text lines. Source: `Icons.Rounded.FormatAlignJustify`. */
    val AlignJustify = Icons.Rounded.FormatAlignJustify

    /** Left chevron arrow. RTL mirrors. Source: `Icons.AutoMirrored.Rounded.ArrowLeft`. */
    val ArrowLeft = Icons.AutoMirrored.Rounded.ArrowLeft

    /** Forward (right) arrow. RTL mirrors. Source: `Icons.AutoMirrored.Rounded.ArrowForward`. */
    val ArrowForward = Icons.AutoMirrored.Rounded.ArrowForward

    /** "A" with up arrow (font size). Source: `Icons.Rounded.FormatSize`. */
    val TextSize = Icons.Rounded.FormatSize

    /** Bullet list with dots. RTL mirrors. Source: `Icons.AutoMirrored.Rounded.FormatListBulleted`. */
    val ListBullets = Icons.AutoMirrored.Rounded.FormatListBulleted

    /** Filled five-point star (rated). Source: `Icons.Rounded.Star`. */
    val Star = Icons.Rounded.Star

    /** Outlined five-point star (unrated). Source: `Icons.Rounded.StarBorder`. */
    val StarBorder = Icons.Rounded.StarBorder

    /** Half-filled five-point star (half-step rating). RTL mirrors. Source: `Icons.AutoMirrored.Rounded.StarHalf`. */
    val StarHalf = Icons.AutoMirrored.Rounded.StarHalf

    /** Phone with rotation arrows. Source: `Icons.Rounded.ScreenRotation`. */
    val ScreenRotation = Icons.Rounded.ScreenRotation

    /** Flame (statistics streak). Source: `Icons.Rounded.LocalFireDepartment`. */
    val Flame = Icons.Rounded.LocalFireDepartment

    /** Lotus petals (relaxed goal option). Source: `Icons.Rounded.Spa`. */
    val Spa = Icons.Rounded.Spa

    /** Open book (regular goal option). Source: `Icons.Rounded.AutoStories`. */
    val AutoStories = Icons.Rounded.AutoStories

    /** Chevron up. Source: `Icons.Rounded.ExpandLess`. */
    val ChevronUp = Icons.Rounded.ExpandLess

    /** Chevron down. Source: `Icons.Rounded.ExpandMore`. */
    val ChevronDown = Icons.Rounded.ExpandMore

    /** Performance / speed gauge. Fallback to BarChart (Speed is not in this material-icons-extended version). */
    val Speed = Icons.Rounded.BarChart

    /** Alias for performance row. */
    val Performance = Icons.Rounded.BarChart
}

@Preview(showBackground = true, heightDp = 1200)
@Composable
private fun NexoIconsGalleryDarkPreview() {
    NexoTheme(darkTheme = true) {
        val icons =
            listOf<Pair<String, ImageVector>>(
                "Home" to NexoIcons.Home,
                "Library" to NexoIcons.Library,
                "Highlights" to NexoIcons.Highlights,
                "Settings" to NexoIcons.Settings,
                "Statistics" to NexoIcons.Statistics,
                "BookOpen" to NexoIcons.BookOpen,
                "Book" to NexoIcons.Book,
                "Person" to NexoIcons.Person,
                "Search" to NexoIcons.Search,
                "Close" to NexoIcons.Close,
                "MoreVert" to NexoIcons.MoreVert,
                "Add" to NexoIcons.Add,
                "FilterList" to NexoIcons.FilterList,
                "Notifications" to NexoIcons.Notifications,
                "GridView" to NexoIcons.GridView,
                "ViewList" to NexoIcons.ViewList,
                "Clock" to NexoIcons.Clock,
                "ChartLine" to NexoIcons.ChartLine,
                "ChartBar" to NexoIcons.ChartBar,
                "Bookmark" to NexoIcons.Bookmark,
                "Upload" to NexoIcons.Upload,
                "Sparkle" to NexoIcons.Sparkle,
                "Quote" to NexoIcons.Quote,
                "Lightbulb" to NexoIcons.Lightbulb,
                "Check" to NexoIcons.Check,
                "ErrorOutline" to NexoIcons.ErrorOutline,
                "Trophy" to NexoIcons.Trophy,
                "Palette" to NexoIcons.Palette,
                "Copy" to NexoIcons.Copy,
                "Tag" to NexoIcons.Tag,
                "Pencil" to NexoIcons.Pencil,
                "Share" to NexoIcons.Share,
                "Trash" to NexoIcons.Trash,
                "LibraryBooks" to NexoIcons.LibraryBooks,
                "CloudDownload" to NexoIcons.CloudDownload,
                "Devices" to NexoIcons.Devices,
                "DarkMode" to NexoIcons.DarkMode,
                "LightMode" to NexoIcons.LightMode,
                "BrightnessAuto" to NexoIcons.BrightnessAuto,
                "Language" to NexoIcons.Language,
                "Storage" to NexoIcons.Storage,
                "Info" to NexoIcons.Info,
                "BugReport" to NexoIcons.BugReport,
                "SignOut" to NexoIcons.SignOut,
                "Smartphone" to NexoIcons.Smartphone,
                "Monitor" to NexoIcons.Monitor,
                "Laptop" to NexoIcons.Laptop,
                "ChevronRight" to NexoIcons.ChevronRight,
                "ArrowBack" to NexoIcons.ArrowBack,
                "ArrowRight" to NexoIcons.ArrowRight,
                "Sync" to NexoIcons.Sync,
                "CloudSync" to NexoIcons.CloudSync,
                "TextAa" to NexoIcons.TextAa,
                "AlignLeft" to NexoIcons.AlignLeft,
                "AlignCenter" to NexoIcons.AlignCenter,
                "AlignRight" to NexoIcons.AlignRight,
                "AlignJustify" to NexoIcons.AlignJustify,
                "ArrowLeft" to NexoIcons.ArrowLeft,
                "ArrowForward" to NexoIcons.ArrowForward,
                "TextSize" to NexoIcons.TextSize,
                "ListBullets" to NexoIcons.ListBullets,
                "Star" to NexoIcons.Star,
                "StarBorder" to NexoIcons.StarBorder,
                "StarHalf" to NexoIcons.StarHalf,
                "ScreenRotation" to NexoIcons.ScreenRotation,
                "Flame" to NexoIcons.Flame,
                "ChevronUp" to NexoIcons.ChevronUp,
                "ChevronDown" to NexoIcons.ChevronDown,
                "Email" to NexoIcons.Email,
                "BookmarkFilled" to NexoIcons.BookmarkFilled,
                "Help" to NexoIcons.Help,
                "MenuBurger" to NexoIcons.MenuBurger,
            )

        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            icons.chunked(6).forEach { rowIcons ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    rowIcons.forEach { (name, icon) ->
                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = Color(0xFF000000),
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = name,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true, heightDp = 1200)
@Composable
private fun NexoIconsGalleryLightPreview() {
    NexoTheme(darkTheme = false) {
        val icons =
            listOf<Pair<String, ImageVector>>(
                "Home" to NexoIcons.Home,
                "Library" to NexoIcons.Library,
                "Highlights" to NexoIcons.Highlights,
                "Settings" to NexoIcons.Settings,
                "Statistics" to NexoIcons.Statistics,
                "BookOpen" to NexoIcons.BookOpen,
                "Book" to NexoIcons.Book,
                "Person" to NexoIcons.Person,
                "Search" to NexoIcons.Search,
                "Close" to NexoIcons.Close,
                "MoreVert" to NexoIcons.MoreVert,
                "Add" to NexoIcons.Add,
                "FilterList" to NexoIcons.FilterList,
                "Notifications" to NexoIcons.Notifications,
                "GridView" to NexoIcons.GridView,
                "ViewList" to NexoIcons.ViewList,
                "Clock" to NexoIcons.Clock,
                "ChartLine" to NexoIcons.ChartLine,
                "ChartBar" to NexoIcons.ChartBar,
                "Bookmark" to NexoIcons.Bookmark,
                "Upload" to NexoIcons.Upload,
                "Sparkle" to NexoIcons.Sparkle,
                "Quote" to NexoIcons.Quote,
                "Lightbulb" to NexoIcons.Lightbulb,
                "Check" to NexoIcons.Check,
                "ErrorOutline" to NexoIcons.ErrorOutline,
                "Trophy" to NexoIcons.Trophy,
                "Palette" to NexoIcons.Palette,
                "Copy" to NexoIcons.Copy,
                "Tag" to NexoIcons.Tag,
                "Pencil" to NexoIcons.Pencil,
                "Share" to NexoIcons.Share,
                "Trash" to NexoIcons.Trash,
                "LibraryBooks" to NexoIcons.LibraryBooks,
                "CloudDownload" to NexoIcons.CloudDownload,
                "Devices" to NexoIcons.Devices,
                "DarkMode" to NexoIcons.DarkMode,
                "LightMode" to NexoIcons.LightMode,
                "BrightnessAuto" to NexoIcons.BrightnessAuto,
                "Language" to NexoIcons.Language,
                "Storage" to NexoIcons.Storage,
                "Info" to NexoIcons.Info,
                "BugReport" to NexoIcons.BugReport,
                "SignOut" to NexoIcons.SignOut,
                "Smartphone" to NexoIcons.Smartphone,
                "Monitor" to NexoIcons.Monitor,
                "Laptop" to NexoIcons.Laptop,
                "ChevronRight" to NexoIcons.ChevronRight,
                "ArrowBack" to NexoIcons.ArrowBack,
                "ArrowRight" to NexoIcons.ArrowRight,
                "Sync" to NexoIcons.Sync,
                "CloudSync" to NexoIcons.CloudSync,
                "TextAa" to NexoIcons.TextAa,
                "AlignLeft" to NexoIcons.AlignLeft,
                "AlignCenter" to NexoIcons.AlignCenter,
                "AlignRight" to NexoIcons.AlignRight,
                "AlignJustify" to NexoIcons.AlignJustify,
                "ArrowLeft" to NexoIcons.ArrowLeft,
                "ArrowForward" to NexoIcons.ArrowForward,
                "TextSize" to NexoIcons.TextSize,
                "ListBullets" to NexoIcons.ListBullets,
                "Star" to NexoIcons.Star,
                "StarBorder" to NexoIcons.StarBorder,
                "StarHalf" to NexoIcons.StarHalf,
                "ScreenRotation" to NexoIcons.ScreenRotation,
                "Flame" to NexoIcons.Flame,
                "ChevronUp" to NexoIcons.ChevronUp,
                "ChevronDown" to NexoIcons.ChevronDown,
                "Email" to NexoIcons.Email,
                "BookmarkFilled" to NexoIcons.BookmarkFilled,
                "Help" to NexoIcons.Help,
                "MenuBurger" to NexoIcons.MenuBurger,
            )

        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            icons.chunked(6).forEach { rowIcons ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    rowIcons.forEach { (name, icon) ->
                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = Color(0xFF000000),
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = name,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

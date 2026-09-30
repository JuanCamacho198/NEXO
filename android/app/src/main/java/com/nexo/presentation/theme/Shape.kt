package com.nexo.presentation.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Material 3 [Shapes] for NEXO.
 *
 * Wires into [NexoTheme] via `MaterialTheme.shapes`. Components should
 * prefer `MaterialTheme.shapes.small/medium/large` over the raw values.
 *
 * - `extraSmall` (4dp) — chips, tags, tiny badges
 * - `small` (8dp) — buttons, text fields, small cards
 * - `medium` (16dp) — large cards, sheets
 * - `large` (50dp) — pill / fully-rounded surfaces (FAB, progress pills)
 * - `extraLarge` (50dp) — kept symmetric to `large` to match the brand
 */
val NexoShapes =
    Shapes(
        extraSmall = RoundedCornerShape(4.dp),
        small = RoundedCornerShape(8.dp),
        medium = RoundedCornerShape(16.dp),
        large = RoundedCornerShape(50.dp),
        extraLarge = RoundedCornerShape(50.dp),
    )

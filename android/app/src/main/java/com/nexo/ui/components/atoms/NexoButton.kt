package com.nexo.ui.components.atoms

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nexo.presentation.theme.NexoTheme
import com.nexo.ui.icons.NexoIcons

enum class NexoButtonVariant {
    FILLED,
    OUTLINED,
    TEXT,
    TONAL,
    ICON,
}

/**
 * Primary app button with 5 visual variants. Thin wrapper over Material 3
 * Button/OutlinedButton/TextButton/IconButton that unifies theming and the
 * `RowScope`-shaped content slot under a single API.
 *
 * @param onClick Invoked on tap. Not called when [enabled] is `false`.
 * @param modifier Modifier applied to the underlying Material 3 button.
 * @param variant Visual style. `FILLED` (primary background), `OUTLINED`
 *   (transparent + outline), `TEXT` (no chrome), `TONAL` (secondary
 *   container background), or `ICON` (circular icon-only via `IconButton`).
 * @param enabled When `false`, no-op on click and content fades to
 *   `LocalContentColor` at `0.38f` alpha (Material 3 default).
 * @param shape Corner shape. Ignored by [NexoButtonVariant.TEXT] (no
 *   container) and by [NexoButtonVariant.ICON] (circle).
 * @param contentPadding Inner padding around [content]. Uses Material 3
 *   default (`ButtonDefaults.ContentPadding`) if not provided.
 * @param colors Optional `ButtonColors` override. `null` (default) keeps the
 *   variant's theme-derived colors; provide e.g.
 *   `ButtonDefaults.outlinedButtonColors(...)` to brand a specific instance.
 *   NOTE: the bundled Material3 `outlinedButtonColors` has no `borderColor`
 *   parameter — brand the outline via [border] instead.
 * @param border Optional [BorderStroke] override. `null` (default) keeps the
 *   variant's default border. Only applied to [NexoButtonVariant.OUTLINED].
 * @param content Slot for the button label — typically one or two
 *   `Text`/`Icon` composables.
 *
 * **Visual**: rounded 4dp (default `shapes.small`), 48dp height. Variants
 * differ in background/border/text color: `FILLED` uses `primary`/
 * `onPrimary`; `TONAL` uses `secondaryContainer`/`onSecondaryContainer`;
 * `OUTLINED`/`TEXT` use `primary` on transparent; `ICON` is a bare
 * `IconButton` with no size or shape overrides.
 * **Behavior**: ripple on tap (Material 3 default `LocalIndication`).
 * Disabled state drops opacity to 0.38.
 * **Recomposition**: recomposes when `onClick`, `variant`, `enabled`,
 * `shape`, or `contentPadding` change; the inner content slot is a
 * subcomposition and only re-evaluates when its captured state changes.
 */
@Composable
fun NexoButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: NexoButtonVariant = NexoButtonVariant.FILLED,
    enabled: Boolean = true,
    shape: Shape = MaterialTheme.shapes.small,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    colors: ButtonColors? = null,
    border: BorderStroke? = null,
    content: @Composable () -> Unit,
) {
    when (variant) {
        NexoButtonVariant.FILLED -> {
            Button(
                onClick = onClick,
                modifier = modifier,
                enabled = enabled,
                shape = shape,
                colors =
                    colors ?: ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                contentPadding = contentPadding,
            ) {
                RowScopeContent(content = content)
            }
        }
        NexoButtonVariant.OUTLINED -> {
            OutlinedButton(
                onClick = onClick,
                modifier = modifier,
                enabled = enabled,
                shape = shape,
                colors =
                    colors ?: ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary,
                    ),
                border = border,
                contentPadding = contentPadding,
            ) {
                RowScopeContent(content = content)
            }
        }
        NexoButtonVariant.TEXT -> {
            TextButton(
                onClick = onClick,
                modifier = modifier,
                enabled = enabled,
                contentPadding = contentPadding,
            ) {
                RowScopeContent(content = content)
            }
        }
        NexoButtonVariant.TONAL -> {
            Button(
                onClick = onClick,
                modifier = modifier,
                enabled = enabled,
                shape = shape,
                colors =
                    colors ?: ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                contentPadding = contentPadding,
            ) {
                RowScopeContent(content = content)
            }
        }
        NexoButtonVariant.ICON -> {
            IconButton(
                onClick = onClick,
                modifier = modifier,
                enabled = enabled,
                content = content,
            )
        }
    }
}

@Composable
private fun RowScope.RowScopeContent(content: @Composable () -> Unit) {
    content()
}

/**
 * Text-only overload of [NexoButton] for the common case of a single
 * `Text` label. Delegates to the slot-based overload.
 *
 * @param text The string rendered inside the button (wrapped in a
 *   Material 3 `Text` with no style override — inherits the variant's
 *   `contentColor`).
 * @param onClick Invoked on tap. Not called when [enabled] is `false`.
 * @param modifier Modifier applied to the underlying Material 3 button.
 * @param variant Visual style; see [NexoButton].
 * @param enabled When `false`, no-op on click and content fades to 0.38 alpha.
 *
 * **Visual**: identical to the slot-based [NexoButton] for the same
 * `variant`, but with a plain `Text` as content (no icon slot).
 * **Behavior**: ripple on tap; disabled state drops opacity to 0.38.
 * **Recomposition**: recomposes when `text`, `onClick`, `variant`, or
 * `enabled` change.
 */
@Composable
fun NexoButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: NexoButtonVariant = NexoButtonVariant.FILLED,
    enabled: Boolean = true,
) {
    NexoButton(
        onClick = onClick,
        modifier = modifier,
        variant = variant,
        enabled = enabled,
    ) {
        Text(text = text)
    }
}

@Preview(showBackground = true)
@Composable
private fun NexoButtonVariantsDarkPreview() {
    NexoTheme(darkTheme = true) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            NexoButton(text = "Filled", onClick = {})
            NexoButton(text = "Outlined", onClick = {}, variant = NexoButtonVariant.OUTLINED)
            NexoButton(text = "Text", onClick = {}, variant = NexoButtonVariant.TEXT)
            NexoButton(text = "Tonal", onClick = {}, variant = NexoButtonVariant.TONAL)
            NexoButton(onClick = {}, variant = NexoButtonVariant.ICON) {
                Icon(NexoIcons.Add, contentDescription = null)
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun NexoButtonVariantsLightPreview() {
    NexoTheme(darkTheme = false) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            NexoButton(text = "Filled", onClick = {})
            NexoButton(text = "Outlined", onClick = {}, variant = NexoButtonVariant.OUTLINED)
            NexoButton(text = "Text", onClick = {}, variant = NexoButtonVariant.TEXT)
            NexoButton(text = "Tonal", onClick = {}, variant = NexoButtonVariant.TONAL)
            NexoButton(onClick = {}, variant = NexoButtonVariant.ICON) {
                Icon(NexoIcons.Add, contentDescription = null)
            }
        }
    }
}

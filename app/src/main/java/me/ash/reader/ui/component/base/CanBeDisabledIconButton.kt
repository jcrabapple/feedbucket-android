package me.ash.reader.ui.component.base

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Icon button with an optional active state (REFRESH-v0.2 section 4.1):
 * when [active] and not [disabled], the icon is centered inside a 36dp
 * primaryContainer circle and the effective content color becomes
 * onPrimaryContainer, overriding [tint]. The touch target stays 48dp; the
 * circle is visual-only. Otherwise behavior is unchanged (including
 * disabledContentColor = outline).
 */
@Composable
fun CanBeDisabledIconButton(
    modifier: Modifier = Modifier,
    disabled: Boolean,
    imageVector: ImageVector? = null,
    icon: @Composable () -> Unit = {},
    size: Dp = 24.dp,
    contentDescription: String?,
    tint: Color = LocalContentColor.current,
    active: Boolean = false,
    onClick: () -> Unit = {},
) {
    val showActivePill = active && !disabled
    IconButton(
        modifier = modifier,
        enabled = !disabled,
        onClick = onClick,
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = if (showActivePill) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                tint
            },
            disabledContentColor = MaterialTheme.colorScheme.outline
        )
    ) {
        if (showActivePill) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                ButtonContent(imageVector, icon, size, contentDescription)
            }
        } else {
            ButtonContent(imageVector, icon, size, contentDescription)
        }
    }
}

@Composable
private fun ButtonContent(
    imageVector: ImageVector?,
    icon: @Composable () -> Unit,
    size: Dp,
    contentDescription: String?,
) {
    if (imageVector != null) {
        Icon(
            modifier = Modifier.size(size),
            imageVector = imageVector,
            contentDescription = contentDescription,
        )
    } else {
        icon()
    }
}

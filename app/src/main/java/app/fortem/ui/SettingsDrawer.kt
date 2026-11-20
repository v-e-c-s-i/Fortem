// SPDX-License-Identifier: GPL-3.0-only
package app.fortem.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Premeasured content slides as a single layer; animation never relays out settings. */
@Composable
internal fun BoxScope.SettingsDrawer(visible: Boolean, onDismiss: () -> Unit,
    content: @Composable () -> Unit) {
    val transition = updateTransition(visible, label = "settings-drawer")
    val hidden = transition.animateFloat(transitionSpec = {
        tween(if (targetState) 280 else 220, easing = FastOutSlowInEasing)
    }, label = "settings-position") { if (it) 0f else 1f }
    val shade = transition.animateFloat(transitionSpec = { tween(220) },
        label = "settings-shade") { if (it) 1f else 0f }
    if (visible || transition.currentState) {
        Box(Modifier.matchParentSize()
            .graphicsLayer { alpha = shade.value }
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = .62f))
            .clickable(onClick = onDismiss))
    }
    BoxWithConstraints(Modifier.matchParentSize()) {
        val drawerWidth = (maxWidth * .382f).coerceAtLeast(280.dp)
            .coerceAtMost((maxWidth - 36.dp).coerceAtLeast(0.dp))
        Box(Modifier.align(AbsoluteAlignment.CenterRight).fillMaxHeight().width(drawerWidth)
            .graphicsLayer { translationX = size.width * hidden.value; clip = true }
            .testTag("settings-drawer")
            // Keep the modifier structure stable when opening, avoiding a coordinator rebuild.
            .semantics { if (!visible) hideFromAccessibility() }) { content() }
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package app.fortem.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.fortem.recording.RecordingSummary

/** A full-window Dialog needs an explicit outside hit target around its narrower surface. */
@Composable
internal fun RecordingsDialogFrame(dismissEnabled: Boolean, onDismiss: () -> Unit,
    feedback: @Composable BoxScope.() -> Unit = {},
    content: @Composable () -> Unit) {
    Dialog(onDismissRequest = { if (dismissEnabled) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        ImmersiveDialog()
        BoxWithConstraints(Modifier.fillMaxSize().testTag("recordings-backdrop")
            .clickable(enabled = dismissEnabled, interactionSource = remember { MutableInteractionSource() },
                indication = null, onClick = onDismiss)
            .windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 12.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center) {
            Surface(shape = RoundedCornerShape(18.dp), modifier = Modifier
                .width(minOf(600.dp, maxWidth)).fillMaxHeight().testTag("recordings-surface")
                .clickable(interactionSource = remember { MutableInteractionSource() },
                    indication = null, onClick = {}), content = content)
            // Screen-level feedback is a sibling of the dialog surface, not part of its list.
            feedback()
        }
    }
}

/** Choose an opening position once, after loading. Sorting/ticks never pull the list away. */
@Composable
internal fun RevealRecordingOnOpen(takes: List<RecordingSummary>, ready: Boolean,
    playingId: Long?, listState: LazyListState) {
    val openingId = remember { playingId }
    var revealed by remember { mutableStateOf(false) }
    LaunchedEffect(ready, takes) {
        if (ready && !revealed) {
            val index = takes.indexOfFirst { it.id == openingId }.coerceAtLeast(0)
            listState.scrollToItem(index)
            revealed = true
        }
    }
}

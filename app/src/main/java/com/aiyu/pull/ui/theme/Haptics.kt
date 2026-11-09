package com.aiyu.pull.ui.theme

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * A light tick when a tap lands on something that handles it. Material components draw their own ripple and
 * never read `LocalIndication`, so the tap is watched from above instead: once the children have run, an up
 * that one of them consumed was a click. A scroll or drag consumes a move first, and a press that slides off
 * or hits a disabled control consumes nothing, so those stay silent.
 * shortcut: a tap that places the cursor in a text field ticks too, give text fields their own rule if that grates.
 */
@Composable
fun Modifier.clickHaptics(): Modifier {
    val haptic = LocalHapticFeedback.current
    return pointerInput(haptic) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
            while (true) {
                val change = awaitPointerEvent(PointerEventPass.Final).changes.first()
                if (change.changedToUpIgnoreConsumed()) {
                    if (change.isConsumed) haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
                    break
                }
                if (change.isConsumed) break
            }
        }
    }
}

/**
 * Wrap the app once, inside the theme. A dialog, sheet or menu is its own window and sits outside this box,
 * so each of those passes `Modifier.clickHaptics()` as its modifier.
 */
@Composable
fun WithClickHaptics(content: @Composable () -> Unit) = Box(Modifier.clickHaptics(), propagateMinConstraints = true) { content() }

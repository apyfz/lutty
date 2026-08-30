package com.apyfz.lutty.ui

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas

/**
 * A deliberately thin slider.
 *
 * Material's slider reserves a 48dp touch target and draws a large thumb, which is most of the
 * height of a control row. This keeps the same drag area but draws a hairline track, so the
 * editing controls take as little room from the image as possible.
 */
@Composable
fun SlimSlider(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onChangeFinished: (() -> Unit)? = null,
) {
    val span = (range.endInclusive - range.start).takeIf { it > 0f } ?: 1f
    var width by remember { mutableFloatStateOf(1f) }
    val fraction = ((value - range.start) / span).coerceIn(0f, 1f)

    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val active = MaterialTheme.colorScheme.primary

    fun emit(x: Float) = onChange(range.start + (x / width).coerceIn(0f, 1f) * span)

    Canvas(
        modifier
            .fillMaxWidth()
            .height(28.dp)
            .pointerInput(range) {
                // onChangeFinished also fires here: a tap is a complete interaction, and callers
                // use it to clear drag state. Without it a tap-to-seek would leave that state set.
                detectTapGestures(onTap = { emit(it.x); onChangeFinished?.invoke() })
            }
            .pointerInput(range) {
                detectHorizontalDragGestures(
                    onDragEnd = { onChangeFinished?.invoke() },
                    onDragCancel = { onChangeFinished?.invoke() },
                ) { change, _ -> emit(change.position.x) }
            },
    ) {
        width = size.width
        val y = size.height / 2f
        val r = 6.dp.toPx()
        val thumbX = (r + fraction * (size.width - 2 * r)).coerceIn(r, size.width - r)
        drawLine(track, Offset(0f, y), Offset(size.width, y), strokeWidth = 3.dp.toPx())
        drawLine(active, Offset(0f, y), Offset(thumbX, y), strokeWidth = 3.dp.toPx())
        drawCircle(active, radius = r, center = Offset(thumbX, y))
    }
}

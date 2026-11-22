package io.github.emotionalleaves.splitziner.render

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput

/**
 * A sheet on white "paper" that can be pinched to zoom and dragged to move. Double-tap fits it
 * to the screen again. The paper stays white in dark theme, like a real print.
 */
@Composable
fun SheetPreview(sheet: PreparedSheet, modifier: Modifier = Modifier) {
    var zoom by remember(sheet) { mutableFloatStateOf(1f) }
    var pan by remember(sheet) { mutableStateOf(Offset.Zero) }
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }
    Canvas(
        modifier
            .clipToBounds()
            .background(Color(0xFF8A8A8A))
            .pointerInput(sheet) {
                detectTransformGestures { centroid, drag, scale, _ ->
                    val newZoom = (zoom * scale).coerceIn(1f, 40f)
                    // Keep the point under the fingers still while zooming.
                    val centre = Offset(size.width / 2f, size.height / 2f)
                    pan = (pan + (centroid - centre)) * (newZoom / zoom) - (centroid - centre) + drag
                    zoom = newZoom
                    if (zoom == 1f) pan = Offset.Zero
                }
            }
            .pointerInput(sheet) { detectTapGestures(onDoubleTap = { zoom = 1f; pan = Offset.Zero }) }
    ) {
        val fit = minOf(size.width / sheet.widthMm, size.height / sheet.heightMm) * 0.97f
        val pxPerMm = fit * zoom
        val w = sheet.widthMm * pxPerMm; val h = sheet.heightMm * pxPerMm
        val left = (size.width - w) / 2f + pan.x; val top = (size.height - h) / 2f + pan.y
        drawRect(Color.White, Offset(left, top), Size(w, h))
        drawIntoCanvas { c ->
            val native = c.nativeCanvas
            native.save()
            native.translate(left, top)
            sheet.draw(native, pxPerMm, paint)
            native.restore()
        }
    }
}

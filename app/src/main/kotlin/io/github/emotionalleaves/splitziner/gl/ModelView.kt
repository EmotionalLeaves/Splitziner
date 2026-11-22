package io.github.emotionalleaves.splitziner.gl

import android.opengl.GLSurfaceView
import android.opengl.Matrix
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.emotionalleaves.splitziner.Diagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitziner.math.Vec3
import splitziner.parts.Part
import splitziner.pipeline.Analysis
import splitziner.view.ViewerGeometry
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Twelve muted colours, timber and slate tones, that stay apart from each other but let the shape read first;
 * identical parts (same item) share one. The selected part is drawn in bright amber, which none of these is.
 */
private val PALETTE = listOf(
    0xD9B98C, 0x9DB4CC, 0xA9C2A0, 0xC9A27E, 0xB8A9C9, 0xD8C89A,
    0x8FB3B0, 0xC79C8E, 0xA7B9D6, 0xCDB48E, 0x9FB79A, 0xBFA7A0,
).map { c -> floatArrayOf(((c shr 16) and 255) / 255f, ((c shr 8) and 255) / 255f, (c and 255) / 255f) }

/** Field of view used by the renderer, in degrees. */
private const val FOV = 35.0

/**
 * Interactive 3D view of the parts. One finger orbits, two fingers pan and zoom, a tap selects.
 * [selectedId] is the part to highlight; [onSelect] is called with a part id, or null for empty space.
 */
@Composable
fun ModelView(
    analysis: Analysis, selectedId: String?, onSelect: (String?) -> Unit, modifier: Modifier = Modifier,
    /** Per part id, how far it moves when fully pulled apart (model mm); null until worked out. */
    offsets: Map<String, Vec3>? = null,
    /** 0 = assembled, 1 = fully pulled apart. */
    explode: Float = 0f,
    /** Per part id: 0 hidden, 1 faint, 2 normal; null shows every part normally. Used to play the build step by step. */
    stage: Map<String, Int>? = null,
) {
    val renderer = remember { ModelRenderer() }
    val viewHolder = remember { arrayOfNulls<GLSurfaceView>(1) }
    val dark = isSystemInDarkTheme()
    val scope = rememberCoroutineScope()
    val currentOnSelect = rememberUpdatedState(onSelect)
    val currentAnalysis = rememberUpdatedState(analysis)

    // The model is recentred and scaled to about one unit so 32-bit floats keep it sharp.
    val center = analysis.bounds.center
    val ext = analysis.bounds.extents
    val unit = 1.0 / maxOf(ext.x, ext.y, ext.z, 1e-9)

    val currentOffsets = rememberUpdatedState(offsets)
    val currentExplode = rememberUpdatedState(explode)
    LaunchedEffect(analysis, offsets) {
        val scene = withContext(Dispatchers.Default) {
            analysis.parts.map { p ->
                val color = PALETTE[(((p.item ?: 1) - 1) % PALETTE.size + PALETTE.size) % PALETTE.size]
                val o = offsets?.get(p.id) ?: Vec3.ZERO
                ScenePart(ViewerGeometry.build(p.mesh, center, unit), color, !p.excluded, floatArrayOf((o.x * unit).toFloat(), (o.y * unit).toFloat(), (o.z * unit).toFloat()))
            }
        }
        renderer.setScene(scene)
        viewHolder[0]?.requestRender()
    }
    LaunchedEffect(selectedId, analysis, dark, explode, stage) {
        renderer.stage = stage?.let { m -> IntArray(analysis.parts.size) { i -> m[analysis.parts[i].id] ?: 0 } }
        renderer.selected = analysis.parts.indexOfFirst { it.id == selectedId }
        renderer.darkBackground = dark
        renderer.explode = explode
        viewHolder[0]?.requestRender()
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> viewHolder[0]?.onResume()
                Lifecycle.Event.ON_PAUSE -> viewHolder[0]?.onPause()
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    fun redraw() { viewHolder[0]?.requestRender() }
    val cam = renderer.camera

    fun pick(pos: Offset, w: Int, h: Int) {
        val a = currentAnalysis.value
        val vp = renderer.viewProjection(w, h)
        scope.launch {
            val hit = withContext(Dispatchers.Default) {
                runCatching {
                    val inv = FloatArray(16)
                    if (!Matrix.invertM(inv, 0, vp, 0)) return@runCatching null
                    val nx = 2f * pos.x / w - 1f; val ny = 1f - 2f * pos.y / h
                    fun unproject(z: Float): Vec3 {
                        val o = FloatArray(4); Matrix.multiplyMV(o, 0, inv, 0, floatArrayOf(nx, ny, z, 1f), 0)
                        return Vec3(o[0] / o[3] / unit + center.x, o[1] / o[3] / unit + center.y, o[2] / o[3] / unit + center.z)
                    }
                    val near = unproject(-1f); val dir = (unproject(1f) - near).normalized()
                    var best: Part? = null; var bestT = Double.MAX_VALUE
                    for (p in a.parts) {
                        if (p.excluded) continue
                        // A pulled-apart part is hit where it is drawn: shift the ray back by the part's travel instead of moving the mesh.
                        val shift = (currentOffsets.value?.get(p.id) ?: Vec3.ZERO) * currentExplode.value.toDouble()
                        val t = p.mesh.bvh.firstHit(near - shift, dir)?.t ?: continue
                        if (t < bestT) { bestT = t; best = p }
                    }
                    best
                }.onFailure { Diagnostics.logError("pick", it) }.getOrNull()
            }
            currentOnSelect.value(hit?.id)
        }
    }

    Box(modifier) {
        AndroidView(
            factory = { ctx ->
                GLSurfaceView(ctx).apply {
                    setEGLContextClientVersion(2)
                    setRenderer(renderer)
                    renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
                    viewHolder[0] = this
                }
            },
            modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val start = down.position
                    val t0 = down.uptimeMillis
                    var travelled = 0f
                    var prevCentroid = start
                    var prevSpan = 0f
                    var prevCount = 1
                    while (true) {
                        val ev = awaitPointerEvent()
                        val pressed = ev.changes.filter { it.pressed }
                        if (pressed.isEmpty()) {
                            val up = ev.changes.firstOrNull()
                            if (travelled < viewConfiguration.touchSlop && (up?.uptimeMillis ?: Long.MAX_VALUE) - t0 < 350) pick(start, size.width, size.height)
                            break
                        }
                        var cx = 0f; var cy = 0f
                        for (c in pressed) { cx += c.position.x; cy += c.position.y }
                        val centroid = Offset(cx / pressed.size, cy / pressed.size)
                        var span = 0f
                        if (pressed.size >= 2) { for (c in pressed) span += (c.position - centroid).getDistance(); span /= pressed.size }
                        if (pressed.size != prevCount) {
                            // A finger was added or lifted: restart the deltas so the view does not jump.
                            prevCount = pressed.size; prevCentroid = centroid; prevSpan = span
                            travelled += viewConfiguration.touchSlop * 2
                            continue
                        }
                        val d = centroid - prevCentroid
                        travelled += d.getDistance()
                        if (pressed.size == 1) {
                            if (travelled > viewConfiguration.touchSlop) {
                                cam.azimuth -= d.x * 0.35f
                                cam.elevation = (cam.elevation + d.y * 0.35f).coerceIn(-89f, 89f)
                            }
                        } else {
                            if (prevSpan > 0f && span > 0f) cam.distance = (cam.distance * prevSpan / span).coerceIn(0.15f, 30f)
                            // Pan: move the target so the model follows the fingers.
                            val perPixel = (2.0 * cam.distance * tan(Math.toRadians(FOV / 2)) / maxOf(size.height, 1)).toFloat()
                            val az = Math.toRadians(cam.azimuth.toDouble()); val el = Math.toRadians(cam.elevation.toDouble())
                            val rx = cos(az).toFloat(); val ry = sin(az).toFloat()
                            val ux = (-sin(el) * sin(az)).toFloat(); val uy = (sin(el) * cos(az)).toFloat(); val uz = cos(el).toFloat()
                            cam.targetX += (-rx * d.x + ux * d.y) * perPixel
                            cam.targetY += (-ry * d.x + uy * d.y) * perPixel
                            cam.targetZ += (uz * d.y) * perPixel
                        }
                        prevCentroid = centroid; prevSpan = span
                        ev.changes.forEach { it.consume() }
                        redraw()
                    }
                }
            },
        )
        // Small view buttons in one row along the top, where a framed model leaves room; down the side they sat
        // over the model's end on a narrow phone.
        androidx.compose.foundation.layout.Row(Modifier.align(Alignment.TopEnd).padding(horizontal = 4.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(2.dp)) {
            fun look(az: Float, el: Float) { cam.reset(); cam.azimuth = az; cam.elevation = el; redraw() }
            for ((label, az, el) in listOf(Triple("Front", 0f, 0f), Triple("Top", 0f, 89f), Triple("Right", 90f, 0f), Triple("3D", 35f, 25f))) {
                androidx.compose.material3.Surface(
                    onClick = { look(az, el) },
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
                    color = androidx.compose.material3.MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.85f),
                ) { Text(label, style = androidx.compose.material3.MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) }
            }
        }
    }
}

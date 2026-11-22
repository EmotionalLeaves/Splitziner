package io.github.emotionalleaves.splitziner.gl

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import io.github.emotionalleaves.splitziner.Diagnostics
import splitziner.math.Vec3
import splitziner.view.PartGeometry
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin

/** One part ready to draw: its buffers, its colour, and whether it is shown. */
class ScenePart(
    val geometry: PartGeometry, val color: FloatArray, val visible: Boolean,
    /** How far this part moves when the model is fully pulled apart, in the scene's scaled units. */
    val offset: FloatArray = floatArrayOf(0f, 0f, 0f),
)

/** Orbit camera with world Z kept up. Angles in degrees; the model is pre-scaled to about one unit across. */
class OrbitCamera {
    @Volatile var azimuth = 35f
    @Volatile var elevation = 25f
    @Volatile var distance = 2.6f
    @Volatile var targetX = 0f
    @Volatile var targetY = 0f
    @Volatile var targetZ = 0f

    fun eye(): FloatArray {
        val az = Math.toRadians(azimuth.toDouble()); val el = Math.toRadians(elevation.toDouble())
        return floatArrayOf(
            targetX + (distance * cos(el) * sin(az)).toFloat(),
            targetY - (distance * cos(el) * cos(az)).toFloat(),
            targetZ + (distance * sin(el)).toFloat(),
        )
    }

    fun reset() { azimuth = 35f; elevation = 25f; distance = 2.6f; targetX = 0f; targetY = 0f; targetZ = 0f }
}

/** Draws shaded parts with dark crease lines. Runs on the GL thread; data arrives through [setScene]. */
class ModelRenderer : GLSurfaceView.Renderer {
    val camera = OrbitCamera()
    @Volatile var selected = -1
    @Volatile var darkBackground = false

    /** 0 = assembled, 1 = fully pulled apart. */
    @Volatile var explode = 0f

    /** Per part: 0 = hidden, 1 = faint (already built), 2 = normal. Null shows everything normally. */
    @Volatile var stage: IntArray? = null

    private var pending: List<ScenePart>? = null
    private val lock = Any()
    private var parts: List<ScenePart> = emptyList()
    private var triBuffers: List<FloatBuffer> = emptyList()
    private var lineBuffers: List<FloatBuffer> = emptyList()

    private var program = 0
    private var lineProgram = 0
    private var width = 1
    private var height = 1
    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val mvp = FloatArray(16)

    fun setScene(scene: List<ScenePart>) { synchronized(lock) { pending = scene } }

    /** Combined view-projection matrix for the current camera, for picking from the UI thread. */
    fun viewProjection(w: Int, h: Int): FloatArray {
        val p = FloatArray(16); val v = FloatArray(16); val out = FloatArray(16)
        computeMatrices(w, h, p, v)
        Matrix.multiplyMM(out, 0, p, 0, v, 0)
        return out
    }

    private fun computeMatrices(w: Int, h: Int, p: FloatArray, v: FloatArray) {
        val aspect = w.toFloat() / maxOf(h, 1)
        Matrix.perspectiveM(p, 0, 35f, aspect, camera.distance * 0.05f, camera.distance * 4f + 10f)
        val e = camera.eye()
        // Looking straight down or up would make "up = Z" degenerate; elevation is clamped short of that.
        Matrix.setLookAtM(v, 0, e[0], e[1], e[2], camera.targetX, camera.targetY, camera.targetZ, 0f, 0f, 1f)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program = link(VERT, FRAG)
        lineProgram = link(LINE_VERT, LINE_FRAG)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        // The context was recreated: buffers survive (they are plain memory) but must be re-read.
        synchronized(lock) { if (pending == null && parts.isNotEmpty()) pending = parts }
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) { width = w; height = h; GLES20.glViewport(0, 0, w, h) }

    override fun onDrawFrame(gl: GL10?) {
        synchronized(lock) { pending?.let { upload(it); pending = null } }
        val bg = if (darkBackground) 0.10f else 0.96f
        GLES20.glClearColor(bg, bg, bg, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        if (parts.isEmpty() || program == 0) return
        computeMatrices(width, height, proj, view)
        Matrix.multiplyMM(mvp, 0, proj, 0, view, 0)
        val e = camera.eye()
        // Light from over the viewer's shoulder, so faces turned toward the camera are brightest.
        val l = Vec3((e[0] - camera.targetX).toDouble(), (e[1] - camera.targetY).toDouble(), (e[2] - camera.targetZ).toDouble() + camera.distance * 0.35).normalized()

        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uMvp"), 1, false, mvp, 0)
        GLES20.glUniform3f(GLES20.glGetUniformLocation(program, "uLight"), l.x.toFloat(), l.y.toFloat(), l.z.toFloat())
        val aPos = GLES20.glGetAttribLocation(program, "aPos"); val aNor = GLES20.glGetAttribLocation(program, "aNormal")
        val uColor = GLES20.glGetUniformLocation(program, "uColor")
        val uOffset = GLES20.glGetUniformLocation(program, "uOffset")
        val ex = explode
        // Push faces back slightly so the crease lines drawn on top of them do not flicker.
        GLES20.glEnable(GLES20.GL_POLYGON_OFFSET_FILL); GLES20.glPolygonOffset(1f, 1f)
        GLES20.glEnableVertexAttribArray(aPos); GLES20.glEnableVertexAttribArray(aNor)
        val st = stage
        for (i in parts.indices) {
            val p = parts[i]; if (!p.visible) continue
            val sg = st?.getOrNull(i) ?: 2
            if (sg == 0) continue
            val b = triBuffers[i]
            b.position(0); GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 24, b)
            b.position(3); GLES20.glVertexAttribPointer(aNor, 3, GLES20.GL_FLOAT, false, 24, b)
            val c = p.color
            GLES20.glUniform3f(uOffset, p.offset[0] * ex, p.offset[1] * ex, p.offset[2] * ex)
            if (i == selected) GLES20.glUniform3f(uColor, 1f, 0.82f, 0.25f)
            else if (sg == 1) { val g = if (darkBackground) 0.32f else 0.82f; GLES20.glUniform3f(uColor, g, g, g) }
            else GLES20.glUniform3f(uColor, c[0], c[1], c[2])
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, p.geometry.triangles.size / 6)
        }
        GLES20.glDisableVertexAttribArray(aNor)
        GLES20.glDisable(GLES20.GL_POLYGON_OFFSET_FILL)

        GLES20.glUseProgram(lineProgram)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(lineProgram, "uMvp"), 1, false, mvp, 0)
        val lc = if (darkBackground) 0.05f else 0.12f
        GLES20.glUniform3f(GLES20.glGetUniformLocation(lineProgram, "uColor"), lc, lc, lc)
        val lPos = GLES20.glGetAttribLocation(lineProgram, "aPos")
        val lOffset = GLES20.glGetUniformLocation(lineProgram, "uOffset")
        GLES20.glEnableVertexAttribArray(lPos)
        GLES20.glLineWidth(2f)
        for (i in parts.indices) {
            val p = parts[i]; if (!p.visible || p.geometry.lines.isEmpty() || (st?.getOrNull(i) ?: 2) == 0) continue
            val b = lineBuffers[i]; b.position(0)
            GLES20.glUniform3f(lOffset, p.offset[0] * ex, p.offset[1] * ex, p.offset[2] * ex)
            GLES20.glVertexAttribPointer(lPos, 3, GLES20.GL_FLOAT, false, 12, b)
            GLES20.glDrawArrays(GLES20.GL_LINES, 0, p.geometry.lines.size / 3)
        }
        GLES20.glDisableVertexAttribArray(lPos)
    }

    private fun upload(scene: List<ScenePart>) {
        parts = scene
        triBuffers = scene.map { direct(it.geometry.triangles) }
        lineBuffers = scene.map { direct(it.geometry.lines) }
    }

    private fun direct(a: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(maxOf(a.size, 1) * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(a); position(0) }

    private fun link(vs: String, fs: String): Int {
        fun compile(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src); GLES20.glCompileShader(s)
            val ok = IntArray(1); GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) Diagnostics.log("shader compile failed: ${GLES20.glGetShaderInfoLog(s)}")
            return s
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs)); GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1); GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) Diagnostics.log("program link failed: ${GLES20.glGetProgramInfoLog(p)}")
        return p
    }

    private companion object {
        const val VERT = """
            uniform mat4 uMvp; uniform vec3 uOffset; attribute vec3 aPos; attribute vec3 aNormal; varying vec3 vNormal;
            void main() { vNormal = aNormal; gl_Position = uMvp * vec4(aPos + uOffset, 1.0); }"""
        // abs() lights both sides of a face, so a part with inside-out triangles still reads as solid.
        const val FRAG = """
            precision mediump float; uniform vec3 uColor; uniform vec3 uLight; varying vec3 vNormal;
            void main() { float d = abs(dot(normalize(vNormal), uLight)); gl_FragColor = vec4(uColor * (0.38 + 0.62 * d), 1.0); }"""
        const val LINE_VERT = """
            uniform mat4 uMvp; uniform vec3 uOffset; attribute vec3 aPos;
            void main() { gl_Position = uMvp * vec4(aPos + uOffset, 1.0); }"""
        const val LINE_FRAG = """
            precision mediump float; uniform vec3 uColor;
            void main() { gl_FragColor = vec4(uColor, 1.0); }"""
    }
}

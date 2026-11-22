package splitziner.view

import splitziner.math.Vec3
import splitziner.mesh.Mesh
import kotlin.math.cos

/** Picks the edges of a mesh that a drawing or a shaded view should show as lines. */
object EdgeExtractor {
    /** Faces meeting at more than this angle form a visible crease. Smooth tessellated curves stay below it. */
    const val FEATURE_ANGLE_DEG = 30.0

    /**
     * Indices (into [Mesh.topology]) of crease edges, plus every edge that is not shared by exactly
     * two triangles (open or non-manifold edges are always drawn).
     */
    fun featureEdges(mesh: Mesh, angleDeg: Double = FEATURE_ANGLE_DEG): IntArray {
        val topo = mesh.topology
        val n = mesh.faceNormals
        val limit = cos(Math.toRadians(angleDeg))
        val out = ArrayList<Int>()
        for (e in 0 until topo.edgeCount) {
            if (topo.edgeUseCount[e] != 2) { out += e; continue }
            val a = topo.edgeFaces[2 * e]; val b = topo.edgeFaces[2 * e + 1]
            val dot = n[3 * a] * n[3 * b] + n[3 * a + 1] * n[3 * b + 1] + n[3 * a + 2] * n[3 * b + 2]
            if (dot < limit) out += e
        }
        return out.toIntArray()
    }
}

/** Float buffers for showing one part on screen: flat-shaded triangles and its crease lines. */
class PartGeometry(
    /** Per vertex: position xyz then normal xyz. Three vertices per triangle. */
    val triangles: FloatArray,
    /** Position xyz per vertex, two vertices per line. */
    val lines: FloatArray,
)

object ViewerGeometry {
    /** Positions are recentred on [center] and multiplied by [scale] so they stay accurate as 32-bit floats. */
    fun build(mesh: Mesh, center: Vec3, scale: Double): PartGeometry {
        val tc = mesh.triangleCount
        val tri = FloatArray(tc * 18)
        val n = mesh.faceNormals
        for (t in 0 until tc) for (c in 0..2) {
            val v = 3 * mesh.triangles[3 * t + c]
            val o = 18 * t + 6 * c
            tri[o] = ((mesh.vertices[v] - center.x) * scale).toFloat()
            tri[o + 1] = ((mesh.vertices[v + 1] - center.y) * scale).toFloat()
            tri[o + 2] = ((mesh.vertices[v + 2] - center.z) * scale).toFloat()
            tri[o + 3] = n[3 * t].toFloat(); tri[o + 4] = n[3 * t + 1].toFloat(); tri[o + 5] = n[3 * t + 2].toFloat()
        }
        val edges = EdgeExtractor.featureEdges(mesh)
        val topo = mesh.topology
        val lines = FloatArray(edges.size * 6)
        for ((i, e) in edges.withIndex()) for (c in 0..1) {
            val v = 3 * topo.edges[2 * e + c]
            lines[6 * i + 3 * c] = ((mesh.vertices[v] - center.x) * scale).toFloat()
            lines[6 * i + 3 * c + 1] = ((mesh.vertices[v + 1] - center.y) * scale).toFloat()
            lines[6 * i + 3 * c + 2] = ((mesh.vertices[v + 2] - center.z) * scale).toFloat()
        }
        return PartGeometry(tri, lines)
    }
}

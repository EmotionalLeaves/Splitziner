package splitziner.parts

import splitziner.math.Vec3
import splitziner.mesh.Mesh

enum class PartKind { PANEL, BOARD, ROD, SHAPED }

/** One physical part of the assembly, in millimetres. */
data class Part(
    /** Stable within a project: P01, P02, ... */
    val id: String,
    val name: String,
    val mesh: Mesh,
    /** How many times this exact body was stored in the file (1 = once). */
    val copies: Int = 1,
    val watertight: Boolean = mesh.isWatertight,
    // Set by the cut list:
    val kind: PartKind? = null,
    /** Stock thickness, width and length in mm, smallest to largest. */
    val t: Double = 0.0,
    val w: Double = 0.0,
    val l: Double = 0.0,
    /** Volume divided by the volume of its stock box; 1.0 for a plain board. */
    val fill: Double = 0.0,
    val desc: String = "",
    /** Item (find) number shared by identical parts. */
    val item: Int? = null,
    val qty: Int = 1,
    // Set by the assembly plan:
    /** Direction the part leaves the assembly, times the minimum travel in mm; null = stays put. */
    val explode: Vec3? = null,
    val group: String? = null,
    val step: Int? = null,
    // User overrides:
    val material: String = "",
    val excluded: Boolean = false,
)

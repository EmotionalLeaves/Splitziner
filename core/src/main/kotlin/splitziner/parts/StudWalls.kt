package splitziner.parts

/**
 * A stud wall (a freestanding gallery wall, a partition): a frame of upright studs between level plates, with a
 * sheet over one or both faces. Its parts are named so, it is not taken for a cabinet with doors or a crib with
 * spindles, and it stands on its feet rather than against a wall.
 */
object StudWalls {
    class Wall(val skins: List<Part>, val studs: List<Part>, val plates: List<Part>, val feet: List<Part>)

    /** A sheet is no thicker than this (mm). */
    private const val SKIN_MAX = 19.5

    fun find(live: List<Part>): Wall? {
        if (live.size < 5) return null
        fun touch(a: Part, b: Part) = (0..2).all { k -> a.mesh.bounds.min[k] <= b.mesh.bounds.max[k] + 0.5 && b.mesh.bounds.min[k] <= a.mesh.bounds.max[k] + 0.5 }
        // Sheets standing upright, big, thin across.
        val sheets = live.filter { p -> val e = p.mesh.bounds.extents; val t = minOf(e.x, e.y); t <= SKIN_MAX && maxOf(e.x, e.y) >= 600.0 && e.z >= 600.0 && t < 0.05 * minOf(maxOf(e.x, e.y), e.z) }
        if (sheets.isEmpty()) return null
        val ax = if (sheets[0].mesh.bounds.extents.y < sheets[0].mesh.bounds.extents.x) 1 else 0
        if (sheets.any { p -> (if (ax == 1) p.mesh.bounds.extents.y else p.mesh.bounds.extents.x) > SKIN_MAX }) return null
        val rest = live - sheets.toSet()
        // The frame: parts touching a sheet's inner face, within the sheets' span across.
        val frame = rest.filter { p -> sheets.any { s -> touch(p, s) && (p.mesh.bounds.min[ax] >= s.mesh.bounds.max[ax] - 0.5 || p.mesh.bounds.max[ax] <= s.mesh.bounds.min[ax] + 0.5) } }
        val studs = frame.filter { p -> val e = p.mesh.bounds.extents; e.z > 4 * maxOf(e.x, e.y) }
        val plates = frame.filter { p -> val e = p.mesh.bounds.extents; e[1 - ax] > 4 * maxOf(e[ax], e.z) }
        if (studs.size < 2 || plates.isEmpty() || studs.size + plates.size != frame.size) return null
        // Studs stand between plates.
        if (studs.any { s -> plates.none { pl -> touch(s, pl) } }) return null
        // Feet: boards on the floor under the bottom plate, running across the wall.
        val floor = live.minOf { it.mesh.bounds.min.z }
        val feet = (rest - frame.toSet()).filter { p -> val b = p.mesh.bounds; b.min.z <= floor + 1.0 && b.extents[ax] > 2 * b.extents[1 - ax] && (frame + sheets).any { touch(p, it) } }
        if (rest.size != frame.size + feet.size) return null
        return Wall(sheets, studs, plates, feet)
    }
}

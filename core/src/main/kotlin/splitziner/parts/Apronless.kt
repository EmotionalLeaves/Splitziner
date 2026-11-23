package splitziner.parts

/**
 * A table whose legs go straight into its top, with no aprons, rails or stretchers (a simple slab table): the legs
 * are bolted on with mounting plates, with the top upside down on the floor.
 */
object Apronless {
    /** The legs, or empty when it is not such a table. */
    fun legs(live: List<Part>): List<Part> {
        val legs = live.filter { it.name == "Leg" }
        if (legs.size < 3) return emptyList()
        val tops = live.filter { it.name == "Top" }
        if (tops.isEmpty()) return emptyList()
        fun touches(a: Part, b: Part) = (0..2).all { k -> a.mesh.bounds.min[k] <= b.mesh.bounds.max[k] + 0.5 && b.mesh.bounds.min[k] <= a.mesh.bounds.max[k] + 0.5 }
        // Every leg's top end meets a top; nothing else meets any leg but the floor.
        if (!legs.all { l -> tops.any { t -> kotlin.math.abs(t.mesh.bounds.min.z - l.mesh.bounds.max.z) < 1.0 && touches(l, t) } }) return emptyList()
        val others = live - legs.toSet() - tops.toSet()
        if (others.any { o -> legs.any { l -> touches(l, o) } }) return emptyList()
        if (legs.any { l -> legs.any { o -> o !== l && touches(l, o) } }) return emptyList()
        return legs
    }
}

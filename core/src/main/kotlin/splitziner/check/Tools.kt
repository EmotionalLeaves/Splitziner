package splitziner.check

/** A tool the maker may own. The set they pick decides what the design check calls possible. */
enum class Tool(val label: String) {
    HAND_SAW("Hand saw"), CIRCULAR_SAW("Circular saw"), TABLE_SAW("Table saw"), MITER_SAW("Mitre saw"),
    JIGSAW("Jigsaw"), BAND_SAW("Band saw"), ROUTER("Router"), DRILL("Drill"), DRILL_PRESS("Drill press"),
    SANDER("Sander"), PLANER("Thickness planer"), CNC("CNC router"), PRINTER("3D printer"),
    POCKET_JIG("Pocket-hole jig"), BRAD_NAILER("Brad nailer"), DOWEL_JIG("Dowel jig"), CLAMPS("Clamps");

    companion object {
        fun parse(names: Collection<String>): Set<Tool> = names.mapNotNull { n -> entries.firstOrNull { it.name == n } }.toSet()
    }
}

/**
 * A kind of work a part needs. [with] are the tools that do it properly; [slowly] are tools that
 * can do it with patience and less accuracy.
 */
enum class Work(val label: String, val with: Set<Tool>, val slowly: Set<Tool> = emptySet()) {
    STRAIGHT_CUT("straight cuts", setOf(Tool.CIRCULAR_SAW, Tool.TABLE_SAW, Tool.MITER_SAW, Tool.BAND_SAW, Tool.CNC), setOf(Tool.HAND_SAW, Tool.JIGSAW)),
    SHEET_CUT("long cuts across sheet material", setOf(Tool.CIRCULAR_SAW, Tool.TABLE_SAW, Tool.CNC), setOf(Tool.JIGSAW, Tool.HAND_SAW)),
    CURVED_CUT("curved cuts", setOf(Tool.JIGSAW, Tool.BAND_SAW, Tool.ROUTER, Tool.CNC)),
    NOTCH("notches or angled cuts", setOf(Tool.JIGSAW, Tool.BAND_SAW, Tool.HAND_SAW, Tool.CNC), setOf(Tool.CIRCULAR_SAW)),
    CUTOUT("an opening cut inside the part", setOf(Tool.JIGSAW, Tool.ROUTER, Tool.CNC)),
    HOLES("round holes", setOf(Tool.DRILL, Tool.DRILL_PRESS, Tool.CNC)),
    EDGE_SHAPE("rounded or bevelled edges", setOf(Tool.ROUTER, Tool.CNC), setOf(Tool.SANDER)),
    THICKNESS("bringing material to a thickness shops do not stock", setOf(Tool.PLANER)),
    CARVE("a solid shape that is not a flat board", setOf(Tool.CNC, Tool.PRINTER), setOf(Tool.BAND_SAW)),
}

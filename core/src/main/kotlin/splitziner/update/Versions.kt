package splitziner.update

/** Version numbers as the releases use them: "v1.2.3" or "1.2.3". */
object Versions {
    fun parse(v: String): List<Int>? {
        val m = Regex("""^v?(\d+)\.(\d+)\.(\d+)""").find(v.trim()) ?: return null
        return m.groupValues.drop(1).map { it.toInt() }
    }

    /** True when [latest] is a later release than [current]. Anything unreadable is never "newer". */
    fun isNewer(latest: String, current: String): Boolean {
        val a = parse(latest) ?: return false
        val b = parse(current) ?: return true
        for (i in 0..2) if (a[i] != b[i]) return a[i] > b[i]
        return false
    }

    /** Release notes as plain text for a small dialog: markdown marks removed, the install footer dropped. */
    fun plainNotes(body: String): String =
        body.substringBefore("**Install:**").lines().joinToString("\n") { it.replace("**", "").replace("`", "").replace(Regex("^\\s*- "), "• ") }.trim()
}

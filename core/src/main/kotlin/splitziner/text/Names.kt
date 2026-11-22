package splitziner.text

/** Readable names for things the user did not name themselves. */
object Names {
    /**
     * A project name from a file name: "kitchen_run.stl" becomes "Kitchen run", "BunkBed-v2.obj" stays "BunkBed v2".
     * Underscores and hyphens are spaces; a name typed all in lower case gets a capital first letter.
     */
    fun fromFile(fileName: String): String {
        val base = fileName.substringBeforeLast('.').replace(Regex("[_\\-]+"), " ").replace(Regex("\\s+"), " ").trim()
        if (base.isEmpty()) return fileName
        return if (base.none { it.isUpperCase() }) base.replaceFirstChar { it.uppercase() } else base
    }
}

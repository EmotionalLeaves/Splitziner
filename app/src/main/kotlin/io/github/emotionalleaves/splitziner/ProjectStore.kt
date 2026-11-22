package io.github.emotionalleaves.splitziner

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import splitziner.parts.ItemOverride
import splitziner.pipeline.AnalysisOptions
import java.io.File
import java.util.UUID

/** What the app remembers about one imported file. The STL itself sits beside it as source.stl. */
data class ProjectMeta(
    val id: String,
    val name: String,
    val fileName: String,
    val createdAt: Long,
    /** "mm", "cm", "in", "m", or null to let the app guess. */
    val fileUnits: String? = null,
    /** "mm", "in", or null to follow the design units. */
    val displayUnits: String? = null,
    val inchStyle: String = "decimal",
    val excluded: Set<String> = emptySet(),
    val partNames: Map<String, String> = emptyMap(),
    val items: Map<String, ItemOverride> = emptyMap(),
    val partCount: Int = 0,
    // Title block and drawing choices:
    val title: String = "", val drawingNo: String = "", val rev: String = "-", val organisation: String = "",
    val designer: String = "", val drawn: String = "", val checked: String = "", val approved: String = "",
    val date: String = "", val notes: List<String> = emptyList(),
    /** Null until the user picks or types tolerances. */
    val tolerance: List<String>? = null,
    /** "auto" or a sheet letter. */
    val sheet: String = "auto",
    /** "auto", "on" or "off". */
    val hidden: String = "auto",
    /** "auto" or "none". */
    val sections: String = "auto",
    /** The user's own build order (part ids per step, base first); null for the automatic order. */
    val stepOrder: List<List<String>>? = null,
    /** Names of the tools the maker has for this project; null until they have said. */
    val tools: List<String>? = null,
    /** How parts are joined (a [splitziner.joinery.JoinMethod] name); null follows the tools. */
    val joinMethod: String? = null,
    /** Which of the file's axes points up ("z", "y", "-z"...). */
    val up: String = "z",
    /** Changes made to the model in the app, replayed on the original file each time it is read. */
    val edits: List<splitziner.edit.Edit> = emptyList(),
    /** The user's wording per step, keyed by the parts in the step. */
    val stepText: Map<String, splitziner.guide.StepText> = emptyMap(),
    /** The finished size as last worked out ("38 x 12 x 72 in"), for the project list. */
    val size: String = "",
    /** When the project was last opened or changed, for the project list. */
    val updatedAt: Long = 0L,
) {
    fun drawingInfo(version: String) = splitziner.sheet.DrawingInfo(
        title = title.ifBlank { name }, drawingNo = drawingNo, rev = rev.ifBlank { "-" }, organisation = organisation,
        designer = designer, drawn = drawn, checked = checked, approved = approved, date = date, notes = notes,
        toleranceLines = tolerance, softwareVersion = version, sourceFile = fileName,
    )

    fun drawingSettings() = splitziner.drawing.DrawingSettings(sheet = sheet, hidden = hidden, sections = sections)

    fun options() = AnalysisOptions(fileUnits, displayUnits, inchStyle, items, excluded, partNames, up = up, edits = edits)
}

/** Projects live in app-private storage: files/projects/<id>/{source.stl, project.json}. */
class ProjectStore(context: Context) {
    private val root = File(context.filesDir, "projects").apply { mkdirs() }

    fun dir(id: String) = File(root, id)
    fun source(id: String) = File(dir(id), "source.stl")

    fun list(): List<ProjectMeta> =
        (root.listFiles() ?: emptyArray()).mapNotNull { d -> runCatching { read(File(d, "project.json")) }.getOrNull() }
            .sortedByDescending { maxOf(it.createdAt, it.updatedAt) }

    /** Copies the file in from [input] without holding it in memory. */
    fun create(fileName: String, input: java.io.InputStream): ProjectMeta {
        val id = UUID.randomUUID().toString()
        dir(id).mkdirs()
        source(id).outputStream().use { input.copyTo(it, 1 shl 16) }
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        val meta = ProjectMeta(id, fileName.substringBeforeLast('.'), fileName, System.currentTimeMillis(), date = today)
        save(meta)
        return meta
    }

    /** The whole project as one file: its model and every setting and change, to back up or move to another phone. */
    fun export(meta: ProjectMeta, out: java.io.OutputStream) {
        java.util.zip.ZipOutputStream(out).use { z ->
            z.putNextEntry(java.util.zip.ZipEntry("project.json")); File(dir(meta.id), "project.json").inputStream().use { it.copyTo(z) }; z.closeEntry()
            z.putNextEntry(java.util.zip.ZipEntry("source.stl")); source(meta.id).inputStream().use { it.copyTo(z) }; z.closeEntry()
        }
    }

    /** Reads a file made by [export] as a new project (a new id, so it never replaces one already here). */
    fun importProject(input: java.io.InputStream): ProjectMeta {
        val id = UUID.randomUUID().toString()
        val d = dir(id).apply { mkdirs() }
        var json: String? = null; var model = false
        try {
            java.util.zip.ZipInputStream(input).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    when (e.name) {
                        "project.json" -> json = z.readBytes().toString(Charsets.UTF_8)
                        "source.stl" -> { source(id).outputStream().use { z.copyTo(it, 1 shl 16) }; model = true }
                    }
                }
            }
            if (json == null || !model) error("That file is not a Splitziner project.")
            val o = JSONObject(json!!); o.put("id", id); o.put("createdAt", System.currentTimeMillis())
            File(d, "project.json").writeText(o.toString())
            return read(File(d, "project.json"))
        } catch (t: Throwable) { d.deleteRecursively(); throw t }
    }

    fun save(meta: ProjectMeta) {
        val o = JSONObject()
        o.put("schema", 1).put("id", meta.id).put("name", meta.name).put("fileName", meta.fileName).put("createdAt", meta.createdAt)
        o.put("fileUnits", meta.fileUnits ?: JSONObject.NULL).put("displayUnits", meta.displayUnits ?: JSONObject.NULL)
        o.put("inchStyle", meta.inchStyle).put("partCount", meta.partCount)
        o.put("excluded", JSONArray(meta.excluded.toList()))
        o.put("partNames", JSONObject(meta.partNames))
        val items = JSONObject()
        for ((k, v) in meta.items) items.put(k, JSONObject().put("material", v.material).put("materialSpec", v.materialSpec).put("partNo", v.partNo).put("grain", v.grain))
        o.put("items", items)
        o.put("title", meta.title).put("drawingNo", meta.drawingNo).put("rev", meta.rev).put("organisation", meta.organisation)
        o.put("designer", meta.designer).put("drawn", meta.drawn).put("checked", meta.checked).put("approved", meta.approved)
        o.put("date", meta.date).put("notes", JSONArray(meta.notes)).put("sheet", meta.sheet).put("hidden", meta.hidden).put("sections", meta.sections)
        o.put("tolerance", meta.tolerance?.let { JSONArray(it) } ?: JSONObject.NULL)
        o.put("tools", meta.tools?.let { JSONArray(it) } ?: JSONObject.NULL)
        o.put("joinMethod", meta.joinMethod ?: JSONObject.NULL)
        o.put("up", meta.up)
        o.put("edits", JSONArray(meta.edits.map { EditJson.write(it) }))
        o.put("stepOrder", meta.stepOrder?.let { order -> JSONArray(order.map { JSONArray(it) }) } ?: JSONObject.NULL)
        val st = JSONObject()
        for ((k, v) in meta.stepText) st.put(k, JSONObject().put("title", v.title ?: "").put("lines", JSONArray(v.lines ?: emptyList<String>())).put("check", v.check ?: ""))
        o.put("stepText", st)
        o.put("size", meta.size).put("updatedAt", meta.updatedAt)
        // Write to a temporary file first so a crash never leaves a half-written project.
        val tmp = File(dir(meta.id), "project.json.tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(File(dir(meta.id), "project.json"))
    }

    private fun read(f: File): ProjectMeta {
        val o = JSONObject(f.readText())
        fun strOrNull(k: String) = if (o.isNull(k)) null else o.getString(k)
        val excluded = o.optJSONArray("excluded")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet()
        val names = o.optJSONObject("partNames")?.let { n -> n.keys().asSequence().associateWith { n.getString(it) } } ?: emptyMap()
        val items = o.optJSONObject("items")?.let { n ->
            n.keys().asSequence().associateWith { k ->
                val v = n.getJSONObject(k); ItemOverride(v.optString("material"), v.optString("materialSpec"), v.optString("partNo"), v.optBoolean("grain", false))
            }
        } ?: emptyMap()
        return ProjectMeta(
            o.getString("id"), o.getString("name"), o.getString("fileName"), o.getLong("createdAt"),
            strOrNull("fileUnits"), strOrNull("displayUnits"), o.optString("inchStyle", "decimal"), excluded, names, items, o.optInt("partCount"),
            title = o.optString("title"), drawingNo = o.optString("drawingNo"), rev = o.optString("rev", "-"), organisation = o.optString("organisation"),
            designer = o.optString("designer"), drawn = o.optString("drawn"), checked = o.optString("checked"), approved = o.optString("approved"),
            date = o.optString("date"), notes = strings(o.optJSONArray("notes")) ?: emptyList(),
            tolerance = if (o.isNull("tolerance")) null else strings(o.optJSONArray("tolerance")),
            tools = if (o.isNull("tools")) null else strings(o.optJSONArray("tools")),
            joinMethod = if (o.isNull("joinMethod")) null else o.optString("joinMethod"),
            up = o.optString("up", "z"),
            edits = o.optJSONArray("edits")?.let { a -> (0 until a.length()).mapNotNull { EditJson.read(a.getJSONObject(it)) } } ?: emptyList(),
            sheet = o.optString("sheet", "auto"), hidden = o.optString("hidden", "auto"), sections = o.optString("sections", "auto"),
            stepOrder = if (o.isNull("stepOrder")) null else o.optJSONArray("stepOrder")?.let { a -> (0 until a.length()).map { strings(a.getJSONArray(it)) ?: emptyList() } },
            stepText = o.optJSONObject("stepText")?.let { n ->
                n.keys().asSequence().associateWith { k ->
                    val v = n.getJSONObject(k)
                    splitziner.guide.StepText(v.optString("title").ifBlank { null }, strings(v.optJSONArray("lines"))?.ifEmpty { null }, v.optString("check").ifBlank { null })
                }
            } ?: emptyMap(),
            size = o.optString("size"), updatedAt = o.optLong("updatedAt", 0L),
        )
    }

    private fun strings(a: JSONArray?): List<String>? = a?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }

    fun delete(id: String) { dir(id).deleteRecursively() }
}


/** Model changes as JSON, so they are saved with the project. */
object EditJson {
    private fun vec(v: splitziner.math.Vec3) = JSONArray(listOf(v.x, v.y, v.z))
    private fun vec(a: JSONArray) = splitziner.math.Vec3(a.getDouble(0), a.getDouble(1), a.getDouble(2))
    private fun ids(a: JSONArray) = (0 until a.length()).map { a.getString(it) }

    fun write(e: splitziner.edit.Edit): JSONObject = when (e) {
        is splitziner.edit.Edit.Move -> JSONObject().put("op", "move").put("ids", JSONArray(e.ids)).put("d", vec(e.d))
        is splitziner.edit.Edit.Thickness -> JSONObject().put("op", "thickness").put("ids", JSONArray(e.ids)).put("t", e.t)
        is splitziner.edit.Edit.SquareUp -> JSONObject().put("op", "square").put("ids", JSONArray(e.ids))
        is splitziner.edit.Edit.Merge -> JSONObject().put("op", "merge").put("ids", JSONArray(e.ids))
        is splitziner.edit.Edit.Trim -> JSONObject().put("op", "trim").put("id", e.id).put("by", e.by)
        is splitziner.edit.Edit.Resize -> JSONObject().put("op", "resize").put("id", e.id).put("t", e.t).put("w", e.w).put("l", e.l)
        is splitziner.edit.Edit.AddBox -> JSONObject().put("op", "add").put("id", e.id).put("name", e.name).put("min", vec(e.min)).put("size", vec(e.size))
        is splitziner.edit.Edit.Duplicate -> JSONObject().put("op", "copy").put("id", e.id).put("newId", e.newId).put("d", vec(e.d))
        is splitziner.edit.Edit.Delete -> JSONObject().put("op", "delete").put("ids", JSONArray(e.ids))
        is splitziner.edit.Edit.SplitBoards -> JSONObject().put("op", "boards").put("ids", JSONArray(e.ids))
        is splitziner.edit.Edit.CutBack -> JSONObject().put("op", "cutback").put("id", e.id).put("by", e.by)
        is splitziner.edit.Edit.House -> JSONObject().put("op", "house").put("id", e.id).put("into", e.into)
        is splitziner.edit.Edit.Rotate -> JSONObject().put("op", "rotate").put("ids", org.json.JSONArray(e.ids)).put("axis", e.axis).put("turns", e.turns)
        is splitziner.edit.Edit.Stretch -> JSONObject().put("op", "stretch").put("axis", e.axis).put("at", e.at).put("by", e.by)
    }

    /** Null for a change this version does not know, so an older app skips it and keeps the rest. */
    fun read(o: JSONObject): splitziner.edit.Edit? = runCatching {
        when (o.getString("op")) {
            "move" -> splitziner.edit.Edit.Move(ids(o.getJSONArray("ids")), vec(o.getJSONArray("d")))
            "thickness" -> splitziner.edit.Edit.Thickness(ids(o.getJSONArray("ids")), o.getDouble("t"))
            "square" -> splitziner.edit.Edit.SquareUp(ids(o.getJSONArray("ids")))
            "merge" -> splitziner.edit.Edit.Merge(ids(o.getJSONArray("ids")))
            "trim" -> splitziner.edit.Edit.Trim(o.getString("id"), o.getString("by"))
            "resize" -> splitziner.edit.Edit.Resize(o.getString("id"), o.getDouble("t"), o.getDouble("w"), o.getDouble("l"))
            "add" -> splitziner.edit.Edit.AddBox(o.getString("id"), o.getString("name"), vec(o.getJSONArray("min")), vec(o.getJSONArray("size")))
            "copy" -> splitziner.edit.Edit.Duplicate(o.getString("id"), o.getString("newId"), vec(o.getJSONArray("d")))
            "delete" -> splitziner.edit.Edit.Delete(ids(o.getJSONArray("ids")))
            "boards" -> splitziner.edit.Edit.SplitBoards(ids(o.getJSONArray("ids")))
            "cutback" -> splitziner.edit.Edit.CutBack(o.getString("id"), o.getString("by"))
            "house" -> splitziner.edit.Edit.House(o.getString("id"), o.getString("into"))
            "rotate" -> splitziner.edit.Edit.Rotate(o.getJSONArray("ids").let { a -> (0 until a.length()).map { a.getString(it) } }, o.getInt("axis"), o.getInt("turns"))
            "stretch" -> splitziner.edit.Edit.Stretch(o.getInt("axis"), o.getDouble("at"), o.getDouble("by"))
            else -> null
        }
    }.getOrNull()
}

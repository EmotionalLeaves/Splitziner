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
) {
    fun drawingInfo(version: String) = splitziner.sheet.DrawingInfo(
        title = title.ifBlank { name }, drawingNo = drawingNo, rev = rev.ifBlank { "-" }, organisation = organisation,
        designer = designer, drawn = drawn, checked = checked, approved = approved, date = date, notes = notes,
        toleranceLines = tolerance, softwareVersion = version, sourceFile = fileName,
    )

    fun drawingSettings() = splitziner.drawing.DrawingSettings(sheet = sheet, hidden = hidden)

    fun options() = AnalysisOptions(fileUnits, displayUnits, inchStyle, items, excluded, partNames)
}

/** Projects live in app-private storage: files/projects/<id>/{source.stl, project.json}. */
class ProjectStore(context: Context) {
    private val root = File(context.filesDir, "projects").apply { mkdirs() }

    fun dir(id: String) = File(root, id)
    fun source(id: String) = File(dir(id), "source.stl")

    fun list(): List<ProjectMeta> =
        (root.listFiles() ?: emptyArray()).mapNotNull { d -> runCatching { read(File(d, "project.json")) }.getOrNull() }
            .sortedByDescending { it.createdAt }

    fun create(fileName: String, bytes: ByteArray): ProjectMeta {
        val id = UUID.randomUUID().toString()
        dir(id).mkdirs()
        source(id).writeBytes(bytes)
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        val meta = ProjectMeta(id, fileName.substringBeforeLast('.'), fileName, System.currentTimeMillis(), date = today)
        save(meta)
        return meta
    }

    fun save(meta: ProjectMeta) {
        val o = JSONObject()
        o.put("schema", 1).put("id", meta.id).put("name", meta.name).put("fileName", meta.fileName).put("createdAt", meta.createdAt)
        o.put("fileUnits", meta.fileUnits ?: JSONObject.NULL).put("displayUnits", meta.displayUnits ?: JSONObject.NULL)
        o.put("inchStyle", meta.inchStyle).put("partCount", meta.partCount)
        o.put("excluded", JSONArray(meta.excluded.toList()))
        o.put("partNames", JSONObject(meta.partNames))
        val items = JSONObject()
        for ((k, v) in meta.items) items.put(k, JSONObject().put("material", v.material).put("materialSpec", v.materialSpec).put("partNo", v.partNo))
        o.put("items", items)
        o.put("title", meta.title).put("drawingNo", meta.drawingNo).put("rev", meta.rev).put("organisation", meta.organisation)
        o.put("designer", meta.designer).put("drawn", meta.drawn).put("checked", meta.checked).put("approved", meta.approved)
        o.put("date", meta.date).put("notes", JSONArray(meta.notes)).put("sheet", meta.sheet).put("hidden", meta.hidden)
        o.put("tolerance", meta.tolerance?.let { JSONArray(it) } ?: JSONObject.NULL)
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
                val v = n.getJSONObject(k); ItemOverride(v.optString("material"), v.optString("materialSpec"), v.optString("partNo"))
            }
        } ?: emptyMap()
        return ProjectMeta(
            o.getString("id"), o.getString("name"), o.getString("fileName"), o.getLong("createdAt"),
            strOrNull("fileUnits"), strOrNull("displayUnits"), o.optString("inchStyle", "decimal"), excluded, names, items, o.optInt("partCount"),
            title = o.optString("title"), drawingNo = o.optString("drawingNo"), rev = o.optString("rev", "-"), organisation = o.optString("organisation"),
            designer = o.optString("designer"), drawn = o.optString("drawn"), checked = o.optString("checked"), approved = o.optString("approved"),
            date = o.optString("date"), notes = strings(o.optJSONArray("notes")) ?: emptyList(),
            tolerance = if (o.isNull("tolerance")) null else strings(o.optJSONArray("tolerance")),
            sheet = o.optString("sheet", "auto"), hidden = o.optString("hidden", "auto"),
        )
    }

    private fun strings(a: JSONArray?): List<String>? = a?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }

    fun delete(id: String) { dir(id).deleteRecursively() }
}

package splitziner.export

import splitziner.dims.LengthFormat
import splitziner.parts.CutListResult

object CsvWriter {
    /** Quotes a field when it holds a comma, quote or line break (RFC 4180). */
    fun field(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    private fun line(vararg f: Any?) = f.joinToString(",") { field(it?.toString() ?: "") } + "\r\n"

    /** The cut list and stock summary. Starts with a byte-order mark so spreadsheet apps read it as UTF-8. */
    fun cutList(result: CutListResult, fmt: LengthFormat): String {
        val sb = StringBuilder("﻿")
        sb.append(line("Item", "Qty", "Kind", "Name", "Description", "Thickness", "Width", "Length", "Units", "Material", "Material spec", "Part no", "Part ids"))
        val byId = result.parts.associateBy { it.id }
        for (r in result.rows) {
            // What the parts are called, when they have names of their own ("Side", "Drawer bottom").
            val names = r.name.ifBlank { r.memberIds.mapNotNull { byId[it] }.filter { it.name != it.id }.map { it.name }.distinct().sorted().joinToString(" / ") }
            sb.append(line(r.item, r.qty, r.kind, names, r.description, fmt.format(r.t), fmt.format(r.w), fmt.format(r.l), fmt.unitLabel,
                r.material, r.materialSpec, r.partNo, r.memberIds.joinToString(", ")))
        }
        if (result.stock.isNotEmpty()) {
            sb.append("\r\n")
            sb.append(line("Stock summary: total length before saw kerf and waste"))
            sb.append(line("Kind", "Thickness", "Width", "Total length", "Units", "Pieces"))
            for (s in result.stock) sb.append(line(s.kind, s.thickness, s.width, s.totalLength, fmt.unitLabel, s.pieces))
        }
        return sb.toString()
    }
}

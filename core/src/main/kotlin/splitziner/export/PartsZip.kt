package splitziner.export

import splitziner.io.StlWriter
import splitziner.parts.Part
import splitziner.split.SplitReport
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Packs every part as its own STL, with a list of what is inside. */
object PartsZip {
    fun safeName(s: String): String = s.replace(Regex("[^A-Za-z0-9_-]"), "_").trim('_').ifBlank { "part" }

    fun write(parts: List<Part>, report: SplitReport, sourceName: String, out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            val csv = StringBuilder("﻿Id,Name,Item,Description,File,Copies found in source,Watertight\r\n")
            for (p in parts) {
                if (p.excluded) continue
                val file = if (p.name == p.id) "${p.id}.stl" else "${p.id}_${safeName(p.name)}.stl"
                zip.putNextEntry(ZipEntry(file))
                StlWriter.writeBinary(p.mesh, zip, "Splitziner ${p.id} units=mm")
                zip.closeEntry()
                csv.append(listOf(p.id, p.name, p.item?.toString() ?: "", p.desc, file, p.copies.toString(), if (p.watertight) "yes" else "no").joinToString(",") { CsvWriter.field(it) }).append("\r\n")
            }
            zip.putNextEntry(ZipEntry("parts.csv")); zip.write(csv.toString().toByteArray(Charsets.UTF_8)); zip.closeEntry()
            val u = report.units
            val readme = "Parts split from $sourceName by Splitziner.\r\n\r\n" +
                "Every STL in this archive is in MILLIMETRES, whatever units the source file used.\r\n" +
                "Source file units: ${u.fileUnits}" + (if (u.margin != null) " (guessed)" else " (chosen by the user)") + ". Designed in: ${u.designUnits}.\r\n" +
                "parts.csv lists each file with its item number and description.\r\n"
            zip.putNextEntry(ZipEntry("README.txt")); zip.write(readme.toByteArray(Charsets.UTF_8)); zip.closeEntry()
        }
    }
}

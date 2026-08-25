package com.vikash.voicescribe.export

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.vikash.voicescribe.data.Recording
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

const val EXPORT_FOOTER = "Transcribed offline with VoiceScribe"

/**
 * On-device transcript exporters — no libraries:
 * PDF via the platform PdfDocument, DOCX as a hand-built OOXML zip.
 */
object TranscriptExporter {

    private fun exportDir(context: Context): File =
        File(context.cacheDir, "exports").apply { mkdirs() }

    private fun baseName(rec: Recording): String =
        rec.title.replace(Regex("[^\\p{L}\\p{N} _-]"), "").trim().take(60)
            .ifBlank { "transcript" }

    private fun metaLine(rec: Recording): String {
        val date = SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault())
            .format(Date(rec.createdAt))
        val dur = formatDuration(rec.durationMs)
        val lang = rec.language?.let { " · Language: ${it.uppercase()}" } ?: ""
        return "$date · $dur$lang"
    }

    private fun formatDuration(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
        else "%d:%02d".format(s / 60, s % 60)
    }

    private fun formatStamp(ms: Long): String {
        val s = ms / 1000
        return "[%d:%02d]".format(s / 60, s % 60)
    }

    // ---------------------------------------------------------------- PDF ----

    private class Block(val text: String, val paint: TextPaint, val spacingAfter: Int)

    private fun paint(sizePt: Float, bold: Boolean = false, gray: Boolean = false) =
        TextPaint().apply {
            isAntiAlias = true
            textSize = sizePt
            typeface = if (bold) Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            else Typeface.SANS_SERIF
            color = if (gray) Color.rgb(110, 110, 110) else Color.rgb(20, 20, 20)
        }

    private fun buildBlocks(rec: Recording): List<Block> {
        val blocks = mutableListOf<Block>()
        blocks += Block(rec.title, paint(20f, bold = true), 6)
        blocks += Block(metaLine(rec), paint(10f, gray = true), 18)
        if (rec.summary.isNotEmpty()) {
            blocks += Block("Summary", paint(14f, bold = true), 8)
            rec.summary.forEach { blocks += Block("•  $it", paint(11f), 6) }
            blocks += Block("", paint(11f), 10)
        }
        blocks += Block("Transcript", paint(14f, bold = true), 8)
        if (rec.segments.isNotEmpty()) {
            rec.segments.forEach {
                blocks += Block("${formatStamp(it.t0Ms)}  ${it.text}", paint(11f), 6)
            }
        } else {
            blocks += Block(rec.transcript.orEmpty(), paint(11f), 6)
        }
        return blocks
    }

    fun exportPdf(context: Context, rec: Recording): File {
        val pageW = 595   // A4 @ 72dpi
        val pageH = 842
        val margin = 52
        val contentW = pageW - margin * 2
        val footerPaint = paint(8f, gray = true)

        val doc = PdfDocument()
        var pageNo = 0
        var page: PdfDocument.Page? = null
        var y = margin

        fun newPage() {
            page?.let { doc.finishPage(it) }
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNo).create())
            page!!.canvas.drawText(EXPORT_FOOTER, margin.toFloat(), (pageH - 24).toFloat(), footerPaint)
            y = margin
        }
        newPage()

        for (block in buildBlocks(rec)) {
            if (block.text.isEmpty()) {
                y += block.spacingAfter
                continue
            }
            val layout = StaticLayout.Builder
                .obtain(block.text, 0, block.text.length, block.paint, contentW)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(2f, 1f)
                .build()
            var line = 0
            while (line < layout.lineCount) {
                val top = layout.getLineTop(line)
                var end = line
                while (end < layout.lineCount &&
                    y + layout.getLineBottom(end) - top <= pageH - margin - 20
                ) end++
                if (end == line) {
                    newPage()
                    continue
                }
                val canvas = page!!.canvas
                canvas.save()
                canvas.translate(margin.toFloat(), (y - top).toFloat())
                canvas.clipRect(0, top, contentW, layout.getLineBottom(end - 1))
                layout.draw(canvas)
                canvas.restore()
                y += layout.getLineBottom(end - 1) - top
                line = end
                if (line < layout.lineCount) newPage()
            }
            y += block.spacingAfter
        }
        page?.let { doc.finishPage(it) }

        val out = File(exportDir(context), "${baseName(rec)}.pdf")
        out.outputStream().use { doc.writeTo(it) }
        doc.close()
        return out
    }

    // --------------------------------------------------------------- DOCX ----

    private fun xmlEscape(s: String): String = buildString(s.length) {
        for (c in s) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            else -> if (c.code >= 0x20 || c == '\t') append(c)
        }
    }

    /** One paragraph: size in half-points, optional bold/gray. */
    private fun para(text: String, halfPtSize: Int = 22, bold: Boolean = false, gray: Boolean = false): String {
        val props = buildString {
            append("<w:rPr>")
            if (bold) append("<w:b/>")
            if (gray) append("<w:color w:val=\"6E6E6E\"/>")
            append("<w:sz w:val=\"$halfPtSize\"/><w:szCs w:val=\"$halfPtSize\"/>")
            append("</w:rPr>")
        }
        return "<w:p><w:r>$props<w:t xml:space=\"preserve\">${xmlEscape(text)}</w:t></w:r></w:p>"
    }

    fun exportDocx(context: Context, rec: Recording): File {
        val body = StringBuilder()
        body.append(para(rec.title, halfPtSize = 40, bold = true))
        body.append(para(metaLine(rec), halfPtSize = 18, gray = true))
        body.append(para(""))
        if (rec.summary.isNotEmpty()) {
            body.append(para("Summary", halfPtSize = 28, bold = true))
            rec.summary.forEach { body.append(para("•  $it")) }
            body.append(para(""))
        }
        body.append(para("Transcript", halfPtSize = 28, bold = true))
        if (rec.segments.isNotEmpty()) {
            rec.segments.forEach { body.append(para("${formatStamp(it.t0Ms)}  ${it.text}")) }
        } else {
            body.append(para(rec.transcript.orEmpty()))
        }
        body.append(para(""))
        body.append(para(EXPORT_FOOTER, halfPtSize = 16, gray = true))

        val documentXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>$body<w:sectPr/></w:body></w:document>"""

        val contentTypes = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>"""

        val rels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>"""

        val out = File(exportDir(context), "${baseName(rec)}.docx")
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            fun entry(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            entry("[Content_Types].xml", contentTypes)
            entry("_rels/.rels", rels)
            entry("word/document.xml", documentXml)
        }
        return out
    }
}

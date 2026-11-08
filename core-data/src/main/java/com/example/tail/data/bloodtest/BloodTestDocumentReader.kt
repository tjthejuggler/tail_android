package com.example.tail.data.bloodtest

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * Reads user-picked blood-test documents (plain text, PDF pages, photos)
 * into a form the AI parser understands:
 *  - text-like files → appended text sections
 *  - images          → base64 data-URLs (multimodal chat input)
 *  - PDFs            → each page rendered to a JPEG data-URL
 *
 * Multiple documents / multi-page documents are all merged so a single
 * blood test spanning several files can be parsed in one shot.
 */
class BloodTestDocumentReader(private val context: Context) {

    data class Contents(
        /** Extracted text from text-like documents (may be empty). */
        val text: String,
        /** data:image/...;base64, URLs for every image/PDF page. */
        val images: List<String>,
        /** Display names of successfully read files. */
        val names: List<String>,
        /** Non-fatal problems (unreadable files), nullable. */
        val errors: String?
    )

    companion object {
        /** Hard cap on rendered PDF pages per parse (protects the LLM budget). */
        private const val MAX_PDF_PAGES = 10
    }

    fun read(uris: List<Uri>): Contents {
        val textParts = StringBuilder()
        val images = mutableListOf<String>()
        val names = mutableListOf<String>()
        val errors = mutableListOf<String>()

        uris.forEach { uri ->
            val name = queryName(uri) ?: uri.lastPathSegment ?: "document"
            val mime = context.contentResolver.getType(uri) ?: guessMime(name)
            try {
                when {
                    mime.startsWith("image/") -> {
                        val b64 = encodeStream(uri)
                        if (b64 != null) {
                            images += "data:$mime;base64,$b64"
                            names += name
                        } else errors += "$name: unreadable image"
                    }
                    mime == "application/pdf" -> {
                        val pages = renderPdf(uri, images)
                        if (pages > 0) names += "$name ($pages page${if (pages == 1) "" else "s"})"
                        else errors += "$name: no pages rendered"
                    }
                    else -> {
                        // text/*, application/json, anything textual
                        val t = context.contentResolver.openInputStream(uri)
                            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                        if (t.isNullOrBlank()) {
                            errors += "$name: empty or unreadable"
                        } else {
                            textParts.append("\n\n── $name ──\n").append(t.take(60000))
                            names += name
                        }
                    }
                }
            } catch (e: Exception) {
                errors += "$name: ${e.message ?: e.javaClass.simpleName}"
            }
        }

        return Contents(
            text = textParts.toString().trim(),
            images = images,
            names = names,
            errors = errors.takeIf { it.isNotEmpty() }?.joinToString("; ")
        )
    }

    private fun queryName(uri: Uri): String? = try {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    } catch (e: Exception) {
        null
    }

    private fun guessMime(name: String): String = when {
        name.endsWith(".pdf", true) -> "application/pdf"
        name.endsWith(".png", true) -> "image/png"
        name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) -> "image/jpeg"
        name.endsWith(".webp", true) -> "image/webp"
        name.endsWith(".gif", true) -> "image/gif"
        else -> "text/plain"
    }

    private fun encodeStream(uri: Uri): String? = try {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        bytes?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
    } catch (e: Exception) {
        null
    }

    /** Renders up to [MAX_PDF_PAGES] pages as JPEG data-URLs into [out]; returns page count. */
    private fun renderPdf(uri: Uri, out: MutableList<String>): Int {
        var rendered = 0
        val fd: ParcelFileDescriptor = try {
            context.contentResolver.openFileDescriptor(uri, "r") ?: return 0
        } catch (e: Exception) {
            return 0
        }
        fd.use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                val pageCount = renderer.pageCount
                val limit = minOf(pageCount, MAX_PDF_PAGES)
                for (i in 0 until limit) {
                    renderer.openPage(i).use { page ->
                        // ~2x the PDF point size gives readable lab-report text
                        val scale = 2f
                        val w = (page.width * scale).toInt().coerceAtLeast(600)
                        val h = (page.height * scale).toInt()
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        Canvas(bmp).drawColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val bos = ByteArrayOutputStream()
                        bmp.compress(Bitmap.CompressFormat.JPEG, 85, bos)
                        bmp.recycle()
                        out += "data:image/jpeg;base64," +
                            Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
                        rendered++
                    }
                }
            }
        }
        return rendered
    }
}

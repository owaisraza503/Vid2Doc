package com.raza.vid2doc

import java.io.OutputStream
import java.util.Locale

/**
 * Minimal PDF writer that embeds JPEG frames as-is (DCTDecode).
 * No re-encoding, so quality stays high and memory use stays tiny.
 */
class PdfWriter(private val out: OutputStream) {
    private var pos = 0L
    private val offsets = ArrayList<Long>()
    private val pageIds = ArrayList<Int>()
    private var nextId = 3 // 1 = catalog, 2 = page tree

    init {
        write("%PDF-1.4\n")
    }

    private fun write(s: String) = write(s.toByteArray(Charsets.ISO_8859_1))

    private fun write(b: ByteArray) {
        out.write(b)
        pos += b.size
    }

    private fun begin(id: Int) {
        while (offsets.size < id) offsets.add(0L)
        offsets[id - 1] = pos
        write("$id 0 obj\n")
    }

    fun addJpeg(jpeg: ByteArray, w: Int, h: Int) {
        val img = nextId++
        val content = nextId++
        val page = nextId++
        val pw = 960f
        val ph = pw * h / w

        begin(img)
        write("<< /Type /XObject /Subtype /Image /Width $w /Height $h /ColorSpace /DeviceRGB " +
                "/BitsPerComponent 8 /Filter /DCTDecode /Length ${jpeg.size} >>\nstream\n")
        write(jpeg)
        write("\nendstream\nendobj\n")

        val c = "q $pw 0 0 $ph 0 0 cm /Im0 Do Q"
        begin(content)
        write("<< /Length ${c.length} >>\nstream\n$c\nendstream\nendobj\n")

        begin(page)
        write("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $pw $ph] " +
                "/Resources << /XObject << /Im0 $img 0 R >> >> /Contents $content 0 R >>\nendobj\n")
        pageIds.add(page)
    }

    fun finish() {
        begin(1)
        write("<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        begin(2)
        write("<< /Type /Pages /Count ${pageIds.size} /Kids [${pageIds.joinToString(" ") { "$it 0 R" }}] >>\nendobj\n")
        val xref = pos
        val n = offsets.size + 1
        write("xref\n0 $n\n0000000000 65535 f \n")
        offsets.forEach { write(String.format(Locale.US, "%010d 00000 n \n", it)) }
        write("trailer\n<< /Size $n /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        out.flush()
    }
}

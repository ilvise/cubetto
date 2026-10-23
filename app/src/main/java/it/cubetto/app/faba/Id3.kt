package it.cubetto.app.faba

import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile

/**
 * Tag ID3 dei brani per il FABA™, identici a quelli di `redele.py` di myfaba-hacks (mutagen):
 * si tolgono i tag ID3v2 e ID3v1 e si scrive un ID3v2.4 con il solo titolo (TIT2, UTF-8)
 * "K{ID}CP{NN}" e il padding predefinito di mutagen. L'audio resta byte per byte com'è.
 * Se il titolo è sbagliato il box lampeggia velocemente e non riproduce la traccia.
 */
object Id3 {

    /** Intervallo [start, end) dell'audio, esclusi i tag ID3v2 iniziali e l'ID3v1 finale (come mutagen). */
    fun audioRange(file: File): LongRange {
        RandomAccessFile(file, "r").use { raf ->
            var start = 0L
            val header = ByteArray(10)
            // Alcuni file hanno più tag ID3v2 consecutivi
            while (true) {
                raf.seek(start)
                if (raf.read(header) != 10) break
                if (header[0] != 'I'.code.toByte() || header[1] != 'D'.code.toByte() || header[2] != '3'.code.toByte()) break
                val size = ((header[6].toInt() and 0x7F) shl 21) or ((header[7].toInt() and 0x7F) shl 14) or
                    ((header[8].toInt() and 0x7F) shl 7) or (header[9].toInt() and 0x7F)
                val footer = if (header[5].toInt() and 0x10 != 0) 10 else 0
                start += 10 + size + footer
            }
            var end = raf.length()
            if (end - start >= 128) {
                val tag = ByteArray(3)
                raf.seek(end - 128)
                raf.readFully(tag)
                if (String(tag, Charsets.ISO_8859_1) == "TAG") end -= 128
            }
            return start until maxOf(start, end)
        }
    }

    private const val HEADER = 10

    private fun synchsafe(n: Int) = byteArrayOf(
        ((n ushr 21) and 0x7F).toByte(), ((n ushr 14) and 0x7F).toByte(), ((n ushr 7) and 0x7F).toByte(), (n and 0x7F).toByte(),
    )

    /**
     * ID3v2.4 iniziale, byte per byte come mutagen per un audio lungo [audioBytes]: frame TIT2
     * con codifica 3 (UTF-8) e terminatore, poi 1024 + [audioBytes]/1000 byte di zeri.
     */
    fun v2Tag(title: String, audioBytes: Long): ByteArray {
        val text = byteArrayOf(3) + title.toByteArray(Charsets.UTF_8) + byteArrayOf(0)
        val frame = "TIT2".toByteArray(Charsets.ISO_8859_1) + synchsafe(text.size) + byteArrayOf(0, 0) + text
        val body = frame.size + (1024 + audioBytes / 1000).toInt()
        val tag = ByteArray(HEADER + body)
        (byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 4, 0, 0) + synchsafe(body)).copyInto(tag, 0)
        frame.copyInto(tag, HEADER)
        return tag
    }

    fun fabaTitle(figureId: String, track: Int) = "K${figureId}CP%02d".format(track)

    /** Dimensione finale del file .MKI senza doverlo generare. */
    fun encodedSize(source: File, figureId: String, track: Int): Long {
        val range = audioRange(source)
        val audio = range.last - range.first + 1
        return v2Tag(fabaTitle(figureId, track), audio).size + audio
    }

    /** Scrive l'MP3 ri-taggato e cifrato nel formato FABA™ su [out]. */
    fun writeFaba(source: File, figureId: String, track: Int, out: OutputStream, onBytes: (Long) -> Unit = {}) {
        val range = audioRange(source)
        val title = fabaTitle(figureId, track)
        val enc = FabaCipher.EncryptingOutputStream(out)
        enc.write(v2Tag(title, range.last - range.first + 1))
        RandomAccessFile(source, "r").use { raf ->
            raf.seek(range.first)
            var remaining = range.last - range.first + 1
            val buf = ByteArray(64 * 1024)
            while (remaining > 0) {
                val n = raf.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                if (n <= 0) break
                enc.write(buf, 0, n)
                remaining -= n
                onBytes(n.toLong())
            }
        }
        enc.flush()
    }
}

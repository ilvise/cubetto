package it.cubetto.app.faba

import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile

/**
 * Gestione minima dei tag ID3 per il FABA™: tutti i tag vengono rimossi e
 * se ne scrive uno solo, il titolo (TIT2) "K{ID}CP{NN}". Se il titolo è
 * sbagliato il box lampeggia velocemente e non riproduce la traccia.
 */
object Id3 {

    /** Intervallo [start, end) dell'audio vero, esclusi ID3v2 iniziali e ID3v1/APE finali. */
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
            if (end - start >= 32) {
                val ape = ByteArray(32)
                raf.seek(end - 32)
                raf.readFully(ape)
                if (String(ape, 0, 8, Charsets.ISO_8859_1) == "APETAGEX") {
                    val size = (ape[12].toLong() and 0xFF) or ((ape[13].toLong() and 0xFF) shl 8) or
                        ((ape[14].toLong() and 0xFF) shl 16) or ((ape[15].toLong() and 0xFF) shl 24)
                    val hasHeader = ape[23].toInt() and 0x80 != 0
                    end -= size + if (hasHeader) 32 else 0
                }
            }
            return start until maxOf(start, end)
        }
    }

    /** Tag ID3v2.3 con il solo frame TIT2 (ISO-8859-1), come quello scritto da id3v2/createFigure.sh. */
    fun titleTag(title: String): ByteArray {
        val text = title.toByteArray(Charsets.ISO_8859_1)
        val frameSize = 1 + text.size
        val frame = ByteArray(10 + frameSize)
        "TIT2".toByteArray(Charsets.ISO_8859_1).copyInto(frame, 0)
        frame[4] = (frameSize ushr 24).toByte(); frame[5] = (frameSize ushr 16).toByte()
        frame[6] = (frameSize ushr 8).toByte(); frame[7] = frameSize.toByte()
        frame[10] = 0 // encoding ISO-8859-1
        text.copyInto(frame, 11)
        val tagSize = frame.size
        val header = byteArrayOf(
            'I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0,
            ((tagSize ushr 21) and 0x7F).toByte(), ((tagSize ushr 14) and 0x7F).toByte(),
            ((tagSize ushr 7) and 0x7F).toByte(), (tagSize and 0x7F).toByte(),
        )
        return header + frame
    }

    fun fabaTitle(figureId: String, track: Int) = "K${figureId}CP%02d".format(track)

    /** Dimensione finale del file .MKI senza doverlo generare. */
    fun encodedSize(source: File, figureId: String, track: Int): Long {
        val range = audioRange(source)
        return titleTag(fabaTitle(figureId, track)).size + (range.last - range.first + 1)
    }

    /** Scrive l'MP3 ri-taggato e cifrato nel formato FABA™ su [out]. */
    fun writeFaba(source: File, figureId: String, track: Int, out: OutputStream, onBytes: (Long) -> Unit = {}) {
        val range = audioRange(source)
        val enc = FabaCipher.EncryptingOutputStream(out)
        enc.write(titleTag(fabaTitle(figureId, track)))
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

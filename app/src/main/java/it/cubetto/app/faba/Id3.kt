package it.cubetto.app.faba

import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile

/**
 * Tag ID3 dei brani per il FABA™, identici a quelli di `createFigure.sh` di myfaba-hacks:
 * si tolgono tutti i tag e si riscrivono con `id3v2 -A "Album Album" -a "Artist Artist"
 * -T "1/1" -c "Converted for Faba Box" -t "K{ID}CP{NN}"`, cioè un ID3v2.3 (Latin-1, padding
 * di id3lib) all'inizio e un ID3v1.1 alla fine. Se il titolo è sbagliato il box lampeggia
 * velocemente e non riproduce la traccia.
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

    // Valori fissi scritti da createFigure.sh
    private const val ALBUM = "Album Album"
    private const val ARTIST = "Artist Artist"
    private const val TRACK = "1/1"
    private const val COMMENT = "Converted for Faba Box"

    /** id3lib arrotonda tag + audio al multiplo di 2048 byte successivo (se è già multiplo, 2048 in più). */
    private const val PAD_MULTIPLE = 2048
    private const val HEADER = 10
    const val V1_SIZE = 128

    private fun frame(id: String, body: ByteArray): ByteArray {
        val head = ByteArray(10)
        id.toByteArray(Charsets.ISO_8859_1).copyInto(head)
        head[4] = (body.size ushr 24).toByte(); head[5] = (body.size ushr 16).toByte()
        head[6] = (body.size ushr 8).toByte(); head[7] = body.size.toByte()
        // byte 8-9: flag del frame a zero
        return head + body
    }

    private fun latin1(s: String) = s.toByteArray(Charsets.ISO_8859_1)

    /** Frame nell'ordine in cui li scrive id3v2: album, artista, traccia, commento, titolo; testo Latin-1 (codifica 0). */
    private fun frames(title: String): ByteArray =
        frame("TALB", byteArrayOf(0) + latin1(ALBUM)) +
            frame("TPE1", byteArrayOf(0) + latin1(ARTIST)) +
            frame("TRCK", byteArrayOf(0) + latin1(TRACK)) +
            // codifica 0, lingua "\0\0\0", descrizione vuota terminata da 0
            frame("COMM", byteArrayOf(0, 0, 0, 0, 0) + latin1(COMMENT)) +
            frame("TIT2", byteArrayOf(0) + latin1(title))

    /** ID3v2.3 iniziale, byte per byte come id3v2/id3lib per un audio lungo [audioBytes]. */
    fun v2Tag(title: String, audioBytes: Long): ByteArray {
        val frames = frames(title)
        val total = ((frames.size + audioBytes + HEADER) / PAD_MULTIPLE + 1) * PAD_MULTIPLE
        val body = (total - audioBytes - HEADER).toInt()
        val tag = ByteArray(HEADER + body)
        byteArrayOf(
            'I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0,
            ((body ushr 21) and 0x7F).toByte(), ((body ushr 14) and 0x7F).toByte(),
            ((body ushr 7) and 0x7F).toByte(), (body and 0x7F).toByte(),
        ).copyInto(tag, 0)
        frames.copyInto(tag, HEADER)
        return tag
    }

    /** ID3v1.1 finale come lo scrive id3v2: campi da 30 byte, anno vuoto, traccia 1, genere 255. */
    fun v1Tag(title: String): ByteArray {
        val tag = ByteArray(V1_SIZE)
        latin1("TAG").copyInto(tag, 0)
        fun field(s: String, at: Int, len: Int) = latin1(s).copyInto(tag, at, 0, minOf(len, s.length))
        field(title, 3, 30)
        field(ARTIST, 33, 30)
        field(ALBUM, 63, 30)
        field(COMMENT, 97, 28)
        tag[126] = 1
        tag[127] = 0xFF.toByte()
        return tag
    }

    fun fabaTitle(figureId: String, track: Int) = "K${figureId}CP%02d".format(track)

    /** Dimensione finale del file .MKI senza doverlo generare. */
    fun encodedSize(source: File, figureId: String, track: Int): Long {
        val range = audioRange(source)
        val audio = range.last - range.first + 1
        return v2Tag(fabaTitle(figureId, track), audio).size + audio + V1_SIZE
    }

    /** Scrive l'MP3 ri-taggato e cifrato nel formato FABA™ su [out]. */
    fun writeFaba(source: File, figureId: String, track: Int, out: OutputStream, onBytes: (Long) -> Unit = {}) {
        val range = audioRange(source)
        val title = fabaTitle(figureId, track)
        val enc = FabaCipher.EncryptingOutputStream(out)
        enc.write(v2Tag(title, range.last - range.first + 1))
        RandomAccessFile(source, "r").use { raf ->
            var remaining = range.last - range.first + 1
            // Il primo frame (Info/Xing) con la stessa modalità canale dell'audio, come lo scrive LAME
            val info = Mp3Frames.consistentInfoFrame(raf, range)
            if (info != null) {
                enc.write(info)
                remaining -= info.size
                onBytes(info.size.toLong())
            }
            raf.seek(range.first + (info?.size ?: 0))
            val buf = ByteArray(64 * 1024)
            while (remaining > 0) {
                val n = raf.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                if (n <= 0) break
                enc.write(buf, 0, n)
                remaining -= n
                onBytes(n.toLong())
            }
        }
        enc.write(v1Tag(title))
        enc.flush()
    }
}

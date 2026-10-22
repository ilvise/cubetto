package it.cubetto.app.faba

import java.io.File
import java.io.RandomAccessFile

/**
 * Lettura veloce delle intestazioni dei frame MP3, senza decodificare l'audio.
 * I file originali FABA™ sono MPEG-1 Layer III a bitrate costante (CBR 96 kbps,
 * 44,1 kHz, joint stereo): i file VBR e quelli mono non vengono riprodotti dal box.
 */
object Mp3Frames {
    private val BITRATES = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
    private val SAMPLE_RATES = intArrayOf(44100, 48000, 32000)

    /** Modalità canale dell'intestazione: 0 stereo, 1 joint stereo, 2 dual channel, 3 mono. */
    const val MODE_MONO = 3
    private const val MODE_JOINT_STEREO = 1

    data class Summary(val frames: Int, val bitratesKbps: Set<Int>, val sampleRates: Set<Int>, val channelModes: Set<Int>) {
        val constantBitrate get() = frames > 0 && bitratesKbps.size == 1
    }

    fun summary(file: File): Summary {
        val range = Id3.audioRange(file)
        val bitrates = mutableSetOf<Int>()
        val rates = mutableSetOf<Int>()
        val modes = mutableSetOf<Int>()
        var frames = 0
        RandomAccessFile(file, "r").use { raf ->
            var pos = range.first
            val end = range.last + 1
            val h = ByteArray(4)
            while (pos + 4 <= end) {
                raf.seek(pos)
                raf.readFully(h)
                val header = ((h[0].toInt() and 0xFF) shl 24) or ((h[1].toInt() and 0xFF) shl 16) or
                    ((h[2].toInt() and 0xFF) shl 8) or (h[3].toInt() and 0xFF)
                if (header ushr 21 != 0x7FF) break
                val version = (header ushr 19) and 3
                val layer = (header ushr 17) and 3
                val bitrateIdx = (header ushr 12) and 15
                val rateIdx = (header ushr 10) and 3
                // Solo MPEG-1 Layer III, il formato degli originali
                if (version != 3 || layer != 1 || bitrateIdx == 0 || bitrateIdx == 15 || rateIdx == 3) break
                val kbps = BITRATES[bitrateIdx]
                val rate = SAMPLE_RATES[rateIdx]
                bitrates += kbps
                rates += rate
                modes += (header ushr 6) and 3
                frames++
                pos += 144 * kbps * 1000 / rate + ((header ushr 9) and 1)
            }
        }
        return Summary(frames, bitrates, rates, modes)
    }

    private fun headerAt(raf: RandomAccessFile, pos: Long, end: Long): Int? {
        if (pos + 4 > end) return null
        val h = ByteArray(4)
        raf.seek(pos)
        raf.readFully(h)
        val header = ((h[0].toInt() and 0xFF) shl 24) or ((h[1].toInt() and 0xFF) shl 16) or
            ((h[2].toInt() and 0xFF) shl 8) or (h[3].toInt() and 0xFF)
        val bitrateIdx = (header ushr 12) and 15
        val ok = header ushr 21 == 0x7FF && (header ushr 19) and 3 == 3 && (header ushr 17) and 3 == 1 &&
            bitrateIdx != 0 && bitrateIdx != 15 && (header ushr 10) and 3 != 3
        return if (ok) header else null
    }

    private fun frameLength(header: Int) =
        144 * BITRATES[(header ushr 12) and 15] * 1000 / SAMPLE_RATES[(header ushr 10) and 3] + ((header ushr 9) and 1)

    /** Posizione di "Xing"/"Info" nel frame: dopo l'intestazione e le side info (MPEG-1). */
    private fun xingOffset(header: Int) = if ((header ushr 6) and 3 == MODE_MONO) 4 + 17 else 4 + 32

    /** CRC-16 del tag LAME (polinomio 0x8005 riflesso), come in LAME. */
    private fun crc16(bytes: ByteArray, len: Int): Int {
        var crc = 0
        for (i in 0 until len) {
            crc = crc xor (bytes[i].toInt() and 0xFF)
            repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1 }
        }
        return crc
    }

    /**
     * Il frame Info/Xing che ffmpeg mette all'inizio ha la modalità "stereo" e niente bit
     * "original", mentre l'audio è joint stereo: LAME (e quindi gli originali FABA™) usa invece
     * la stessa intestazione dei frame audio. Restituisce il primo frame con l'intestazione
     * corretta e il CRC del tag LAME ricalcolato, o null se non serve cambiare nulla.
     */
    fun consistentInfoFrame(raf: RandomAccessFile, range: LongRange): ByteArray? {
        val end = range.last + 1
        val first = headerAt(raf, range.first, end) ?: return null
        val length = frameLength(first)
        val second = headerAt(raf, range.first + length, end) ?: return null
        val frame = ByteArray(length)
        raf.seek(range.first)
        raf.readFully(frame)
        val xing = xingOffset(first)
        val tag = String(frame, xing, 4, Charsets.ISO_8859_1)
        if (tag != "Xing" && tag != "Info") return null
        // Bitrate, frequenza e padding restano quelli del frame Info; modalità, copyright, original
        // ed enfasi vengono dall'audio. In joint stereo LAME scrive mode extension "M/S" (FF FB 70 64)
        val mode = (second ushr 6) and 3
        val modeExt = if (mode == MODE_JOINT_STEREO) 2 else (first ushr 4) and 3
        val wanted = (first and 0xFFFFFE00.toInt()) or (second and 0x1CF) or (modeExt shl 4)
        if (wanted == first || xingOffset(wanted) != xing) return null
        val crcPos = xing + 154
        val hadCrc = crcPos + 2 <= length &&
            crc16(frame, crcPos) == (((frame[crcPos].toInt() and 0xFF) shl 8) or (frame[crcPos + 1].toInt() and 0xFF))
        frame[2] = (wanted ushr 8).toByte()
        frame[3] = wanted.toByte()
        if (hadCrc) {
            val crc = crc16(frame, crcPos)
            frame[crcPos] = (crc ushr 8).toByte()
            frame[crcPos + 1] = crc.toByte()
        }
        return frame
    }

    /** True se il file ha il formato che il box sa riprodurre: MPEG-1 Layer III CBR a 44,1 kHz, non mono. */
    fun isFabaCompatible(file: File): Boolean = runCatching {
        val s = summary(file)
        s.constantBitrate && s.sampleRates == setOf(44100) && MODE_MONO !in s.channelModes
    }.getOrDefault(false)
}

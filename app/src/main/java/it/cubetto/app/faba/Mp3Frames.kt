package it.cubetto.app.faba

import java.io.File
import java.io.RandomAccessFile

/**
 * Lettura veloce delle intestazioni dei frame MP3, senza decodificare l'audio.
 * I file originali FABA™ sono MPEG-1 Layer III a bitrate costante (CBR 96 kbps,
 * 44,1 kHz, mono): i file VBR non vengono riprodotti dal box.
 */
object Mp3Frames {
    private val BITRATES = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
    private val SAMPLE_RATES = intArrayOf(44100, 48000, 32000)

    data class Summary(val frames: Int, val bitratesKbps: Set<Int>, val sampleRates: Set<Int>) {
        val constantBitrate get() = frames > 0 && bitratesKbps.size == 1
    }

    fun summary(file: File): Summary {
        val range = Id3.audioRange(file)
        val bitrates = mutableSetOf<Int>()
        val rates = mutableSetOf<Int>()
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
                frames++
                pos += 144 * kbps * 1000 / rate + ((header ushr 9) and 1)
            }
        }
        return Summary(frames, bitrates, rates)
    }

    /** True se il file ha il formato che il box sa riprodurre: MPEG-1 Layer III CBR a 44,1 kHz. */
    fun isFabaCompatible(file: File): Boolean = runCatching {
        val s = summary(file)
        s.constantBitrate && s.sampleRates == setOf(44100)
    }.getOrDefault(false)
}

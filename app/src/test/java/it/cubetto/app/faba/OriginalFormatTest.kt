package it.cubetto.app.faba

import it.cubetto.app.data.AudioOptions
import it.cubetto.app.ytdlp.YtDlpManager
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/** Confronto con il formato dei file originali FABA™ (K0016, figura ufficiale). */
class OriginalFormatTest {

    /** Primi 41 byte (decifrati) di MKI01/K0016/CP01.MKI originale; poi solo zeri fino al byte 4096. */
    private val originalHeader = (
        "49 44 33 03 00 00 00 00 1f 76 54 49 54 32 00 00 00 15 00 00 01 ff fe " +
            "4b 00 30 00 30 00 31 00 36 00 43 00 50 00 30 00 31 00"
        ).split(' ').map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun titleTagIsIdenticalToTheOriginalHeader() {
        val tag = Id3.titleTag(Id3.fabaTitle("0016", 1))
        assertEquals(4096, tag.size)
        assertArrayEquals(originalHeader, tag.copyOfRange(0, originalHeader.size))
        assertTrue(tag.copyOfRange(originalHeader.size, tag.size).all { it.toInt() == 0 })
    }

    /**
     * Prova completa sui file veri del box: ogni originale, decifrato e riscritto da Cubetto,
     * deve tornare identico byte per byte. Gira solo se FABA_ORIGINAL_DIR punta a una
     * cartella Kxxxx copiata dal box (i file originali non sono nel repository).
     */
    @Test
    fun rebuildingOriginalFilesGivesIdenticalBytes() {
        val dir = System.getenv("FABA_ORIGINAL_DIR")?.let(::File)
        assumeTrue("FABA_ORIGINAL_DIR non impostata", dir != null && dir.isDirectory)
        val figureId = dir!!.name.removePrefix("K")
        val files = dir.listFiles { f -> f.name.matches(Regex("""CP\d{2}\.MKI""")) }!!.sortedBy { it.name }
        assumeTrue(files.isNotEmpty())
        for (original in files) {
            val encrypted = original.readBytes()
            val plain = encrypted.copyOf().also { FabaCipher.decrypt(it) }
            assertTrue("${original.name}: formato originale", Mp3Frames.isFabaCompatible(original.writePlainTo(plain)))
            val track = original.name.substring(2, 4).toInt()
            val out = ByteArrayOutputStream()
            Id3.writeFaba(original.writePlainTo(plain), figureId, track, out)
            assertArrayEquals("${original.name} ricreato da Cubetto", encrypted, out.toByteArray())
        }
    }

    private fun File.writePlainTo(plain: ByteArray) =
        File.createTempFile(nameWithoutExtension, ".mp3").apply { writeBytes(plain); deleteOnExit() }

    // ---- Formato audio ----

    /** Frame MPEG-1 Layer III a 44,1 kHz con il bitrate indicato (indice della tabella); joint stereo come gli originali. */
    private fun frame(bitrateIdx: Int, mode: Int = 1): ByteArray {
        val kbps = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)[bitrateIdx]
        val size = 144 * kbps * 1000 / 44100
        return ByteArray(size).also {
            it[0] = 0xFF.toByte(); it[1] = 0xFB.toByte(); it[2] = (bitrateIdx shl 4).toByte(); it[3] = ((mode shl 6) or 0x04).toByte()
        }
    }

    private fun mp3(vararg bitrateIdx: Int, mode: Int = 1): File {
        val bytes = Id3.titleTag("x") + bitrateIdx.map { frame(it, mode) }.reduce { a, b -> a + b }
        return File.createTempFile("frames", ".mp3").apply { writeBytes(bytes); deleteOnExit() }
    }

    @Test
    fun constantBitrateIsAccepted() {
        val f = mp3(7, 7, 7, 7) // 96 kbps come gli originali
        val s = Mp3Frames.summary(f)
        assertEquals(4, s.frames)
        assertEquals(setOf(96), s.bitratesKbps)
        assertTrue(Mp3Frames.isFabaCompatible(f))
    }

    @Test
    fun variableBitrateIsRejected() {
        assertFalse(Mp3Frames.isFabaCompatible(mp3(4, 9, 11, 7)))
    }

    @Test
    fun monoIsRejected() {
        // I file mono, anche CBR a 96 kbps, non vengono riprodotti dal box
        assertFalse(Mp3Frames.isFabaCompatible(mp3(7, 7, 7, 7, mode = Mp3Frames.MODE_MONO)))
    }

    @Test
    fun monoOptionKeepsTwoChannels() {
        val args = YtDlpManager.ffmpegArgs(AudioOptions(mono = true), 200, splitChapters = false)
        assertTrue(args, args.startsWith("-ac 2 -ar 44100 -af aformat=channel_layouts=mono,aformat=channel_layouts=stereo,"))
        val plain = YtDlpManager.ffmpegArgs(AudioOptions(normalize = false, trimSilence = false, mono = false), 200, false)
        assertEquals("-ac 2 -ar 44100", plain)
    }
}

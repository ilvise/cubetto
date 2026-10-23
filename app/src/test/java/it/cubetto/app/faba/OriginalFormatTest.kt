package it.cubetto.app.faba

import it.cubetto.app.data.AudioOptions
import it.cubetto.app.ytdlp.LocalConverter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/** Confronto con myfaba-hacks (redele.py: mutagen + cifratura MKI) e con i file originali FABA™. */
class OriginalFormatTest {

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /**
     * Inizio reale (decifrato) di K1000/CP01.MKI creato da redele.py con 2607096 byte di audio,
     * provato funzionante sul box: ID3v2.4, TIT2 UTF-8 con terminatore, 3631 byte di padding.
     */
    private val redeleHeader = hex("49443304000000001c44" + "54495432000000 0b0000034b3130303043503031 00".replace(" ", ""))

    @Test
    fun tagIsIdenticalToRedele() {
        val tag = Id3.v2Tag(Id3.fabaTitle("1000", 1), 2607096)
        assertEquals(3662, tag.size)
        assertArrayEquals(redeleHeader, tag.copyOfRange(0, redeleHeader.size))
        assertTrue(tag.copyOfRange(redeleHeader.size, tag.size).all { it.toInt() == 0 })
        // Padding di mutagen: 1024 + audio/1000
        assertEquals(10 + 21 + 1024 + 10020, Id3.v2Tag("K1000CP04", 10020570).size)
    }

    /**
     * Prova completa con una cartella creata da redele.py e provata sul box: MYFABA_DIR deve
     * contenere in_k1000/ (gli MP3 di partenza) e out_k1000/K1000/ (i .MKI). Ogni .MKI rifatto
     * da Cubetto con lo stesso MP3 deve essere identico byte per byte. I file non sono nel repository.
     */
    @Test
    fun sameBytesAsRedeleOnRealFiles() {
        val dir = System.getenv("MYFABA_DIR")?.let(::File)
        assumeTrue("MYFABA_DIR non impostata", dir != null && dir.isDirectory)
        val outDir = File(dir, "out_k1000/K1000")
        val inputs = File(dir, "in_k1000").listFiles { f -> f.extension.equals("mp3", true) }!!.toList()
        val byAudio = inputs.associateBy { audioOf(it.readBytes(), Id3.audioRange(it)) }
        val outs = outDir.listFiles { f -> f.name.matches(Regex("""CP\d{2}\.MKI""")) }!!.sortedBy { it.name }
        assumeTrue(outs.isNotEmpty())
        for (mki in outs) {
            val expected = mki.readBytes()
            val plain = expected.copyOf().also { FabaCipher.decrypt(it) }
            val decrypted = mki.writePlainTo(plain)
            val source = byAudio[audioOf(plain, Id3.audioRange(decrypted))]
            assertTrue("${mki.name}: MP3 di partenza non trovato", source != null)
            val track = mki.name.substring(2, 4).toInt()
            val out = ByteArrayOutputStream()
            Id3.writeFaba(source!!, "1000", track, out)
            assertEquals("${mki.name}: dimensione", expected.size.toLong(), Id3.encodedSize(source, "1000", track))
            assertArrayEquals("${mki.name} rifatto da Cubetto", expected, out.toByteArray())
        }
    }

    private fun audioOf(bytes: ByteArray, range: LongRange) = bytes.copyOfRange(range.first.toInt(), range.last.toInt() + 1).contentHashCode()

    /**
     * Prova completa sui file veri del box: ogni originale, decifrato e riscritto da Cubetto,
     * deve avere lo stesso audio byte per byte e il tag di redele.py. Gira solo se
     * FABA_ORIGINAL_DIR punta a una cartella Kxxxx copiata dal box (non sono nel repository).
     */
    @Test
    fun rebuildingOriginalFilesKeepsTheAudio() {
        val dir = System.getenv("FABA_ORIGINAL_DIR")?.let(::File)
        assumeTrue("FABA_ORIGINAL_DIR non impostata", dir != null && dir.isDirectory)
        val figureId = dir!!.name.removePrefix("K")
        val files = dir.listFiles { f -> f.name.matches(Regex("""CP\d{2}\.MKI""")) }!!.sortedBy { it.name }
        assumeTrue(files.isNotEmpty())
        for (original in files) {
            val plain = original.readBytes().also { FabaCipher.decrypt(it) }
            val source = original.writePlainTo(plain)
            assertTrue("${original.name}: formato originale", Mp3Frames.isFabaCompatible(source))
            val track = original.name.substring(2, 4).toInt()
            val out = ByteArrayOutputStream()
            Id3.writeFaba(source, figureId, track, out)
            val rebuilt = out.toByteArray().also { FabaCipher.decrypt(it) }
            assertEquals(Id3.encodedSize(source, figureId, track), rebuilt.size.toLong())
            val title = Id3.fabaTitle(figureId, track)
            val audio = plain.copyOfRange(4096, plain.size)
            val tag = Id3.v2Tag(title, audio.size.toLong())
            assertArrayEquals("${original.name}: tag", tag, rebuilt.copyOfRange(0, tag.size))
            assertArrayEquals("${original.name}: audio", audio, rebuilt.copyOfRange(tag.size, rebuilt.size))
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
        val bytes = Id3.v2Tag("x", 0) + bitrateIdx.map { frame(it, mode) }.reduce { a, b -> a + b }
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
    fun audioIsCopiedUntouched() {
        // Come redele.py: nemmeno il frame Info di ffmpeg ("stereo" con audio joint stereo) si tocca
        val audio = frame(7, mode = 0).also { "Info".toByteArray().copyInto(it, 36) } + frame(7) + frame(7)
        val source = File.createTempFile("info", ".mp3").apply { writeBytes(Id3.v2Tag("vecchio", 0) + audio); deleteOnExit() }
        val out = ByteArrayOutputStream()
        Id3.writeFaba(source, "0016", 1, out)
        val plain = out.toByteArray().also { FabaCipher.decrypt(it) }
        assertArrayEquals(Id3.v2Tag("K0016CP01", audio.size.toLong()) + audio, plain)
    }

    @Test
    fun sameFfmpegCommandForEveryInput() {
        val args = LocalConverter.ffmpegArgs(AudioOptions(), 200, trimSilence = true, title = "x")
        val af = args[args.indexOf("-af") + 1]
        assertTrue(af, af.startsWith("aformat=channel_layouts=mono,silenceremove="))
        assertTrue(af, af.endsWith(",loudnorm=I=-16:TP=-1.5:LRA=11,aresample=44100,aformat=sample_rates=44100:channel_layouts=stereo"))
        assertEquals("96k", args[args.indexOf("-b:a") + 1])
        assertEquals("2", args[args.indexOf("-ac") + 1])
        assertEquals("44100", args[args.indexOf("-ar") + 1])
        assertEquals("libmp3lame", args[args.indexOf("-c:a") + 1])
        val plain = LocalConverter.ffmpegArgs(AudioOptions(normalize = false, trimSilence = false, mono = false), 200, false, "x")
        assertEquals("aresample=44100,aformat=sample_rates=44100:channel_layouts=stereo", plain[plain.indexOf("-af") + 1])
        assertEquals("128k", plain[plain.indexOf("-b:a") + 1])
    }
}

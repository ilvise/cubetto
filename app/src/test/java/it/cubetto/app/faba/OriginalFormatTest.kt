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

/** Confronto con myfaba-hacks (createFigure.sh: id3v2 + MKICipher) e con i file originali FABA™. */
class OriginalFormatTest {

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /**
     * Uscita reale di `id3v2 -D` e `id3v2 -A "Album Album" -a "Artist Artist" -T "1/1"
     * -c "Converted for Faba Box" -t "K0016CP03"` (id3v2 0.1.12, id3lib 3.8.3) su un MP3 senza
     * tag con 36675 byte di audio: ID3v2.3 di 189 byte (117 di frame e il resto zeri) e ID3v1.1.
     */
    private val id3v2Frames = hex(
        "54414c420000000c000000416c62756d20416c62756d545045310000000e00000041727469737420417274697374" +
            "5452434b00000004000000312f31434f4d4d0000001b00000000000000436f6e76657274656420666f72204661" +
            "626120426f78544954320000000a0000004b303031364350303" + "3"
    )
    private val id3v1 = hex(
        "5441474b3030313643503033000000000000000000000000000000000000000000417274697374204172746973" +
            "740000000000000000000000000000000000416c62756d20416c62756d00000000000000000000000000000000" +
            "00000000000000436f6e76657274656420666f72204661626120426f780000000000000001ff"
    )

    @Test
    fun tagsAreIdenticalToMyfabaHacks() {
        val title = Id3.fabaTitle("0016", 3)
        val tag = Id3.v2Tag(title, 36675)
        assertEquals(189, tag.size)
        assertArrayEquals(hex("49443303000000000133"), tag.copyOfRange(0, 10))
        assertArrayEquals(id3v2Frames, tag.copyOfRange(10, 10 + id3v2Frames.size))
        assertTrue(tag.copyOfRange(10 + id3v2Frames.size, tag.size).all { it.toInt() == 0 })
        assertArrayEquals(id3v1, Id3.v1Tag(title))
        // Padding di id3lib: tag + audio arrotondati al multiplo di 2048 successivo
        assertEquals(910 + 10, Id3.v2Tag(title, 240744).size)
        assertEquals(1432 + 10, Id3.v2Tag(title, 2400862).size)
        // Se tag + audio è già multiplo di 2048, id3lib aggiunge altri 2048 byte
        assertEquals(2175, Id3.v2Tag(title, 2048L * 10 - 127).size)
    }

    /**
     * Prova completa sui file veri del box: ogni originale, decifrato e riscritto da Cubetto,
     * deve avere lo stesso audio byte per byte e i tag di myfaba-hacks. Gira solo se
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
            assertArrayEquals("${original.name}: audio", audio, rebuilt.copyOfRange(tag.size, rebuilt.size - 128))
            assertArrayEquals(Id3.v1Tag(title), rebuilt.copyOfRange(rebuilt.size - 128, rebuilt.size))
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

    /** Frame Info come lo scrive ffmpeg: modalità stereo, bit "original" spento, tag LAME con CRC. */
    private fun ffmpegInfoFrame(): ByteArray {
        val f = frame(7, mode = 0)
        f[3] = 0 // ffmpeg: stereo, niente bit original
        "Info".toByteArray().copyInto(f, 36)
        "Lavc61.3.".toByteArray().copyInto(f, 156)
        val crc = crc16(f, 190)
        f[190] = (crc ushr 8).toByte(); f[191] = crc.toByte()
        return f
    }

    private fun crc16(b: ByteArray, len: Int): Int {
        var crc = 0
        for (i in 0 until len) {
            crc = crc xor (b[i].toInt() and 0xFF)
            repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1 }
        }
        return crc
    }

    @Test
    fun infoFrameGetsTheAudioChannelModeLikeLame() {
        val audio = ffmpegInfoFrame() + frame(7) + frame(7)
        val source = File.createTempFile("info", ".mp3").apply { writeBytes(audio); deleteOnExit() }
        val out = ByteArrayOutputStream()
        Id3.writeFaba(source, "0016", 1, out)
        val plain = out.toByteArray().also { FabaCipher.decrypt(it) }
        val start = Id3.v2Tag("K0016CP01", audio.size.toLong()).size
        val info = plain.copyOfRange(start, start + 313)
        // Intestazione come negli originali FABA™ (joint stereo M/S, bit original), CRC del tag LAME valido
        assertArrayEquals(hex("fffb7064"), info.copyOfRange(0, 4))
        assertEquals(crc16(info, 190), ((info[190].toInt() and 0xFF) shl 8) or (info[191].toInt() and 0xFF))
        // Il resto del frame Info e l'audio sono identici
        assertArrayEquals(audio.copyOfRange(4, 190), info.copyOfRange(4, 190))
        assertArrayEquals(audio.copyOfRange(313, audio.size), plain.copyOfRange(start + 313, start + audio.size))
        assertEquals(plain.size.toLong(), Id3.encodedSize(source, "0016", 1))
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

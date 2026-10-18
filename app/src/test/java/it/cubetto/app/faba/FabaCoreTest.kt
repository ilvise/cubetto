package it.cubetto.app.faba

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class FabaCoreTest {

    // Tabelle copiate da MKICipher.java (myfaba-hacks): l'implementazione di riferimento
    private val high = arrayOf(
        intArrayOf(0x30, 0x30, 0x20, 0x20, 0x10, 0x10, 0x00, 0x00, 0x70, 0x70, 0x60, 0x60, 0x50, 0x50, 0x40, 0x40, 0xB0, 0xB0, 0xA0, 0xA0, 0x90, 0x90, 0x80, 0x80, 0xF0, 0xF0, 0xE0, 0xE0, 0xD0, 0xD0, 0xC0, 0xC0),
        intArrayOf(0x00, 0x00, 0x10, 0x10, 0x20, 0x20, 0x30, 0x30, 0x40, 0x40, 0x50, 0x50, 0x60, 0x60, 0x70, 0x70, 0x80, 0x80, 0x90, 0x90, 0xA0, 0xA0, 0xB0, 0xB0, 0xC0, 0xC0, 0xD0, 0xD0, 0xE0, 0xE0, 0xF0, 0xF0),
        intArrayOf(0x10, 0x10, 0x00, 0x00, 0x30, 0x30, 0x20, 0x20, 0x50, 0x50, 0x40, 0x40, 0x70, 0x70, 0x60, 0x60, 0x90, 0x90, 0x80, 0x80, 0xB0, 0xB0, 0xA0, 0xA0, 0xD0, 0xD0, 0xC0, 0xC0, 0xF0, 0xF0, 0xE0, 0xE0),
        intArrayOf(0x20, 0x20, 0x30, 0x30, 0x00, 0x00, 0x10, 0x10, 0x60, 0x60, 0x70, 0x70, 0x40, 0x40, 0x50, 0x50, 0xA0, 0xA0, 0xB0, 0xB0, 0x80, 0x80, 0x90, 0x90, 0xE0, 0xE0, 0xF0, 0xF0, 0xC0, 0xC0, 0xD0, 0xD0),
    )
    private val lowEven = arrayOf(
        intArrayOf(0x0, 0x1, 0x2, 0x3, 0x4, 0x5, 0x6, 0x7), intArrayOf(0x5, 0x4, 0x7, 0x6, 0x1, 0x0, 0x3, 0x2),
        intArrayOf(0x9, 0x8, 0xb, 0xa, 0xd, 0xc, 0xf, 0xe), intArrayOf(0x0, 0x1, 0x2, 0x3, 0x4, 0x5, 0x6, 0x7),
    )
    private val lowOdd = arrayOf(
        intArrayOf(0x8, 0x9, 0xA, 0xB, 0xC, 0xD, 0xE, 0xF), intArrayOf(0xD, 0xC, 0xF, 0xE, 0x9, 0x8, 0xB, 0xA),
        intArrayOf(0x1, 0x0, 0x3, 0x2, 0x5, 0x4, 0x7, 0x6), intArrayOf(0x8, 0x9, 0xA, 0xB, 0xC, 0xD, 0xE, 0xF),
    )

    private fun javaCipher(b: Int, pos: Int): Int {
        val p = pos % 4
        return high[p][b % 32] + if (b % 2 == 0) lowEven[p][b / 32] else lowOdd[p][b / 32]
    }

    @Test
    fun cipherMatchesReferenceImplementation() {
        val data = ByteArray(1024) { it.toByte() }
        val expected = ByteArray(data.size) { javaCipher(data[it].toInt() and 0xFF, it).toByte() }
        val actual = data.copyOf()
        FabaCipher.encrypt(actual)
        assertArrayEquals(expected, actual)
        FabaCipher.decrypt(actual)
        assertArrayEquals(data, actual)
    }

    @Test
    fun streamingCipherKeepsPositionAcrossWrites() {
        val data = ByteArray(1000) { (it * 7).toByte() }
        val out = ByteArrayOutputStream()
        FabaCipher.EncryptingOutputStream(out).use { s ->
            s.write(data, 0, 3); s.write(data, 3, 500); s.write(data, 503, 497)
        }
        val expected = data.copyOf().also { FabaCipher.encrypt(it) }
        assertArrayEquals(expected, out.toByteArray())
    }

    @Test
    fun writeFabaStripsOldTagsAndSetsTitle() {
        val oldTag = Id3.titleTag("Una canzone qualsiasi")
        val audio = ByteArray(5000) { (it % 251).toByte() }
        val v1 = ByteArray(128).also { "TAG".toByteArray().copyInto(it) }
        val src = File.createTempFile("src", ".mp3").apply { writeBytes(oldTag + audio + v1); deleteOnExit() }

        val out = ByteArrayOutputStream()
        Id3.writeFaba(src, "1032", 7, out)
        val plain = out.toByteArray().also { FabaCipher.decrypt(it) }
        val newTag = Id3.titleTag("K1032CP07")
        assertArrayEquals(newTag + audio, plain)
        assertEquals(plain.size.toLong(), Id3.encodedSize(src, "1032", 7))
    }

    @Test
    fun tagTextSitsAtTheFixedPositionReadByTheBox() {
        val bytes = FabaTag.pages("0742").reduce { a, b -> a + b }
        // pagina 7 byte 2 .. pagina 10 byte 3, con la pagina 4 all'offset 0
        val start = (7 - 4) * 4 + 2
        val text = String(bytes, start, 14, Charsets.US_ASCII)
        assertEquals("02190530074200", text)
        assertEquals("0742", FabaTag.parseFigureId(bytes))
        assertNull(FabaTag.parseFigureId(ByteArray(32)))
    }

    @Test
    fun registryParsesBundledTagsAndSuggestsOtherLanguages() {
        val figures = FigureRegistry.parse(File("src/main/assets/TAGS.md").readText())
        assertTrue(figures.size > 300)
        val it = IdAdvisor.options(figures, "it", mapOf("1016" to "Nanna"))
        val first = it.first()
        assertEquals(IdStatus.SUGGESTED, first.status)
        assertTrue(first.figures.none { f -> f.language == "Italian" })
        assertEquals(IdStatus.USED_BY_ME, it.first { o -> o.id == "1016" }.status)
        assertEquals(IdStatus.OWN_LANGUAGE, it.first { o -> o.id == "0010" }.status)
        // In francese le statuine italiane diventano consigliate
        val fr = IdAdvisor.options(figures, "fr", emptyMap())
        assertEquals(IdStatus.SUGGESTED, fr.first { o -> o.id == "0010" }.status)
        assertEquals(IdStatus.OWN_LANGUAGE, fr.first { o -> o.id == "0200" }.status)
        assertTrue(it.any { o -> o.status == IdStatus.FREE && o.id == "1032" })
    }
}

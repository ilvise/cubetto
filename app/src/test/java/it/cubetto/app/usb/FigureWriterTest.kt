package it.cubetto.app.usb

import it.cubetto.app.faba.FabaCipher
import it.cubetto.app.faba.Id3
import me.jahnen.libaums.core.fs.UsbFile
import me.jahnen.libaums.core.fs.UsbFileOutputStream
import me.jahnen.libaums.core.fs.UsbFileStreamFactory
import me.jahnen.libaums.core.fs.fat32.Fat32FileSystem
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Copia di una statuina su un'immagine FAT32 che imita il box: la cartella K1000 di fabbrica
 * contiene già un CP01.MKI segnaposto e altri file, che devono sparire. Poi si rilegge il disco
 * byte per byte come farebbe il firmware.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FigureWriterTest {

    private fun write(dir: UsbFile, name: String, bytes: ByteArray) {
        val f = dir.createFile(name)
        FatShortNames.fix(f)
        f.length = bytes.size.toLong()
        UsbFileOutputStream(f).use { it.write(bytes) }
    }

    private fun mp3(seed: Int, size: Int): File {
        val audio = ByteArray(size) { (it * seed % 251).toByte() }
        return File.createTempFile("brano$seed", ".mp3").apply { writeBytes(Id3.v2Tag("vecchio titolo", 0) + audio); deleteOnExit() }
    }

    /** Cluster occupati nella FAT (dal 2 in su). */
    private fun usedClusters(disk: FatTestDisk.RamDisk): Int {
        val b = ByteBuffer.wrap(disk.data).order(ByteOrder.LITTLE_ENDIAN)
        val fat = FatTestDisk.RESERVED * 512
        return (2 until 120_000).count { b.getInt(fat + it * 4) and 0x0FFFFFFF != 0 }
    }

    private fun factoryBox(): Pair<FatTestDisk.RamDisk, UsbFile> {
        val disk = FatTestDisk.format()
        val fs = Fat32FileSystem.read(disk)!!
        val mki = fs.rootDirectory.createDirectory("MKI01").also(FatShortNames::fix)
        val k = mki.createDirectory("K1000").also(FatShortNames::fix)
        write(k, "CP01.MKI", ByteArray(360 * 1024) { 7 }) // segnaposto di fabbrica
        write(k, "CP02.MKI", ByteArray(3000) { 8 })
        write(k, "INFO.TXT", ByteArray(100) { 9 })
        return disk to mki
    }

    @Test
    fun replacesEverythingInTheFactoryFolder() {
        val (disk, mki) = factoryBox()
        val sources = listOf(mp3(3, 20_000), mp3(5, 7_000), mp3(11, 12_345))
        var backedUp: List<String>? = null
        val writer = FigureWriter(mki, "1000")
        writer.prepare(oldId = null, ours = false) { f -> backedUp = f.listFiles().map { it.name }.sorted() }
        assertEquals(listOf("CP01.MKI", "CP02.MKI", "INFO.TXT"), backedUp)
        val plan = writer.plan(sources) { false }
        writer.write(plan) { _, copied -> assertTrue(copied) }

        // Rilettura da zero, solo nomi 8.3 come il firmware
        val again = Fat32FileSystem.read(disk)!!
        val k = again.rootDirectory.search("MKI01/K1000")!!
        assertEquals(listOf("CP01.MKI", "CP02.MKI", "CP03.MKI"), k.listFiles().map { it.name }.sorted())
        sources.forEachIndexed { i, src ->
            val f = k.search(FigureWriter.name(i))!!
            val bytes = UsbFileStreamFactory.createBufferedInputStream(f, again).use { it.readBytes() }
            val expected = java.io.ByteArrayOutputStream().also { Id3.writeFaba(src, "1000", i + 1, it) }.toByteArray()
            assertArrayEquals(expected, bytes)
            assertEquals(Id3.fabaTitle("1000", i + 1), String(bytes.copyOf().also { FabaCipher.decrypt(it) }, 21, 9, Charsets.UTF_8))
        }
        val scanner = FatScanner(disk)
        val folder = mutableListOf<FatScanner.Folder>()
        scanner.scan(scanner.mkiEntry("MKI01")!!, Regex("""K\d{4}"""), { false }, {}) { folder += it }
        assertFalse(folder.single().needsRepair)
        assertEquals(3, folder.single().files)

        // Nessun cluster perso: i vecchi file sono stati liberati
        val clusters = usedClusters(disk)
        val bytes = plan.sumOf { it.size }
        assertTrue("cluster usati $clusters", clusters <= 3 + bytes / 512 + 3 + 3)
    }

    @Test
    fun secondSyncKeepsUnchangedTracksAndRemovesTheRest() {
        val (disk, mki) = factoryBox()
        val a = mp3(3, 9_000); val b = mp3(5, 8_000); val c = mp3(7, 6_000)
        FigureWriter(mki, "1000").run {
            prepare(null, false) {}
            write(plan(listOf(a, b, c)) { false }) { _, _ -> }
        }
        // Qualcuno ha aggiunto un file a mano
        write(mki.search("K1000")!!, "CP09.MKI", ByteArray(10))

        // Seconda copia: tolto il terzo brano, il primo è uguale
        val copied = mutableListOf<Int>()
        FigureWriter(mki, "1000").run {
            prepare(null, ours = true) { error("niente backup per una cartella nostra") }
            write(plan(listOf(a, c)) { i -> i == 0 }) { i, did -> if (did) copied += i }
        }
        assertEquals(listOf(1), copied)
        val k = Fat32FileSystem.read(disk)!!.rootDirectory.search("MKI01/K1000")!!
        assertEquals(listOf("CP01.MKI", "CP02.MKI"), k.listFiles().map { it.name }.sorted())
        assertEquals(Id3.encodedSize(c, "1000", 2), k.search("CP02.MKI")!!.length)
    }

    @Test
    fun changingIdRemovesTheOldFolder() {
        val (disk, mki) = factoryBox()
        FigureWriter(mki, "1001").run {
            prepare(oldId = "1000", ours = false) {}
            write(plan(listOf(mp3(3, 5000))) { false }) { _, _ -> }
        }
        val again = Fat32FileSystem.read(disk)!!.rootDirectory.search("MKI01")!!
        assertEquals(listOf("K1001"), again.listFiles().map { it.name })
    }
}

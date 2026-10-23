package it.cubetto.app.usb

import me.jahnen.libaums.core.fs.UsbFile
import me.jahnen.libaums.core.fs.UsbFileOutputStream
import me.jahnen.libaums.core.fs.UsbFileStreamFactory
import me.jahnen.libaums.core.fs.fat32.Fat32FileSystem
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Scrive MKI01/K0201/CPnn.MKI con libaums su un'immagine FAT32 in memoria e poi legge
 * le directory byte per byte, come farebbe il firmware del FABA™ (solo nomi 8.3).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FatShortNamesTest {

    /** Voci grezze della directory nel cluster indicato: (nome 8.3, attributi, cluster). */
    private fun rawEntries(disk: FatTestDisk.RamDisk, cluster: Int): List<Triple<String, Int, Int>> {
        val b = ByteBuffer.wrap(disk.data).order(ByteOrder.LITTLE_ENDIAN)
        val fat = FatTestDisk.RESERVED * 512
        val out = mutableListOf<Triple<String, Int, Int>>()
        var c = cluster
        while (c in 2 until 0x0FFFFFF8) {
            val base = FatTestDisk.DATA_START + (c - 2) * 512
            for (off in base until base + 512 step 32) {
                val first = disk.data[off].toInt() and 0xFF
                if (first == 0) return out
                if (first == 0xE5) continue
                val attr = disk.data[off + 11].toInt() and 0xFF
                val name = String(disk.data, off, 11, Charsets.US_ASCII)
                val start = ((b.getShort(off + 20).toInt() and 0xFFFF) shl 16) or (b.getShort(off + 26).toInt() and 0xFFFF)
                out += Triple(name, attr, start)
            }
            c = b.getInt(fat + c * 4) and 0x0FFFFFFF
        }
        return out
    }

    private fun write(dir: UsbFile, name: String, bytes: ByteArray, fs: Fat32FileSystem) {
        val f = dir.createFile(name)
        FatShortNames.fix(f)
        f.length = bytes.size.toLong()
        UsbFileOutputStream(f).use { it.write(bytes) }
    }

    @Test
    fun faba83NamesWithoutLfn() {
        val disk = FatTestDisk.format()
        val fs = Fat32FileSystem.read(disk)!!
        val mki = fs.rootDirectory.createDirectory("MKI01").also(FatShortNames::fix)
        val k = mki.createDirectory("K0201").also(FatShortNames::fix)
        val song1 = ByteArray(5000) { (it % 251).toByte() }
        val song2 = ByteArray(1300) { (it % 7).toByte() }
        write(k, "CP01.MKI", song1, fs)
        write(k, "CP02.MKI", song2, fs)

        // Il firmware vede solo voci 8.3, senza LFN (attributo 0x0F)
        val root = rawEntries(disk, 2)
        assertTrue(root.none { it.second == 0x0F })
        val mkiRaw = root.single { it.first == "MKI01      " }
        assertEquals(0x10, mkiRaw.second and 0x10)
        val mkiEntries = rawEntries(disk, mkiRaw.third)
        assertTrue(mkiEntries.none { it.second == 0x0F })
        val kRaw = mkiEntries.single { it.first == "K0201      " }
        val files = rawEntries(disk, kRaw.third).filter { !it.first.startsWith(".") }
        assertEquals(listOf("CP01    MKI", "CP02    MKI"), files.map { it.first })
        assertTrue(files.none { it.second == 0x0F })
        // Come una copia da PC: file con il solo bit ARCHIVE, cartelle con il solo bit DIRECTORY
        assertEquals(listOf(0x20, 0x20), files.map { it.second })
        assertEquals(0x10, kRaw.second)

        // E libaums, riaprendo il disco, ritrova i file con i contenuti giusti
        val again = Fat32FileSystem.read(disk)!!
        val f1 = again.rootDirectory.search("MKI01/K0201/CP01.MKI")!!
        assertEquals("CP01.MKI", f1.name)
        assertArrayEquals(song1, UsbFileStreamFactory.createBufferedInputStream(f1, again).use { it.readBytes() })
        assertEquals(song2.size.toLong(), again.rootDirectory.search("MKI01/K0201/CP02.MKI")!!.length)
    }

    @Test
    fun repairsFilesWrittenBeforeTheFix() {
        val disk = FatTestDisk.format()
        val fs = Fat32FileSystem.read(disk)!!
        // Come facevano le versioni precedenti: nomi corti generati da libaums
        val k = fs.rootDirectory.createDirectory("MKI01").createDirectory("K0201")
        k.createFile("CP01.MKI")
        val before = rawEntries(disk, 2).map { it.first }
        assertTrue(before.none { it == "MKI01      " })

        // Riaprendo (nuova sessione) e correggendo, i nomi diventano quelli veri
        val again = Fat32FileSystem.read(disk)!!
        val mki = again.rootDirectory.search("MKI01")!!
        FatShortNames.fix(mki)
        val folder = mki.search("K0201")!!
        FatShortNames.fix(folder)
        FatShortNames.fix(folder.search("CP01.MKI")!!)
        FatShortNames.fix(folder.search("CP01.MKI")!!) // idempotente

        val mkiRaw = rawEntries(disk, 2).single { it.first == "MKI01      " }
        val kRaw = rawEntries(disk, mkiRaw.third).single { it.first == "K0201      " }
        val repaired = rawEntries(disk, kRaw.third).filter { !it.first.startsWith(".") }
        assertEquals(listOf("CP01    MKI"), repaired.map { it.first })
        assertEquals(0x20, repaired.single().second)
    }
}

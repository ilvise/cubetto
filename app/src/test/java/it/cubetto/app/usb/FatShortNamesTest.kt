package it.cubetto.app.usb

import me.jahnen.libaums.core.driver.BlockDeviceDriver
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

    private class RamDisk(size: Int) : BlockDeviceDriver {
        val data = ByteArray(size)
        override val blockSize = 512
        override val blocks = (size / 512).toLong()
        override fun init() {}
        override fun read(deviceOffset: Long, buffer: ByteBuffer) {
            val n = buffer.remaining()
            buffer.put(data, deviceOffset.toInt(), n)
        }
        override fun write(deviceOffset: Long, buffer: ByteBuffer) {
            val n = buffer.remaining()
            buffer.get(data, deviceOffset.toInt(), n)
        }
    }

    private val sectors = 131072 // 64 MB, 1 settore per cluster: abbastanza cluster per FAT32
    private val reserved = 32
    private val fatSectors = 1024

    /** Formattazione FAT32 minima: boot sector, FSInfo, due FAT, cluster radice vuoto. */
    private fun format(): RamDisk {
        val disk = RamDisk(sectors * 512)
        val b = ByteBuffer.wrap(disk.data).order(ByteOrder.LITTLE_ENDIAN)
        b.put(0, 0xEB.toByte()); b.put(1, 0x58); b.put(2, 0x90.toByte())
        "MSWIN4.1".toByteArray().copyInto(disk.data, 3)
        b.putShort(11, 512); b.put(13, 1); b.putShort(14, reserved.toShort()); b.put(16, 2)
        b.put(21, 0xF8.toByte()); b.putInt(32, sectors); b.putInt(36, fatSectors)
        b.putInt(44, 2); b.putShort(48, 1); b.putShort(50, 6)
        b.put(64, 0x80.toByte()); b.put(66, 0x29); b.putInt(67, 0x12345678)
        "FABA       ".toByteArray().copyInto(disk.data, 71)
        "FAT32   ".toByteArray().copyInto(disk.data, 82)
        b.put(510, 0x55); b.put(511, 0xAA.toByte())
        val fsInfo = 512
        b.putInt(fsInfo, 0x41615252); b.putInt(fsInfo + 484, 0x61417272)
        b.putInt(fsInfo + 488, -1); b.putInt(fsInfo + 492, 3); b.putInt(fsInfo + 508, 0xAA550000.toInt())
        for (f in 0 until 2) {
            val fat = (reserved + f * fatSectors) * 512
            b.putInt(fat, 0x0FFFFFF8); b.putInt(fat + 4, 0x0FFFFFFF); b.putInt(fat + 8, 0x0FFFFFFF)
        }
        return disk
    }

    private val dataStart get() = (reserved + 2 * fatSectors) * 512

    /** Voci grezze della directory nel cluster indicato: (nome 8.3, attributi, cluster). */
    private fun rawEntries(disk: RamDisk, cluster: Int): List<Triple<String, Int, Int>> {
        val b = ByteBuffer.wrap(disk.data).order(ByteOrder.LITTLE_ENDIAN)
        val fat = reserved * 512
        val out = mutableListOf<Triple<String, Int, Int>>()
        var c = cluster
        while (c in 2 until 0x0FFFFFF8) {
            val base = dataStart + (c - 2) * 512
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
        val disk = format()
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

        // E libaums, riaprendo il disco, ritrova i file con i contenuti giusti
        val again = Fat32FileSystem.read(disk)!!
        val f1 = again.rootDirectory.search("MKI01/K0201/CP01.MKI")!!
        assertEquals("CP01.MKI", f1.name)
        assertArrayEquals(song1, UsbFileStreamFactory.createBufferedInputStream(f1, again).use { it.readBytes() })
        assertEquals(song2.size.toLong(), again.rootDirectory.search("MKI01/K0201/CP02.MKI")!!.length)
    }

    @Test
    fun repairsFilesWrittenBeforeTheFix() {
        val disk = format()
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
        assertEquals(listOf("CP01    MKI"), rawEntries(disk, kRaw.third).filter { !it.first.startsWith(".") }.map { it.first })
    }
}

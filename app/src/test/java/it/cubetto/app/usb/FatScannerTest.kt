package it.cubetto.app.usb

import me.jahnen.libaums.core.fs.UsbFile
import me.jahnen.libaums.core.fs.UsbFileOutputStream
import me.jahnen.libaums.core.fs.fat32.Fat32FileSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** La lettura diretta del FAT32 deve dare gli stessi file e byte di libaums, con meno letture. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FatScannerTest {

    private fun write(dir: UsbFile, name: String, size: Int) {
        val f = dir.createFile(name)
        FatShortNames.fix(f)
        f.length = size.toLong()
        UsbFileOutputStream(f).use { it.write(ByteArray(size) { i -> (i % 13).toByte() }) }
    }

    private fun folder(mki: UsbFile, name: String) = mki.createDirectory(name).also(FatShortNames::fix)

    @Test
    fun readsEveryFigureFolder() {
        val disk = FatTestDisk.format()
        val fs = Fat32FileSystem.read(disk)!!
        val mki = fs.rootDirectory.createDirectory("MKI01").also(FatShortNames::fix)
        write(folder(mki, "K0201"), "CP01.MKI", 5000)
        folder(mki, "K0016").let { write(it, "CP01.MKI", 3000); write(it, "CP02.MKI", 1700) }
        folder(mki, "K0300") // vuota
        write(folder(mki, "TEMP"), "X.BIN", 10) // non è una statuina

        disk.reads = 0
        val scanner = FatScanner(disk)
        val entry = scanner.mkiEntry("MKI01")!!
        val found = mutableListOf<FatScanner.Folder>()
        var total = -1
        scanner.scan(entry, Regex("""K\d{4}"""), { false }, { total = it }) { found += it }

        assertEquals(3, total)
        assertEquals(
            listOf(FatScanner.Folder("K0016", 2, 4700, false), FatScanner.Folder("K0201", 1, 5000, false), FatScanner.Folder("K0300", 0, 0, false)),
            found.sortedBy { it.name },
        )
        // Boot sector, radice, FAT, MKI01 e poche letture per le cartelle (vicine sul disco)
        assertTrue("letture: ${disk.reads}", disk.reads <= 6)
    }

    @Test
    fun foldersWrittenByOldVersionsNeedRepair() {
        val disk = FatTestDisk.format()
        val fs = Fat32FileSystem.read(disk)!!
        val mki = fs.rootDirectory.createDirectory("MKI01").also(FatShortNames::fix)
        mki.createDirectory("K0201").createFile("CP01.MKI") // nomi corti generati, come prima della correzione
        mki.createDirectory("Copia di backup") // cartella creata dal PC con nome lungo: non è una statuina
        val scanner = FatScanner(disk)
        val found = mutableListOf<FatScanner.Folder>()
        scanner.scan(scanner.mkiEntry("MKI01")!!, Regex("""K\d{4}"""), { false }, {}) { found += it }
        // Solo la statuina va riparata: la cartella del PC non deve far rileggere tutto ogni volta
        assertTrue(found.single().needsRepair)

        // Un box senza MKI01 corretto non si legge così: si torna a libaums
        val other = FatTestDisk.format()
        Fat32FileSystem.read(other)!!.rootDirectory.createDirectory("MKI01")
        assertNull(FatScanner(other).mkiEntry("MKI01")?.takeIf { !it.lfn })
        assertFalse(found.isEmpty())
    }
}

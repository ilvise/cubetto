package it.cubetto.app.usb

import it.cubetto.app.faba.Id3
import me.jahnen.libaums.core.fs.UsbFile
import me.jahnen.libaums.core.fs.UsbFileOutputStream
import java.io.BufferedOutputStream
import java.io.File

/**
 * Scrittura di una statuina in MKI01/K{ID}: separata da [FabaUsb] (stato, database, backup)
 * per poterla provare su un'immagine FAT32 in memoria.
 */
internal class FigureWriter(private val mki: UsbFile, private val id: String) {

    /** Un brano da copiare: [size] è la dimensione finale del .MKI, [keep] se sul box c'è già uguale. */
    data class Item(val source: File, val name: String, val size: Long, val keep: Boolean)

    lateinit var folder: UsbFile
        private set

    /**
     * Prepara la cartella. Se non è nostra ([ours] false) si svuota del tutto: file originali,
     * segnaposto (spesso un CP01.MKI da ~360 KB) e qualunque altro file. [backup] riceve la
     * cartella prima, e decide lui se salvarla. La cartella del vecchio ID ([oldId]) si toglie.
     */
    fun prepare(oldId: String?, ours: Boolean, backup: (UsbFile) -> Unit) {
        FatShortNames.fix(mki)
        if (oldId != null && oldId != id) mki.search("K$oldId")?.delete()
        val existing = mki.search("K$id")
        if (existing != null && !ours) {
            backup(existing)
            existing.listFiles().forEach { it.delete() }
        }
        folder = existing ?: mki.createDirectory("K$id")
        // Anche sulle cartelle già scritte: ripara le copie fatte prima della correzione dei nomi 8.3
        FatShortNames.fix(folder)
    }

    /** Il piano di copia; [unchanged] dice se il brano [i] è lo stesso dell'ultima copia. */
    fun plan(sources: List<File>, unchanged: (Int) -> Boolean): List<Item> = sources.mapIndexed { i, src ->
        val name = name(i)
        val size = Id3.encodedSize(src, id, i + 1)
        Item(src, name, size, unchanged(i) && folder.search(name)?.length == size)
    }

    /** Byte in più che servono sul box per eseguire [plan]. */
    fun neededBytes(plan: List<Item>) = plan.filter { !it.keep }.sumOf { it.size - (folder.search(it.name)?.length ?: 0L) }

    /** Copia i brani cambiati e toglie tutto ciò che nella cartella non fa parte di [plan]. */
    fun write(plan: List<Item>, onTrack: (index: Int, copied: Boolean) -> Unit) {
        plan.forEachIndexed { i, item ->
            onTrack(i, !item.keep)
            if (item.keep) {
                folder.search(item.name)?.let(FatShortNames::fix)
                return@forEachIndexed
            }
            folder.search(item.name)?.delete()
            val file = folder.createFile(item.name)
            FatShortNames.fix(file)
            // Allocare subito tutti i cluster e scrivere a blocchi grandi è molto più veloce
            file.length = item.size
            BufferedOutputStream(UsbFileOutputStream(file), WRITE_BUFFER).use { out ->
                Id3.writeFaba(item.source, id, i + 1, out)
            }
        }
        // Tracce in più di una copia precedente e qualunque altro file rimasto
        val wanted = plan.map { it.name }.toSet()
        folder.listFiles().filter { !it.isDirectory && it.name.uppercase() !in wanted }.forEach { it.delete() }
    }

    companion object {
        private const val WRITE_BUFFER = 512 * 1024

        fun name(index: Int) = "CP%02d.MKI".format(index + 1)
    }
}

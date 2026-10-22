package it.cubetto.app.usb

import me.jahnen.libaums.core.driver.BlockDeviceDriver
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Lettura veloce delle cartelle MKI01/Kxxxx leggendo il FAT32 direttamente dalla partizione.
 * Il box ha centinaia di cartelle e ogni lettura USB è lenta: libaums per ogni cartella legge
 * prima la FAT e poi la directory, una richiesta alla volta. Qui di ogni cartella si legge
 * solo il primo cluster (ci stanno 128+ voci, i brani sono al massimo 99) e le cartelle
 * vicine sul disco si leggono con una sola richiesta.
 */
internal class FatScanner(private val disk: BlockDeviceDriver) {

    /** Una voce 8.3 di directory; [lfn]/[generated]: nome lungo o corto generato (scritto da vecchie versioni). */
    data class Entry(val name: String, val isDirectory: Boolean, val cluster: Long, val size: Long, val lfn: Boolean) {
        val generated get() = '~' in name
    }

    data class Folder(val name: String, val files: Int, val bytes: Long, val needsRepair: Boolean)

    private val clusterSize: Int
    private val fatOffset: Long
    private val dataOffset: Long
    private val rootCluster: Long
    private val fatCache = HashMap<Long, ByteBuffer>()

    init {
        val boot = read(0, 512)
        val bytesPerSector = boot.getShort(11).toInt() and 0xFFFF
        val sectorsPerCluster = boot.get(13).toInt() and 0xFF
        val reserved = boot.getShort(14).toInt() and 0xFFFF
        val fats = boot.get(16).toInt() and 0xFF
        val sectorsPerFat = boot.getInt(36).toLong() and 0xFFFFFFFFL
        if (bytesPerSector == 0 || sectorsPerCluster == 0 || sectorsPerFat == 0L) throw IOException("Non è un FAT32")
        clusterSize = bytesPerSector * sectorsPerCluster
        fatOffset = reserved.toLong() * bytesPerSector
        dataOffset = fatOffset + fats * sectorsPerFat * bytesPerSector
        rootCluster = boot.getInt(44).toLong() and 0x0FFFFFFF
    }

    private fun read(offset: Long, length: Int): ByteBuffer {
        val buf = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)
        disk.read(offset, buf)
        buf.clear()
        return buf
    }

    private fun clusterOffset(cluster: Long) = dataOffset + (cluster - 2) * clusterSize

    private fun next(cluster: Long): Long {
        val pos = fatOffset + cluster * 4
        val chunk = pos / FAT_CHUNK
        val buf = fatCache.getOrPut(chunk) { read(chunk * FAT_CHUNK, FAT_CHUNK) }
        return (buf.getInt((pos % FAT_CHUNK).toInt()).toLong()) and 0x0FFFFFFF
    }

    private fun isData(cluster: Long) = cluster in 2 until 0x0FFFFFF8

    /** Tutta la directory che parte da [cluster]. */
    private fun readDirectory(cluster: Long): List<Entry> {
        val out = mutableListOf<Entry>()
        var c = cluster
        var guard = 0
        while (isData(c) && guard++ < 4096) {
            if (parse(read(clusterOffset(c), clusterSize), 0, clusterSize, out)) return out
            c = next(c)
        }
        return out
    }

    /** Aggiunge a [out] le voci di [length] byte da [start]; true se ha trovato la fine della directory. */
    private fun parse(buf: ByteBuffer, start: Int, length: Int, out: MutableList<Entry>): Boolean {
        var lfn = false
        for (off in start until start + length step 32) {
            val first = buf.get(off).toInt() and 0xFF
            if (first == 0) return true
            val attr = buf.get(off + 11).toInt() and 0xFF
            if (first == 0xE5) { lfn = false; continue }
            if (attr and 0x3F == 0x0F) { lfn = true; continue }
            if (attr and 0x08 != 0) { lfn = false; continue } // etichetta del volume
            val raw = ByteArray(11).also { for (i in 0 until 11) it[i] = buf.get(off + i) }
            if (first == 0x05) raw[0] = 0xE5.toByte()
            val base = String(raw, 0, 8, Charsets.ISO_8859_1).trimEnd()
            val ext = String(raw, 8, 3, Charsets.ISO_8859_1).trimEnd()
            val name = if (ext.isEmpty()) base else "$base.$ext"
            val cluster = ((buf.getShort(off + 20).toLong() and 0xFFFF) shl 16) or (buf.getShort(off + 26).toLong() and 0xFFFF)
            if (name != "." && name != "..") {
                out += Entry(name, attr and 0x10 != 0, cluster, buf.getInt(off + 28).toLong() and 0xFFFFFFFFL, lfn)
            }
            lfn = false
        }
        return false
    }

    /** La voce di MKI01 nella radice, o null se il box non ce l'ha. */
    fun mkiEntry(name: String): Entry? = readDirectory(rootCluster).firstOrNull { it.isDirectory && it.name == name }

    /**
     * Le cartelle di [mki] il cui nome rispetta [filter], con numero di file e byte.
     * Quelle con [Folder.needsRepair] vanno rilette con libaums, che ne conosce il nome lungo.
     * [onTotal] riceve subito quante sono, [onFolder] ogni cartella appena letta.
     */
    fun scan(mki: Entry, filter: Regex, cancelled: () -> Boolean, onTotal: (Int) -> Unit, onFolder: (Folder) -> Unit) {
        // Anche le cartelle con nomi scritti da vecchie versioni: vanno segnalate per la riparazione
        val dirs = readDirectory(mki.cluster)
            .filter { it.isDirectory && isData(it.cluster) && (filter.matches(it.name) || it.lfn || it.generated) }
            .sortedBy { it.cluster }
        onTotal(dirs.size)
        var i = 0
        while (i < dirs.size) {
            if (cancelled()) return
            // Cartelle vicine sul disco: una sola lettura per tutte
            var j = i + 1
            while (j < dirs.size && dirs[j].cluster - dirs[j - 1].cluster <= MAX_GAP &&
                (dirs[j].cluster - dirs[i].cluster + 1) * clusterSize <= MAX_READ
            ) j++
            val span = ((dirs[j - 1].cluster - dirs[i].cluster + 1) * clusterSize).toInt()
            val buf = read(clusterOffset(dirs[i].cluster), span)
            for (k in i until j) {
                val dir = dirs[k]
                val entries = mutableListOf<Entry>()
                val start = ((dir.cluster - dirs[i].cluster) * clusterSize).toInt()
                // Directory più lunga di un cluster (raro): si segue la catena
                val all = if (parse(buf, start, clusterSize, entries)) entries else readDirectory(dir.cluster)
                val files = all.filter { !it.isDirectory }
                // Solo i brani CPnn: gli altri file del box (es. il segnaposto) non si toccano
                val repair = dir.lfn || dir.generated || files.any { it.name.startsWith("CP") && (it.lfn || it.generated) }
                onFolder(Folder(dir.name, files.size, files.sumOf { it.size }, repair))
            }
            i = j
        }
    }

    companion object {
        private const val FAT_CHUNK = 4096
        private const val MAX_GAP = 16L
        private const val MAX_READ = 64 * 1024
    }
}

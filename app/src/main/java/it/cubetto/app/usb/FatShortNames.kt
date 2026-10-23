package it.cubetto.app.usb

import me.jahnen.libaums.core.fs.UsbFile
import java.io.IOException

/**
 * libaums dà a ogni file e cartella un nome corto generato ("CP0000~0.MKI", "K00000~0")
 * e scrive il nome vero solo nelle voci LFN. Il firmware del FABA™ legge solo i nomi 8.3:
 * non trova MKI01/Kxxxx/CPnn.MKI e lampeggia veloce. Qui si imposta il nome corto vero
 * e si tolgono le voci LFN, come nei file originali del box.
 *
 * Inoltre libaums crea i file con gli attributi a zero, mentre ogni copia da PC imposta
 * il bit ARCHIVE (0x20): lo si imposta anche qui, così le voci sono come quelle di una
 * copia fatta con myfaba-hacks dal computer.
 *
 * libaums non lo permette dalle API pubbliche: si usa la reflection sulle sue classi
 * interne (testato con libaums 0.10.0, vedi FatShortNamesTest).
 */
internal object FatShortNames {
    private val VALID_83 = Regex("""[A-Z0-9_]{1,8}(\.[A-Z0-9_]{1,3})?""")

    private const val ATTR_OFFSET = 0x0B
    private const val ATTR_DIRECTORY = 0x10
    private const val ATTR_ARCHIVE = 0x20

    /**
     * Rende il nome corto di [file] uguale al suo nome (che deve essere 8.3 maiuscolo) e, se è
     * un file, imposta il bit ARCHIVE. No-op se è già tutto a posto.
     */
    fun fix(file: UsbFile) {
        val dir = file.parent ?: return // la root non ha voce
        val name = file.name
        if (!VALID_83.matches(name)) throw IOException("$name non è un nome 8.3")

        val entry = field(file, "entry").get(file) ?: return
        val actual = entry.javaClass.getMethod("getActualEntry").invoke(entry)
        val current = actual.javaClass.getMethod("getShortName").invoke(actual)
        val shortNameClass = current.javaClass
        val wanted = shortNameClass.getDeclaredConstructor(String::class.java, String::class.java)
            .apply { isAccessible = true }
            .newInstance(name.substringBefore('.'), name.substringAfter('.', ""))
        val lfnField = field(entry, "lfnName")
        val data = field(actual, "data").get(actual) as java.nio.ByteBuffer
        val attr = data.get(ATTR_OFFSET).toInt() and 0xFF
        val wantedAttr = if (attr and ATTR_DIRECTORY != 0) attr else attr or ATTR_ARCHIVE
        if (current == wanted && lfnField.get(entry) == null && attr == wantedAttr) return
        data.put(ATTR_OFFSET, wantedAttr.toByte())

        @Suppress("UNCHECKED_CAST")
        val shortNames = field(dir, "shortNameMap").get(dir) as MutableMap<Any, Any>
        val other = shortNames[wanted]
        if (other != null && other !== actual) throw IOException("Nome corto $name già usato in ${dir.name}")
        if (current != wanted) {
            shortNames.remove(current)
            shortNames[wanted] = actual
            actual.javaClass.getMethod("setShortName", shortNameClass).invoke(actual, wanted)
        }
        lfnField.set(entry, null)

        // FatDirectory.write() è "internal": in bytecode ha il nome con il suffisso del modulo
        val write = dir.javaClass.declaredMethods.first { (it.name == "write" || it.name.startsWith("write$")) && it.parameterCount == 0 }
        write.isAccessible = true
        write.invoke(dir)
    }

    private fun field(obj: Any, name: String) =
        generateSequence<Class<*>>(obj.javaClass) { it.superclass }
            .firstNotNullOf { c -> runCatching { c.getDeclaredField(name) }.getOrNull() }
            .apply { isAccessible = true }
}

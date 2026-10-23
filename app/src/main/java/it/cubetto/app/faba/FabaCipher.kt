package it.cubetto.app.faba

import java.io.OutputStream

/**
 * Cifratura "MKI" usata dal box FABA™ di prima generazione.
 * Ogni byte viene ruotato a sinistra di 3 bit e poi messo in XOR con una chiave
 * di 4 byte che dipende dalla posizione (pos % 4).
 * Port di redele.py / MKICipher.java da github.com/wansors/myfaba-hacks (GPL-3.0).
 *
 * Solo cifratura, di proposito: l'app non decifra mai i file del box, per non poter essere
 * usata per copiare l'audio delle statuine originali. La decifratura esiste solo nei test.
 */
object FabaCipher {
    private val KEY = intArrayOf(0x30, 0x05, 0x19, 0x20)

    private val ENCRYPT: Array<ByteArray> = Array(4) { k ->
        ByteArray(256) { b -> ((((b shl 3) or (b ushr 5)) and 0xFF) xor KEY[k]).toByte() }
    }

    fun encrypt(buf: ByteArray, len: Int = buf.size, startPos: Long = 0) = transform(buf, len, startPos, ENCRYPT)

    internal fun transform(buf: ByteArray, len: Int, startPos: Long, tables: Array<ByteArray>) {
        for (i in 0 until len) {
            val table = tables[((startPos + i) and 3).toInt()]
            buf[i] = table[buf[i].toInt() and 0xFF]
        }
    }

    /** OutputStream che cifra al volo: permette di scrivere file grandi senza tenerli in memoria. */
    class EncryptingOutputStream(private val out: OutputStream) : OutputStream() {
        private var pos = 0L
        private val single = ByteArray(1)

        override fun write(b: Int) {
            single[0] = b.toByte()
            write(single, 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            val chunk = b.copyOfRange(off, off + len)
            encrypt(chunk, len, pos)
            pos += len
            out.write(chunk)
        }

        override fun flush() = out.flush()
        override fun close() = out.close()
    }
}

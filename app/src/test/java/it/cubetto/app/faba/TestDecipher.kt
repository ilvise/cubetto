package it.cubetto.app.faba

/**
 * Decifratura MKI solo per i test (per verificare quello che scrive l'app): di proposito
 * non fa parte dell'app, che non decifra mai i file del box.
 */
object TestDecipher {
    private val KEY = intArrayOf(0x30, 0x05, 0x19, 0x20)

    private val DECRYPT: Array<ByteArray> = Array(4) { k ->
        ByteArray(256).also { inv ->
            for (b in 0 until 256) inv[(((b shl 3) or (b ushr 5)) and 0xFF) xor KEY[k]] = b.toByte()
        }
    }

    fun decrypt(buf: ByteArray, len: Int = buf.size, startPos: Long = 0) = FabaCipher.transform(buf, len, startPos, DECRYPT)
}

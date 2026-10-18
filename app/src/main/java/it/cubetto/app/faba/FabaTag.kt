package it.cubetto.app.faba

/**
 * Contenuto del tag NFC di una statuina FABA™ (prima generazione).
 *
 * Il box legge i byte grezzi a posizione fissa: il testo "02190530{ID}00" deve
 * trovarsi da pagina 7 byte 2 a pagina 10 byte 3 (contando da 0), cioè la
 * disposizione di un NTAG203 di fabbrica con il Lock Control TLV. Scrivendo le pagine
 * a mano otteniamo la stessa disposizione su NTAG213/215/216, senza il trucco
 * del prefisso "abcde" che serve con NFC Tools.
 */
object FabaTag {
    const val PREFIX = "02190530"
    const val FIRST_PAGE = 4

    fun payloadText(figureId: String): String {
        require(isValidId(figureId)) { "ID non valido: $figureId" }
        return "$PREFIX${figureId}00"
    }

    fun isValidId(id: String) = id.length == 4 && id.all { it.isDigit() }

    /** Pagine 4..11 (8 pagine da 4 byte) da scrivere sul tag. */
    fun pages(figureId: String): List<ByteArray> {
        val text = payloadText(figureId).toByteArray(Charsets.US_ASCII) // 14 byte
        val lang = "en".toByteArray(Charsets.US_ASCII)
        val payload = byteArrayOf(0x02) + lang + text // 17 byte
        val record = byteArrayOf(0xD1.toByte(), 0x01, payload.size.toByte(), 0x54) + payload // 21 byte
        val lockControlTlv = byteArrayOf(0x01, 0x03, 0xA0.toByte(), 0x10, 0x44)
        val ndefTlv = byteArrayOf(0x03, record.size.toByte()) + record
        val data = lockControlTlv + ndefTlv + byteArrayOf(0xFE.toByte())
        val padded = data.copyOf(32)
        return (0 until 8).map { padded.copyOfRange(it * 4, it * 4 + 4) }
    }

    /** Estrae l'ID statuina dai byte letti a partire da pagina 4, o null se non è un tag FABA™. */
    fun parseFigureId(bytesFromPage4: ByteArray): String? {
        val ascii = String(bytesFromPage4, Charsets.ISO_8859_1)
        val idx = ascii.indexOf(PREFIX)
        if (idx < 0 || idx + PREFIX.length + 4 > ascii.length) return null
        val id = ascii.substring(idx + PREFIX.length, idx + PREFIX.length + 4)
        return id.takeIf { isValidId(it) }
    }

    /** Capability Container (pagina 3) per un chip vergine, in base alla memoria. */
    fun capabilityContainer(chip: ChipType): ByteArray = when (chip) {
        ChipType.NTAG215 -> byteArrayOf(0xE1.toByte(), 0x10, 0x3E, 0x00)
        ChipType.NTAG216 -> byteArrayOf(0xE1.toByte(), 0x10, 0x6D, 0x00)
        else -> byteArrayOf(0xE1.toByte(), 0x10, 0x12, 0x00)
    }

    enum class ChipType(val label: String) {
        NTAG203("NTAG203"), NTAG213("NTAG213"), NTAG215("NTAG215"), NTAG216("NTAG216"), UNKNOWN("NTAG");

        companion object {
            /** Risposta al comando GET_VERSION (0x60), byte 6 = dimensione memoria. */
            fun fromVersion(version: ByteArray?): ChipType = when (version?.getOrNull(6)?.toInt()) {
                null -> NTAG203
                0x0F -> NTAG213
                0x11 -> NTAG215
                0x13 -> NTAG216
                else -> UNKNOWN
            }
        }
    }

    /** I cloni con UID che inizia per 0x1D o 0xFF non funzionano col FABA™ (FAQ_TAGS.md). */
    fun isSuspiciousUid(uid: ByteArray): Boolean = uid.isNotEmpty() && (uid[0].toInt() and 0xFF) != 0x04
}

package it.cubetto.app.nfc

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.MifareUltralight
import android.nfc.tech.NfcA
import android.util.Log
import it.cubetto.app.faba.FabaTag
import it.cubetto.app.faba.FabaTag.ChipType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface NfcMode {
    data object Idle : NfcMode
    data object Read : NfcMode
    data class Write(val figureId: String, val force: Boolean = false) : NfcMode
}

sealed interface NfcResult {
    data object Waiting : NfcResult
    data class Written(val figureId: String, val chip: ChipType, val verified: Boolean) : NfcResult
    data class ReadOk(val figureId: String?, val chip: ChipType, val uid: String, val suspicious: Boolean) : NfcResult
    /** Chip che probabilmente il FABA™ non legge: chiede conferma prima di scrivere. */
    data class SuspiciousChip(val uid: String, val chip: ChipType) : NfcResult
    data class Failed(val reason: Reason) : NfcResult

    enum class Reason { NOT_NTAG, LOCKED, LOST, VERIFY }
}

/**
 * Lettura e scrittura dei tag NFC con la reader mode di Android. La scrittura è
 * "grezza" pagina per pagina, così il testo sta esattamente dove il box lo cerca.
 */
class NfcController {
    private val _mode = MutableStateFlow<NfcMode>(NfcMode.Idle)
    val mode: StateFlow<NfcMode> = _mode.asStateFlow()
    private val _result = MutableStateFlow<NfcResult>(NfcResult.Waiting)
    val result: StateFlow<NfcResult> = _result.asStateFlow()

    private var activity: Activity? = null

    fun isAvailable(a: Activity) = NfcAdapter.getDefaultAdapter(a) != null
    fun isEnabled(a: Activity) = NfcAdapter.getDefaultAdapter(a)?.isEnabled == true

    fun attach(a: Activity) {
        activity = a
        applyReaderMode()
    }

    fun detach(a: Activity) {
        NfcAdapter.getDefaultAdapter(a)?.disableReaderMode(a)
        if (activity === a) activity = null
    }

    fun start(mode: NfcMode) {
        _result.value = NfcResult.Waiting
        _mode.value = mode
        applyReaderMode()
    }

    fun stop() {
        _mode.value = NfcMode.Idle
        applyReaderMode()
    }

    private fun applyReaderMode() {
        val a = activity ?: return
        val adapter = NfcAdapter.getDefaultAdapter(a) ?: return
        if (_mode.value == NfcMode.Idle) {
            adapter.disableReaderMode(a)
        } else {
            val flags = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
            adapter.enableReaderMode(a, { tag -> onTag(tag) }, flags, null)
        }
    }

    private fun onTag(tag: Tag) {
        val mode = _mode.value
        val uid = tag.id.joinToString("") { "%02X".format(it) }
        val ul = MifareUltralight.get(tag)
        if (ul == null) {
            _result.value = NfcResult.Failed(NfcResult.Reason.NOT_NTAG)
            return
        }
        val chip = chipType(tag)
        val suspicious = FabaTag.isSuspiciousUid(tag.id)
        try {
            ul.connect()
            when (mode) {
                NfcMode.Idle -> Unit
                NfcMode.Read -> {
                    val bytes = ul.readPages(4) + ul.readPages(8) + ul.readPages(12)
                    _result.value = NfcResult.ReadOk(FabaTag.parseFigureId(bytes), chip, uid, suspicious)
                }
                is NfcMode.Write -> {
                    if (suspicious && !mode.force) {
                        _result.value = NfcResult.SuspiciousChip(uid, chip)
                        return
                    }
                    val cc = ul.readPages(3).copyOfRange(0, 4)
                    if (cc.all { it.toInt() == 0 }) ul.writePage(3, FabaTag.capabilityContainer(chip))
                    val pages = FabaTag.pages(mode.figureId)
                    pages.forEachIndexed { i, p -> ul.writePage(FabaTag.FIRST_PAGE + i, p) }
                    val back = ul.readPages(4) + ul.readPages(8)
                    val expected = pages.reduce { acc, b -> acc + b }
                    val ok = back.copyOfRange(0, expected.size).contentEquals(expected)
                    _result.value = if (ok) NfcResult.Written(mode.figureId, chip, true) else NfcResult.Failed(NfcResult.Reason.VERIFY)
                    if (ok) _mode.value = NfcMode.Idle
                }
            }
        } catch (e: android.nfc.TagLostException) {
            _result.value = NfcResult.Failed(NfcResult.Reason.LOST)
        } catch (e: java.io.IOException) {
            Log.w(TAG, "nfc", e)
            _result.value = NfcResult.Failed(if (mode is NfcMode.Write) NfcResult.Reason.LOCKED else NfcResult.Reason.LOST)
        } finally {
            runCatching { ul.close() }
        }
    }

    private fun chipType(tag: Tag): ChipType {
        val nfcA = NfcA.get(tag) ?: return ChipType.UNKNOWN
        return try {
            nfcA.connect()
            ChipType.fromVersion(nfcA.transceive(byteArrayOf(0x60)))
        } catch (e: Exception) {
            ChipType.fromVersion(null) // niente GET_VERSION: NTAG203
        } finally {
            runCatching { nfcA.close() }
        }
    }

    companion object {
        private const val TAG = "Nfc"
    }
}

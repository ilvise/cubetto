package it.cubetto.app.usb

import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import me.jahnen.libaums.core.driver.ByteBlockDevice
import me.jahnen.libaums.core.driver.scsi.ScsiBlockDevice
import me.jahnen.libaums.core.usb.UsbCommunication
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** I byte che arrivano davvero al box quando si preme "Espelli". */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScsiEjectTest {

    /** Dispositivo USB finto: registra i comandi (CBW) e risponde sempre "eseguito" (CSW). */
    private class FakeUsb : UsbCommunication {
        val commands = mutableListOf<ByteArray>()
        private var lastTag = 0
        override val inEndpoint: UsbEndpoint get() = error("non usato")
        override val outEndpoint: UsbEndpoint get() = error("non usato")
        override val usbInterface: UsbInterface get() = error("non usato")
        override fun bulkOutTransfer(src: ByteBuffer): Int {
            val bytes = ByteArray(src.remaining()).also { src.get(it) }
            commands += bytes
            lastTag = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(4)
            return bytes.size
        }
        override fun bulkInTransfer(dest: ByteBuffer): Int {
            dest.order(ByteOrder.LITTLE_ENDIAN).putInt(0x53425355).putInt(lastTag).putInt(0).put(0)
            return 13
        }
        override fun controlTransfer(requestType: Int, request: Int, value: Int, index: Int, buffer: ByteArray, length: Int) = 0
        override fun resetDevice() {}
        override fun clearFeatureHalt(endpoint: UsbEndpoint) {}
        override fun close() {}
    }

    /** CDB del comando: dal byte 15 del CBW, lunghezza al byte 14. */
    private fun cdb(cbw: ByteArray) = cbw.copyOfRange(15, 15 + cbw[14]).joinToString(" ") { "%02X".format(it) }

    @Test
    fun ejectSendsSyncCacheThenStopUnitLikeAComputer() {
        val usb = FakeUsb()
        val partition = ByteBlockDevice(ScsiBlockDevice(usb, lun = 0), 2048)
        ScsiEject.eject(partition)
        assertEquals(2, usb.commands.size)
        usb.commands.forEach { assertEquals(31, it.size) } // CBW standard
        assertEquals("35 00 00 00 00 00 00 00 00 00", cdb(usb.commands[0])) // SYNCHRONIZE CACHE (10)
        assertEquals("1B 00 00 00 02 00", cdb(usb.commands[1])) // START STOP UNIT, LoEj=1 Start=0
        // Nessuna fase dati
        usb.commands.forEach { assertEquals(0, ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).getInt(8)) }
    }

    @Test
    fun syncAfterCopySendsOnlySyncCache() {
        val usb = FakeUsb()
        ScsiEject.syncCache(ByteBlockDevice(ScsiBlockDevice(usb, lun = 0)))
        assertEquals(listOf("35 00 00 00 00 00 00 00 00 00"), usb.commands.map(::cdb))
    }
}

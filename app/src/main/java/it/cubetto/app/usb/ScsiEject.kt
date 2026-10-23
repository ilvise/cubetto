package it.cubetto.app.usb

import me.jahnen.libaums.core.driver.BlockDeviceDriver
import me.jahnen.libaums.core.driver.scsi.commands.CommandBlockWrapper
import java.nio.ByteBuffer

/**
 * I comandi SCSI che un computer manda quando si espelle una chiavetta, e che libaums non ha:
 * SYNCHRONIZE CACHE (il dispositivo scrive davvero quello che tiene in cache) e START STOP UNIT
 * con espulsione (il dispositivo sa che l'host ha finito). Senza, chiudere la connessione
 * equivale a staccare il cavo senza "Rimozione sicura".
 *
 * Si usano i metodi privati di ScsiBlockDevice con la reflection (testato con libaums 0.10.0).
 */
internal object ScsiEject {

    private class Command(lun: Byte, private val cdb: ByteArray) :
        CommandBlockWrapper(0, Direction.NONE, lun, cdb.size.toByte()) {
        override fun serialize(buffer: ByteBuffer) {
            super.serialize(buffer)
            buffer.put(cdb)
        }
    }

    /** SYNCHRONIZE CACHE (10) su tutto il supporto. */
    private val SYNC_CACHE = byteArrayOf(0x35, 0, 0, 0, 0, 0, 0, 0, 0, 0)

    /** START STOP UNIT con LoEj = 1 e Start = 0: espulsione. */
    private val STOP_AND_EJECT = byteArrayOf(0x1B, 0, 0, 0, 0x02, 0)

    /** Fa scrivere al box tutto quello che ha in cache. */
    fun syncCache(partition: BlockDeviceDriver) = send(partition, SYNC_CACHE)

    /** Cache scritta e supporto espulso, come "Rimozione sicura" sul computer. */
    fun eject(partition: BlockDeviceDriver) {
        send(partition, SYNC_CACHE)
        send(partition, STOP_AND_EJECT)
    }

    private fun send(partition: BlockDeviceDriver, cdb: ByteArray) {
        val scsi = scsiDevice(partition)
        val lun = field(scsi, "lun").get(scsi) as Byte
        val transfer = generateSequence<Class<*>>(scsi.javaClass) { it.superclass }
            .firstNotNullOf { c -> c.declaredMethods.firstOrNull { it.name == "transferCommandWithoutDataPhase" } }
            .apply { isAccessible = true }
        transfer.invoke(scsi, Command(lun, cdb))
    }

    /** La partizione di libaums avvolge il dispositivo SCSI (ByteBlockDevice.targetBlockDevice). */
    private fun scsiDevice(partition: BlockDeviceDriver): Any {
        var device: Any = partition
        repeat(4) {
            val inner = runCatching { field(device, "targetBlockDevice").get(device) }.getOrNull() ?: return device
            device = inner
        }
        return device
    }

    private fun field(obj: Any, name: String) =
        generateSequence<Class<*>>(obj.javaClass) { it.superclass }
            .firstNotNullOf { c -> runCatching { c.getDeclaredField(name) }.getOrNull() }
            .apply { isAccessible = true }
}

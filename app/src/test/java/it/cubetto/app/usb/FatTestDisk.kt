package it.cubetto.app.usb

import me.jahnen.libaums.core.driver.BlockDeviceDriver
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Immagine FAT32 in memoria per i test (indirizzi in byte, come una partizione di libaums). */
object FatTestDisk {

    class RamDisk(size: Int) : BlockDeviceDriver {
        val data = ByteArray(size)
        var reads = 0
        override val blockSize = 512
        override val blocks = (size / 512).toLong()
        override fun init() {}
        override fun read(deviceOffset: Long, buffer: ByteBuffer) {
            reads++
            val n = buffer.remaining()
            buffer.put(data, deviceOffset.toInt(), n)
        }
        override fun write(deviceOffset: Long, buffer: ByteBuffer) {
            val n = buffer.remaining()
            buffer.get(data, deviceOffset.toInt(), n)
        }
    }

    private const val SECTORS = 131072 // 64 MB, 1 settore per cluster: abbastanza cluster per FAT32
    const val RESERVED = 32
    private const val FAT_SECTORS = 1024
    const val DATA_START = (RESERVED + 2 * FAT_SECTORS) * 512

    /** Formattazione FAT32 minima: boot sector, FSInfo, due FAT, cluster radice vuoto. */
    fun format(): RamDisk {
        val disk = RamDisk(SECTORS * 512)
        val b = ByteBuffer.wrap(disk.data).order(ByteOrder.LITTLE_ENDIAN)
        b.put(0, 0xEB.toByte()); b.put(1, 0x58); b.put(2, 0x90.toByte())
        "MSWIN4.1".toByteArray().copyInto(disk.data, 3)
        b.putShort(11, 512); b.put(13, 1); b.putShort(14, RESERVED.toShort()); b.put(16, 2)
        b.put(21, 0xF8.toByte()); b.putInt(32, SECTORS); b.putInt(36, FAT_SECTORS)
        b.putInt(44, 2); b.putShort(48, 1); b.putShort(50, 6)
        b.put(64, 0x80.toByte()); b.put(66, 0x29); b.putInt(67, 0x12345678)
        "FABA       ".toByteArray().copyInto(disk.data, 71)
        "FAT32   ".toByteArray().copyInto(disk.data, 82)
        b.put(510, 0x55); b.put(511, 0xAA.toByte())
        val fsInfo = 512
        b.putInt(fsInfo, 0x41615252); b.putInt(fsInfo + 484, 0x61417272)
        b.putInt(fsInfo + 488, -1); b.putInt(fsInfo + 492, 3); b.putInt(fsInfo + 508, 0xAA550000.toInt())
        for (f in 0 until 2) {
            val fat = (RESERVED + f * FAT_SECTORS) * 512
            b.putInt(fat, 0x0FFFFFF8); b.putInt(fat + 4, 0x0FFFFFFF); b.putInt(fat + 8, 0x0FFFFFFF)
        }
        return disk
    }
}

package com.noop.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Concrete-vector tests for the three frame checksums. The "123456789" check values are the
 * canonical reference constants for each algorithm:
 *  - CRC-8 (poly 0x07) check  = 0xF4
 *  - CRC-32 (zlib)     check  = 0xCBF43926
 *  - CRC16-Modbus      check  = 0x4B37
 */
class CrcTest {

    private val check = "123456789".toByteArray(Charsets.US_ASCII)

    @Test
    fun crc8_referenceCheckValue() {
        assertEquals(0xF4, Crc.crc8(check))
    }

    @Test
    fun crc8_emptyIsZero() {
        assertEquals(0x00, Crc.crc8(ByteArray(0)))
    }

    @Test
    fun crc8_resultIsByteWide() {
        // A length header of 0x0008 must yield the same crc8 the device verifies.
        assertEquals(0xA8, Crc.crc8(byteArrayOf(0x08, 0x00)))
    }

    @Test
    fun crc32_referenceCheckValue() {
        assertEquals(0xCBF43926L, Crc.crc32(check))
    }

    @Test
    fun crc32_emptyIsZero() {
        assertEquals(0x00000000L, Crc.crc32(ByteArray(0)))
    }

    @Test
    fun crc32_isUnsignedThirtyTwoBit() {
        // Result must always be in 0..0xFFFFFFFF (never negative / sign-extended).
        val v = Crc.crc32(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()))
        assert(v in 0L..0xFFFFFFFFL) { "crc32 out of range: $v" }
    }

    @Test
    fun crc16Modbus_referenceCheckValue() {
        assertEquals(0x4B37, Crc.crc16Modbus(check))
    }

    @Test
    fun crc16Modbus_whoop5HelloHeader() {
        // CRC16-Modbus over the first 6 bytes of the Whoop 5.0 hello must equal the LE value
        // stored at bytes [6..7] of that hello (0xE6, 0x71 -> 0x71E6).
        val hello = DeviceFamily.WHOOP5_CLIENT_HELLO
        val want = (hello[6].toInt() and 0xFF) or ((hello[7].toInt() and 0xFF) shl 8)
        assertEquals(want, Crc.crc16Modbus(hello.copyOfRange(0, 6)))
        assertEquals(0x71E6, want)
    }
}

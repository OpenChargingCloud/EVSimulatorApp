package cloud.charging.v2g.tp

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * The V2GTP header, checked against the same bytes as the C# `V2GTPFrameTests` — the two are ports
 * of one wire format, so they are asked the same questions.
 */
class V2GTPTest {

    @Test
    fun `the header round-trips through the exact wire bytes`() {
        val buf = ByteArray(V2GTP.HEADER_SIZE)
        V2GTP.writeHeader(buf, V2GTP.PAYLOAD_TYPE_APP_PROTOCOL, 42u)   // 0x8001 (SAP / -2 EXI payload id)

        // Pinned literally, not recomputed: a byte order agreed on by writer and reader alone
        // round-trips no matter which way round it is.
        assertContentEquals(
            byteArrayOf(0x01, 0xFE.toByte(), 0x80.toByte(), 0x01, 0x00, 0x00, 0x00, 0x2A),
            buf)

        val header = V2GTP.tryReadHeader(buf)!!
        assertEquals(V2GTP.PAYLOAD_TYPE_APP_PROTOCOL, header.payloadType)
        assertEquals(42u, header.payloadLength)
    }

    @Test
    fun `both multi-byte fields are big-endian at their widest`() {
        val buf = ByteArray(V2GTP.HEADER_SIZE)
        V2GTP.writeHeader(buf, 0xABCDu.toUShort(), 0xFFFFFFFFu)

        assertContentEquals(
            byteArrayOf(0x01, 0xFE.toByte(), 0xAB.toByte(), 0xCD.toByte(),
                        0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()),
            buf)

        val header = V2GTP.tryReadHeader(buf)!!
        assertEquals(0xABCDu.toUShort(), header.payloadType)
        assertEquals(0xFFFFFFFFu, header.payloadLength,
            "the length field must stay unsigned — 4 GiB - 1, not -1")
    }

    @Test
    fun `a header can be written and read at an offset`() {
        val buf = ByteArray(V2GTP.HEADER_SIZE + 3)
        assertEquals(V2GTP.HEADER_SIZE, V2GTP.writeHeader(buf, V2GTP.PAYLOAD_TYPE_ISO20_DC, 7u, offset = 3))

        assertNull(V2GTP.tryReadHeader(buf), "byte 0 is still the untouched padding")
        val header = V2GTP.tryReadHeader(buf, offset = 3)!!
        assertEquals(V2GTP.PAYLOAD_TYPE_ISO20_DC, header.payloadType)
        assertEquals(7u, header.payloadLength)
    }

    @Test
    fun `a wrong version byte is not a V2GTP frame`() {
        val buf = ByteArray(8)
        buf[0] = 0x02; buf[1] = 0xFE.toByte()
        assertNull(V2GTP.tryReadHeader(buf))

        // …and so is the inverse byte on its own.
        val buf2 = ByteArray(8)
        buf2[0] = 0x01; buf2[1] = 0x01
        assertNull(V2GTP.tryReadHeader(buf2))
    }

    @Test
    fun `a buffer one byte short of a header is refused`() {
        assertNull(V2GTP.tryReadHeader(ByteArray(V2GTP.HEADER_SIZE - 1)))
        assertNull(V2GTP.tryReadHeader(ByteArray(V2GTP.HEADER_SIZE), offset = 1))
    }

    @Test
    fun `writing into too small a destination throws rather than truncating`() {
        assertFailsWith<IllegalArgumentException> {
            V2GTP.writeHeader(ByteArray(V2GTP.HEADER_SIZE - 1), V2GTP.PAYLOAD_TYPE_ISO20_AC, 0u)
        }
        assertFailsWith<IllegalArgumentException> {
            V2GTP.writeHeader(ByteArray(V2GTP.HEADER_SIZE), V2GTP.PAYLOAD_TYPE_ISO20_AC, 0u, offset = 1)
        }
    }

    /**
     * The payload ids are wire constants, so they are pinned to their numbers here rather than
     * only to each other. The 0x8001 collision is deliberate — see [V2GTP].
     */
    @Test
    fun `the payload type constants are the wire values`() {
        assertEquals(0x8001u.toUShort(), V2GTP.PAYLOAD_TYPE_APP_PROTOCOL)
        assertEquals(V2GTP.PAYLOAD_TYPE_APP_PROTOCOL, V2GTP.PAYLOAD_TYPE_DIN_ISO2_MAIN,
            "SAP shares the -2 payload id and is told apart by session phase, not by type")
        assertEquals(0x8002u.toUShort(), V2GTP.PAYLOAD_TYPE_ISO20_MAIN)
        assertEquals(0x8003u.toUShort(), V2GTP.PAYLOAD_TYPE_ISO20_AC)
        assertEquals(0x8004u.toUShort(), V2GTP.PAYLOAD_TYPE_ISO20_DC)
        assertEquals(0x8005u.toUShort(), V2GTP.PAYLOAD_TYPE_ISO20_ACDP)
        assertEquals(0x8006u.toUShort(), V2GTP.PAYLOAD_TYPE_ISO20_WPT)
    }
}

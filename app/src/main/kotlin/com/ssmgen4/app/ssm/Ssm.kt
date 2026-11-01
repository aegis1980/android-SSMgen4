package com.ssmgen4.app.ssm

/**
 * Subaru Select Monitor protocol constants and payload builders.
 *
 * Over the K-line every message is a framed packet, `[0x80][dst][src][len][payload][sum]`.
 * Over CAN (which is what ECU 4B04785007 speaks) only the payload travels: the adapter
 * puts it in an ISO-TP transfer addressed to CAN id 0x7E0, and the ECU answers on 0x7E8
 * with `[command | 0x40][data...]`. [frame] is kept for the K-line case and as a readable
 * description of the format.
 */
object Ssm {
    const val HEADER = 0x80

    const val DEVICE_ECU = 0x10
    const val DEVICE_TCU = 0x18
    const val DEVICE_TOOL = 0xF0

    /** K-line init. The reply carries the SSM id, ECU id and the capability bitmap. */
    const val CMD_INIT = 0xBF
    /** Same handshake when the request travels over CAN. */
    const val CMD_CAN_INIT = 0xAA
    /** Read a list of individual byte addresses in one go. */
    const val CMD_READ_ADDRESSES = 0xA8
    /** Read a contiguous block (RAM only, addresses >= 0x10000). */
    const val CMD_READ_BLOCK = 0xA0
    /** The ECU declined; `data[1]` holds the reason. */
    const val CMD_NEGATIVE = 0x7F
    /** A reply echoes the request command with this bit set: A8 -> E8, AA -> EA. */
    const val RESPONSE_BIT = 0x40

    /** Negative-response code when one of the requested addresses is off limits. */
    const val REFUSED_ADDRESS = 0x12

    /** The most addresses sent in one read; the ECU side is not asked for more. */
    const val MAX_ADDRESSES_PER_REQUEST = 32

    /** `[A8][continuous][addr0 hi mid lo][addr1 ...]` */
    fun readAddressesPayload(addresses: IntArray, continuous: Boolean = false): ByteArray {
        require(addresses.isNotEmpty()) { "no addresses requested" }
        require(addresses.size <= 64) { "too many addresses in one request: ${addresses.size}" }
        val out = ByteArray(2 + addresses.size * 3)
        out[0] = CMD_READ_ADDRESSES.toByte()
        out[1] = if (continuous) 1 else 0
        var i = 2
        for (address in addresses) {
            out[i++] = (address shr 16).toByte()
            out[i++] = (address shr 8).toByte()
            out[i++] = address.toByte()
        }
        return out
    }

    /** Full K-line frame around [payload]. Not used over CAN. */
    fun frame(destination: Int, source: Int, payload: ByteArray): ByteArray {
        require(payload.isNotEmpty()) { "SSM payload must not be empty" }
        require(payload.size <= 255) { "SSM payload too long: ${payload.size}" }
        val packet = ByteArray(payload.size + 5)
        packet[0] = HEADER.toByte()
        packet[1] = destination.toByte()
        packet[2] = source.toByte()
        packet[3] = payload.size.toByte()
        payload.copyInto(packet, 4)
        packet[packet.lastIndex] = checksum(packet, 0, packet.size - 1)
        return packet
    }

    fun checksum(data: ByteArray, from: Int, count: Int): Byte {
        var sum = 0
        for (i in from until from + count) sum += data[i].toInt() and 0xFF
        return (sum and 0xFF).toByte()
    }

    fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (b in bytes) append(HEX_DIGITS[(b.toInt() shr 4) and 0xF]).append(HEX_DIGITS[b.toInt() and 0xF])
    }

    private const val HEX_DIGITS = "0123456789ABCDEF"
}

/** One SSM message after the transport has stripped its own wrapping. */
class SsmMessage(val destination: Int, val source: Int, val command: Int, val data: ByteArray) {

    /** Our own request reflected back by the adapter or the bus. */
    val isEcho: Boolean get() = source == Ssm.DEVICE_TOOL

    val isNegative: Boolean get() = command == Ssm.CMD_NEGATIVE

    /** For a negative reply `data[0]` is the refused command and `data[1]` the reason. */
    val negativeCode: Int
        get() = if (isNegative && data.size >= 2) data[1].toInt() and 0xFF else -1

    override fun toString(): String =
        "SsmMessage(cmd=%02X, from=%02X, data=%s)".format(command, source, Ssm.hex(data))
}

/**
 * Reply to [Ssm.CMD_INIT] / [Ssm.CMD_CAN_INIT]: three bytes of SSM id, five of ECU id, then
 * a bitmap of which standard parameters this ECU can read. Capability offsets in the
 * parameter table index into the *whole* reply, header included, so byte 8 is the first
 * bitmap byte.
 */
class EcuInitResponse(val raw: ByteArray) {

    val ssmId: String get() = if (raw.size >= 3) Ssm.hex(raw.copyOfRange(0, 3)) else ""

    val ecuId: String get() = if (raw.size >= 8) Ssm.hex(raw.copyOfRange(3, 8)) else ""

    fun supports(byteIndex: Int, bit: Int): Boolean =
        byteIndex in raw.indices && bit in 0..7 && (raw[byteIndex].toInt() shr bit) and 1 == 1

    override fun toString(): String = "EcuInitResponse(ecuId=$ecuId, bytes=${raw.size})"
}

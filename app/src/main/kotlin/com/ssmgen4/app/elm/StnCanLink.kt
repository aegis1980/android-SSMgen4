package com.ssmgen4.app.elm

import android.util.Log
import com.ssmgen4.app.ssm.EcuInitResponse
import com.ssmgen4.app.ssm.IsoTp
import com.ssmgen4.app.ssm.Ssm
import com.ssmgen4.app.ssm.SsmMessage

/**
 * SSM over CAN through an ELM327 or STN adapter.
 *
 * The STN path is the one that matters for the OBDLink LX: with `STCSEGT/STCSEGR 1` the
 * adapter does the whole multi-frame ISO-TP exchange itself, so a 32-address request is a
 * single `STPX` line and a single reply. A plain ELM327 gets one address per request and
 * the `hex + frame count` form instead. If the adapter answers `?` the request form steps
 * down (STPX -> hex+count -> bare hex) and is retried.
 *
 * Init sequence and timeouts are the ones observed working against an STN1155 v5.6.19.
 */
class StnCanLink(
    private val port: ElmPort,
    private val log: (String) -> Unit = { Log.d(TAG, it) },
) {
    private enum class Form { STPX, HEX_COUNT, HEX }

    var chipName: String = "adapter"
        private set
    var isStn: Boolean = false
        private set

    /** Addresses the ECU has declined on this link. Callers should stop asking for them. */
    val refusedAddresses: Set<Int> get() = refusedBy(Ssm.DEVICE_ECU)

    /** Addresses [device] has declined on this link. ECU and TCU keep separate lists. */
    fun refusedBy(device: Int): Set<Int> = refused[device].orEmpty()
    private val refused = HashMap<Int, LinkedHashSet<Int>>()

    /** Negative-response code of the most recent request, or -1 if it was not refused. */
    val lastNegativeCode: Int get() = lastRefusal

    private var form = Form.HEX_COUNT
    private var perRequest = 1
    private var addressed = -1
    private var replyHeader = "7E8"
    private var lastHex = ""
    private var lastDevice = Ssm.DEVICE_ECU
    private var lastRefusal = -1
    private var refusedWhole = false
    private var owed = false
    private val pending = StringBuilder()

    // ---- setup -------------------------------------------------------------------------

    @Throws(LinkException::class)
    fun open() {
        var banner = command("ATZ", RESET_TIMEOUT_MS)
        if (banner.isEmpty()) banner = command("ATZ", RESET_TIMEOUT_MS)
        for (setup in listOf("ATE0", "ATL0", "ATS0", "ATH1", "ATAT1", "ATSP6", "ATCAF1")) command(setup)

        val sti = command("STI")
        isStn = sti.any { it.startsWith("STN", ignoreCase = true) }
        if (isStn) {
            for (setup in listOf("STCSEGT 1", "STCSEGR 1", "STCFCPA 7E0,7E8", "STCFCPA 7E1,7E9")) command(setup)
        }
        perRequest = if (isStn) Ssm.MAX_ADDRESSES_PER_REQUEST else 1
        form = if (isStn) Form.STPX else Form.HEX_COUNT
        chipName = (if (isStn) sti else command("ATI")).firstOrNull() ?: banner.lastOrNull() ?: "adapter"
        log("adapter ready: $chipName, " + if (isStn) "sends long requests itself" else "one address per request")
    }

    /** SSM init of [device] (ECU on 7E0, TCU on 7E1). Null if it did not answer. */
    @Throws(LinkException::class)
    fun initialise(timeoutMs: Int = INIT_TIMEOUT_MS, device: Int = Ssm.DEVICE_ECU): EcuInitResponse? {
        val reply = exchange(device, byteArrayOf(Ssm.CMD_CAN_INIT.toByte()), Ssm.CMD_CAN_INIT or Ssm.RESPONSE_BIT, timeoutMs)
        return reply?.let { EcuInitResponse(it.data) }
    }

    fun close() {
        runCatching { port.close() }
    }

    // ---- reading -----------------------------------------------------------------------

    /** How long to give the ECU for [addressCount] addresses, scaled by ISO-TP frame count. */
    fun readTimeoutMs(addressCount: Int): Int =
        (IsoTp_frames(addressCount * 3 + 2) * PER_FRAME_MS + CAN_TIMEOUT_MS).coerceAtMost(MAX_TIMEOUT_MS)

    /**
     * One byte per address, in order, or null if the ECU did not answer. When a multi-address
     * read is refused outright, each address is probed on its own so the culprits end up in
     * [refusedAddresses] and the caller can leave them out next time.
     */
    @Throws(LinkException::class)
    fun read(addresses: IntArray, device: Int = Ssm.DEVICE_ECU, timeoutMs: Int = readTimeoutMs(addresses.size)): ByteArray? {
        val out = ByteArray(addresses.size)
        var at = 0
        while (at < addresses.size) {
            val size = minOf(perRequest, addresses.size - at)
            val chunk = addresses.copyOfRange(at, at + size)
            val reply = readChunk(chunk, device, if (size == addresses.size) timeoutMs else readTimeoutMs(size))
            if (reply == null) {
                // The adapter itself choked on a long request: halve and keep going.
                if (refusedWhole && size > 1) {
                    perRequest = maxOf(1, size / 2)
                    log("adapter refused $size addresses in one request, trying $perRequest")
                    continue
                }
                return null
            }
            reply.copyInto(out, at, 0, size)
            at += size
        }
        return out
    }

    private fun readChunk(addresses: IntArray, device: Int, timeoutMs: Int): ByteArray? {
        refusedWhole = false
        val payload = Ssm.readAddressesPayload(addresses)
        val reply = exchange(device, payload, Ssm.CMD_READ_ADDRESSES or Ssm.RESPONSE_BIT, timeoutMs)
        if (reply != null && reply.data.size >= addresses.size) {
            return if (reply.data.size == addresses.size) reply.data else reply.data.copyOf(addresses.size)
        }
        if (lastRefusal == Ssm.REFUSED_ADDRESS && addresses.size > 1) findRefused(addresses, device)
        return null
    }

    private fun findRefused(addresses: IntArray, device: Int) {
        val declined = refused.getOrPut(device) { LinkedHashSet() }
        val before = declined.size
        val who = if (device == Ssm.DEVICE_TCU) "TCU" else "ECU"
        for (address in addresses.distinct()) {
            if (address in declined) continue
            val reply = exchange(device, Ssm.readAddressesPayload(intArrayOf(address)), Ssm.CMD_READ_ADDRESSES or Ssm.RESPONSE_BIT, readTimeoutMs(1))
            if (reply == null && lastRefusal == Ssm.REFUSED_ADDRESS) {
                declined += address
                log("%s refuses address %06X on this link".format(who, address))
            }
        }
        if (declined.size == before) log("every address answered on its own; the read was refused as a whole")
        // Report the refusal that triggered the probe, not the outcome of the last probe.
        lastRefusal = Ssm.REFUSED_ADDRESS
    }

    // ---- one request / one reply -------------------------------------------------------

    private fun exchange(device: Int, payload: ByteArray, expectedCommand: Int, timeoutMs: Int): SsmMessage? {
        lastRefusal = -1
        transmit(device, payload)
        return awaitReply(expectedCommand, timeoutMs, payload[0])
    }

    private fun awaitReply(expectedCommand: Int, timeoutMs: Int, command: Byte): SsmMessage? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) {
                log("no reply to command %02X within %d ms".format(command, timeoutMs))
                return null
            }
            for (message in receive(remaining.toInt().coerceAtMost(timeoutMs))) {
                if (message.isEcho) continue
                if (message.command == expectedCommand) return message
                if (message.isNegative) {
                    lastRefusal = message.negativeCode
                    log("ECU refused command %02X, code %02X".format(command, lastRefusal))
                    return null
                }
            }
        }
    }

    private fun transmit(device: Int, payload: ByteArray) {
        address(device)
        lastHex = Ssm.hex(payload)
        lastDevice = device
        send(requestLine())
    }

    private fun requestLine(): String = when (form) {
        Form.STPX -> "STPX H:%03X, D:%s, R:1".format(requestId(lastDevice), lastHex)
        Form.HEX_COUNT -> lastHex + (replyFrames()?.let { "%X".format(it) } ?: "")
        Form.HEX -> lastHex
    }

    /** For the plain-ELM form: tell the adapter how many reply frames to wait for. */
    private fun replyFrames(): Int? {
        if (!lastHex.startsWith("A8") || lastHex.length < 10) return null
        val addresses = (lastHex.length / 2 - 2) / 3
        return IsoTp_frames(addresses + 1).takeIf { it in 1..15 }
    }

    private fun receive(timeoutMs: Int): List<SsmMessage> {
        val lines = awaitPrompt(timeoutMs.toLong()) ?: return emptyList()

        if (lines.any { it == "?" }) {
            val singleFrame = lastHex.length <= 14
            if (form == Form.STPX || (form == Form.HEX_COUNT && singleFrame)) {
                form = if (form == Form.STPX) Form.HEX_COUNT else Form.HEX
                log("adapter refused that request form, now $form")
                send(requestLine())
                return receive(timeoutMs)
            }
            refusedWhole = true
        }

        val payload = IsoTp.assemble(lines.map(::spaced), replyHeader)
        val message = payload?.let { decode(responseId(lastDevice), it) }
        if (message != null) return listOf(message)

        val shown = lines.joinToString(" | ").ifEmpty { "(nothing)" }
        log("no SSM reply: ${shown.take(120)}")
        return listOf(NO_ANSWER)
    }

    /** `[command][data...]` off the CAN bus into a message; only SSM reply commands count. */
    private fun decode(canId: Int, payload: ByteArray): SsmMessage? {
        if (payload.isEmpty()) return null
        val command = payload[0].toInt() and 0xFF
        val known = command == Ssm.CMD_NEGATIVE || command == 0xEA || command == 0xE0 || command == 0xE8 ||
            command == 0xF0 || command == 0xF8 || command == 0xFF
        if (!known) return null
        val source = if (canId == CAN_ID_TCU_RESPONSE) Ssm.DEVICE_TCU else Ssm.DEVICE_ECU
        return SsmMessage(Ssm.DEVICE_TOOL, source, command, payload.copyOfRange(1, payload.size))
    }

    // ---- adapter plumbing --------------------------------------------------------------

    private fun address(device: Int) {
        if (device == addressed) return
        val request = requestId(device)
        val reply = responseId(device)
        command("ATSH%03X".format(request))
        command("ATCRA%03X".format(reply))
        replyHeader = "%03X".format(reply)
        addressed = device
    }

    private fun command(text: String, timeoutMs: Long = COMMAND_TIMEOUT_MS): List<String> {
        send(text)
        val lines = awaitPrompt(timeoutMs) ?: emptyList()
        log("  > $text  " + lines.joinToString(" | ").ifEmpty { "(nothing)" })
        return lines
    }

    private fun send(text: String) {
        if (owed) resync()
        drain()
        pending.setLength(0)
        port.write((text + "\r").toByteArray(Charsets.US_ASCII))
        owed = true
    }

    /** A previous command never produced a prompt; get the adapter back in step. */
    private fun resync() {
        owed = false
        if (awaitPrompt(OWED_WAIT_MS) != null) return
        log("adapter out of step, resetting the prompt")
        port.write("ATE0\r".toByteArray(Charsets.US_ASCII))
        awaitPrompt(OWED_WAIT_MS)
        awaitPrompt(SETTLE_MS)
        pending.setLength(0)
    }

    private fun drain() {
        while (true) {
            val stale = port.read(0) ?: throw LinkException("Bluetooth adapter disconnected")
            if (stale.isEmpty()) return
        }
    }

    /** Everything up to the next `>` prompt, split into trimmed non-empty lines. */
    private fun awaitPrompt(timeoutMs: Long): List<String>? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!pending.contains('>')) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) return null
            val got = port.read(left) ?: throw LinkException("Bluetooth adapter disconnected")
            pending.append(String(got, Charsets.US_ASCII))
        }
        val end = pending.indexOf(">")
        val reply = pending.substring(0, end)
        pending.delete(0, end + 1)
        owed = false
        return reply.split('\r', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "SEARCHING..." }
    }

    /** `ATS0` prints `7E8E8xxxx`; put the spaces back so the frame parser sees tokens. */
    private fun spaced(line: String): String {
        if (' ' in line || !NO_SPACES.matches(line)) return line
        return line.substring(0, 3) + " " + line.substring(3).chunked(2).joinToString(" ")
    }

    private companion object {
        const val TAG = "StnCanLink"

        const val CAN_ID_ECU_REQUEST = 0x7E0
        const val CAN_ID_ECU_RESPONSE = 0x7E8
        const val CAN_ID_TCU_REQUEST = 0x7E1
        const val CAN_ID_TCU_RESPONSE = 0x7E9

        const val RESET_TIMEOUT_MS = 3000L
        const val COMMAND_TIMEOUT_MS = 2000L
        const val OWED_WAIT_MS = 1000L
        const val SETTLE_MS = 300L
        const val INIT_TIMEOUT_MS = 2000
        const val CAN_TIMEOUT_MS = 500
        const val PER_FRAME_MS = 40
        const val MAX_TIMEOUT_MS = 2500

        val NO_SPACES = Regex("^[0-9A-Fa-f]{3}([0-9A-Fa-f]{2})+$")
        val NO_ANSWER = SsmMessage(Ssm.DEVICE_TOOL, Ssm.DEVICE_ECU, Ssm.CMD_NEGATIVE, byteArrayOf(0, 0))

        fun requestId(device: Int) = if (device == Ssm.DEVICE_TCU) CAN_ID_TCU_REQUEST else CAN_ID_ECU_REQUEST
        fun responseId(device: Int) = if (device == Ssm.DEVICE_TCU) CAN_ID_TCU_RESPONSE else CAN_ID_ECU_RESPONSE

        /** ISO-TP frames needed for [payloadBytes]: 7 fit in one, 6 in the first, 7 per consecutive. */
        @Suppress("FunctionName")
        fun IsoTp_frames(payloadBytes: Int): Int = if (payloadBytes <= 7) 1 else 1 + (payloadBytes - 6 + 6) / 7
    }
}

package com.fitc.ssmgen4.ssm

import java.io.ByteArrayOutputStream

/**
 * Turns the lines an ELM/STN adapter prints for one CAN reply back into the SSM payload.
 *
 * With `ATCAF1` the STN reassembles ISO-TP itself and prints one line whose first data
 * byte is already the SSM command (always >= 0x40). Without it the raw ISO-TP frames come
 * through: a single frame `0N ...`, or a first frame `1N NN ...` followed by consecutive
 * frames `2N ...`. Both shapes are handled so a plain ELM327 still works.
 */
object IsoTp {

    private val WHITESPACE = Regex("\\s+")

    /** Bytes of one `7E8 xx xx ...` line, or null if it is not addressed from [header]. */
    fun bytes(line: String, header: String): ByteArray? {
        val tokens = line.trim().split(WHITESPACE)
        if (tokens.size < 2 || !tokens[0].equals(header, ignoreCase = true)) return null
        val out = ByteArray(tokens.size - 1)
        for (i in 1 until tokens.size) {
            out[i - 1] = tokens[i].toIntOrNull(16)?.toByte() ?: return null
        }
        return out
    }

    fun assemble(lines: List<String>, header: String): ByteArray? {
        val frames = lines.mapNotNull { bytes(it, header) }.filter { it.isNotEmpty() }
        val first = frames.firstOrNull() ?: return null
        val pci = first[0].toInt() and 0xFF

        // Adapter already stripped the ISO-TP layer: what is left starts with the command.
        if (pci >= 0x40) {
            val out = ByteArrayOutputStream()
            frames.forEach { out.write(it, 0, it.size) }
            return out.toByteArray()
        }

        return when (pci shr 4) {
            0 -> { // single frame, low nibble is the length
                val length = pci and 0x0F
                if (first.size < length + 1) null else first.copyOfRange(1, length + 1)
            }
            1 -> { // first frame: 12-bit length, then consecutive frames 0x21, 0x22, ...
                if (first.size < 2) return null
                val length = ((pci and 0x0F) shl 8) or (first[1].toInt() and 0xFF)
                val out = ByteArrayOutputStream()
                out.write(first, 2, first.size - 2)
                for (frame in frames.drop(1)) {
                    if ((frame[0].toInt() and 0xF0) == 0x20) out.write(frame, 1, frame.size - 1)
                }
                val all = out.toByteArray()
                if (all.size < length) null else all.copyOf(length)
            }
            else -> null
        }
    }
}

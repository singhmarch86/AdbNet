package dev.mobileshield.core.reputation

import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A compact, serializable Bloom filter — the data structure behind
 * privacy-preserving fraud lookups. A prebuilt filter of reported-fraud
 * identifiers is downloaded once and queried **entirely on-device**, so a payee
 * or host is never sent to a server to be checked.
 *
 * Membership has no false negatives (a reported identifier always matches) and
 * a tunable false-positive rate. Hashing is a stable FNV-1a + double-hashing
 * scheme (not `String.hashCode`, which is not guaranteed stable across
 * platforms), so a filter built anywhere matches everywhere. Pure Kotlin, so it
 * is identical on Android and iOS.
 */
class BloomFilter private constructor(
    private val bits: LongArray,
    private val bitSize: Int,
    val hashCount: Int,
) {
    fun add(item: String) {
        forEachIndex(item) { setBit(it) }
    }

    fun mightContain(item: String): Boolean {
        forEachIndex(item) { if (!getBit(it)) return false }
        return true
    }

    private inline fun forEachIndex(item: String, action: (Int) -> Unit) {
        val bytes = item.encodeToByteArray()
        val h1 = fnv1a(bytes, FNV_OFFSET_1)
        val h2 = fnv1a(bytes, FNV_OFFSET_2) or 1L // odd, so steps cover the space
        val m = bitSize.toLong()
        for (i in 0 until hashCount) {
            val combined = h1 + i.toLong() * h2
            action(((combined and Long.MAX_VALUE) % m).toInt())
        }
    }

    private fun setBit(index: Int) {
        bits[index ushr 6] = bits[index ushr 6] or (1L shl (index and 63))
    }

    private fun getBit(index: Int): Boolean =
        (bits[index ushr 6] and (1L shl (index and 63))) != 0L

    /** Serializes to a self-describing byte array (see [fromBytes]). */
    fun toBytes(): ByteArray {
        val out = ByteArray(12 + bits.size * 8)
        putInt(out, 0, bitSize)
        putInt(out, 4, hashCount)
        putInt(out, 8, bits.size)
        var off = 12
        for (word in bits) {
            putLong(out, off, word)
            off += 8
        }
        return out
    }

    companion object {
        // 64-bit FNV-1a constants.
        private const val FNV_PRIME = 0x100000001b3L
        private val FNV_OFFSET_1 = 0xcbf29ce484222325uL.toLong()
        private val FNV_OFFSET_2 = 0x9e3779b97f4a7c15uL.toLong()

        /**
         * Sizes a filter for [expectedInsertions] items at false-positive
         * probability [fpp] (0 < fpp < 1), using the standard formulas.
         */
        fun create(expectedInsertions: Int, fpp: Double = 0.001): BloomFilter {
            require(fpp in 0.0..1.0 && fpp > 0.0) { "fpp must be in (0,1)" }
            val n = max(1, expectedInsertions)
            val m = max(64, ceil(-n * ln(fpp) / (LN2 * LN2)).toInt())
            val k = max(1, (m.toDouble() / n * LN2).roundToInt())
            return BloomFilter(LongArray((m + 63) / 64), m, k)
        }

        fun fromBytes(bytes: ByteArray): BloomFilter {
            require(bytes.size >= 12) { "truncated bloom filter" }
            val bitSize = getInt(bytes, 0)
            val hashCount = getInt(bytes, 4)
            val words = getInt(bytes, 8)
            require(bytes.size == 12 + words * 8) { "corrupt bloom filter" }
            val arr = LongArray(words)
            var off = 12
            for (i in 0 until words) {
                arr[i] = getLong(bytes, off)
                off += 8
            }
            return BloomFilter(arr, bitSize, hashCount)
        }

        private const val LN2 = 0.6931471805599453

        private fun fnv1a(bytes: ByteArray, basis: Long): Long {
            var h = basis
            for (b in bytes) {
                h = h xor (b.toLong() and 0xFF)
                h *= FNV_PRIME
            }
            return h
        }

        private fun putInt(b: ByteArray, i: Int, v: Int) {
            b[i] = (v ushr 24).toByte()
            b[i + 1] = (v ushr 16).toByte()
            b[i + 2] = (v ushr 8).toByte()
            b[i + 3] = v.toByte()
        }

        private fun getInt(b: ByteArray, i: Int): Int =
            ((b[i].toInt() and 0xFF) shl 24) or
                ((b[i + 1].toInt() and 0xFF) shl 16) or
                ((b[i + 2].toInt() and 0xFF) shl 8) or
                (b[i + 3].toInt() and 0xFF)

        private fun putLong(b: ByteArray, i: Int, v: Long) {
            for (j in 0 until 8) b[i + j] = (v ushr (56 - j * 8)).toByte()
        }

        private fun getLong(b: ByteArray, i: Int): Long {
            var v = 0L
            for (j in 0 until 8) v = (v shl 8) or (b[i + j].toLong() and 0xFF)
            return v
        }
    }
}

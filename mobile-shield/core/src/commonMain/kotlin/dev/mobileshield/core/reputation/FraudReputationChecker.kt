package dev.mobileshield.core.reputation

enum class ReputationStatus {
    /** The identifier is in the downloaded reported-fraud set. */
    REPORTED_FRAUD,

    /** Not in the set. This means "not reported", NOT "proven safe". */
    NOT_LISTED,
}

data class ReputationResult(
    val status: ReputationStatus,
    val normalizedKey: String,
    val message: String,
)

/**
 * Checks a payee VPA or a host against a downloaded Bloom filter of reported
 * fraud identifiers — privately, on-device, with no per-lookup network call.
 *
 * Because a Bloom filter has no false negatives, NOT_LISTED is a reliable "not
 * on the reported-fraud list" (though never a guarantee of safety). A match is
 * "reported as fraud" subject to the filter's small, tunable false-positive
 * rate, so the UI should phrase it as "reported" and let the user decide.
 *
 * VPAs and hosts are stored with distinct key prefixes so the two namespaces
 * never collide inside one filter.
 */
class FraudReputationChecker(private val filter: BloomFilter) {

    fun checkVpa(vpa: String): ReputationResult {
        val key = vpaKey(vpa)
        return result(key, "payment ID")
    }

    fun checkHost(host: String): ReputationResult {
        val key = hostKey(host)
        return result(key, "website")
    }

    private fun result(key: String, kind: String): ReputationResult {
        val status = if (filter.mightContain(key)) ReputationStatus.REPORTED_FRAUD else ReputationStatus.NOT_LISTED
        val message = when (status) {
            ReputationStatus.REPORTED_FRAUD ->
                "This $kind has been reported for fraud. Do not pay or proceed unless you are certain it is genuine."
            ReputationStatus.NOT_LISTED ->
                "This $kind is not on the reported-fraud list. That is reassuring but not a guarantee — still verify."
        }
        return ReputationResult(status, key, message)
    }

    companion object {
        private fun vpaKey(vpa: String) = "vpa:" + vpa.trim().lowercase()
        private fun hostKey(host: String) =
            "host:" + host.trim().lowercase().removePrefix("www.")

        /**
         * Builds a checker from known-fraud [vpas] and [hosts]. In production the
         * filter is built server-side from a maintained, privacy-cleared feed and
         * shipped as bytes via [BloomFilter.toBytes]; this is the same builder.
         */
        fun build(
            vpas: Collection<String> = emptyList(),
            hosts: Collection<String> = emptyList(),
            fpp: Double = 0.001,
        ): FraudReputationChecker {
            val filter = BloomFilter.create(vpas.size + hosts.size, fpp)
            vpas.forEach { filter.add(vpaKey(it)) }
            hosts.forEach { filter.add(hostKey(it)) }
            return FraudReputationChecker(filter)
        }

        fun fromBytes(bytes: ByteArray): FraudReputationChecker =
            FraudReputationChecker(BloomFilter.fromBytes(bytes))
    }
}

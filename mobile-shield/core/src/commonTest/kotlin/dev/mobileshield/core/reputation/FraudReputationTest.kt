package dev.mobileshield.core.reputation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BloomFilterTest {
    @Test
    fun `no false negatives for added items`() {
        val f = BloomFilter.create(1000, 0.01)
        val items = (0 until 1000).map { "item-$it@okaxis" }
        items.forEach { f.add(it) }
        items.forEach { assertTrue(f.mightContain(it), "added item should match: $it") }
    }

    @Test
    fun `false positive rate is near the target`() {
        val f = BloomFilter.create(1000, 0.01)
        (0 until 1000).forEach { f.add("member-$it") }
        var fp = 0
        val trials = 5000
        for (i in 0 until trials) {
            if (f.mightContain("nonmember-$i")) fp++
        }
        val rate = fp.toDouble() / trials
        assertTrue(rate < 0.05, "fp rate $rate should be well under 5% for a 1% filter")
    }

    @Test
    fun `survives serialization round trip`() {
        val f = BloomFilter.create(500, 0.01)
        val members = (0 until 500).map { "m$it" }
        members.forEach { f.add(it) }
        val restored = BloomFilter.fromBytes(f.toBytes())
        members.forEach { assertTrue(restored.mightContain(it)) }
        assertEquals(f.hashCount, restored.hashCount)
    }
}

class FraudReputationCheckerTest {
    private val checker = FraudReputationChecker.build(
        vpas = listOf("scammer@okaxis", "fraud123@ybl"),
        hosts = listOf("sbi-verify.xyz", "evil-bank.top"),
        fpp = 0.0001,
    )

    @Test
    fun `reported fraud vpa is flagged`() {
        assertEquals(ReputationStatus.REPORTED_FRAUD, checker.checkVpa("scammer@okaxis").status)
    }

    @Test
    fun `vpa match is case and space insensitive`() {
        assertEquals(ReputationStatus.REPORTED_FRAUD, checker.checkVpa("  Scammer@OKAXIS ").status)
    }

    @Test
    fun `unknown vpa is not listed`() {
        assertEquals(ReputationStatus.NOT_LISTED, checker.checkVpa("alice@okhdfcbank").status)
    }

    @Test
    fun `reported host is flagged and www is stripped`() {
        assertEquals(ReputationStatus.REPORTED_FRAUD, checker.checkHost("www.sbi-verify.xyz").status)
        assertEquals(ReputationStatus.NOT_LISTED, checker.checkHost("example.com").status)
    }

    @Test
    fun `vpa and host namespaces do not collide`() {
        // A host string that equals a fraud vpa's text must not match as a host.
        assertEquals(ReputationStatus.NOT_LISTED, checker.checkHost("scammer@okaxis").status)
    }

    @Test
    fun `survives serialization`() {
        val restored = FraudReputationChecker.fromBytes(
            FraudReputationChecker.build(vpas = listOf("x@okaxis"), fpp = 0.001)
                .let { BloomFilter.create(1, 0.001).also { bf -> bf.add("vpa:x@okaxis") }.toBytes() }
        )
        assertEquals(ReputationStatus.REPORTED_FRAUD, restored.checkVpa("x@okaxis").status)
    }
}

package dev.mobileshield.core.filter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HostNameTest {
    @Test
    fun `normalizes case trailing dot and port`() {
        val h = HostName.parseOrNull("WWW.Example.COM.:443")
        assertNotNull(h)
        assertEquals("www.example.com", h.ascii)
        assertEquals(listOf("www", "example", "com"), h.labels)
    }

    @Test
    fun `converts unicode to punycode`() {
        val unicode = HostName.parseOrNull("bücher.example")
        val puny = HostName.parseOrNull("xn--bcher-kva.example")
        assertNotNull(unicode)
        assertNotNull(puny)
        assertEquals(puny, unicode)
    }

    @Test
    fun `rejects empty labels and blanks`() {
        assertNull(HostName.parseOrNull(""))
        assertNull(HostName.parseOrNull("   "))
        assertNull(HostName.parseOrNull("a..b"))
        assertNull(HostName.parseOrNull("."))
    }

    @Test
    fun `subdomain matching respects label boundaries`() {
        val example = HostName.parseOrNull("example.com")!!
        assertTrue(HostName.parseOrNull("example.com")!!.isUnder(example))
        assertTrue(HostName.parseOrNull("www.example.com")!!.isUnder(example))
        assertTrue(HostName.parseOrNull("a.b.example.com")!!.isUnder(example))
        // Not a sub-domain: shares a suffix string but not on a boundary.
        assertEquals(false, HostName.parseOrNull("notexample.com")!!.isUnder(example))
        assertEquals(false, HostName.parseOrNull("example.com.evil.net")!!.isUnder(example))
        assertEquals(false, HostName.parseOrNull("com")!!.isUnder(example))
    }
}

class DomainBlocklistMatcherTest {
    private fun matcher(
        block: List<String> = listOf("ads.example.com", "tracker.net"),
        allow: List<String> = emptyList(),
        safe: Map<String, String> = emptyMap(),
        blockDoh: Boolean = false,
    ) = DomainBlocklistMatcher(block, allow, safe, blockKnownDohResolvers = blockDoh)

    @Test
    fun `blocks listed domain and its subdomains`() {
        val m = matcher()
        assertEquals(FilterAction.BLOCK, m.decide("ads.example.com").action)
        assertEquals(FilterAction.BLOCK, m.decide("img.ads.example.com").action)
        assertEquals(FilterAction.BLOCK, m.decide("tracker.net").action)
    }

    @Test
    fun `allows unrelated and sibling domains`() {
        val m = matcher()
        assertEquals(FilterAction.ALLOW, m.decide("example.com").action)
        assertEquals(FilterAction.ALLOW, m.decide("safe.example.com").action)
        assertEquals(FilterAction.ALLOW, m.decide("nottracker.net").action)
    }

    @Test
    fun `allowlist overrides blocklist`() {
        val m = matcher(
            block = listOf("example.com"),
            allow = listOf("school.example.com"),
        )
        assertEquals(FilterAction.BLOCK, m.decide("example.com").action)
        assertEquals(FilterAction.BLOCK, m.decide("ads.example.com").action)
        val allowed = m.decide("lab.school.example.com")
        assertEquals(FilterAction.ALLOW, allowed.action)
        assertEquals(FilterReason.ALLOWLISTED, allowed.reason)
    }

    @Test
    fun `enforces safe search by rewrite before block`() {
        val m = matcher(
            block = listOf("google.com"),
            safe = mapOf("google.com" to "forcesafesearch.google.com"),
        )
        val d = m.decide("www.google.com")
        assertEquals(FilterAction.REWRITE, d.action)
        assertEquals(FilterReason.SAFE_SEARCH_ENFORCED, d.reason)
        assertEquals("forcesafesearch.google.com", d.rewriteTo)
    }

    @Test
    fun `blocks known doh resolvers when enabled`() {
        val m = matcher(block = emptyList(), blockDoh = true)
        assertEquals(FilterAction.BLOCK, m.decide("dns.google").action)
        assertEquals(FilterAction.BLOCK, m.decide("cloudflare-dns.com").action)
        // And does not when disabled.
        val off = matcher(block = emptyList(), blockDoh = false)
        assertEquals(FilterAction.ALLOW, off.decide("dns.google").action)
    }

    @Test
    fun `reports the matched rule`() {
        val d = matcher().decide("img.ads.example.com")
        assertEquals("ads.example.com", d.matchedRule)
    }

    @Test
    fun `unparseable host is allowed rather than crashing`() {
        assertEquals(FilterAction.ALLOW, matcher().decide("a..b").action)
    }
}

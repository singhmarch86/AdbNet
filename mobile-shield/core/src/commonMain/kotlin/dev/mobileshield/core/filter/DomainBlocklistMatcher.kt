package dev.mobileshield.core.filter

/** What the DNS layer should do with a looked-up host. */
enum class FilterAction {
    /** Resolve normally. */
    ALLOW,

    /** Answer as if the name does not exist (NXDOMAIN) / sinkhole it. */
    BLOCK,

    /**
     * Resolve, but to a forced SafeSearch / restricted-mode address instead of
     * the real one. [FilterDecision.rewriteTo] carries the replacement host.
     */
    REWRITE,
}

/** The reason a decision was reached, useful for logging and the UI. */
enum class FilterReason {
    NOT_ON_ANY_LIST,
    ALLOWLISTED,
    BLOCKLISTED,
    SAFE_SEARCH_ENFORCED,
}

data class FilterDecision(
    val action: FilterAction,
    val reason: FilterReason,
    /** The blocklist/allowlist entry that matched, if any. */
    val matchedRule: String? = null,
    /** For [FilterAction.REWRITE], the host to resolve instead. */
    val rewriteTo: String? = null,
)

/**
 * Decides, for a single DNS query, whether to allow, block, or rewrite it.
 *
 * Matching is label-boundary aware (see [HostName.isUnder]) so a rule for
 * `example.com` covers `www.example.com` but never `notexample.com`.
 *
 * Precedence, highest first:
 *  1. Allowlist — an explicit allow always wins, so a parent-approved site is
 *     never collateral-damaged by a broad blocklist entry.
 *  2. SafeSearch enforcement — search engines are rewritten to their
 *     restricted-mode hosts rather than blocked outright.
 *  3. Blocklist — including the bundled known DNS-over-HTTPS bootstrap hosts,
 *     which apps use to bypass DNS filtering.
 *  4. Otherwise allow.
 *
 * The matcher holds no network state and makes no I/O; it is a pure decision
 * function so it can be unit-tested exhaustively and reused unchanged on
 * Android inside the VpnService DNS handler.
 */
class DomainBlocklistMatcher(
    blocklist: Collection<String>,
    allowlist: Collection<String> = emptyList(),
    /**
     * Map of search-engine host -> SafeSearch host to rewrite it to. When empty
     * (the default) no rewriting happens.
     */
    safeSearchRewrites: Map<String, String> = emptyMap(),
    /**
     * When true, the built-in set of known DoH bootstrap hosts is merged into
     * the blocklist so encrypted-DNS bypass is closed by default.
     */
    blockKnownDohResolvers: Boolean = true,
) {
    private val blockRules: List<Pair<HostName, String>> = buildList {
        for (raw in blocklist) HostName.parseOrNull(raw)?.let { add(it to raw) }
        if (blockKnownDohResolvers) {
            for (raw in KNOWN_DOH_HOSTS) HostName.parseOrNull(raw)?.let { add(it to raw) }
        }
    }

    private val allowRules: List<Pair<HostName, String>> =
        allowlist.mapNotNull { raw -> HostName.parseOrNull(raw)?.let { it to raw } }

    private val safeSearch: List<Triple<HostName, String, String>> =
        safeSearchRewrites.entries.mapNotNull { (host, target) ->
            HostName.parseOrNull(host)?.let { Triple(it, host, target) }
        }

    fun decide(rawHost: String): FilterDecision {
        val host = HostName.parseOrNull(rawHost)
            ?: return FilterDecision(FilterAction.ALLOW, FilterReason.NOT_ON_ANY_LIST)

        allowRules.firstOrNull { host.isUnder(it.first) }?.let { (_, rule) ->
            return FilterDecision(FilterAction.ALLOW, FilterReason.ALLOWLISTED, matchedRule = rule)
        }

        safeSearch.firstOrNull { host.isUnder(it.first) }?.let { (_, rule, target) ->
            return FilterDecision(
                FilterAction.REWRITE,
                FilterReason.SAFE_SEARCH_ENFORCED,
                matchedRule = rule,
                rewriteTo = target,
            )
        }

        blockRules.firstOrNull { host.isUnder(it.first) }?.let { (_, rule) ->
            return FilterDecision(FilterAction.BLOCK, FilterReason.BLOCKLISTED, matchedRule = rule)
        }

        return FilterDecision(FilterAction.ALLOW, FilterReason.NOT_ON_ANY_LIST)
    }

    companion object {
        /**
         * Bootstrap host names of widely-used public DNS-over-HTTPS resolvers.
         * Blocking these forces apps back onto the system resolver (which the
         * VPN filters) instead of tunnelling DNS past it. This is deliberately a
         * small, well-known set; it is not a content blocklist and ships no
         * third-party data whose license we have not cleared.
         */
        val KNOWN_DOH_HOSTS: List<String> = listOf(
            "dns.google",
            "cloudflare-dns.com",
            "mozilla.cloudflare-dns.com",
            "dns.quad9.net",
            "doh.opendns.com",
            "dns.adguard.com",
            "doh.cleanbrowsing.org",
            "dns.nextdns.io",
        )
    }
}

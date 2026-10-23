package dev.mobileshield.android.data

import dev.mobileshield.core.filter.DomainBlocklistMatcher

/**
 * The matcher used by the VPN, plus a tiny illustrative seed list.
 *
 * This ships only a handful of example entries and the SafeSearch rewrites. We
 * deliberately do NOT bundle any third-party blocklist here until its license
 * is cleared (see the project README). In a real build the lists would be
 * loaded from a user-chosen, license-cleared source and cached locally.
 */
object Blocklists {

    /** A few obviously-tracking hosts, purely to demonstrate the pipeline. */
    private val SEED_BLOCK = listOf(
        "doubleclick.net",
        "ads.example.com",
    )

    /** Force the major search engines into SafeSearch via DNS rewrite. */
    private val SAFE_SEARCH = mapOf(
        "google.com" to "forcesafesearch.google.com",
        "www.google.com" to "forcesafesearch.google.com",
        "bing.com" to "strict.bing.com",
        "duckduckgo.com" to "safe.duckduckgo.com",
        "youtube.com" to "restrict.youtube.com",
        "www.youtube.com" to "restrict.youtube.com",
    )

    fun createMatcher(
        extraBlock: List<String> = emptyList(),
        allow: List<String> = emptyList(),
    ): DomainBlocklistMatcher = DomainBlocklistMatcher(
        blocklist = SEED_BLOCK + extraBlock,
        allowlist = allow,
        safeSearchRewrites = SAFE_SEARCH,
        blockKnownDohResolvers = true,
    )
}

package dev.mobileshield.android.filter

import android.content.Context

/** A selectable upstream DNS resolver. [servers] are tried in order. */
data class DnsResolver(
    val id: String,
    val label: String,
    val description: String,
    val servers: List<String>,
)

/** The resolvers the user can choose between for the content filter. */
object DnsResolvers {
    val CLOUDFLARE_FAMILY = DnsResolver(
        "cf_family",
        "Cloudflare for Families",
        "Blocks adult content and malware. Fast.",
        listOf("1.1.1.3", "1.0.0.3"),
    )
    val CLEANBROWSING_FAMILY = DnsResolver(
        "cleanbrowsing_family",
        "CleanBrowsing Family",
        "Blocks adult content, proxies and mixed sites; forces SafeSearch.",
        listOf("185.228.168.168", "185.228.169.168"),
    )
    val OPENDNS_FAMILYSHIELD = DnsResolver(
        "opendns_family",
        "OpenDNS FamilyShield",
        "Blocks adult content.",
        listOf("208.67.222.123", "208.67.220.123"),
    )
    val CLOUDFLARE_MALWARE = DnsResolver(
        "cf_malware",
        "Malware only (no content filter)",
        "Blocks malware but NOT adult content.",
        listOf("1.1.1.2", "1.0.0.2"),
    )

    val ALL = listOf(CLOUDFLARE_FAMILY, CLEANBROWSING_FAMILY, OPENDNS_FAMILYSHIELD, CLOUDFLARE_MALWARE)
    val DEFAULT = CLOUDFLARE_FAMILY

    fun byId(id: String?): DnsResolver = ALL.firstOrNull { it.id == id } ?: DEFAULT
}

/** Persists the user's content-filter choices. */
object FilterSettings {
    private const val PREFS = "mobileshield_filter"
    private const val KEY_RESOLVER = "resolver_id"

    fun resolver(context: Context): DnsResolver =
        DnsResolvers.byId(prefs(context).getString(KEY_RESOLVER, null))

    fun setResolver(context: Context, resolver: DnsResolver) {
        prefs(context).edit().putString(KEY_RESOLVER, resolver.id).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

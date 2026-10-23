package dev.mobileshield.android.guard

/**
 * Package ids of common UPI / banking apps. When one of these is in the
 * foreground, a payment may be about to happen, so the live guard evaluates the
 * current context. Not exhaustive — extend from a maintained list.
 */
object BankApps {
    val PACKAGES: Set<String> = setOf(
        "com.google.android.apps.nbu.paisa.user", // Google Pay (India)
        "net.one97.paytm",                         // Paytm
        "com.phonepe.app",                         // PhonePe
        "in.org.npci.upiapp",                      // BHIM
        "com.csam.icici.bank.imobile",             // ICICI iMobile
        "com.sbi.upi",                             // SBI / BHIM SBI Pay
        "com.sbi.lotusintouch",                    // YONO SBI
        "com.snapwork.hdfc",                       // HDFC
        "com.axis.mobile",                         // Axis
        "com.bankofbaroda.mconnect",               // BoB
        "com.mobikwik_new",                        // MobiKwik
        "com.freecharge.android",                  // Freecharge
        "com.whatsapp",                            // WhatsApp Pay
    )

    fun isBankApp(packageName: String?): Boolean = packageName != null && packageName in PACKAGES
}

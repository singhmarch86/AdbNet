package dev.mobileshield.core.smishing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SmishingClassifierTest {
    private val classifier = SmishingClassifier()

    @Test
    fun `legit otp message is low risk`() {
        val r = classifier.classify("Your OTP is 923112 for your transaction. Do not share it with anyone.")
        assertEquals(SmishingLevel.LOW, r.level)
        assertTrue(r.signals.none { it.code == SmishingCode.CREDENTIAL_REQUEST })
    }

    @Test
    fun `plain personal message is low risk`() {
        val r = classifier.classify("Hey, are we still meeting at 6pm today?")
        assertEquals(SmishingLevel.LOW, r.level)
        assertTrue(r.signals.isEmpty())
    }

    @Test
    fun `classic kyc smishing with shortener is high or critical`() {
        val r = classifier.classify(
            "URGENT: Your SBI account will be blocked today. Update KYC now: http://bit.ly/kyc-verify"
        )
        assertTrue(r.level == SmishingLevel.HIGH || r.level == SmishingLevel.CRITICAL)
        assertTrue(r.signals.any { it.code == SmishingCode.URGENCY_THREAT })
        assertTrue(r.signals.any { it.code == SmishingCode.KYC_SCAM })
        assertTrue(r.signals.any { it.code == SmishingCode.URL_SHORTENER })
    }

    @Test
    fun `reward lure with link is flagged`() {
        val r = classifier.classify("Congratulations! You won a prize of Rs 50000. Claim your reward at www.prize-claim.xyz")
        assertTrue(r.signals.any { it.code == SmishingCode.REWARD_LURE })
        assertTrue(r.score >= 15)
    }

    @Test
    fun `apk link is a strong signal`() {
        val r = classifier.classify("Install the new bank security app: http://secure-bank.in/update.apk")
        assertTrue(r.signals.any { it.code == SmishingCode.APK_LINK })
    }

    @Test
    fun `raw ip url is flagged`() {
        val r = classifier.classify("Verify here http://192.168.10.5/login to avoid suspension")
        assertTrue(r.signals.any { it.code == SmishingCode.RAW_IP_URL })
    }

    @Test
    fun `explicit request to share otp is credential request`() {
        val r = classifier.classify("Please share OTP received on your phone to complete verification")
        assertTrue(r.signals.any { it.code == SmishingCode.CREDENTIAL_REQUEST })
    }

    @Test
    fun `hinglish smishing is detected`() {
        val r = classifier.classify("Aapka account band ho jayega, turant KYC update karein: bit.ly/kyc")
        assertTrue(r.signals.any { it.code == SmishingCode.URGENCY_THREAT })
        assertTrue(r.signals.any { it.code == SmishingCode.KYC_SCAM })
    }

    @Test
    fun `devanagari reward lure is detected`() {
        val r = classifier.classify("बधाई हो! आपने लॉटरी जीता है। इनाम पाने के लिए क्लिक करें")
        assertTrue(r.signals.any { it.code == SmishingCode.REWARD_LURE })
    }

    @Test
    fun `personal sender number adds a signal when a link is present`() {
        val withLink = classifier.classify("Update now http://x.top/a", sender = "9876543210")
        assertTrue(withLink.signals.any { it.code == SmishingCode.SENDER_LOOKS_PERSONAL })
        val brandSender = classifier.classify("Update now http://x.top/a", sender = "VM-SBIINB")
        assertTrue(brandSender.signals.none { it.code == SmishingCode.SENDER_LOOKS_PERSONAL })
    }

    @Test
    fun `headline reflects the top signal and score caps at 100`() {
        val r = classifier.classify(
            "URGENT! SBI KYC expire today. Share OTP and PIN now or account blocked. " +
                "Call 9998887776 and open http://1.2.3.4/x.apk to claim your refund"
        )
        assertEquals(SmishingLevel.CRITICAL, r.level)
        assertEquals(100, r.score)
        assertTrue(r.headline.isNotBlank())
    }
}

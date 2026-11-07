package com.example.guzlikvoice

import android.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/** Формат лицензии совпадает с server/app/security.py (LicenseSigner). */
@RunWith(RobolectricTestRunner::class)
class LicenseManagerTest {
    private val flags = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP

    private fun keyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    private fun sign(kp: KeyPair, payload: String): String {
        val body = payload.toByteArray()
        val sig = Signature.getInstance("SHA256withECDSA").run { initSign(kp.private); update(body); sign() }
        return Base64.encodeToString(body, flags) + "." + Base64.encodeToString(sig, flags)
    }

    private fun pub(kp: KeyPair) = Base64.encodeToString(kp.public.encoded, Base64.NO_WRAP)

    private val payload = """{"exp":2000000000,"iat":1700000000,"offline_until":1700604800,"paid":true,"sub":"1","v":1}"""

    @Test fun `valid license is accepted`() {
        val kp = keyPair()
        val lic = LicenseManager.verify(sign(kp, payload), pub(kp))
        assertNotNull(lic)
        assertEquals(2_000_000_000L, lic!!.exp)
        assertEquals(1_700_604_800L, lic.offlineUntil)
    }

    @Test fun `tampered payload is rejected`() {
        val kp = keyPair()
        val sig = sign(kp, payload).substringAfter('.')
        val forged = Base64.encodeToString(payload.replace("2000000000", "2999999999").toByteArray(), flags)
        assertNull(LicenseManager.verify("$forged.$sig", pub(kp)))
    }

    @Test fun `license signed by another key is rejected`() {
        assertNull(LicenseManager.verify(sign(keyPair(), payload), pub(keyPair())))
    }

    @Test fun `garbage is rejected`() {
        assertNull(LicenseManager.verify("not-a-license", pub(keyPair())))
    }
}

package com.privatevault.app.security

import com.google.protobuf.CodedOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class AuthenticatorTransferTest {
    private fun message(write: (CodedOutputStream) -> Unit): ByteArray {
        val output = ByteArrayOutputStream()
        val coded = CodedOutputStream.newInstance(output)
        write(coded)
        coded.flush()
        return output.toByteArray()
    }

    private fun qr(type: Int = 2, batchSize: Int = 1, index: Int = 0): String {
        val account = message {
            it.writeByteArray(1, "12345678901234567890".toByteArray())
            it.writeString(2, "Example:alice@example.com")
            it.writeString(3, "Example")
            it.writeEnum(4, 1)
            it.writeEnum(5, 2)
            it.writeEnum(6, type)
        }
        val payload = message {
            it.writeByteArray(1, account)
            it.writeInt32(2, 1)
            it.writeInt32(3, batchSize)
            it.writeInt32(4, index)
            it.writeInt32(5, 42)
        }
        return "otpauth-migration://offline?data=" + URLEncoder.encode(Base64.getEncoder().encodeToString(payload), "UTF-8")
    }

    @Test fun parsesGoogleTransferPagesAndCodeParameters() {
        val first = parseAuthenticatorTransferQr(qr(batchSize = 2))
        val second = parseAuthenticatorTransferQr(qr(batchSize = 2, index = 1))
        assertEquals(42, first.batchId)
        assertEquals(2, first.batchSize)
        assertEquals(1, second.batchIndex)
        assertEquals("Example", first.accounts.single().issuer)
        assertEquals("alice@example.com", first.accounts.single().account)
        assertEquals(8, first.accounts.single().digits)
        assertEquals("SHA1", first.accounts.single().algorithm)
        assertEquals("TotpSetup(redacted)", first.accounts.single().toString())
    }

    @Test fun rejectsHotpAndMalformedTransfers() {
        assertThrows(IllegalArgumentException::class.java) { parseAuthenticatorTransferQr(qr(type = 1)) }
        assertThrows(IllegalArgumentException::class.java) { parseAuthenticatorTransferQr(qr(batchSize = 2, index = 2)) }
        assertThrows(IllegalArgumentException::class.java) { parseAuthenticatorTransferQr("otpauth-migration://offline?data=bad") }
    }

    @Test fun acceptsStandardSetupQr() {
        val page = parseAuthenticatorTransferQr("otpauth://totp/Example:alice?secret=JBSWY3DPEHPK3PXP&issuer=Example")
        assertEquals(1, page.batchSize)
        assertEquals("alice", page.accounts.single().account)
    }

    @Test fun importsOnlyStandardTotpUrisFromText() {
        val uri = "otpauth://totp/Example:alice?secret=JBSWY3DPEHPK3PXP&issuer=Example"
        assertEquals(2, parseAuthenticatorTransferText("$uri\n$uri\n").accounts.size)
        assertThrows(IllegalArgumentException::class.java) { parseAuthenticatorTransferText("$uri\nunknown") }
    }

    @Test fun decryptsOneAuthJsonAndRejectsWrongPassword() {
        val password = "temporary test password".toCharArray()
        val salt = ByteArray(12) { (it + 1).toByte() }
        val iv = ByteArray(12) { (it + 20).toByte() }
        val plain = """{"groups":[{"group_name":"Sample","tpa_secrets":[{"secret":"JBSWY3DPEHPK3PXP","issuer":"Example","label":"alice","period":30,"digits":6,"algorithm":"SHA1","type":"totp","icon":""}]}]}"""
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(password, salt, 100_000, 256)).encoded
        val encrypted = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            doFinal(plain.toByteArray())
        }
        val envelope = """{"data":"${Base64.getEncoder().encodeToString(iv + encrypted)}","enc_salt":"${Base64.getEncoder().encodeToString(salt)}","nonce":1750000000000,"format":"oneauth_04","app_version":"4.4.2","platform":"android"}"""
        assertEquals(true, isEncryptedOneAuthExport(envelope))
        val account = parseEncryptedOneAuthExport(envelope, password).accounts.single()
        assertEquals("Example", account.issuer)
        assertEquals("alice", account.account)
        assertEquals("JBSWY3DPEHPK3PXP", account.secret)
        assertThrows(IllegalArgumentException::class.java) {
            parseEncryptedOneAuthExport(envelope, "wrong password".toCharArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseEncryptedOneAuthExport(envelope.replace("oneauth_04", "oneauth_05"), password)
        }
        key.fill(0)
        password.fill('\u0000')
    }
}

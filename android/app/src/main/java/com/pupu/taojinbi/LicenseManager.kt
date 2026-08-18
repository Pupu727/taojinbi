package com.pupu.taojinbi

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Base64
import org.json.JSONObject
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

object LicenseManager {
    private const val LICENSE_FILE = "license.json"
    private const val APP_ID = "com.pupu.taojinbi"

    /**
     * 需要替换成你自己的 RSA 公钥（X509 SubjectPublicKeyInfo，Base64）。
     * 可用 builder/license_tool.py 生成。
     */
    private const val PUBLIC_KEY_B64 = "REPLACE_WITH_YOUR_RSA_PUBLIC_KEY_BASE64"

    data class ValidationResult(
        val ok: Boolean,
        val message: String,
    )

    fun currentDeviceCode(context: Context): String {
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID,
        ).orEmpty()
        val raw = listOf(
            "pkg=$APP_ID",
            "aid=$androidId",
            "brand=${Build.BRAND}",
            "model=${Build.MODEL}",
            "device=${Build.DEVICE}",
            "board=${Build.BOARD}",
            "fingerprint=${Build.FINGERPRINT}",
        ).joinToString("|")
        return sha256(raw).take(32)
    }

    fun copyDeviceCode(context: Context): String {
        val code = currentDeviceCode(context)
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("taojinbi_device_code", code))
        return code
    }

    fun validateInstalledLicense(context: Context): ValidationResult {
        if (PUBLIC_KEY_B64.startsWith("REPLACE_")) {
            return ValidationResult(false, "授权公钥未配置，请联系开发者")
        }
        val text = runCatching {
            context.openFileInput(LICENSE_FILE).bufferedReader().use { it.readText() }
        }.getOrNull() ?: return ValidationResult(
            false,
            "未授权。请在设置页复制设备码并导入授权文件。",
        )
        return validateLicenseText(context, text)
    }

    fun importLicense(context: Context, uri: Uri): ValidationResult {
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull() ?: return ValidationResult(false, "读取授权文件失败")

        val result = validateLicenseText(context, text)
        if (!result.ok) return result

        runCatching {
            context.openFileOutput(LICENSE_FILE, Context.MODE_PRIVATE).bufferedWriter().use { it.write(text) }
        }.onFailure {
            return ValidationResult(false, "写入授权失败: ${it.message}")
        }
        return ValidationResult(true, "授权已生效")
    }

    private fun validateLicenseText(context: Context, text: String): ValidationResult {
        return runCatching {
            val root = JSONObject(text)
            val payload = root.optString("payload")
            val signatureB64 = root.optString("signature")
            if (payload.isBlank() || signatureB64.isBlank()) {
                return ValidationResult(false, "授权文件格式错误")
            }
            if (!verifySignature(payload, signatureB64)) {
                return ValidationResult(false, "签名校验失败，授权文件无效")
            }

            val p = JSONObject(payload)
            val appId = p.optString("app_id")
            val hwid = p.optString("device_code")
            val kind = p.optString("license_type")
            if (appId != APP_ID) return ValidationResult(false, "授权包不匹配当前应用")
            if (kind != "perpetual") return ValidationResult(false, "仅支持买断制 perpetual 授权")
            if (hwid != currentDeviceCode(context)) return ValidationResult(false, "授权未绑定当前设备")
            ValidationResult(true, "授权校验通过")
        }.getOrElse {
            ValidationResult(false, "授权解析失败: ${it.message}")
        }
    }

    private fun verifySignature(payload: String, signatureB64: String): Boolean {
        val keyBytes = Base64.decode(PUBLIC_KEY_B64, Base64.DEFAULT)
        val pub = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(keyBytes))
        val verifier = Signature.getInstance("SHA256withRSA")
        verifier.initVerify(pub)
        verifier.update(payload.toByteArray(Charsets.UTF_8))
        val sigBytes = Base64.decode(signatureB64, Base64.DEFAULT)
        return verifier.verify(sigBytes)
    }

    private fun sha256(raw: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { b -> "%02x".format(b) }
    }
}

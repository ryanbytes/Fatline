package dev.scanrelay.app.playback

import android.content.Context
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** Fixed private destination; never use ThinLine APIs for diagnostics. */
internal object GitHubDiagnosticsConfig {
    const val OWNER_REPO = "ryanbytes/Scratch"
    const val BRANCH = "main"
    const val UPLOAD_INTERVAL_MS = 60L * 60L * 1000L
    const val MAX_UPLOAD_BYTES = 192 * 1024

    fun selectBatch(files: List<File>): List<File> {
        val selected = ArrayList<File>()
        var length = 0L
        for (file in files) {
            if (file.length() <= 0 || file.length() > MAX_UPLOAD_BYTES) continue
            if (length + file.length() > MAX_UPLOAD_BYTES) break
            selected.add(file)
            length += file.length()
        }
        return selected
    }
}

/**
 * A repository-scoped, fine-grained GitHub PAT is entered locally by the user.
 * It is encrypted at rest with a non-exportable AndroidKeyStore AES-GCM key.
 * Neither token nor request response bodies are written to diagnostics.
 */
object GitHubDiagnosticsCredentials {
    private const val PREF = "fatline_github_diagnostics"
    private const val KEY_ALIAS = "fatline_github_diagnostics_aes_v1"
    private const val IV_KEY = "iv"
    private const val TOKEN_KEY = "token"
    private const val NEXT_UPLOAD_KEY = "next_upload"
    private const val LAST_STATUS_KEY = "last_status"
    private const val INSTANCE_KEY = "installation_id"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    fun configured(context: Context): Boolean = prefs(context).contains(TOKEN_KEY)

    fun save(context: Context, value: String) {
        val token = value.trim()
        require(token.startsWith("github_pat_") || token.startsWith("ghp_")) {
            "Enter a GitHub personal access token"
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        prefs(context).edit()
            .putString(IV_KEY, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(TOKEN_KEY, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putLong(NEXT_UPLOAD_KEY, System.currentTimeMillis() + GitHubDiagnosticsConfig.UPLOAD_INTERVAL_MS)
            .putString(LAST_STATUS_KEY, "Configured; first automatic attempt in about one hour")
            .apply()
    }

    fun remove(context: Context) {
        prefs(context).edit().remove(TOKEN_KEY).remove(IV_KEY)
            .remove(NEXT_UPLOAD_KEY).putString(LAST_STATUS_KEY, "GitHub uploading disabled").apply()
    }

    internal fun token(context: Context): String? = runCatching {
        val preferences = prefs(context)
        val iv = Base64.decode(preferences.getString(IV_KEY, null) ?: return null, Base64.NO_WRAP)
        val ciphertext = Base64.decode(preferences.getString(TOKEN_KEY, null) ?: return null, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }.getOrNull()

    internal fun instance(context: Context): String {
        val preferences = prefs(context)
        preferences.getString(INSTANCE_KEY, null)?.let { return it }
        return UUID.randomUUID().toString().also {
            preferences.edit().putString(INSTANCE_KEY, it).apply()
        }
    }

    internal fun nextUploadAt(context: Context): Long = prefs(context).getLong(NEXT_UPLOAD_KEY, 0L)
    internal fun recordAttempt(context: Context, nextMs: Long, result: String) {
        prefs(context).edit().putLong(NEXT_UPLOAD_KEY, nextMs)
            .putString(LAST_STATUS_KEY, result).apply()
    }

    fun status(context: Context): String =
        prefs(context).getString(LAST_STATUS_KEY, "Not configured") ?: "Not configured"
}

/** Called only by the foreground scanner service's low-frequency IO coroutine. */
internal object GitHubDiagnosticsUploader {
    private val http = OkHttpClient.Builder().callTimeout(java.time.Duration.ofSeconds(25)).build()

    fun uploadIfDue(context: Context, directory: File, now: Long = System.currentTimeMillis()) {
        if (!GitHubDiagnosticsCredentials.configured(context)) return
        val due = GitHubDiagnosticsCredentials.nextUploadAt(context)
        if (due > now && due - now < 24L * 60L * 60L * 1000L) return
        val pending = AutomaticPerformanceLogStore.pending(directory)
        if (pending.isEmpty()) return
        // Persist throttle *before* network traffic so process restarts cannot
        // cause a tight request loop, even for a 401 or a network outage.
        val next = now + GitHubDiagnosticsConfig.UPLOAD_INTERVAL_MS
        GitHubDiagnosticsCredentials.recordAttempt(context, next, "Upload attempt in progress")
        val token = GitHubDiagnosticsCredentials.token(context)
        if (token.isNullOrBlank()) {
            GitHubDiagnosticsCredentials.recordAttempt(context, next, "Stored GitHub authorization unavailable")
            return
        }
        val files = GitHubDiagnosticsConfig.selectBatch(pending)
        if (files.isEmpty()) {
            GitHubDiagnosticsCredentials.recordAttempt(context, next, "No valid diagnostic records")
            return
        }
        val payload = files.joinToString(separator = "\n") { it.readText() }
        val name = "${files.first().nameWithoutExtension}-${files.last().nameWithoutExtension}.txt"
        val path = "fatline-diagnostics/${GitHubDiagnosticsCredentials.instance(context)}/$name"
        val json = JSONObject()
            .put("message", "Store private FatLine performance diagnostics")
            .put("branch", GitHubDiagnosticsConfig.BRANCH)
            .put("content", Base64.encodeToString(payload.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
        val url = "https://api.github.com/repos/${GitHubDiagnosticsConfig.OWNER_REPO}/contents/$path"
        try {
            val request = Request.Builder().url(url)
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .put(json.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            http.newCall(request).execute().use { response ->
                if (response.code == 201) {
                    files.forEach { it.delete() }
                    GitHubDiagnosticsCredentials.recordAttempt(context, next, "Uploaded ${files.size} summaries (HTTP 201)")
                } else {
                    // Never echo the response body; it might include request details.
                    val delay = if (response.code == 403 || response.code == 429) 2 * GitHubDiagnosticsConfig.UPLOAD_INTERVAL_MS else GitHubDiagnosticsConfig.UPLOAD_INTERVAL_MS
                    GitHubDiagnosticsCredentials.recordAttempt(context, now + delay, "GitHub upload failed (HTTP ${response.code}); saved locally")
                }
            }
        } catch (_: Exception) {
            GitHubDiagnosticsCredentials.recordAttempt(context, next, "GitHub upload unavailable; saved locally")
        }
    }
}

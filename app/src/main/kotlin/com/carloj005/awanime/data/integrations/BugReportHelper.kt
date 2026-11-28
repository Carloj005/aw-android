package com.carloj005.awanime.data.integrations

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.carloj005.awanime.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

enum class ReportKind(val label: String, val githubLabel: String, val apiKind: String, val titlePrefix: String) {
    BUG("Bug", "bug", "bug", "[bug] "),
    FEATURE("Funzionalità", "enhancement", "feature", "[feature] "),
}

sealed interface BugReportSubmitResult {
    data class Success(val issueNumber: Int, val issueUrl: String?) : BugReportSubmitResult
    data object RateLimited : BugReportSubmitResult
    data class Unavailable(val message: String) : BugReportSubmitResult
}

/**
 * Submits anonymous bug reports and feature requests directly from the app (via the AW Anime
 * Cloudflare Worker relay -> GitHub Issues, requiring zero user accounts) with optional GitHub
 * web form, Android share sheet, and clipboard fallbacks.
 */
object BugReportHelper {

    private val json = Json { ignoreUnknownKeys = true }
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    suspend fun submitAnonymousReport(
        httpClient: OkHttpClient,
        kind: ReportKind,
        title: String,
        description: String,
        activeDomain: String,
        endpointUrl: String = BuildConfig.BUG_REPORT_ENDPOINT,
        versionName: String = BuildConfig.VERSION_NAME,
        versionCode: Int = BuildConfig.VERSION_CODE,
        androidRelease: String = Build.VERSION.RELEASE ?: "unknown",
        sdkInt: Int = Build.VERSION.SDK_INT,
        deviceLabel: String = "${Build.MANUFACTURER.orEmpty()} ${Build.MODEL.orEmpty()}".trim(),
    ): BugReportSubmitResult = withContext(Dispatchers.IO) {
        if (!endpointUrl.startsWith("https://")) {
            return@withContext BugReportSubmitResult.Unavailable("Endpoint segnalazioni non configurato.")
        }

        val payload = buildJsonObject {
            put("kind", kind.apiKind)
            put("title", title.trim().take(110))
            put("description", description.trim().take(2000))
            put("appVersion", versionName)
            put("versionCode", versionCode)
            put("androidRelease", androidRelease)
            put("sdkInt", sdkInt)
            put("device", deviceLabel)
            put("activeDomain", activeDomain)
        }.toString()

        val req = Request.Builder()
            .url(endpointUrl)
            .header("Content-Type", "application/json; charset=utf-8")
            .header("Accept", "application/json")
            .header("X-AW-Client", "aw-anime-android")
            .header("User-Agent", "AW-Anime/$versionName")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        runCatching {
            httpClient.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                val obj = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
                when {
                    resp.isSuccessful -> {
                        val number = (obj?.get("issueNumber") as? JsonPrimitive)?.intOrNull ?: 0
                        val url = (obj?.get("issueUrl") as? JsonPrimitive)?.content
                        BugReportSubmitResult.Success(issueNumber = number, issueUrl = url)
                    }
                    resp.code == 429 -> BugReportSubmitResult.RateLimited
                    resp.code == 503 -> BugReportSubmitResult.Unavailable(
                        "Invio diretto temporaneamente non attivo: usa 'Condividi' o 'GitHub'."
                    )
                    else -> BugReportSubmitResult.Unavailable(
                        "Invio non riuscito (HTTP ${resp.code}): prova con 'Condividi' o 'Copia'."
                    )
                }
            }
        }.getOrElse {
            BugReportSubmitResult.Unavailable("Connessione non disponibile: usa 'Copia' o 'Condividi'.")
        }
    }

    fun formatDiagnosticBody(
        description: String,
        activeDomain: String,
        versionName: String = BuildConfig.VERSION_NAME,
        versionCode: Int = BuildConfig.VERSION_CODE,
        androidRelease: String = Build.VERSION.RELEASE ?: "unknown",
        sdkInt: Int = Build.VERSION.SDK_INT,
        deviceLabel: String = "${Build.MANUFACTURER.orEmpty()} ${Build.MODEL.orEmpty()}".trim(),
    ): String {
        val cleanDesc = description.trim().ifBlank { "(Descrivi qui il problema o i passaggi per riprodurlo)" }
        return buildString {
            appendLine("### Descrizione")
            appendLine(cleanDesc)
            appendLine()
            appendLine("### Diagnostica dispositivo")
            appendLine("- **App**: AW Anime v$versionName (build $versionCode)")
            appendLine("- **Android**: $androidRelease (API $sdkInt)")
            if (deviceLabel.isNotBlank()) {
                appendLine("- **Dispositivo**: $deviceLabel")
            }
            appendLine("- **Dominio attivo**: $activeDomain")
        }.trim()
    }

    fun buildGitHubIssueUrl(
        kind: ReportKind,
        rawTitle: String,
        description: String,
        activeDomain: String,
        repo: String = BuildConfig.GITHUB_REPO,
        versionName: String = BuildConfig.VERSION_NAME,
        versionCode: Int = BuildConfig.VERSION_CODE,
        androidRelease: String = Build.VERSION.RELEASE ?: "unknown",
        sdkInt: Int = Build.VERSION.SDK_INT,
        deviceLabel: String = "${Build.MANUFACTURER.orEmpty()} ${Build.MODEL.orEmpty()}".trim(),
    ): String {
        val cleanTitle = rawTitle.trim().take(120)
        val prefixedTitle = if (cleanTitle.lowercase().startsWith(kind.titlePrefix.trim().lowercase())) {
            cleanTitle
        } else {
            "${kind.titlePrefix}$cleanTitle"
        }
        val body = formatDiagnosticBody(
            description = description,
            activeDomain = activeDomain,
            versionName = versionName,
            versionCode = versionCode,
            androidRelease = androidRelease,
            sdkInt = sdkInt,
            deviceLabel = deviceLabel,
        )
        val encTitle = URLEncoder.encode(prefixedTitle, StandardCharsets.UTF_8.name())
        val encBody = URLEncoder.encode(body, StandardCharsets.UTF_8.name())
        val encLabel = URLEncoder.encode(kind.githubLabel, StandardCharsets.UTF_8.name())
        return "https://github.com/$repo/issues/new?title=$encTitle&labels=$encLabel&body=$encBody"
    }

    fun openGitHubIssueForm(
        context: Context,
        kind: ReportKind,
        title: String,
        description: String,
        activeDomain: String,
    ): Boolean {
        val url = buildGitHubIssueUrl(
            kind = kind,
            rawTitle = title,
            description = description,
            activeDomain = activeDomain,
        )
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    fun shareReport(
        context: Context,
        kind: ReportKind,
        title: String,
        description: String,
        activeDomain: String,
    ): Boolean {
        val fullText = "${kind.titlePrefix}${title.trim()}\n\n" + formatDiagnosticBody(
            description = description,
            activeDomain = activeDomain,
        )
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "${kind.titlePrefix}${title.trim()}")
            putExtra(Intent.EXTRA_TEXT, fullText)
        }
        return runCatching {
            context.startActivity(Intent.createChooser(sendIntent, "Invia segnalazione AW Anime"))
            true
        }.getOrDefault(false)
    }

    fun copyReportToClipboard(
        context: Context,
        kind: ReportKind,
        title: String,
        description: String,
        activeDomain: String,
    ): Boolean {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return false
        val fullText = "${kind.titlePrefix}${title.trim()}\n\n" + formatDiagnosticBody(
            description = description,
            activeDomain = activeDomain,
        )
        return runCatching {
            clipboard.setPrimaryClip(ClipData.newPlainText("AW Anime Bug Report", fullText))
            true
        }.getOrDefault(false)
    }
}

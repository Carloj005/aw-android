package com.carloj005.awanime.data.integrations

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.carloj005.awanime.BuildConfig
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

enum class ReportKind(val label: String, val githubLabel: String, val titlePrefix: String) {
    BUG("Bug", "bug", "[bug] "),
    FEATURE("Funzionalità", "enhancement", "[feature] "),
}

/**
 * Builds pre-filled GitHub Issue URLs and copyable diagnostic reports from within the app
 * without requiring any credentials or collecting personal user data.
 */
object BugReportHelper {

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

package com.mercury.yktwx.ui.components

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

internal fun updatePublishedTime(instant: Instant?, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String? =
    instant?.let { DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(locale).withZone(zone).format(it) }

/** The dialog already shows the version and publication time above the notes. */
internal fun updateNotesForDisplay(source: String, versionName: String): String {
    val lines = source.lines()
    val first = lines.indexOfFirst { it.isNotBlank() }
    if (first < 0) return source
    val version = "v?${Regex.escape(versionName)}"
    val heading = Regex("""#{1,6}\s+(?:$version|\[$version]\([^\r\n)]+\))(?:\s+\(\d{4}-\d{2}-\d{2}\))?\s*""")
    if (!heading.matches(lines[first].trim())) return source
    return lines.drop(first + 1).dropWhile { it.isBlank() }.joinToString("\n")
}

/** Small bounded Markdown subset. HTML/images remain text; links are HTTP(S) only. */
internal fun updateMarkdown(source: String, openLink: ((String) -> Unit)? = null): AnnotatedString = buildAnnotatedString {
    var code = false
    val inline = Regex("\\*\\*(.+?)\\*\\*|(?<!\\*)\\*([^*]+)\\*|`([^`]+)`|(?<!!)\\[([^\\]]+)\\]\\(([^)]+)\\)")
    source.lines().forEachIndexed { index, line ->
        if (index > 0) append("\n")
        if (line.trimStart().startsWith("```")) { code = !code; return@forEachIndexed }
        val heading = Regex("^(#{1,6})\\s+(.+)$").matchEntire(line)
        val normalized = when {
            code -> line
            heading != null -> heading.groupValues[2]
            line.startsWith("> ") -> "│ " + line.drop(2)
            line.matches(Regex("\\s*[-*+]\\s+.*")) -> line.replaceFirst(Regex("^\\s*[-*+]\\s+"), "• ")
            line.trim() in listOf("---", "***", "___") -> "────────────"
            else -> line
        }
        val start = length
        if (code) { append(normalized); addStyle(SpanStyle(fontFamily = FontFamily.Monospace), start, length) }
        else {
            var position = 0
            inline.findAll(normalized).forEach { match ->
                append(normalized.substring(position, match.range.first))
                val tokenStart = length
                when {
                    match.groupValues[1].isNotEmpty() -> { append(match.groupValues[1]); addStyle(SpanStyle(fontWeight = FontWeight.Bold), tokenStart, length) }
                    match.groupValues[2].isNotEmpty() -> { append(match.groupValues[2]); addStyle(SpanStyle(fontStyle = FontStyle.Italic), tokenStart, length) }
                    match.groupValues[3].isNotEmpty() -> { append(match.groupValues[3]); addStyle(SpanStyle(fontFamily = FontFamily.Monospace), tokenStart, length) }
                    else -> {
                        val url = match.groupValues[5]
                        val safe = runCatching { URI(url).let { it.scheme in listOf("https", "http") && !it.host.isNullOrBlank() && it.rawUserInfo == null } }.getOrDefault(false)
                        append(match.groupValues[4])
                        if (safe) addLink(if (openLink == null) LinkAnnotation.Url(url) else
                            LinkAnnotation.Url(url, linkInteractionListener = { openLink(url) }), tokenStart, length)
                    }
                }
                position = match.range.last + 1
            }
            append(normalized.substring(position))
        }
        if (heading != null) addStyle(SpanStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp), start, length)
    }
}

@Composable
internal fun UpdateMarkdown(source: String, modifier: Modifier = Modifier, onLinkError: (String) -> Unit = {}) {
    val context = LocalContext.current
    SelectionContainer {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            updateMarkdownBlocks(source).forEach { block ->
                Text(updateMarkdown(block) { url ->
                    try { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
                    catch (_: Exception) { onLinkError("无法打开更新说明中的链接") }
                }, fontSize = 13.sp, lineHeight = 21.sp,
                    color = if (block.trimStart().startsWith("#")) MiuixTheme.colorScheme.onSurface
                        else MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
    }
}

internal fun updateMarkdownBlocks(source: String): List<String> = buildList {
    val lines = mutableListOf<String>()
    var fenced = false
    source.lines().forEach { line ->
        if (line.trimStart().startsWith("```")) fenced = !fenced
        if (line.isBlank() && !fenced) {
            if (lines.isNotEmpty()) { add(lines.joinToString("\n")); lines.clear() }
        } else lines += line
    }
    if (lines.isNotEmpty()) add(lines.joinToString("\n"))
}

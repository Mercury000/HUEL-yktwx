package com.mercury.yktwx.ui.pages

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mercury.yktwx.R
import com.mikepenz.aboutlibraries.Libs
import com.mikepenz.aboutlibraries.util.withJson
import com.mercury.yktwx.ui.components.SectionTitle
import com.mercury.yktwx.ui.components.ContentPlaceholder
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private data class ReferenceProject(
    val id: Int,
    val name: String,
    val url: String,
    val developers: String,
    val licenses: String?,
)

@Composable
fun ReferencesPage(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val context = LocalContext.current
    val applicationContext = context.applicationContext
    val unknownDeveloper = stringResource(R.string.unknown_generic)
    val referenceProjects by produceState(ReferenceProjects.current, applicationContext) {
        value = ReferenceProjects.load(applicationContext)
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding() + 12.dp,
            bottom = contentPadding.calculateBottomPadding() + 12.dp,
            start = 16.dp,
            end = 16.dp,
        ),
    ) {
        item(key = "description", contentType = "description") {
            Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                Text(
                    text = stringResource(R.string.about_references_description),
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
                    fontSize = MiuixTheme.textStyles.body2.fontSize,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        item(key = "heading", contentType = "heading") {
            SectionTitle(stringResource(R.string.about_reference_projects))
        }
        val projects = referenceProjects
        if (projects == null) {
            items(5, key = { "loading-$it" }, contentType = { "loading" }) { index ->
                ReferenceRowSurface(index, 5) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp)) {
                        ContentPlaceholder(Modifier.fillMaxWidth(.7f).height(20.dp))
                        Spacer(Modifier.height(8.dp))
                        ContentPlaceholder(Modifier.fillMaxWidth(.45f).height(14.dp))
                    }
                }
            }
        } else {
            // Each library is measured only when needed; adjacent rows retain one card outline.
            itemsIndexed(projects, key = { _, project -> project.id }, contentType = { _, _ -> "project" }) { index, project ->
                ReferenceRowSurface(index, projects.size) {
                    BasicComponent(
                        title = "${project.developers.ifBlank { unknownDeveloper }}/${project.name}",
                        summary = project.licenses ?: "Unknown",
                        onClick = { context.openLink(project.url) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ReferenceRowSurface(index: Int, count: Int, content: @Composable () -> Unit) {
    val first = index == 0
    val last = index == count - 1
    val colors = CardDefaults.defaultColors()
    val modifier = if (first || last) {
        val radius = CardDefaults.CornerRadius
        Modifier.squircleSurface(
            color = colors.color,
            topStart = if (first) radius else 0.dp,
            topEnd = if (first) radius else 0.dp,
            bottomStart = if (last) radius else 0.dp,
            bottomEnd = if (last) radius else 0.dp,
        )
    } else {
        Modifier
    }
    Surface(
        modifier = Modifier.fillMaxWidth().then(modifier),
        color = if (first || last) Color.Transparent else colors.color,
        contentColor = colors.contentColor,
        content = content,
    )
}

/** APK metadata is immutable; cache values, without retaining an Activity or localized fallback. */
private object ReferenceProjects {
    private val mutex = Mutex()
    @Volatile
    private var cached: List<ReferenceProject>? = null
    val current: List<ReferenceProject>? get() = cached

    suspend fun load(context: Context): List<ReferenceProject> = mutex.withLock {
        cached ?: withContext(Dispatchers.IO) {
            try {
                read(context)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }?.also { cached = it } ?: emptyList()
    }

    private fun read(context: Context): List<ReferenceProject> =
            Libs.Builder()
                .withJson(context, R.raw.aboutlibraries)
                .build()
                .libraries
                .mapIndexedNotNull { index, library ->
                    val url = library.website?.takeIf { it.isNotBlank() }
                        ?: library.scm?.url?.takeIf { it.isNotBlank() }
                    url?.let {
                        ReferenceProject(
                            id = index,
                            name = library.name,
                            url = it,
                            developers = library.developers
                                .mapNotNull { developer -> developer.name?.takeIf(String::isNotBlank) }
                                .joinToString(),
                            licenses = library.licenses
                                .map { license -> license.name }
                                .filter(String::isNotBlank)
                                .sortedWith(String.CASE_INSENSITIVE_ORDER)
                                .joinToString()
                                .takeIf(String::isNotBlank),
                        )
                    }
                }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
}

/**
 * Opens a link without letting a missing browser take the app down: with Android 11
 * package visibility an unresolvable VIEW intent throws instead of doing nothing.
 */
private fun Context.openLink(url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, R.string.link_open_failed, Toast.LENGTH_SHORT).show()
    } catch (_: SecurityException) {
        Toast.makeText(this, R.string.link_open_failed, Toast.LENGTH_SHORT).show()
    }
}

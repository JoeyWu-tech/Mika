package com.wangzan101.mika.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.sandbox.SliceKind
import com.wangzan101.mika.sandbox.StorageUsage
import com.wangzan101.mika.sandbox.StorageUsageReader
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.EmptyNote
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.PageScaffold
import com.wangzan101.mika.ui.components.RowValue
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 容器的存储:一张饼图(运行环境 / 技能 / 各对话的工作区),下面按对话列出工作区占用,可以单独清理或全部清空。
 * 运行环境和技能随 App 自带,不在这里删——清了会重新解包,腾不出多少地方。
 */
@Composable
fun ContainerUsageScreen(context: Context, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val scope = rememberCoroutineScope()
    var usage by remember { mutableStateOf<StorageUsage?>(null) }
    var reload by remember { mutableStateOf(0) }
    produceState(Unit, reload) {
        usage = withContext(Dispatchers.IO) { StorageUsageReader.read(context) }
    }
    var confirmClearAll by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<Pair<String, String>?>(null) }

    PageScaffold(ContainerUsageCopy.TITLE, onBack) {
        val u = usage ?: return@PageScaffold
        if (u.total == 0L) {
            EmptyNote(ContainerUsageCopy.EMPTY)
            return@PageScaffold
        }
        PieCard(u)
        GroupLabel(ContainerUsageCopy.WORKSPACES)
        if (u.workspaces.isEmpty()) {
            EmptyNote(ContainerUsageCopy.NO_WORKSPACE)
        } else {
            GroupCard {
                u.workspaces.forEachIndexed { i, w ->
                    ListRow(null, w.title, first = i == 0, onClick = { confirmDelete = w.id to w.title }) {
                        RowValue(ContainerUsageCopy.size(w.bytes))
                        Text(
                            ContainerUsageCopy.DELETE, Modifier.padding(start = 12.dp).testTag("usage-delete-" + w.id),
                            style = MikaType.Value, color = MikaTheme.colors.bad,
                        )
                    }
                }
            }
            MikaButton(
                ContainerUsageCopy.CLEAR_ALL, { confirmClearAll = true },
                Modifier.fillMaxWidth().padding(top = 8.dp).testTag("usage-clear-all"),
                kind = ButtonKind.Secondary, size = ButtonSize.Medium,
            )
        }
        FooterNote(ContainerUsageCopy.NOTE)
    }

    confirmDelete?.let { (id, title) ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(ContainerUsageCopy.DELETE_TITLE) },
            text = { Text(ContainerUsageCopy.deleteBody(title)) },
            confirmButton = {
                TextButton({
                    confirmDelete = null
                    scope.launch {
                        withContext(Dispatchers.IO) { StorageUsageReader.deleteWorkspace(context, id) }
                        reload++
                    }
                }) { Text(ContainerUsageCopy.DELETE) }
            },
            dismissButton = { TextButton({ confirmDelete = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (confirmClearAll) AlertDialog(
        onDismissRequest = { confirmClearAll = false },
        title = { Text(ContainerUsageCopy.CLEAR_ALL_TITLE) },
        text = { Text(ContainerUsageCopy.CLEAR_ALL_BODY) },
        confirmButton = {
            TextButton({
                confirmClearAll = false
                scope.launch {
                    withContext(Dispatchers.IO) { StorageUsageReader.deleteAllWorkspaces(context) }
                    reload++
                }
            }) { Text(stringResource(R.string.clear)) }
        },
        dismissButton = { TextButton({ confirmClearAll = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

/** 饼图 + 图例:每块占多少、一共多大。 */
@Composable
private fun PieCard(u: StorageUsage) {
    val c = MikaTheme.colors
    val palette = listOf(c.accent, c.accentSoft, c.ink3)
    GroupCard {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(120.dp).testTag("usage-pie")) {
                var start = -90f
                u.slices().forEachIndexed { i, s ->
                    val sweep = 360f * s.fraction(u.total)
                    // 留一点缝,几块挨着也看得出边界
                    drawArc(palette[i % palette.size], start, (sweep - 1f).coerceAtLeast(0.5f), useCenter = true)
                    start += sweep
                }
            }
            Column(Modifier.padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                u.slices().forEachIndexed { i, s ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Canvas(Modifier.size(10.dp)) { drawCircle(palette[i % palette.size]) }
                        Text(
                            ContainerUsageCopy.slice(s.kind) + " · " + ContainerUsageCopy.size(s.bytes) + " · " + percent(s.fraction(u.total)),
                            Modifier.padding(start = 8.dp), style = MikaType.Caption, color = c.ink2,
                        )
                    }
                }
                Text(
                    ContainerUsageCopy.TOTAL + " · " + ContainerUsageCopy.size(u.total),
                    Modifier.padding(top = 2.dp), style = MikaType.Caption, color = c.ink,
                )
            }
        }
    }
}

private fun percent(f: Float): String = ((f * 100).toInt()).toString() + "%"

/** 页面上全部文字。 */
object ContainerUsageCopy {
    val TITLE get() = tr(R.string.container_usage_title)
    val EMPTY get() = tr(R.string.container_usage_empty)
    val NO_WORKSPACE get() = tr(R.string.container_usage_no_workspace)
    val WORKSPACES get() = tr(R.string.container_usage_workspaces)
    val TOTAL get() = tr(R.string.container_usage_total)
    val DELETE get() = tr(R.string.container_usage_delete)
    val DELETE_TITLE get() = tr(R.string.container_usage_delete_title)
    val CLEAR_ALL get() = tr(R.string.container_usage_clear_all)
    val CLEAR_ALL_TITLE get() = tr(R.string.container_usage_clear_all_title)
    val CLEAR_ALL_BODY get() = tr(R.string.container_usage_clear_all_body)
    val NOTE get() = tr(R.string.container_usage_note)

    fun deleteBody(title: String) = tr(R.string.container_usage_delete_body, title)

    fun slice(kind: SliceKind): String = tr(
        when (kind) {
            SliceKind.Runtime -> R.string.container_usage_runtime
            SliceKind.Skills -> R.string.container_usage_skills
            SliceKind.Workspaces -> R.string.container_usage_slices_workspaces
        },
    )

    /** 字节数按人看得懂的单位写。 */
    fun size(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes.toDouble() / (1L shl 30))
        bytes >= 1L shl 20 -> "%.1f MB".format(bytes.toDouble() / (1L shl 20))
        bytes >= 1L shl 10 -> "%.0f KB".format(bytes.toDouble() / (1L shl 10))
        else -> bytes.toString() + " B"
    }
}

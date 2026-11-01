package com.wangzan101.mika.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.core.cap.sandbox.SandboxStatus
import com.wangzan101.mika.cap.sandbox.RootfsInstaller
import com.wangzan101.mika.cap.sandbox.RuntimePreparer
import com.wangzan101.mika.cap.sandbox.SandboxState
import kotlinx.coroutines.launch
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.components.TileTone
import com.wangzan101.mika.ui.components.MikaSwitch
import com.wangzan101.mika.ui.theme.MikaType
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.ui.components.RowValue
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.ui.components.ValueTone

/**
 * 设置 › 文件与计算环境 的文案(纯函数,便于测试)。运行环境对用户是看不见的实现细节(产品决定):
 * 字面上只说「脚本」「处理文件」「运行环境」,不出现沙箱、Linux、PRoot、Alpine 等技术名词。
 */
object SandboxCopy {
    val NETWORK_TITLE: String get() = tr(R.string.sandbox_network)
    val NETWORK_SUBTITLE: String get() = tr(R.string.sandbox_network_sub)
    val NETWORK_EXPLAIN: String get() = tr(R.string.sandbox_network_explain)
    val STORAGE_TITLE: String get() = tr(R.string.sandbox_storage)
    val STORAGE_GRANT: String get() = tr(R.string.sandbox_storage_grant)
    val STORAGE_SUB_ON: String get() = tr(R.string.sandbox_storage_sub_on)
    val STORAGE_SUB_OFF: String get() = tr(R.string.sandbox_storage_sub_off)
    val STORAGE_GRANT_SUB: String get() = tr(R.string.sandbox_storage_grant_sub)
    val PROXY_TITLE: String get() = tr(R.string.sandbox_proxy)
    val PROXY_HINT: String get() = tr(R.string.sandbox_proxy_hint)
    val PROXY_NONE: String get() = tr(R.string.sandbox_proxy_none)
    val PROXY_SET: String get() = tr(R.string.sandbox_proxy_set)
    val STORAGE_USAGE_TITLE: String get() = tr(R.string.sandbox_storage_usage)
    val STORAGE_USAGE_SUB: String get() = tr(R.string.sandbox_storage_usage_sub)
    fun proxySub(proxy: String): String = if (proxy.isBlank()) tr(R.string.sandbox_proxy_sub) else proxy
    val STORAGE_PATHS_TITLE: String get() = tr(R.string.sandbox_storage_paths)
    val STORAGE_PATHS_HINT: String get() = tr(R.string.sandbox_storage_paths_hint)
    val STORAGE_PATHS_ALL: String get() = tr(R.string.sandbox_storage_paths_all)
    val STORAGE_PATHS_CUSTOM_HINT: String get() = tr(R.string.sandbox_storage_paths_custom_hint)
    val STORAGE_PATHS_ADD: String get() = tr(R.string.sandbox_storage_paths_add)
    val STORAGE_PATHS_INVALID: String get() = tr(R.string.sandbox_storage_paths_invalid)
    fun storagePathsSub(paths: Set<String>): String =
        if (paths.isEmpty()) tr(R.string.sandbox_storage_paths_all) else paths.sorted().joinToString(tr(R.string.list_sep))
    val ISOLATION_UNAVAILABLE: String get() = tr(R.string.sandbox_isolation_unavailable)
    val RESET_TITLE: String get() = tr(R.string.sandbox_reset_title)
    val RESET_BODY: String get() = tr(R.string.sandbox_reset_body)

    /** 「重置运行环境」下面的一行:平时说明会怎样,准备中或准备失败时如实说。 */
    fun resetSubtitle(s: SandboxStatus): String = when (s) {
        is SandboxStatus.Installing -> tr(R.string.runtime_preparing)
        is SandboxStatus.Broken -> tr(R.string.sandbox_broken, s.reason)
        else -> tr(R.string.sandbox_reset_sub)
    }
}

/** 接上真实状态的「文件与计算环境」页内容:联网开关读写 [SandboxState];重置后马上在后台重新准备。 */
@Composable
fun AdvancedSettings(onOpenStorage: () -> Unit = {}) {
    val context = LocalContext.current
    val state = remember { SandboxState.get(context) }
    val status by state.status.collectAsState()
    var network by remember { mutableStateOf(state.networkAllowed) }
    val flags = remember { FeatureFlags.get(context) }
    // 从系统设置回来时重读:刚授权就该变成开关
    var storageGranted by remember { mutableStateOf(Environment.isExternalStorageManager()) }
    var storageOn by remember { mutableStateOf(flags.containerStorage()) }
    var proxy by remember { mutableStateOf(flags.containerProxy()) }
    var editingProxy by remember { mutableStateOf(false) }
    var storagePaths by remember { mutableStateOf(flags.containerStoragePaths()) }
    var choosingPaths by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        storageGranted = Environment.isExternalStorageManager()
        onPauseOrDispose { }
    }
    val scope = rememberCoroutineScope()
    AdvancedSection(
        status = status, networkAllowed = network, isolationIssue = state.isolationIssue != null,
        onNetworkChange = { state.networkAllowed = it; network = it },
        storageGranted = storageGranted, storageOn = storageOn,
        onStorageChange = { flags.setContainerStorage(it); storageOn = it },
        onStorageGrant = {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + context.packageName)),
                )
            }
        },
        proxy = proxy, onProxy = { editingProxy = true }, onOpenStorage = onOpenStorage,
        storagePaths = storagePaths, onStoragePaths = { choosingPaths = true },
        onReset = {
            scope.launch {
                RootfsInstaller.create(context).reset()
                RuntimePreparer.ensure(context, retryFailed = true)
            }
        },
    )
    if (editingProxy) ProxyDialog(
        initial = proxy,
        onDismiss = { editingProxy = false },
        onSave = { flags.setContainerProxy(it); proxy = it.trim(); editingProxy = false },
    )
    if (choosingPaths) StoragePathsDialog(
        initial = storagePaths,
        onDismiss = { choosingPaths = false },
        onSave = { flags.setContainerStoragePaths(it); storagePaths = it; choosingPaths = false },
    )
}

/** 改容器的网络代理:一个输入框,空着表示不用。 */
@Composable
private fun ProxyDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(SandboxCopy.PROXY_TITLE) },
        text = {
            Column {
                Text(SandboxCopy.PROXY_HINT, style = MikaType.Caption, color = MikaTheme.colors.ink3)
                OutlinedTextField(
                    text, { text = it }, Modifier.fillMaxWidth().padding(top = 8.dp).testTag("sandbox-proxy-input"),
                    singleLine = true, placeholder = { Text("http://127.0.0.1:7890") },
                )
            }
        },
        confirmButton = { TextButton({ onSave(text) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** 不带状态的「文件与计算环境」页内容,便于测试。不支持的设备整组不显示。 */
@Composable
fun AdvancedSection(
    status: SandboxStatus,
    networkAllowed: Boolean,
    isolationIssue: Boolean,
    onNetworkChange: (Boolean) -> Unit,
    onReset: () -> Unit,
    /** 用户在系统设置里给过「所有文件访问权限」。 */
    storageGranted: Boolean = false,
    /** 设置里愿不愿意把手机存储挂进容器(给了权限才有意义)。 */
    storageOn: Boolean = false,
    onStorageChange: (Boolean) -> Unit = {},
    /** 没给权限时点这一行:跳到系统的「所有文件访问权限」页,由用户自己开。 */
    onStorageGrant: () -> Unit = {},
    /** 容器的网络代理,空串表示不用。 */
    proxy: String = "",
    onProxy: () -> Unit = {},
    onOpenStorage: () -> Unit = {},
    /** 挂进容器的目录(相对共享存储);空集表示整个存储。 */
    storagePaths: Set<String> = emptySet(),
    onStoragePaths: () -> Unit = {},
) {
    if (status is SandboxStatus.Unsupported) return
    var confirming by remember { mutableStateOf(false) }

    GroupCard {
        ListRow(MikaIcons.Link, SandboxCopy.NETWORK_TITLE, SandboxCopy.NETWORK_SUBTITLE, first = true) {
            MikaSwitch(networkAllowed, onNetworkChange, Modifier.testTag("sandbox-network"))
        }
        Note(SandboxCopy.NETWORK_EXPLAIN, if (networkAllowed) MikaTheme.colors.warn else MikaTheme.colors.ink3)
        // 代理只给容器里的命令用,不影响 Mika 自己调模型
        ListRow(MikaIcons.Globe, SandboxCopy.PROXY_TITLE, SandboxCopy.proxySub(proxy), onClick = onProxy) {
            RowValue(if (proxy.isBlank()) SandboxCopy.PROXY_NONE else SandboxCopy.PROXY_SET, ValueTone.Link)
        }
        ListRow(MikaIcons.Doc, SandboxCopy.STORAGE_USAGE_TITLE, SandboxCopy.STORAGE_USAGE_SUB, onClick = onOpenStorage) {
            RowChevron()
        }
        if (isolationIssue) Note(SandboxCopy.ISOLATION_UNAVAILABLE, MikaTheme.colors.bad)
        // 手机存储:权限要用户自己去系统设置给,Mika 不主动申请
        ListRow(
            MikaIcons.Phone, SandboxCopy.STORAGE_TITLE,
            if (storageGranted) (if (storageOn) SandboxCopy.STORAGE_SUB_ON else SandboxCopy.STORAGE_SUB_OFF) else SandboxCopy.STORAGE_GRANT_SUB,
            onClick = if (storageGranted) ({ onStorageChange(!storageOn) }) else onStorageGrant,
        ) {
            if (storageGranted) MikaSwitch(storageOn, onStorageChange, Modifier.testTag("sandbox-storage"))
            else RowValue(SandboxCopy.STORAGE_GRANT, ValueTone.Link)
        }
        // 只挂哪几个目录:没选就是整个共享存储。没授权时这一行没有意义,不显示。
        if (storageGranted) ListRow(MikaIcons.Doc, SandboxCopy.STORAGE_PATHS_TITLE, SandboxCopy.storagePathsSub(storagePaths), onClick = onStoragePaths) {
            RowChevron()
        }
        ListRow(MikaIcons.Close, tr(R.string.sandbox_reset), SandboxCopy.resetSubtitle(status), titleColor = MikaTheme.colors.bad, tone = TileTone.Bad,
            onClick = if (status is SandboxStatus.Installing) null else ({ confirming = true }))
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(SandboxCopy.RESET_TITLE) },
            text = { Text(SandboxCopy.RESET_BODY) },
            confirmButton = { TextButton({ confirming = false; onReset() }) { Text(tr(R.string.reset), color = MikaTheme.colors.bad) } },
            dismissButton = { TextButton({ confirming = false }) { Text(tr(R.string.cancel)) } },
        )
    }
}

@Composable
private fun Note(text: String, color: Color) {
    Text(text, Modifier.fillMaxWidth().padding(start = 60.dp, end = 16.dp, bottom = 10.dp), style = MikaType.Hint, color = color)
}

/** 挂哪几个目录:标准目录可勾,也能自己填一个(相对共享存储的路径,如 Projects/2026)。一个都不加就是整个存储。 */
@Composable
private fun StoragePathsDialog(initial: Set<String>, onDismiss: () -> Unit, onSave: (Set<String>) -> Unit) {
    var picked by remember { mutableStateOf(initial) }
    var custom by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf(false) }
    fun add() {
        val v = custom.trim().trimEnd('/')
        // 只认相对共享存储的路径:不要开头的 /,也不要 .. 绕出去
        if (v.isEmpty() || v.startsWith("/") || v.split('/').any { it == ".." }) {
            problem = true
            return
        }
        picked = picked + v
        custom = ""
        problem = false
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(SandboxCopy.STORAGE_PATHS_TITLE) },
        text = {
            Column {
                Text(SandboxCopy.STORAGE_PATHS_HINT, style = MikaType.Caption, color = MikaTheme.colors.ink3)
                androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(picked.isEmpty(), { picked = emptySet() }, Modifier.testTag("storage-path-whole"))
                    Text(SandboxCopy.STORAGE_PATHS_ALL, Modifier.padding(start = 4.dp), style = MikaType.Body)
                }
                // 标准目录和自己填的排在一起;自己填的也能取消
                (STORAGE_DIRS + picked.filter { it !in STORAGE_DIRS }.sorted()).forEach { dir ->
                    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(dir in picked, { on: Boolean -> picked = if (on) picked + dir else picked - dir }, Modifier.testTag("storage-path-$dir"))
                        Text(dir, Modifier.padding(start = 4.dp), style = MikaType.Body)
                    }
                }
                OutlinedTextField(
                    custom, { custom = it; problem = false },
                    Modifier.fillMaxWidth().padding(top = 8.dp).testTag("storage-path-custom"),
                    singleLine = true, placeholder = { Text(SandboxCopy.STORAGE_PATHS_CUSTOM_HINT) },
                    trailingIcon = { TextButton({ add() }) { Text(SandboxCopy.STORAGE_PATHS_ADD) } },
                )
                if (problem) {
                    Text(SandboxCopy.STORAGE_PATHS_INVALID, Modifier.padding(top = 4.dp), style = MikaType.Hint, color = MikaTheme.colors.bad)
                }
            }
        },
        confirmButton = { TextButton({ onSave(picked) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** 共享存储里常见的几个目录;别的文件夹自己填。 */
private val STORAGE_DIRS = listOf("Download", "Documents", "Pictures", "DCIM", "Music", "Movies")

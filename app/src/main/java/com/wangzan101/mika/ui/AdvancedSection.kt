package com.wangzan101.mika.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
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
fun AdvancedSettings() {
    val context = LocalContext.current
    val state = remember { SandboxState.get(context) }
    val status by state.status.collectAsState()
    var network by remember { mutableStateOf(state.networkAllowed) }
    val flags = remember { FeatureFlags.get(context) }
    // 从系统设置回来时重读:刚授权就该变成开关
    var storageGranted by remember { mutableStateOf(Environment.isExternalStorageManager()) }
    var storageOn by remember { mutableStateOf(flags.containerStorage()) }
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
        onReset = {
            scope.launch {
                RootfsInstaller.create(context).reset()
                RuntimePreparer.ensure(context, retryFailed = true)
            }
        },
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
) {
    if (status is SandboxStatus.Unsupported) return
    var confirming by remember { mutableStateOf(false) }

    GroupCard {
        ListRow(MikaIcons.Link, SandboxCopy.NETWORK_TITLE, SandboxCopy.NETWORK_SUBTITLE, first = true) {
            MikaSwitch(networkAllowed, onNetworkChange, Modifier.testTag("sandbox-network"))
        }
        Note(SandboxCopy.NETWORK_EXPLAIN, if (networkAllowed) MikaTheme.colors.warn else MikaTheme.colors.ink3)
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

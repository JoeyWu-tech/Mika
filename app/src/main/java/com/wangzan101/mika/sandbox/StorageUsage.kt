package com.wangzan101.mika.sandbox

import android.content.Context
import com.wangzan101.mika.cap.sandbox.SandboxDirs
import com.wangzan101.mika.chat.ConversationStore
import java.io.File

/** 一个对话的工作区占了多少;[title] 是对话的标题(没有标题时是它的原话,再没有就是编号)。 */
data class WorkspaceUsage(val id: String, val title: String, val bytes: Long)

/**
 * 容器的占用:运行环境本体、技能、各对话的工作区。工作区是大头,也是唯一能安全清理的部分
 * ——运行环境和技能随 App 自带,清理了就重新解包。
 */
data class StorageUsage(val rootfs: Long, val skills: Long, val workspaces: List<WorkspaceUsage>) {
    val workspacesTotal: Long get() = workspaces.sumOf { it.bytes }
    val total: Long get() = rootfs + skills + workspacesTotal

    /** 饼图的三块:[total] 为 0 时全给 0(不画)。 */
    fun slices(): List<Slice> = listOf(
        Slice(SliceKind.Runtime, rootfs),
        Slice(SliceKind.Skills, skills),
        Slice(SliceKind.Workspaces, workspacesTotal),
    ).filter { it.bytes > 0 || total == 0L }
}

/** 饼图上的一块:哪一类、多少字节、占多少(0–1)。 */
enum class SliceKind { Runtime, Skills, Workspaces }

data class Slice(val kind: SliceKind, val bytes: Long) {
    fun fraction(total: Long): Float = if (total <= 0L) 0f else (bytes.toDouble() / total).toFloat()
}

/** 读一遍磁盘上的目录大小。要在后台线程调:工作区可能有很多文件。 */
object StorageUsageReader {
    fun read(context: Context): StorageUsage {
        val dirs = SandboxDirs(context)
        val store = ConversationStore.shared(context)
        val workspaces = dirs.work.listFiles().orEmpty().filter { it.isDirectory }.map { dir ->
            val id = conversationIdOf(dir.name)
            WorkspaceUsage(id, titleOf(store, id), size(dir))
        }.sortedByDescending { it.bytes }
        return StorageUsage(size(dirs.rootfs), size(dirs.skills), workspaces)
    }

    /** 删掉一个对话的工作区;删不掉返回 false。 */
    fun deleteWorkspace(context: Context, conversationId: String): Boolean {
        val dir = SandboxDirs(context).workspace(conversationId)
        return runCatching { dir.deleteRecursively() }.getOrDefault(false)
    }

    /** 清空所有工作区(运行环境和技能不动)。 */
    fun deleteAllWorkspaces(context: Context) {
        SandboxDirs(context).work.listFiles().orEmpty().forEach { runCatching { it.deleteRecursively() } }
    }

    fun size(f: File): Long = when {
        !f.exists() -> 0L
        f.isFile -> f.length()
        else -> f.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    /** 目录名回到对话编号:简单编号是 `c-<ID>`,别的是一段哈希(认不回来时用目录名本身)。 */
    private fun conversationIdOf(dirName: String): String =
        if (dirName.startsWith("c-")) dirName.removePrefix("c-") else dirName

    private fun titleOf(store: ConversationStore, id: String): String =
        store.title(id) ?: store.turns(id).firstOrNull()?.userText?.take(40) ?: id
}

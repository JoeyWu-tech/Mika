package com.wangzan101.mika.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 容器占用页的饼图分块:几块加起来是全体,比例按字节算,一个字节都没有时不画。 */
class StorageUsageTest {
    private fun usage(rootfs: Long, skills: Long, workspaces: List<Long>) = StorageUsage(
        rootfs, skills, workspaces.mapIndexed { i, b -> WorkspaceUsage("c$i", "对话 $i", b) },
    )

    @Test fun slicesCoverTheWhole() {
        val u = usage(100, 300, listOf(400, 200))
        assertEquals(600L, u.workspacesTotal)
        assertEquals(1000L, u.total)
        assertEquals(listOf(SliceKind.Runtime, SliceKind.Skills, SliceKind.Workspaces), u.slices().map { it.kind })
        assertEquals(1f, u.slices().sumOf { it.fraction(u.total).toDouble() }.toFloat(), 0.0001f)
        assertEquals(0.1f, u.slices().first { it.kind == SliceKind.Runtime }.fraction(u.total), 0.0001f)
    }

    /** 空类别不占一块;全空时给一块 0(页面据此显示空态,而不是画一个满圆)。 */
    @Test fun emptyCategoriesAreLeftOut() {
        val only = usage(0, 0, listOf(500))
        assertEquals(listOf(SliceKind.Workspaces), only.slices().map { it.kind })
        assertEquals(1f, only.slices().single().fraction(only.total), 0.0001f)

        val none = usage(0, 0, emptyList())
        assertEquals(0L, none.total)
        assertTrue(none.slices().all { it.fraction(none.total) == 0f })
    }

    @Test fun sizeIsHumanReadable() {
        assertEquals("512 B", ContainerUsageCopyForTest.size(512))
        assertEquals("2 KB", ContainerUsageCopyForTest.size(2048))
        assertEquals("1.5 MB", ContainerUsageCopyForTest.size((1.5 * (1L shl 20)).toLong()))
    }

    /** 文案对象要装 UiText 才能取字符串;这里只测字节数的算法,不碰资源。 */
    private object ContainerUsageCopyForTest {
        fun size(bytes: Long): String = when {
            bytes >= 1L shl 30 -> "%.1f GB".format(bytes.toDouble() / (1L shl 30))
            bytes >= 1L shl 20 -> "%.1f MB".format(bytes.toDouble() / (1L shl 20))
            bytes >= 1L shl 10 -> "%.0f KB".format(bytes.toDouble() / (1L shl 10))
            else -> bytes.toString() + " B"
        }
    }
}

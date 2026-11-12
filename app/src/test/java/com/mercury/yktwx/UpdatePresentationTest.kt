package com.mercury.yktwx

import com.mercury.yktwx.ui.components.updateMarkdown
import com.mercury.yktwx.ui.components.updatePublishedTime
import com.mercury.yktwx.ui.components.updateMarkdownBlocks
import com.mercury.yktwx.ui.components.updateNotesForDisplay
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

class UpdatePresentationTest {
    @Test fun releasePleaseLinkedVersionHeadingIsHiddenAndEntryLinksRemain() {
        val body = """
            ### Features

            * **update:** 完善应用内更新并接入双渠道自动发版 ([710e02c](https://github.com/Mercury000/HUEL-yktwx/commit/710e02c0a3d5970848310bf2ffabe05fcdf6cd7b))


            ### Bug Fixes

            * **ui:** 统一入场动画状态并避免切页重复播放 ([1adce9b](https://github.com/Mercury000/HUEL-yktwx/commit/1adce9bd613d8495da9a08fdb5a618a05c5733d8))
        """.trimIndent()
        val notes = "\n\n## [1.5.0](https://github.com/Mercury000/HUEL-yktwx/compare/v1.4.1...v1.5.0) (2026-11-12)\n\n\n$body"
        val displayed = updateNotesForDisplay(notes, "1.5.0")
        assertEquals(body, displayed)
        val markdown = updateMarkdown(displayed)
        assertEquals(2, markdown.getLinkAnnotations(0, markdown.length).size)
    }
    @Test fun versionHeadingsSupportPlainTextTagsAndBetaVersions() {
        for (version in listOf("1.5.0", "1.5.0-beta", "1.5.0-beta.1")) {
            for (title in listOf(version, "v$version", "[$version](https://github.com/release)", "[v$version](https://github.com/release)")) {
                for (date in listOf("", " (2026-11-12)")) {
                    val notes = "## $title$date\n\n### 修复\n\n- 修复问题"
                    assertEquals("$version / $title$date", "### 修复\n\n- 修复问题", updateNotesForDisplay(notes, version))
                }
            }
        }
    }
    @Test fun meaningfulHeadingsOtherVersionsAndCodeArePreserved() {
        for (notes in listOf("", "\n \n", "## 更新内容\n\n- 修复问题",
            "## [1.4.1](https://github.com/release) (2026-11-12)\n\n- 历史日志",
            "## 1.5.01 (2026-11-12)\n\n- 其他版本", "## 1x5x0 (2026-11-12)\n\n- 其他标题",
            "## 1.5.0-beta (2026-11-12)\n\n- 测试版", "## 1.5.0 改进说明\n\n- 修复问题",
            "说明\n\n## 1.5.0 (2026-11-12)", "```\n## 1.5.0 (2026-11-12)\n```")) {
            assertEquals(notes, notes, updateNotesForDisplay(notes, "1.5.0"))
        }
        val body = "### 修复\n\n- 修复问题\n\n## 1.5.0 (2026-11-12)\n\n历史说明"
        assertEquals(body, updateNotesForDisplay("## 1.5.0 (2026-11-12)\n\n$body", "1.5.0"))
    }
    @Test fun compactParagraphLayoutKeepsFencedCodeTogetherIncludingBlankLines() {
        val blocks = updateMarkdownBlocks("## 更新\n\n- 优化布局\n\n```\n代码\n\n[代码链接](https://example.com/code)\n```\n\n[发布页](https://github.com/release)")
        assertEquals(4, blocks.size)
        assertTrue(blocks[2].contains("\n\n"))
        assertEquals(0, updateMarkdown(blocks[2]).getLinkAnnotations(0, updateMarkdown(blocks[2]).length).size)
        assertEquals(1, updateMarkdown(blocks[3]).getLinkAnnotations(0, updateMarkdown(blocks[3]).length).size)
    }
    @Test fun markdownAllowsOnlyWebLinksAndNeverLoadsImagesOrHtml() {
        val notes = updateMarkdown("## 更新\n\n- **修复**和*优化*\n[发布页](https://github.com/release)\n[非法](javascript:alert)\n![图片](https://example.com/image.png)\n<script>display text</script>\n```\n[代码](https://example.com/code)\n```")
        assertEquals(1, notes.getLinkAnnotations(0, notes.length).size)
        assertTrue(notes.text.contains("<script>display text</script>"))
        assertTrue(notes.text.contains("• 修复和优化"))
        assertTrue(notes.text.contains("[代码](https://example.com/code)"))
    }
    @Test fun publicationTimeUsesDeviceZoneAndLocaleInsteadOfRawTransportValue() {
        val instant = Instant.parse("2026-11-01T00:00:00Z")
        val chinese = updatePublishedTime(instant, ZoneId.of("Asia/Shanghai"), Locale.CHINA)!!
        val american = updatePublishedTime(instant, ZoneId.of("America/New_York"), Locale.US)!!
        assertTrue(chinese.contains("08:00"))
        assertNotEquals(chinese, american)
        assertFalse(chinese.contains("T00")); assertFalse(chinese.endsWith("Z"))
        assertNull(updatePublishedTime(null))
    }
}

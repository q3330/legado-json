package io.legado.app.web.mcp

import com.google.gson.JsonParser
import io.legado.app.constant.BookSourceType
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.ContentRule
import io.legado.app.data.entities.rule.SearchRule
import io.legado.app.help.ai.AiSkillRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BookSourceCreatorMcpToolsTest {

    @Test
    fun `book source creator tools expose expected schemas`() {
        val toolsByName = BookSourceCreatorMcpTools.tools().associateBy { it["name"] as String }
        assertTrue(toolsByName.containsKey("book_source_fetch_html"))
        assertTrue(toolsByName.containsKey("book_source_webview_capture"))
        assertTrue(toolsByName.containsKey("book_source_eval_rule"))

        @Suppress("UNCHECKED_CAST")
        val fetchSchema = toolsByName.getValue("book_source_fetch_html")["inputSchema"] as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val fetchProps = fetchSchema["properties"] as Map<String, Any>
        assertTrue(fetchProps.containsKey("url"))
        assertTrue(fetchProps.containsKey("source_override"))
        assertTrue(fetchProps.containsKey("use_web_view"))
        assertEquals(listOf("url"), fetchSchema["required"])

        @Suppress("UNCHECKED_CAST")
        val captureSchema = toolsByName.getValue("book_source_webview_capture")["inputSchema"] as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val captureProps = captureSchema["properties"] as Map<String, Any>
        assertTrue(captureProps.containsKey("mode"))
        assertTrue(captureProps.containsKey("source_regex"))
        assertTrue(captureProps.containsKey("include_regex"))

        @Suppress("UNCHECKED_CAST")
        val evalSchema = toolsByName.getValue("book_source_eval_rule")["inputSchema"] as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val evalProps = evalSchema["properties"] as Map<String, Any>
        assertTrue(evalProps.containsKey("list_rule"))
        assertTrue(evalProps.containsKey("field_rules"))
        assertTrue(evalProps.containsKey("rule"))
        assertFalse(evalSchema.containsKey("required"))
    }

    @Test
    fun `mergeBookSource patches nested rules without discarding untouched fields`() {
        val base = BookSource(
            bookSourceUrl = "https://example.com",
            bookSourceName = "Example",
            bookSourceType = BookSourceType.default,
            header = "{\"User-Agent\":\"TestAgent\"}",
            searchUrl = "https://example.com/search?q={{key}}",
            ruleSearch = SearchRule(
                bookList = ".old-list",
                name = ".title@text",
                bookUrl = "a@href"
            ),
            ruleContent = ContentRule(
                content = "#content@textNodes"
            )
        )
        val patch = JsonParser.parseString(
            """
            {
              "bookSourceType": 4,
              "ruleSearch": {
                "bookList": ".video-card",
                "coverUrl": "img@data-src"
              },
              "ruleContent": {
                "content": "video source@src",
                "sourceRegex": "https?://.*\\.m3u8"
              }
            }
            """.trimIndent()
        ).asJsonObject

        val merged = BookSourceCreatorMcpTools.mergeBookSource(base, patch)
        assertEquals(BookSourceType.video, merged.bookSourceType)
        assertEquals("https://example.com", merged.bookSourceUrl)
        assertEquals("{\"User-Agent\":\"TestAgent\"}", merged.header)
        assertEquals("https://example.com/search?q={{key}}", merged.searchUrl)
        assertEquals(".video-card", merged.ruleSearch?.bookList)
        assertEquals(".title@text", merged.ruleSearch?.name)
        assertEquals("a@href", merged.ruleSearch?.bookUrl)
        assertEquals("img@data-src", merged.ruleSearch?.coverUrl)
        assertEquals("video source@src", merged.ruleContent?.content)
        assertEquals("https?://.*\\.m3u8", merged.ruleContent?.sourceRegex)
    }

    @Test
    fun `preinstalled book_source_creator skill package declares valid metadata and references`() {
        val skillsDir = listOf(
            File("src/main/assets/skills"),
            File("app/src/main/assets/skills")
        ).firstOrNull { it.isDirectory }
        assertNotNull("Expected assets/skills directory in project", skillsDir)

        val entryFile = File(skillsDir, "book_source_creator.md")
        val packageSkillFile = File(skillsDir, "book_source_creator/SKILL.md")
        assertTrue(entryFile.isFile)
        assertTrue(packageSkillFile.isFile)

        val entryText = entryFile.readText(Charsets.UTF_8)
        assertEquals(entryText, packageSkillFile.readText(Charsets.UTF_8))

        val runtime = AiSkillRegistry.parseRuntimeDeclaration(entryText)
        assertTrue(
            runtime.mcpCapabilities.containsAll(
                listOf(
                    "book_source.query",
                    "book_source.manage",
                    "book_source.search",
                    "book_source.debug",
                    "explore.query",
                    "developer.network_logs"
                )
            )
        )

        val expectedRefs = listOf(
            "css_and_rule_syntax.md",
            "request_and_charset.md",
            "templates_novel_comic_audio.md",
            "templates_video_sources.md",
            "troubleshooting_checklist.md"
        )
        expectedRefs.forEach { refName ->
            assertTrue(
                "Missing reference doc $refName",
                File(skillsDir, "book_source_creator/references/$refName").isFile
            )
        }
    }

    @Test
    fun `mcp http server allows only loopback and private LAN clients`() {
        assertTrue(McpHttpServer.isAllowedClientIp("127.0.0.1"))
        assertTrue(McpHttpServer.isAllowedClientIp("::1"))
        assertTrue(McpHttpServer.isAllowedClientIp("192.168.1.25"))
        assertTrue(McpHttpServer.isAllowedClientIp("10.0.0.8"))
        assertTrue(McpHttpServer.isAllowedClientIp("172.16.5.4"))
        assertFalse(McpHttpServer.isAllowedClientIp("8.8.8.8"))
        assertFalse(McpHttpServer.isAllowedClientIp("1.1.1.1"))
        assertFalse(McpHttpServer.isAllowedClientIp(""))
        assertFalse(McpHttpServer.isAllowedClientIp(null))
    }

    @Test
    fun `ensureAiCreatedBookSourceGroup appends default AI group without duplicates`() {
        assertEquals("AI制作书源", McpServer.ensureAiCreatedBookSourceGroup(null))
        assertEquals("AI制作书源", McpServer.ensureAiCreatedBookSourceGroup("   "))
        assertEquals("AI制作书源,R18,视频直链", McpServer.ensureAiCreatedBookSourceGroup("R18,视频直链"))
        assertEquals("R18,AI制作书源,视频直链", McpServer.ensureAiCreatedBookSourceGroup("R18, AI制作书源 ,视频直链"))
    }
}

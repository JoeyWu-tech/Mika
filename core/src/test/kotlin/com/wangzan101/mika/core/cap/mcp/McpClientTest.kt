package com.wangzan101.mika.core.cap.mcp

import com.wangzan101.mika.core.action.ActionRisk
import com.wangzan101.mika.core.action.ConfirmDecision
import com.wangzan101.mika.core.action.Decision
import com.wangzan101.mika.core.action.Origin
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.observation.Trust
import com.wangzan101.mika.core.run.Attendance
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.run.RunProvenance
import com.wangzan101.mika.core.run.RunSpec
import com.wangzan101.mika.core.tool.ActionPass
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.tool.ToolDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpClientTest {
    private class FakeEndpoint : McpEndpoint {
        override val serverId = "demo"
        var offered = listOf(McpToolDescriptor("search", "Search", buildJsonObject { put("type", "object") }, McpAnnotations(readOnlyHint = true)))
        var result = McpResult("found")
        var calls = 0
        var offeredResources: List<McpResourceDescriptor> = emptyList()
        var offeredPrompts: List<McpPromptDescriptor> = emptyList()
        var resourcesError: Exception? = null
        override fun tools() = offered
        override fun resources() = resourcesError?.let { throw it } ?: offeredResources
        override fun prompts() = offeredPrompts
        override suspend fun call(name: String, arguments: JsonObject): McpResult { calls++; return result }
        override suspend fun readResource(uri: String) = McpResult("resource:$uri")
        override suspend fun getPrompt(name: String, arguments: JsonObject) =
            McpResult("prompt:$name\n$arguments")
    }

    private fun endpointWithSchema(schema: String) = FakeEndpoint().apply {
        offered = listOf(McpToolDescriptor("search", "Search", Json.parseToJsonElement(schema).jsonObject))
    }

    private fun parameters(tools: ToolDispatcher): JsonObject = Json.parseToJsonElement(
        tools.definitions().single { it.name == "mcp_demo__search" }.parametersJsonSchema,
    ).jsonObject

    @Test fun nullPropertiesIsTreatedAsNoParameters() = runBlocking {
        val endpoint = endpointWithSchema("""{"type":"object","properties":null}""")
        val tools = toolDispatcher(FakeScreen(), mcpServers = listOf(McpConnection(endpoint)))
        assertEquals(setOf("reason"), parameters(tools)["properties"]!!.jsonObject.keys)
        val result = tools.execute(Part.ToolCall("c", "mcp_demo__search", "{}"), ActionPass())
        assertEquals(JsonPrimitive(true), result.content["ok"])
        assertEquals(1, endpoint.calls)
    }

    @Test fun booleanPropertySchemaBecomesPermissiveObject() = runBlocking {
        val endpoint = endpointWithSchema(
            """{"type":"object","properties":{"q":true,"n":{"type":"number","minimum":0}},"required":["q"]}""",
        )
        val tools = toolDispatcher(FakeScreen(), mcpServers = listOf(McpConnection(endpoint)))
        val properties = parameters(tools)["properties"]!!.jsonObject
        assertEquals(JsonObject(emptyMap()), properties["q"])
        assertEquals(Json.parseToJsonElement("""{"type":"number","minimum":0}"""), properties["n"])
        val result = tools.execute(Part.ToolCall("c", "mcp_demo__search", """{"q":42}"""), ActionPass())
        assertEquals(JsonPrimitive(true), result.content["ok"])
        assertEquals(1, endpoint.calls)
    }

    @Test fun malformedRequiredIsIgnoredAndNonStringEntriesAreDropped() {
        val scalar = toolDispatcher(FakeScreen(), mcpServers = listOf(McpConnection(
            endpointWithSchema("""{"type":"object","properties":{"q":{"type":"string"}},"required":"q"}"""),
        )))
        assertEquals(emptyList<String>(), parameters(scalar)["required"]!!.jsonArray.map { it.jsonPrimitive.content })

        val mixed = toolDispatcher(FakeScreen(), mcpServers = listOf(McpConnection(
            endpointWithSchema("""{"type":"object","properties":{"q":{},"n":{}},"required":["q",1,true,null,"n"]}"""),
        )))
        assertEquals(listOf("q", "n"), parameters(mixed)["required"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test fun wellFormedSchemaKeepsPropertyConstraintsAndRequired() {
        val tools = toolDispatcher(FakeScreen(), mcpServers = listOf(McpConnection(endpointWithSchema(
            """{"type":"object","properties":{"q":{"type":"string","description":"Query","minLength":1,"enum":["a","b"],"default":"a"},"n":{"type":"integer","minimum":1}},"required":["q"],"additionalProperties":false}""",
        ))))
        val schema = parameters(tools)
        val properties = schema["properties"]!!.jsonObject
        assertEquals(Json.parseToJsonElement(
            """{"type":"string","description":"Query","minLength":1,"enum":["a","b"],"default":"a"}""",
        ), properties["q"])
        assertEquals(Json.parseToJsonElement("""{"type":"integer","minimum":1}"""), properties["n"])
        assertEquals(listOf("q"), schema["required"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test fun oneServerAssemblyFailureKeepsOtherCapabilitiesAndReportsCause() = runBlocking {
        val broken = object : McpEndpoint {
            override val serverId = "broken"
            override fun tools(): List<McpToolDescriptor> = throw IllegalStateException("catalog exploded")
            override suspend fun call(name: String, arguments: JsonObject) = McpResult("unused")
        }
        val healthy = FakeEndpoint()
        val tools = toolDispatcher(FakeScreen(), mcpServers = listOf(McpConnection(broken), McpConnection(healthy)))
        val names = tools.definitions().map { it.name }
        assertTrue("phone_observe" in names)
        assertTrue("mcp_demo__search" in names)
        assertFalse(names.any { it.startsWith("mcp_broken__") })
        val diagnostics = tools.promptSections(tools.definitions()).joinToString("\n")
        assertTrue(diagnostics, "broken" in diagnostics)
        assertTrue(diagnostics, "catalog exploded" in diagnostics)
        assertTrue(diagnostics, "other capabilities remain available" in diagnostics)

        val result = tools.execute(Part.ToolCall("c", "mcp_demo__search", "{}"), ActionPass())
        assertEquals(JsonPrimitive(true), result.content["ok"])
        assertEquals(1, healthy.calls)
    }

    @Test fun oneToolAssemblyFailureBecomesDiagnosableToolError() = runBlocking {
        val endpoint = FakeEndpoint()
        val descriptor = endpoint.offered.single()
        val assembled = assembleMcpTool("mcp:demo", endpoint, descriptor, "mcp_demo__search") {
            throw IllegalStateException("schema adapter exploded")
        }
        assertEquals("mcp_demo__search", assembled.tool.name) // 名字照传进来的用
        assertTrue(assembled.failure.orEmpty(), "schema adapter exploded" in assembled.failure.orEmpty())
        val result = assembled.tool.execute(
            Args(assembled.tool.def, buildJsonObject {}),
            toolDispatcher(FakeScreen()).ctx,
        )
        assertEquals("mcp_tool_assembly_failed", result.content["error"]?.jsonPrimitive?.content)
        assertEquals("demo", result.content["server"]?.jsonPrimitive?.content)
        assertEquals("search", result.content["tool"]?.jsonPrimitive?.content)
        assertTrue(result.content["message"]?.jsonPrimitive?.content.orEmpty().contains("schema adapter exploded"))
    }

    @Test fun syntheticToolAssemblyFailureDoesNotRemoveServerTools() {
        val endpoint = FakeEndpoint().apply { resourcesError = IllegalStateException("resources exploded") }
        val tools = toolDispatcher(FakeScreen(), mcpServers = listOf(McpConnection(endpoint)))
        val names = tools.definitions().map { it.name }
        assertTrue("mcp_demo__search" in names)
        assertFalse("mcp_demo__resource_read" in names)
        val diagnostics = tools.promptSections(tools.definitions()).joinToString("\n")
        assertTrue(diagnostics, "resources exploded" in diagnostics)
        assertTrue(diagnostics, "resource_read" in diagnostics)
    }

    @Test fun annotationRiskDefaultsToWriteAndEscalatesOpenWorldAndDestructive() {
        assertEquals(ActionRisk.Change, mcpApprovalRisk(McpAnnotations()).level)
        assertEquals(ActionRisk.Read, mcpApprovalRisk(McpAnnotations(readOnlyHint = true)).level)
        assertEquals(ActionRisk.External, mcpApprovalRisk(McpAnnotations(readOnlyHint = true, openWorldHint = true)).level)
        assertEquals(ActionRisk.Irreversible, mcpApprovalRisk(McpAnnotations(destructiveHint = true)).level)
    }

    @Test fun runCatalogIsFrozenAndCapabilitySubsetFiltersMcp() {
        val endpoint = FakeEndpoint()
        val spec = RunSpec(RunId("r"), "task", "thread")
        val tools = toolDispatcher(FakeScreen(), runSpec = spec, mcpServers = listOf(McpConnection(endpoint)))
        val first = tools.definitions().map { it.name }
        assertTrue("mcp_demo__search" in first)
        assertEquals(ActionRisk.Change, tools.approvalRisk("mcp_demo__search")?.level)
        val trusted = toolDispatcher(FakeScreen(), mcpServers = listOf(McpConnection(endpoint, trust = McpTrust.TrustAnnotations)))
        assertEquals(ActionRisk.Read, trusted.approvalRisk("mcp_demo__search")?.level)
        endpoint.offered = listOf(McpToolDescriptor("changed", "Changed", buildJsonObject {}))
        assertEquals(first, tools.definitions().map { it.name })
        val subset = toolDispatcher(FakeScreen(), runSpec = spec.copy(capabilities = setOf("phone")), mcpServers = listOf(McpConnection(endpoint)))
        assertFalse(subset.definitions().any { it.name.startsWith("mcp_") })
    }

    @Test fun allowedToolsLimitsExposedTools() {
        val endpoint = FakeEndpoint().apply {
            offered = listOf(
                McpToolDescriptor("search", "Search", buildJsonObject { put("type", "object") }),
                McpToolDescriptor("write", "Write", buildJsonObject { put("type", "object") }),
            )
        }
        fun mcpNames(connection: McpConnection) =
            toolDispatcher(FakeScreen(), mcpServers = listOf(connection)).definitions().map { it.name }.filter { it.startsWith("mcp_") }
        assertEquals(listOf("mcp_demo__search", "mcp_demo__write"), mcpNames(McpConnection(endpoint)))
        assertEquals(listOf("mcp_demo__write"), mcpNames(McpConnection(endpoint, allowedTools = setOf("write"))))
        assertEquals(emptyList<String>(), mcpNames(McpConnection(endpoint, allowedTools = emptySet())))
    }

    @Test fun resourcesAndPromptsBecomeReadOnlyTools() = runBlocking {
        val endpoint = FakeEndpoint().apply {
            offeredResources = listOf(McpResourceDescriptor("file://a", "A", "first"))
            offeredPrompts = listOf(
                McpPromptDescriptor("summarize", "Summarize", listOf(McpPromptArgument("topic", "Topic", true))),
            )
        }
        val tools = toolDispatcher(FakeScreen(), mcpServers = listOf(McpConnection(endpoint)))
        val names = tools.definitions().map { it.name }
        assertTrue("mcp_demo__resource_read" in names)
        assertTrue("mcp_demo__prompt_get" in names)
        assertEquals(ActionRisk.Read, tools.approvalRisk("mcp_demo__resource_read")?.level)
        assertEquals(ActionRisk.Read, tools.approvalRisk("mcp_demo__prompt_get")?.level)

        val resource = tools.execute(
            Part.ToolCall("c", "mcp_demo__resource_read", """{"uri":"file://a"}"""),
            ActionPass(),
        )
        assertEquals("resource:file://a", resource.observations.single().text)
        assertEquals(Trust.Untrusted, resource.observations.single().trust)

        val prompt = tools.execute(
            Part.ToolCall("c", "mcp_demo__prompt_get", """{"name":"summarize","arguments":{"topic":"x"}}"""),
            ActionPass(),
        )
        assertTrue(prompt.observations.single().text.contains("prompt:summarize"))
    }

    @Test fun largeResultUsesReferenceAndUntrustedObservation() = runBlocking {
        val endpoint = FakeEndpoint().apply { result = McpResult("x".repeat(40)) }
        var saved: String? = null
        val store = McpResultStore { _, _, body -> saved = body; "result://one" }
        val tools = toolDispatcher(FakeScreen(), mcpServers = listOf(McpConnection(endpoint, store, previewChars = 10)))
        val result = tools.execute(Part.ToolCall("c", "mcp_demo__search", "{}"), ActionPass())
        assertEquals("x".repeat(40), saved)
        assertEquals("result://one", result.content["fullResult"]?.toString()?.trim('"'))
        assertTrue(result.content["text"].toString().contains("<untrusted>"))
        assertEquals(Trust.Untrusted, result.observations.single().trust)
        assertEquals(10, result.observations.single().text.length)
    }

    @Test fun unattendedExternalMcpCallIsNotAskedAndNotCalledByTheJudge() = runBlocking {
        val endpoint = FakeEndpoint().apply {
            offered = listOf(McpToolDescriptor("send", "Send", buildJsonObject { put("type", "object") },
                McpAnnotations(openWorldHint = true)))
        }
        val tools = toolDispatcher(FakeScreen(), mcpServers = listOf(McpConnection(endpoint)))
        // 不花钱的对外调用:无人值守也直接做(只记审批),不在判定时就调服务端
        val decision = ConfirmDecision(tools, null, provenance = RunProvenance(RunOrigin.Trigger, Attendance.Unattended))
            .judge("mcp_demo__send", buildJsonObject {}, Origin.Model(1))
        assertEquals(Decision.Clear, decision)
        assertEquals(0, endpoint.calls)
    }

    /** 只列工具、记下被调用的原名的服务;工具说明就写原名,好从定义里认出是哪一个。 */
    private class NamedEndpoint(override val serverId: String, private val names: List<String>) : McpEndpoint {
        val called = mutableListOf<String>()
        override fun tools() = names.map { McpToolDescriptor(it, it, buildJsonObject { put("type", "object") }) }
        override suspend fun call(name: String, arguments: JsonObject): McpResult { called += name; return McpResult("ok") }
    }

    /** 原名 → 任务里的名字。 */
    private fun namesOf(tools: ToolDispatcher): Map<String, String> =
        tools.definitions().filter { it.name.startsWith("mcp_") }.associate { it.description to it.name }

    private val validName = Regex("^[A-Za-z0-9_]{1,$MCP_TOOL_NAME_MAX}$")

    /** 设置里加的服务编号是 36 位的 UUID:原名长的工具名字也在上限以内、调得到;短的照旧。 */
    @Test fun longToolNamesUnderAUuidServerStayWithinTheLimitAndStillRoute() = runBlocking {
        val uuid = "3f2b6c1e-8d4a-4b7e-9c21-5a6f0e1d2c3b"
        val long = listOf("list_repository_collaborators", "list_allowed_directories")
        val endpoint = NamedEndpoint(uuid, long + "search")
        val tools = toolDispatcher(FakeScreen(), mcpServers = listOf(McpConnection(endpoint)))
        val names = namesOf(tools)
        assertTrue(names.toString(), names.values.all(validName::matches))
        assertEquals("mcp_3f2b6c1e_8d4a_4b7e_9c21_5a6f0e1d2c3b__search", names["search"])
        assertEquals("mcp_3f2b6c1e__list_repository_collaborators_a11d7c62", names["list_repository_collaborators"])
        assertEquals("mcp_3f2b6c1e__list_allowed_directories_02d44151", names["list_allowed_directories"])
        for (original in long) {
            val result = tools.execute(Part.ToolCall("c", names.getValue(original), "{}"), ActionPass())
            assertEquals(JsonPrimitive(true), result.content["ok"])
        }
        assertEquals(long, endpoint.called)
    }

    /** 名字写死在这里:换一个进程、换一台设备算出来也得是这几个。前 41 个字符相同的两个长名字截到上限后还分得开。 */
    @Test fun shortNamesArePinnedAndTruncatedWithinTheLimit() {
        val uuid = "3f2b6c1e-8d4a-4b7e-9c21-5a6f0e1d2c3b"
        assertEquals("mcp_3f2b6c1e_8d4a_4b7e_9c21_5a6f0e1d2c3b__" + "a".repeat(22), mcpToolName(uuid, "a".repeat(22)))
        assertEquals("mcp_3f2b6c1e__" + "a".repeat(23) + "_20e42354", mcpToolName(uuid, "a".repeat(23)))
        val long = listOf("manage_repository_notification_subscription", "manage_repository_notification_subscription_v2")
        assertEquals(
            listOf(
                "mcp_3f2b6c1e__manage_repository_notification_subscripti_727e5643",
                "mcp_3f2b6c1e__manage_repository_notification_subscripti_267a2d1c",
            ),
            mcpToolNames(uuid, long),
        )
        assertTrue(mcpToolNames(uuid, long).all { it.length == MCP_TOOL_NAME_MAX })
        assertEquals(listOf("mcp_demo__get_user_186aa1ca", "mcp_demo__get_user_d94024c8"), mcpToolNames("demo", listOf("get-user", "get_user")))
    }

    /** 清洗后同名的几个都改名、各调各的;服务端换个顺序名字不变;不撞的照旧。 */
    @Test fun namesThatCollideAfterCleaningAreAllRenamedStably() = runBlocking {
        val listed = listOf("get-user", "get_user", "查询天气", "搜索新闻", "search")
        val endpoint = NamedEndpoint("demo", listed)
        val tools = toolDispatcher(FakeScreen(), mcpServers = listOf(McpConnection(endpoint)))
        val names = namesOf(tools)
        assertEquals(listed.toSet(), names.keys)
        assertTrue(names.toString(), names.values.all(validName::matches))
        assertEquals("mcp_demo__search", names["search"])
        assertFalse(names.toString(), "mcp_demo__get_user" in names.values)
        assertTrue(names.toString(), names.getValue("get-user").startsWith("mcp_demo__get_user_"))
        for (original in listed) tools.execute(Part.ToolCall("c", names.getValue(original), "{}"), ActionPass())
        assertEquals(listed, endpoint.called)
        assertEquals(names, namesOf(toolDispatcher(FakeScreen(), mcpServers = listOf(McpConnection(NamedEndpoint("demo", listed.reversed()))))))
    }

    /** 服务端把同一个名字列了几次:只用第一个,任务照常开局,提示里写明。 */
    @Test fun aToolListedSeveralTimesIsUsedOnceAndReported() {
        fun tool(name: String, description: String) = McpToolDescriptor(name, description, buildJsonObject { put("type", "object") })
        val endpoint = FakeEndpoint().apply { offered = listOf(tool("search", "first"), tool("search", "second"), tool("write", "Write"), tool("search", "third")) }
        val tools = toolDispatcher(FakeScreen(), mcpServers = listOf(McpConnection(endpoint)))
        val mcp = tools.definitions().filter { it.name.startsWith("mcp_") }
        assertEquals(listOf("mcp_demo__search", "mcp_demo__write"), mcp.map { it.name })
        assertEquals("first", mcp.first().description)
        val diagnostics = tools.promptSections(tools.definitions()).joinToString("\n")
        assertTrue(diagnostics, "'search': listed 3 times, only the first is used" in diagnostics)
    }
}

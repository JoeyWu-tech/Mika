package com.wangzan101.mika.core.cap.mcp

import com.wangzan101.mika.core.action.ActionRisk
import com.wangzan101.mika.core.action.Risk as ApprovalRisk
import com.wangzan101.mika.core.capability.Capability
import com.wangzan101.mika.core.capability.CapabilityStatus
import com.wangzan101.mika.core.capability.PromptEnv
import com.wangzan101.mika.core.observation.ObsKind
import com.wangzan101.mika.core.observation.Observation
import com.wangzan101.mika.core.observation.Trust
import com.wangzan101.mika.core.observation.Untrusted
import com.wangzan101.mika.core.tool.AgentTool
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDef
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.security.MessageDigest

/** 传输层只须实现这两个操作；每个 Run 开局调用一次 tools，之后目录不变。 */
interface McpEndpoint {
    val serverId: String
    fun tools(): List<McpToolDescriptor>
    suspend fun call(name: String, arguments: JsonObject): McpResult

    /** 连接时从服务端读到的资源目录;不支持就是空。 */
    fun resources(): List<McpResourceDescriptor> = emptyList()
    suspend fun readResource(uri: String): McpResult =
        McpResult("MCP server does not support resources", isError = true)

    /** 连接时从服务端读到的提示词模板;不支持就是空。 */
    fun prompts(): List<McpPromptDescriptor> = emptyList()
    suspend fun getPrompt(name: String, arguments: JsonObject): McpResult =
        McpResult("MCP server does not support prompts", isError = true)
}

data class McpAnnotations(
    val readOnlyHint: Boolean? = null,
    val destructiveHint: Boolean? = null,
    val openWorldHint: Boolean? = null,
)

data class McpToolDescriptor(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val annotations: McpAnnotations = McpAnnotations(),
    val estimatedCostUnits: Long = 0,
)

data class McpResult(val text: String, val isError: Boolean = false, val costUnits: Long = 0)

/** resources/list 的一项;v1 只用 uri/name/description 给模型看。 */
data class McpResourceDescriptor(
    val uri: String,
    val name: String,
    val description: String = "",
    val mimeType: String? = null,
)

/** prompts/list 的一项。 */
data class McpPromptDescriptor(
    val name: String,
    val description: String = "",
    val arguments: List<McpPromptArgument> = emptyList(),
)

data class McpPromptArgument(
    val name: String,
    val description: String = "",
    val required: Boolean = false,
)

/** 每个服务独立配置；Restricted 不采信服务自称的只读注解。 */
enum class McpTrust { Restricted, TrustAnnotations }

/** 长结果的完整文本由宿主保存；返回的引用只含文件路径或资源 ID。 */
fun interface McpResultStore { fun save(serverId: String, toolName: String, content: String): String }

data class McpConnection(
    val endpoint: McpEndpoint,
    val resultStore: McpResultStore? = null,
    val maxCostUnits: Long? = null,
    val previewChars: Int = 4000,
    val trust: McpTrust = McpTrust.Restricted,
    /** 只暴露这些工具;null 表示全部,空集表示一个都不给。 */
    val allowedTools: Set<String>? = null,
) {
    init { require(previewChars > 0 && (maxCostUnits == null || maxCostUnits >= 0)) }
}

/** 没有只读注解就按可写处理；破坏性与开放世界操作记成不可逆、对外(审批记录据此知道任务做过对外的事)。 */
fun mcpApprovalRisk(a: McpAnnotations, trust: McpTrust = McpTrust.TrustAnnotations): ApprovalRisk = when {
    a.destructiveHint == true -> ApprovalRisk(ActionRisk.Irreversible)
    a.openWorldHint == true -> ApprovalRisk(ActionRisk.External)
    a.readOnlyHint == true && trust == McpTrust.TrustAnnotations -> ApprovalRisk(ActionRisk.Read)
    else -> ApprovalRisk(ActionRisk.Change)
}

internal class McpCapability(connection: McpConnection, override val id: String) : Capability {
    private val endpoint = connection.endpoint
    private val budget = McpBudget(connection.maxCostUnits)
    private val listed = endpoint.tools()
        .filter { connection.allowedTools == null || it.name in connection.allowedTools }
    /** 服务端把同一个名字列了不止一次时只用第一个:同名的工具会让整个任务开不了局。 */
    private val descriptors = listed.distinctBy { it.name }
    private val toolNames = mcpToolNames(endpoint.serverId, descriptors.map { it.name })
    private val serverToolNames = descriptors.map { mcpSafe(it.name) }.toSet()
    private val assemblyFailures = mutableListOf<String>()
    private val tools = buildList {
        listed.groupingBy { it.name }.eachCount().filterValues { it > 1 }.forEach { (name, times) ->
            assemblyFailures += "tool '$name': listed $times times, only the first is used"
        }
        descriptors.forEachIndexed { i, descriptor ->
            val assembled = assembleMcpTool(id, endpoint, descriptor, toolNames[i]) {
                McpClientTool(id, endpoint, descriptor, toolNames[i], budget, connection.resultStore, connection.previewChars, connection.trust)
            }
            add(assembled.tool)
            assembled.failure?.let(assemblyFailures::add)
        }
        try {
            resourceTool(connection)?.let(::add)
        } catch (e: Exception) {
            assemblyFailures += "resource_read: ${mcpAssemblyReason(e)}"
        }
        try {
            promptTool(connection)?.let(::add)
        } catch (e: Exception) {
            assemblyFailures += "prompt_get: ${mcpAssemblyReason(e)}"
        }
    }
    override fun status(): CapabilityStatus = if (assemblyFailures.isEmpty()) CapabilityStatus.Available
        else CapabilityStatus.Degraded(assemblyFailures.joinToString("; "))
    override fun tools(): List<AgentTool> = tools
    override fun sections(env: PromptEnv): List<String> = if (assemblyFailures.isEmpty()) emptyList() else listOf(
        "MCP server '${endpoint.serverId}' was only partially assembled. Other tools remain available.\n" +
            assemblyFailures.joinToString("\n") { "- $it" },
    )

    /** resources/read 合成一个只读工具;服务端有同名工具时让位给服务端。 */
    private fun resourceTool(connection: McpConnection): AgentTool? {
        val resources = endpoint.resources()
        if (resources.isEmpty() || "resource_read" in serverToolNames) return null
        val available = resources.take(30).joinToString(", ") { it.uri }
        return McpSyntheticTool(
            capability = id,
            serverId = endpoint.serverId,
            def = ToolDef(
                name = mcpToolName(endpoint.serverId, "resource_read"),
                description = "Read a resource from the MCP server. Available: $available".take(4000),
                props = mapOf("uri" to buildJsonObject {
                    put("type", "string")
                    put("description", "Resource URI from the list: $available".take(4000))
                }),
                required = listOf("uri"),
            ),
            budget = budget,
            resultStore = connection.resultStore,
            previewChars = connection.previewChars,
            storeName = "resource_read",
            call = { args -> endpoint.readResource(args["uri"]?.jsonPrimitive?.contentOrNull.orEmpty()) },
        )
    }

    /** prompts/get 合成一个只读工具,参数名和说明来自 prompts/list。 */
    private fun promptTool(connection: McpConnection): AgentTool? {
        val prompts = endpoint.prompts()
        if (prompts.isEmpty() || "prompt_get" in serverToolNames) return null
        val names = prompts.take(30).joinToString(", ") { it.name }
        return McpSyntheticTool(
            capability = id,
            serverId = endpoint.serverId,
            def = ToolDef(
                name = mcpToolName(endpoint.serverId, "prompt_get"),
                description = "Get a prompt template from the MCP server. Available: $names".take(4000),
                props = mapOf(
                    "name" to buildJsonObject {
                        put("type", "string")
                        put("description", "Prompt name: $names".take(4000))
                    },
                    "arguments" to buildJsonObject {
                        put("type", "object")
                        put("description", "Prompt arguments (see the prompt description on the server)")
                    },
                ),
                required = listOf("name"),
            ),
            budget = budget,
            resultStore = connection.resultStore,
            previewChars = connection.previewChars,
            storeName = "prompt_get",
            call = { args ->
                val name = args["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                endpoint.getPrompt(name, args["arguments"] as? JsonObject ?: JsonObject(emptyMap()))
            },
        )
    }
}

internal data class McpToolAssembly(val tool: AgentTool, val failure: String?)

/** 一个工具的声明坏了只降级这个工具;名字([name],见 [mcpToolNames])保留下来,调用方能拿到明确的装配错误。 */
internal fun assembleMcpTool(
    capability: String,
    endpoint: McpEndpoint,
    descriptor: McpToolDescriptor,
    name: String,
    assemble: () -> AgentTool,
): McpToolAssembly = try {
    McpToolAssembly(assemble(), null)
} catch (e: Exception) {
    val reason = mcpAssemblyReason(e)
    McpToolAssembly(
        McpFailedTool(capability, endpoint.serverId, descriptor, name, reason),
        "tool '${descriptor.name}': $reason",
    )
}

internal fun mcpAssemblyReason(e: Exception): String {
    val type = e::class.simpleName ?: "Exception"
    val detail = e.message?.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
    return "$type$detail".replace(Regex("\\s+"), " ").take(500)
}

/** 工具名的长度上限:各家接口里最严的(Chat Completions 的函数名)是 64 个字符,超了整个请求被拒。 */
internal const val MCP_TOOL_NAME_MAX = 64

/**
 * 一个服务的工具在任务里的名字,和 [toolNames] 一一对应。平时是 `mcp_<服务编号>__<工具名>`(见 [mcpToolName]);
 * 清洗后同一服务里有两个工具同名(`get-user` 和 `get_user`,字数相同的两个中文名)时,这几个都改用短格式,
 * 服务端换个顺序名字也不变。服务端列重了的同一个名字由调用方先去掉。名字取决于同一服务里还有哪些工具:
 * 服务端新加一个会撞名的工具,或放宽了允许的工具,原来没撞的名字也会变。
 */
internal fun mcpToolNames(serverId: String, toolNames: List<String>): List<String> {
    require(toolNames.size == toolNames.toSet().size) { "duplicate MCP tool names: $toolNames" }
    val plain = toolNames.map { mcpToolName(serverId, it) }
    val count = plain.groupingBy { it }.eachCount()
    return toolNames.mapIndexed { i, name -> if (count.getValue(plain[i]) > 1) mcpToolName(serverId, name, short = true) else plain[i] }
}

/**
 * `mcp_<服务编号>__<工具名>`,不在 `[A-Za-z0-9_]` 里的字符换成 `_`。服务编号是 36 位的 UUID,工具名长于 22 个字符时
 * 整个名字就超过 [MCP_TOOL_NAME_MAX];超了,或 [short] 时改用短格式:服务编号只留前 8 位,整体截到上限减 9,
 * 再接 `_` 和「服务编号 + 工具原名」(清洗前)的 SHA-256 前 8 位十六进制,跨进程、跨设备都一样。没超的一个字符都不变。
 */
internal fun mcpToolName(serverId: String, toolName: String, short: Boolean = false): String {
    val plain = "mcp_${mcpSafe(serverId)}__${mcpSafe(toolName)}"
    if (!short && plain.length <= MCP_TOOL_NAME_MAX) return plain
    val digest = MessageDigest.getInstance("SHA-256").digest("$serverId\u0000$toolName".toByteArray(Charsets.UTF_8))
    val hash = digest.take(4).joinToString("") { "%02x".format(it) }
    return "mcp_${mcpSafe(serverId).take(8)}__${mcpSafe(toolName)}".take(MCP_TOOL_NAME_MAX - 9) + "_" + hash
}

private val UNSAFE_NAME_CHARS = Regex("[^A-Za-z0-9_]")

internal fun mcpSafe(name: String): String = name.replace(UNSAFE_NAME_CHARS, "_")

private class McpFailedTool(
    override val capability: String,
    private val serverId: String,
    private val descriptor: McpToolDescriptor,
    name: String,
    reason: String,
) : AgentTool {
    private val message = "MCP tool '${descriptor.name}' on server '$serverId' could not be assembled: $reason"
    override val def = ToolDef(
        name,
        "${descriptor.description}\n\nUnavailable: $message".trim(),
    )
    override val verb = Verb.Interact
    override val risk = Risk.Read
    override val approvalRisk = ApprovalRisk(ActionRisk.Read)
    override val offScreen = true

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution = ToolExecution(buildJsonObject {
        put("ok", false)
        put("error", "mcp_tool_assembly_failed")
        put("server", serverId)
        put("tool", descriptor.name)
        put("message", message)
        put("recoverable", false)
    })
}

private class McpBudget(private val limit: Long?) {
    private var used = 0L
    @Synchronized fun charge(units: Long): Boolean {
        if (units < 0 || limit != null && units > limit - used) return false
        used += units
        return true
    }
}

private class McpClientTool(
    override val capability: String,
    private val endpoint: McpEndpoint,
    private val descriptor: McpToolDescriptor,
    name: String,
    private val budget: McpBudget,
    private val resultStore: McpResultStore?,
    private val previewChars: Int,
    trust: McpTrust,
) : AgentTool {
    private val schema = descriptor.inputSchema
    private val approval = mcpApprovalRisk(descriptor.annotations, trust)
    override val def: ToolDef = ToolDef(
        name,
        descriptor.description,
        (schema["properties"] as? JsonObject).orEmpty()
            .mapValues { (_, value) -> value as? JsonObject ?: JsonObject(emptyMap()) },
        (schema["required"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content },
    )
    override val verb = Verb.Interact
    override val risk = when (approval.level) {
        ActionRisk.Read -> Risk.Read
        ActionRisk.Change -> Risk.Write
        else -> Risk.External
    }
    override val approvalRisk: ApprovalRisk = approval
    override val offScreen = true
    override val logged = risk != Risk.Read

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution = mcpExecution(
        capability = capability,
        serverId = endpoint.serverId,
        storeName = descriptor.name,
        budget = budget,
        costUnits = descriptor.estimatedCostUnits,
        resultStore = resultStore,
        previewChars = previewChars,
        call = { endpoint.call(descriptor.name, a.o) },
    )
}

/** resources/prompts 合成的只读工具;结果裁剪、预算、不可信标记和普通 MCP 工具共用一套。 */
private class McpSyntheticTool(
    override val capability: String,
    private val serverId: String,
    override val def: ToolDef,
    private val budget: McpBudget,
    private val resultStore: McpResultStore?,
    private val previewChars: Int,
    private val storeName: String,
    private val call: suspend (JsonObject) -> McpResult,
) : AgentTool {
    override val verb = Verb.Interact
    override val risk = Risk.Read
    override val approvalRisk = ApprovalRisk(ActionRisk.Read)
    override val offScreen = true
    override val logged = false

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution = mcpExecution(
        capability = capability,
        serverId = serverId,
        storeName = storeName,
        budget = budget,
        costUnits = 0,
        resultStore = resultStore,
        previewChars = previewChars,
        call = { call(a.o) },
    )
}

private suspend fun mcpExecution(
    capability: String,
    serverId: String,
    storeName: String,
    budget: McpBudget,
    costUnits: Long,
    resultStore: McpResultStore?,
    previewChars: Int,
    call: suspend () -> McpResult,
): ToolExecution {
    if (!budget.charge(costUnits)) return ToolExecution(buildJsonObject {
        put("ok", false); put("error", "mcp_cost_limit")
    })
    val result = call()
    val long = result.text.length > previewChars
    val ref = if (long) resultStore?.save(serverId, storeName, result.text) else null
    if (long && ref == null) return ToolExecution(buildJsonObject {
        put("ok", false); put("error", "mcp_result_too_large")
    })
    val preview = if (long) result.text.take(previewChars) else result.text
    return ToolExecution(buildJsonObject {
        put("ok", !result.isError)
        put("text", Untrusted.wrap(preview))
        ref?.let { put("fullResult", it) }
        if (long) put("truncated", true)
    }, observations = listOf(Observation(ObsKind.Output, preview, source = capability, trust = Trust.Untrusted)))
}

package games.qore.foreman.server.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import games.qore.foreman.api.ForemanTool
import java.util.logging.Level
import java.util.logging.Logger

/**
 * MCP's JSON-RPC methods (initialize, tools, prompts), whatever carries them: the HTTP server ([McpServer]) and the
 * relay over the game connection ([games.qore.foreman.server.bridge.Relay]).
 */
class McpDispatcher(
    private val logger: Logger,
    /** Read at each initialize: they include what the server learned since it started. */
    private val instructions: () -> String,
    val tools: ToolRegistry,
    /** Read at each request, so edited prompt files apply at once. */
    private val prompts: () -> List<Prompt>,
    /** Called after every tool call, from the calling thread, with the player who made it (or null) and how it came. */
    private val onCall: (Tool, Args, ToolResult, Long, Caller?, String) -> Unit,
) {
    /** The answer to [request], or null for a notification. [via]: how it came (`http`, `relay`), for the audit. */
    fun dispatch(request: JsonObject, caller: Caller?, via: String): JsonObject? {
        val id = request.get("id")
        val method = request.get("method")?.asString ?: return null
        if (id == null || id.isJsonNull) return null

        val params = request.getAsJsonObject("params") ?: JsonObject()
        return try {
            val result = when (method) {
                "initialize" -> initialize(params)
                "ping" -> JsonObject()
                "tools/list" -> JsonObject().apply {
                    add("tools", JsonArray().apply {
                        tools.all().forEach { tool ->
                            add(JsonObject().apply {
                                addProperty("name", tool.name)
                                addProperty("description", tool.description)
                                add("inputSchema", tool.schema)
                                // Who provides it and what for, for the client's tool list and the chats that load tools by group.
                                add("_meta", JsonObject().apply {
                                    addProperty("provider", tool.provider)
                                    addProperty("group", ForemanTool.of(tool.name)?.takeIf { tool.provider == "Foreman" }?.group ?: tool.provider)
                                    addProperty("modifies", tool.modifies)
                                })
                            })
                        }
                    })
                }

                "tools/call" -> call(params, caller, via) ?: return error(id, -32602, "Unknown tool: ${params.get("name")}")
                "prompts/list" -> JsonObject().apply {
                    add("prompts", JsonArray().apply {
                        prompts().forEach { prompt ->
                            add(JsonObject().apply {
                                addProperty("name", prompt.name)
                                addProperty("description", prompt.description)
                                add("arguments", JsonArray().apply {
                                    prompt.arguments.forEach { a ->
                                        add(JsonObject().apply {
                                            addProperty("name", a.name)
                                            addProperty("description", a.description)
                                            addProperty("required", a.required)
                                        })
                                    }
                                })
                            })
                        }
                    })
                }

                "prompts/get" -> {
                    val prompt = prompts().firstOrNull { it.name == params.get("name")?.asString }
                        ?: return error(id, -32602, "Unknown prompt: ${params.get("name")}")
                    val values = params.getAsJsonObject("arguments")?.entrySet()?.associate { it.key to it.value.asString } ?: emptyMap()
                    val text = try {
                        prompt.render(values)
                    } catch (e: ToolError) {
                        return error(id, -32602, e.message ?: "Bad arguments")
                    }
                    JsonObject().apply {
                        addProperty("description", prompt.description)
                        add("messages", JsonArray().apply {
                            add(JsonObject().apply {
                                addProperty("role", "user")
                                add("content", JsonObject().apply {
                                    addProperty("type", "text")
                                    addProperty("text", text)
                                })
                            })
                        })
                    }
                }

                else -> return error(id, -32601, "Method not found: $method")
            }

            JsonObject().apply {
                addProperty("jsonrpc", "2.0")
                add("id", id)
                add("result", result)
            }
        } catch (e: Exception) {
            logger.log(Level.WARNING, "MCP request $method failed", e)
            error(id, -32603, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun initialize(params: JsonObject): JsonObject {
        val requested = params.get("protocolVersion")?.asString
        return JsonObject().apply {
            addProperty("protocolVersion", if (requested in PROTOCOL_VERSIONS) requested else PROTOCOL_VERSIONS.last())
            add("capabilities", JsonObject().apply {
                add("tools", JsonObject())
                add("prompts", JsonObject())
            })
            add("serverInfo", JsonObject().apply {
                addProperty("name", "foreman")
                addProperty("version", "0.1")
            })
            addProperty("instructions", instructions())
        }
    }

    private fun call(params: JsonObject, caller: Caller?, via: String): JsonObject? {
        val tool = tools[params.get("name")?.asString] ?: return null
        val args = Args(params.getAsJsonObject("arguments") ?: JsonObject()).also { it.caller = caller }

        val start = System.currentTimeMillis()
        val result = try {
            tool.handler(args)
        } catch (e: ToolError) {
            ToolResult.error(e.message ?: "Error")
        } catch (e: Exception) {
            val cause = generateSequence<Throwable>(e) { it.cause }.last()
            logger.log(Level.WARNING, "Tool ${tool.name} failed", e)
            ToolResult.error("${cause.javaClass.simpleName}: ${cause.message}")
        }
        onCall(tool, args, result, System.currentTimeMillis() - start, caller, via)

        return JsonObject().apply {
            add("content", JsonArray().apply { result.content.forEach { add(it) } })
            addProperty("isError", result.isError)
        }
    }

    companion object {
        private val PROTOCOL_VERSIONS = listOf("2024-11-05", "2025-03-26", "2025-06-18", "2025-11-25")

        fun error(id: JsonElement?, code: Int, message: String) = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            add("id", id)
            add("error", JsonObject().apply {
                addProperty("code", code)
                addProperty("message", message)
            })
        }
    }
}

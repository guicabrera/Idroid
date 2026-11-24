package com.idroid.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** The model is never assumed: it's picked from the provider's live list (see [listModels]). */
enum class AiProvider(val label: String) {
    ANTHROPIC("Anthropic"),
    OPENAI("OpenAI"),
    GOOGLE("Google"),
}

data class AiModel(val id: String, val label: String)

/** Asks the provider which models this key can use, newest first where the API says so. */
suspend fun listModels(provider: AiProvider, apiKey: String): List<AiModel> = withContext(Dispatchers.IO) {
    val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    val json = Json { ignoreUnknownKeys = true }
    val request = when (provider) {
        AiProvider.ANTHROPIC -> Request.Builder().url("https://api.anthropic.com/v1/models?limit=100")
            .header("x-api-key", apiKey).header("anthropic-version", "2023-06-01")
        AiProvider.OPENAI -> Request.Builder().url("https://api.openai.com/v1/models")
            .header("Authorization", "Bearer $apiKey")
        AiProvider.GOOGLE -> Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models?pageSize=200")
            .header("x-goog-api-key", apiKey)
    }.get().build()
    val body = http.newCall(request).execute().use { response ->
        val text = response.body?.string().orEmpty()
        if (!response.isSuccessful) throw IOException("${provider.label} ${response.code}: ${text.take(200)}")
        json.parseToJsonElement(text).jsonObject
    }
    fun JsonElement.s(key: String) = (jsonObject[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
    when (provider) {
        AiProvider.ANTHROPIC -> body["data"]?.jsonArray.orEmpty().mapNotNull { m ->
            val id = m.s("id") ?: return@mapNotNull null
            AiModel(id, m.s("display_name") ?: id)
        }
        AiProvider.OPENAI -> {
            // The list mixes in embeddings, audio, images…: keep chat models that can call tools.
            val skip = listOf("audio", "realtime", "tts", "transcribe", "image", "embedding", "search", "moderation", "whisper", "dall-e", "davinci", "babbage", "instruct")
            body["data"]?.jsonArray.orEmpty()
                .filter { m -> val id = m.s("id").orEmpty(); (id.startsWith("gpt-") || id.matches(Regex("^o\\d.*"))) && skip.none { id.contains(it) } }
                .sortedByDescending { (it.jsonObject["created"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0 }
                .map { m -> AiModel(m.s("id")!!, m.s("id")!!) }
        }
        AiProvider.GOOGLE -> body["models"]?.jsonArray.orEmpty()
            .filter { m -> m.jsonObject["supportedGenerationMethods"]?.jsonArray.orEmpty().any { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull == "generateContent" } }
            .mapNotNull { m ->
                val id = m.s("name")?.removePrefix("models/") ?: return@mapNotNull null
                if (!id.startsWith("gemini")) null else AiModel(id, m.s("displayName") ?: id)
            }
    }
}

data class AiSettings(
    val provider: AiProvider = AiProvider.ANTHROPIC,
    val model: String = "",
    val apiKey: String = "",
    /** Runs the agent's commands without asking. Off by default. */
    val autoRun: Boolean = false,
) {
    val configured get() = apiKey.isNotBlank() && model.isNotBlank()
}

/** API keys are credentials: kept in EncryptedSharedPreferences like the pairing token. */
class AiSettingsRepository(context: Context) {
    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "idroid_ai",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun load(): AiSettings {
        val provider = runCatching { AiProvider.valueOf(prefs.getString("provider", null)!!) }.getOrDefault(AiProvider.ANTHROPIC)
        return AiSettings(
            provider = provider,
            model = prefs.getString("model.${provider.name}", null).orEmpty(),
            apiKey = prefs.getString("key.${provider.name}", null).orEmpty(),
            autoRun = prefs.getBoolean("autoRun", false),
        )
    }

    /** Model and key are remembered per provider, so switching back keeps them. */
    fun keyFor(provider: AiProvider) = prefs.getString("key.${provider.name}", null).orEmpty()
    fun modelFor(provider: AiProvider) = prefs.getString("model.${provider.name}", null).orEmpty()

    fun save(settings: AiSettings) {
        prefs.edit()
            .putString("provider", settings.provider.name)
            .putString("model.${settings.provider.name}", settings.model.trim())
            .putString("key.${settings.provider.name}", settings.apiKey.trim())
            .putBoolean("autoRun", settings.autoRun)
            .apply()
    }
}

/** A command the model wants to run; [id] ties the result back to the call. */
data class ToolCall(val id: String, val command: String, val why: String?)

/** One model reply: text for the user and the commands it asked for. */
data class AgentReply(val text: String, val calls: List<ToolCall>)

/**
 * A small agent loop over one tool, run_command, that executes on the Mac.
 * Each provider keeps its own transcript shape; assistant turns are echoed
 * back exactly as received (Anthropic thinking blocks, Gemini thought
 * signatures must survive). Running the commands is the caller's job, so the
 * UI can ask for approval first.
 */
class TerminalAgent(private val settings: AiSettings, private val macHome: String) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .build()
    private val json = Json { ignoreUnknownKeys = true }
    private val history = mutableListOf<JsonElement>()

    private fun system(cwd: String) = """
        You help the user operate their Mac from their Android phone through a terminal.
        Run commands with the run_command tool; they execute with zsh as the user, non-interactively
        (no TTY, no stdin), in the current folder $cwd (home is $macHome). Output comes back to you.
        Useful tools on this Mac: git, gh, brew, the Obsidian CLI (`obsidian`), Claude Code (`claude -p "..."`),
        osascript for controlling apps, open, pmset.
        Prefer read-only commands to investigate before changing anything; never run destructive commands
        (rm -rf, force pushes, disk changes) unless the user explicitly asked for exactly that.
        Keep answers short: this is read on a phone. When the user asks only for a command, propose it via the tool.
    """.trimIndent()

    suspend fun send(userText: String, cwd: String): AgentReply {
        history += userMessage(userText)
        return call(cwd)
    }

    /** Feeds command results back (all of one turn's calls at once) and asks for the next step. */
    suspend fun results(results: List<Pair<ToolCall, String>>, cwd: String): AgentReply {
        history += toolResults(results)
        return call(cwd)
    }

    // --- Provider shapes ---

    private fun userMessage(text: String): JsonElement = when (settings.provider) {
        AiProvider.ANTHROPIC, AiProvider.OPENAI -> buildJsonObject { put("role", "user"); put("content", text) }
        AiProvider.GOOGLE -> buildJsonObject {
            put("role", "user")
            putJsonArray("parts") { addJsonObject { put("text", text) } }
        }
    }

    private fun toolResults(results: List<Pair<ToolCall, String>>): JsonElement = when (settings.provider) {
        AiProvider.ANTHROPIC -> buildJsonObject {
            put("role", "user")
            putJsonArray("content") {
                results.forEach { (call, output) ->
                    addJsonObject {
                        put("type", "tool_result")
                        put("tool_use_id", call.id)
                        put("content", output)
                    }
                }
            }
        }
        // OpenAI wants one "tool" message per call; they are expanded in call().
        AiProvider.OPENAI -> buildJsonArray {
            results.forEach { (call, output) ->
                addJsonObject { put("role", "tool"); put("tool_call_id", call.id); put("content", output) }
            }
        }
        AiProvider.GOOGLE -> buildJsonObject {
            put("role", "user")
            putJsonArray("parts") {
                results.forEach { (_, output) ->
                    addJsonObject {
                        putJsonObject("functionResponse") {
                            put("name", "run_command")
                            putJsonObject("response") { put("output", output) }
                        }
                    }
                }
            }
        }
    }

    private val toolDescription = "Run a shell command on the user's Mac (zsh, non-interactive) and return its output and exit code."

    private fun toolSchema() = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("command") { put("type", "string"); put("description", "The command line to run") }
            putJsonObject("why") { put("type", "string"); put("description", "One short sentence on what it does, shown to the user") }
        }
        putJsonArray("required") { add("command") }
    }

    private suspend fun call(cwd: String): AgentReply = withContext(Dispatchers.IO) {
        when (settings.provider) {
            AiProvider.ANTHROPIC -> anthropic(cwd)
            AiProvider.OPENAI -> openAi(cwd)
            AiProvider.GOOGLE -> gemini(cwd)
        }
    }

    private fun anthropic(cwd: String): AgentReply {
        val body = buildJsonObject {
            put("model", settings.model)
            put("max_tokens", 16000)
            put("system", system(cwd))
            // Route policy declines to a fallback model instead of stopping
            // (only the models that accept the "default" fallback form).
            if (FALLBACK_MODELS.any { settings.model.startsWith(it) }) put("fallbacks", "default")
            putJsonArray("tools") {
                addJsonObject {
                    put("name", "run_command")
                    put("description", toolDescription)
                    put("input_schema", toolSchema())
                }
            }
            put("messages", JsonArray(history))
        }
        val response = post(
            "https://api.anthropic.com/v1/messages", body,
            "x-api-key" to settings.apiKey,
            "anthropic-version" to "2023-06-01",
            "anthropic-beta" to "server-side-fallback-2026-07-01",
        )
        val content = response["content"]?.jsonArray ?: JsonArray(emptyList())
        history += buildJsonObject { put("role", "assistant"); put("content", content) }
        if (response["stop_reason"]?.jsonPrimitive?.contentOrNull == "refusal") {
            return AgentReply("The model declined this request.", emptyList())
        }
        val text = content.mapNotNull { block ->
            block.jsonObject.takeIf { it.str("type") == "text" }?.str("text")
        }.joinToString("\n")
        val calls = content.mapNotNull { block ->
            val o = block.jsonObject
            if (o.str("type") != "tool_use") return@mapNotNull null
            val input = o["input"]?.jsonObject ?: return@mapNotNull null
            ToolCall(o.str("id").orEmpty(), input.str("command").orEmpty(), input.str("why"))
        }
        return AgentReply(text, calls)
    }

    private fun openAi(cwd: String): AgentReply {
        val messages = buildJsonArray {
            addJsonObject { put("role", "system"); put("content", system(cwd)) }
            history.forEach { item -> if (item is JsonArray) item.forEach { add(it) } else add(item) }
        }
        val body = buildJsonObject {
            put("model", settings.model)
            put("messages", messages)
            putJsonArray("tools") {
                addJsonObject {
                    put("type", "function")
                    putJsonObject("function") {
                        put("name", "run_command")
                        put("description", toolDescription)
                        put("parameters", toolSchema())
                    }
                }
            }
        }
        val response = post("https://api.openai.com/v1/chat/completions", body, "Authorization" to "Bearer ${settings.apiKey}")
        val message = response["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject
            ?: throw IOException("empty reply")
        history += message
        val calls = message["tool_calls"]?.jsonArray.orEmpty().mapNotNull { element ->
            val o = element.jsonObject
            val args = o["function"]?.jsonObject?.str("arguments") ?: return@mapNotNull null
            val input = runCatching { json.parseToJsonElement(args).jsonObject }.getOrNull() ?: return@mapNotNull null
            ToolCall(o.str("id").orEmpty(), input.str("command").orEmpty(), input.str("why"))
        }
        return AgentReply(message.str("content").orEmpty(), calls)
    }

    private fun gemini(cwd: String): AgentReply {
        val body = buildJsonObject {
            putJsonObject("systemInstruction") { putJsonArray("parts") { addJsonObject { put("text", system(cwd)) } } }
            put("contents", JsonArray(history))
            putJsonArray("tools") {
                addJsonObject {
                    putJsonArray("functionDeclarations") {
                        addJsonObject {
                            put("name", "run_command")
                            put("description", toolDescription)
                            put("parameters", toolSchema())
                        }
                    }
                }
            }
        }
        val response = post(
            "https://generativelanguage.googleapis.com/v1beta/models/${settings.model}:generateContent", body,
            "x-goog-api-key" to settings.apiKey,
        )
        val content = response["candidates"]?.jsonArray?.firstOrNull()?.jsonObject?.get("content")?.jsonObject
            ?: throw IOException("empty reply")
        history += content
        val parts = content["parts"]?.jsonArray.orEmpty()
        val text = parts.mapNotNull { it.jsonObject.str("text") }.joinToString("\n")
        val calls = parts.mapIndexedNotNull { i, part ->
            val call = part.jsonObject["functionCall"]?.jsonObject ?: return@mapIndexedNotNull null
            val args = call["args"]?.jsonObject ?: return@mapIndexedNotNull null
            ToolCall(call.str("id") ?: "call-$i", args.str("command").orEmpty(), args.str("why"))
        }
        return AgentReply(text, calls)
    }

    private fun post(url: String, body: JsonObject, vararg headers: Pair<String, String>): JsonObject {
        val builder = Request.Builder().url(url).post(body.toString().toRequestBody("application/json".toMediaType()))
        headers.forEach { (k, v) -> builder.header(k, v) }
        http.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = runCatching {
                    json.parseToJsonElement(text).jsonObject["error"]?.let { e ->
                        (e as? JsonObject)?.str("message") ?: e.jsonPrimitive.contentOrNull
                    }
                }.getOrNull()
                throw IOException("${settings.provider.label} ${response.code}: ${message ?: text.take(200)}")
            }
            return json.parseToJsonElement(text).jsonObject
        }
    }

    private companion object {
        val FALLBACK_MODELS = listOf("claude-fable-5", "claude-opus-5", "claude-sonnet-5-5")
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
}

package com.example.core.agent

import android.content.Context
import android.util.Log
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

data class ProviderConfig(
    val provider: String, // "gemini", "openai", "openrouter", "anthropic", "ollama"
    val model: String,
    val apiKey: String,
    val baseUrl: String
)

data class LLMResponse(
    val content: String,
    val thinking: String?,
    val toolCallsJson: String?
)

class LLMRouter(private val context: Context) {
    private val TAG = "LLMRouter"
    private val prefs = context.getSharedPreferences("hermes_router_prefs", Context.MODE_PRIVATE)

    fun getActiveConfig(): ProviderConfig {
        val provider = prefs.getString("provider", "gemini") ?: "gemini"
        val fallbackModel = when (provider) {
            "gemini" -> "gemini-3.5-flash"
            "openai" -> "gpt-4o-mini"
            "openrouter" -> "meta-llama/llama-3.1-8b-instruct:free"
            "anthropic" -> "claude-3-5-haiku"
            "ollama" -> "llama3"
            else -> "gemini-3.5-flash"
        }
        val model = prefs.getString("model", fallbackModel) ?: fallbackModel
        val savedKey = prefs.getString("api_key", "") ?: ""
        
        // Default Key retrieval
        val apiKey = if (savedKey.isNotEmpty()) {
            savedKey
        } else {
            if (provider == "gemini") {
                try {
                    BuildConfig.GEMINI_API_KEY
                } catch (e: Exception) {
                    ""
                }
            } else ""
        }

        val fallbackUrl = when (provider) {
            "gemini" -> "https://generativelanguage.googleapis.com"
            "openai" -> "https://api.openai.com/v1"
            "openrouter" -> "https://openrouter.ai/api/v1"
            "anthropic" -> "https://api.anthropic.com/v1"
            "ollama" -> "http://10.0.2.2:11434"
            else -> ""
        }
        val baseUrl = prefs.getString("base_url", fallbackUrl) ?: fallbackUrl

        return ProviderConfig(provider, model, apiKey, baseUrl)
    }

    fun saveConfig(provider: String, model: String, apiKey: String, baseUrl: String) {
        prefs.edit()
            .putString("provider", provider)
            .putString("model", model)
            .putString("api_key", apiKey)
            .putString("base_url", baseUrl)
            .apply()
    }

    suspend fun complete(
        systemPrompt: String,
        prompt: String,
        history: List<Pair<String, Boolean>> = emptyList(),
        temperature: Float = 0.4f
    ): LLMResponse = withContext(Dispatchers.IO) {
        val config = getActiveConfig()
        
        when (config.provider) {
            "gemini" -> executeGemini(config, systemPrompt, prompt, history, temperature)
            "openai", "openrouter" -> executeOpenAICompatible(config, systemPrompt, prompt, history, temperature)
            "ollama" -> executeOllama(config, systemPrompt, prompt, history)
            else -> executeGemini(config, systemPrompt, prompt, history, temperature)
        }
    }

    private suspend fun executeGemini(
        config: ProviderConfig,
        systemPrompt: String,
        prompt: String,
        history: List<Pair<String, Boolean>>,
        temperature: Float
    ): LLMResponse {
        val apiKey = config.apiKey
        if (apiKey.isEmpty() || apiKey == "AI_STUDIO_INJECTED_KEY") {
            return LLMResponse("Lỗi: Chưa cấu hình GEMINI_API_KEY. Vui lòng cung cấp khóa API trong mục Settings.", null, null)
        }

        val urlString = "${config.baseUrl}/v1beta/models/${config.model}:generateContent?key=$apiKey"
        val root = JSONObject()
        val contentsArray = JSONArray()

        // Translate history messages
        history.forEach { (text, isUser) ->
            val contentObj = JSONObject()
            contentObj.put("role", if (isUser) "user" else "model")
            val partsArray = JSONArray()
            val partObj = JSONObject()
            partObj.put("text", text)
            partsArray.put(partObj)
            contentObj.put("parts", partsArray)
            contentsArray.put(contentObj)
        }

        // Add current message
        val currentContentObj = JSONObject()
        currentContentObj.put("role", "user")
        val currentPartsArray = JSONArray()
        val currentPartObj = JSONObject()
        currentPartObj.put("text", prompt)
        currentPartsArray.put(currentPartObj)
        currentContentObj.put("parts", currentPartsArray)
        contentsArray.put(currentContentObj)
        root.put("contents", contentsArray)

        // Set system instruction
        val systemInstructionObj = JSONObject()
        val systemPartsArray = JSONArray()
        val systemPartObj = JSONObject()
        systemPartObj.put("text", systemPrompt)
        systemPartsArray.put(systemPartObj)
        systemInstructionObj.put("parts", systemPartsArray)
        root.put("systemInstruction", systemInstructionObj)

        // Configuration
        val configObj = JSONObject()
        configObj.put("temperature", temperature)
        root.put("generationConfig", configObj)

        val responseStr = makePostRequest(urlString, root.toString(), mapOf("Content-Type" to "application/json"))
        return parseGeminiResponse(responseStr)
    }

    private suspend fun executeOpenAICompatible(
        config: ProviderConfig,
        systemPrompt: String,
        prompt: String,
        history: List<Pair<String, Boolean>>,
        temperature: Float
    ): LLMResponse {
        val apiKey = config.apiKey
        val urlString = "${config.baseUrl}/chat/completions"

        val root = JSONObject()
        root.put("model", config.model)
        root.put("temperature", temperature)

        val messagesArray = JSONArray()
        
        // System prompt
        val systemMsg = JSONObject()
        systemMsg.put("role", "system")
        systemMsg.put("content", systemPrompt)
        messagesArray.put(systemMsg)

        // History
        history.forEach { (text, isUser) ->
            val msg = JSONObject()
            msg.put("role", if (isUser) "user" else "assistant")
            msg.put("content", text)
            messagesArray.put(msg)
        }

        // Current prompt
        val currentMsg = JSONObject()
        currentMsg.put("role", "user")
        currentMsg.put("content", prompt)
        messagesArray.put(currentMsg)

        root.put("messages", messagesArray)

        val headers = mutableMapOf<String, String>()
        headers["Content-Type"] = "application/json"
        if (apiKey.isNotEmpty()) {
            headers["Authorization"] = "Bearer $apiKey"
        }

        val responseStr = makePostRequest(urlString, root.toString(), headers)
        return parseOpenAIResponse(responseStr)
    }

    private suspend fun executeOllama(
        config: ProviderConfig,
        systemPrompt: String,
        prompt: String,
        history: List<Pair<String, Boolean>>
    ): LLMResponse {
        // Ollama usually uses /api/generate or /api/chat
        val urlString = "${config.baseUrl}/api/chat"
        val root = JSONObject()
        root.put("model", config.model)
        root.put("stream", false)

        val messagesArray = JSONArray()

        val systemMsg = JSONObject()
        systemMsg.put("role", "system")
        systemMsg.put("content", systemPrompt)
        messagesArray.put(systemMsg)

        history.forEach { (text, isUser) ->
            val msg = JSONObject()
            msg.put("role", if (isUser) "user" else "assistant")
            msg.put("content", text)
            messagesArray.put(msg)
        }

        val currentMsg = JSONObject()
        currentMsg.put("role", "user")
        currentMsg.put("content", prompt)
        messagesArray.put(currentMsg)

        root.put("messages", messagesArray)

        val responseStr = makePostRequest(urlString, root.toString(), mapOf("Content-Type" to "application/json"))
        return parseOllamaResponse(responseStr)
    }

    private fun makePostRequest(urlString: String, jsonBody: String, headers: Map<String, String>): String {
        try {
            val url = URL(urlString)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            headers.forEach { (key, value) ->
                conn.setRequestProperty(key, value)
            }
            conn.doOutput = true
            conn.connectTimeout = 25000
            conn.readTimeout = 25000

            OutputStreamWriter(conn.outputStream, "UTF-8").use { os ->
                os.write(jsonBody)
                os.flush()
            }

            val responseCode = conn.responseCode
            val inputStream = if (responseCode in 200..299) conn.inputStream else conn.errorStream ?: conn.inputStream
            
            BufferedReader(InputStreamReader(inputStream, "UTF-8")).use { br ->
                val response = StringBuilder()
                var responseLine: String?
                while (br.readLine().also { responseLine = it } != null) {
                    response.append(responseLine?.trim())
                }
                if (responseCode !in 200..299) {
                    return "{\"error\": \"HTTP Error $responseCode: ${response.toString().replace("\"", "\\\"")}\"}"
                }
                return response.toString()
            }
        } catch (e: Exception) {
            return "{\"error\": \"Lỗi kết nối: ${e.message?.replace("\"", "\\\"")}\"}"
        }
    }

    private fun parseGeminiResponse(raw: String): LLMResponse {
        try {
            val json = JSONObject(raw)
            if (json.has("error")) {
                return LLMResponse(json.getString("error"), null, null)
            }
            val candidates = json.getJSONArray("candidates")
            val candidate = candidates.getJSONObject(0)
            val content = candidate.getJSONObject("content")
            val parts = content.getJSONArray("parts")
            val text = parts.getJSONObject(0).getString("text")
            return extractThoughtsAndActions(text)
        } catch (e: Exception) {
            return LLMResponse("Lỗi phân tích cú pháp Gemini: ${e.message}\nRaw: $raw", null, null)
        }
    }

    private fun parseOpenAIResponse(raw: String): LLMResponse {
        try {
            val json = JSONObject(raw)
            if (json.has("error")) {
                val errorObj = json.optJSONObject("error")
                val message = errorObj?.optString("message") ?: json.getString("error")
                return LLMResponse(message, null, null)
            }
            val choices = json.getJSONArray("choices")
            val choice = choices.getJSONObject(0)
            val message = choice.getJSONObject("message")
            val text = message.getString("content")
            return extractThoughtsAndActions(text)
        } catch (e: Exception) {
            return LLMResponse("Lỗi phân tích cú pháp OpenAI: ${e.message}\nRaw: $raw", null, null)
        }
    }

    private fun parseOllamaResponse(raw: String): LLMResponse {
        try {
            val json = JSONObject(raw)
            if (json.has("error")) {
                return LLMResponse(json.getString("error"), null, null)
            }
            val message = json.getJSONObject("message")
            val text = message.getString("content")
            return extractThoughtsAndActions(text)
        } catch (e: Exception) {
            return LLMResponse("Lỗi phân tích cú pháp Ollama: ${e.message}\nRaw: $raw", null, null)
        }
    }

    private fun extractThoughtsAndActions(input: String): LLMResponse {
        // Look for <hermes_actions>...</hermes_actions> tags
        val startTag = "<hermes_actions>"
        val endTag = "</hermes_actions>"
        
        var cleanContent = input
        var thinking: String? = null
        var toolCallsJson: String? = null

        if (input.contains(startTag) && input.contains(endTag)) {
            val startIndex = input.indexOf(startTag)
            val endIndex = input.indexOf(endTag)
            toolCallsJson = input.substring(startIndex + startTag.length, endIndex).trim()
            
            // Clean content to not output raw tags to user and treat pre-tag as thinking or clean response
            val preTagText = input.substring(0, startIndex).trim()
            val postTagText = input.substring(endIndex + endTag.length).trim()
            
            cleanContent = if (preTagText.isNotEmpty()) preTagText else "Đang thực thi các hành động..."
            if (postTagText.isNotEmpty()) {
                cleanContent += "\n\n$postTagText"
            }
            thinking = preTagText
        }
        
        return LLMResponse(cleanContent, thinking, toolCallsJson)
    }
}

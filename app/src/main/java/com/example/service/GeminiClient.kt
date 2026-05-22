package com.example.service

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

object GeminiClient {
    private const val TAG = "GeminiClient"

    // System instruction detailing Claude-like reasoning and device-control tool calling
    private const val SYSTEM_INSTRUCTION = """
You are "Hermes Assistant", an under-the-hood AI screen assistant running directly on the user's Android phone, designed with the helpfulness and depth of Claude.
Your primary role is to process user commands, explain your thoughts, and decide when to control the phone using accessibility gestures.

IMPORTANT DIRECTIONS:
1. Speak in Vietnamese as requested by the user.
2. Structure your thought process clearly, detailing why you are performing each move.
3. If the user request requires interacting with the phone (e.g. clicking, swiping, opening an app, going back), you MUST "call your tools" by outputting your action plan within a `<hermes_actions>` tag. Inside this tag, put a valid JSON array of actions.
4. If the user request does NOT need phone controls (e.g. simple chat, question, or diagnostic check), do NOT put the `<hermes_actions>` tag.

The exact available actions/tools you can generate are:
- {"type": "CLICK", "x": Float, "y": Float, "delayMs": Long} (taps on coordinate x, y)
- {"type": "SWIPE", "x": Float, "y": Float, "endX": Float, "endY": Float, "durationMs": Long, "delayMs": Long} (swipes from start to end)
- {"type": "ACTION_BACK", "delayMs": Long} (performs system back button)
- {"type": "ACTION_HOME", "delayMs": Long} (goes to home screen)
- {"type": "ACTION_RECENTS", "delayMs": Long} (opens recent apps)
- {"type": "ACTION_NOTIFICATIONS", "delayMs": Long} (pulls down status bar)
- {"type": "OPEN_APP", "packageName": "String", "delayMs": Long} (opens an app, e.g. "com.android.settings" for Settings, "com.google.android.youtube" for YouTube, etc.)

Examples of a device control command:
User is asking to open settings and swipe up:
Response:
Tôi hiểu bạn muốn mở Cài Đặt và cuộn màn hình. Tôi sẽ sử dụng công cụ mở ứng dụng Cài Đặt và tiến hành vuốt màn hình lên.

<hermes_actions>
[
  {"type": "OPEN_APP", "packageName": "com.android.settings", "delayMs": 2000},
  {"type": "SWIPE", "x": 500, "y": 1500, "endX": 500, "endY": 500, "durationMs": 400, "delayMs": 1500},
  {"type": "ACTION_BACK", "delayMs": 1000}
]
</hermes_actions>

Ensure the JSON inside `<hermes_actions>` is valid JSON (e.g., correct commas and brackets).
"""

    suspend fun chatWithGemini(userMessage: String, history: List<Pair<String, Boolean>>): String = withContext(Dispatchers.IO) {
        // Retrieve API key from BuildConfig
        var apiKey = ""
        try {
            apiKey = BuildConfig.GEMINI_API_KEY
        } catch (e: Exception) {
            Log.e(TAG, "Error reading GEMINI_API_KEY from BuildConfig: ${e.message}")
        }

        if (apiKey.isEmpty() || apiKey == "AI_STUDIO_INJECTED_KEY") {
            return@withContext "Lỗi: Chưa cấu hình khóa API Gemini trong Bộ Lưu Trữ Bí Mật AI Studio. Vui lòng cung cấp khóa API trong bảng Secrets."
        }

        val urlString = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent?key=$apiKey"

        try {
            val url = URL(urlString)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json; utf-8")
            conn.setRequestProperty("Accept", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 30000
            conn.readTimeout = 30000

            // Build request payload
            val root = JSONObject()
            val contentsArray = JSONArray()

            // Translate history (Pair: first = message, second = isUser)
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

            // Append current message
            val currentContentObj = JSONObject()
            currentContentObj.put("role", "user")
            val currentPartsArray = JSONArray()
            val currentPartObj = JSONObject()
            currentPartObj.put("text", userMessage)
            currentPartsArray.put(currentPartObj)
            currentContentObj.put("parts", currentPartsArray)
            contentsArray.put(currentContentObj)

            root.put("contents", contentsArray)

            // Setup system instruction
            val systemInstructionObj = JSONObject()
            val systemPartsArray = JSONArray()
            val systemPartObj = JSONObject()
            systemPartObj.put("text", SYSTEM_INSTRUCTION)
            systemPartsArray.put(systemPartObj)
            systemInstructionObj.put("parts", systemPartsArray)
            root.put("systemInstruction", systemInstructionObj)

            // Generation config
            val configObj = JSONObject()
            configObj.put("temperature", 0.4)
            root.put("generationConfig", configObj)

            val jsonString = root.toString()

            OutputStreamWriter(conn.outputStream, "UTF-8").use { os ->
                os.write(jsonString)
                os.flush()
            }

            val responseCode = conn.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { br ->
                    val response = StringBuilder()
                    var responseLine: String?
                    while (br.readLine().also { responseLine = it } != null) {
                        response.append(responseLine?.trim())
                    }
                    val jsonResponse = JSONObject(response.toString())
                    val candidates = jsonResponse.getJSONArray("candidates")
                    val firstCandidate = candidates.getJSONObject(0)
                    val content = firstCandidate.getJSONObject("content")
                    val parts = content.getJSONArray("parts")
                    val resultText = parts.getJSONObject(0).getString("text")
                    return@withContext resultText
                }
            } else {
                BufferedReader(InputStreamReader(conn.errorStream ?: conn.inputStream, "UTF-8")).use { br ->
                    val response = StringBuilder()
                    var responseLine: String?
                    while (br.readLine().also { responseLine = it } != null) {
                        response.append(responseLine?.trim())
                    }
                    return@withContext "Lỗi Máy Chủ API (${conn.responseCode}): ${response.toString()}"
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext "Lỗi kết nối mạng: ${e.localizedMessage ?: e.message}"
        }
    }

    // Parses <hermes_actions>...</hermes_actions> from the model text output
    fun parseActions(aiResponseText: String): List<MacroStep> {
        val steps = mutableListOf<MacroStep>()
        try {
            val startTag = "<hermes_actions>"
            val endTag = "</hermes_actions>"
            if (aiResponseText.contains(startTag) && aiResponseText.contains(endTag)) {
                val startIndex = aiResponseText.indexOf(startTag) + startTag.length
                val endIndex = aiResponseText.indexOf(endTag)
                val jsonArrayText = aiResponseText.substring(startIndex, endIndex).trim()

                val jsonArray = JSONArray(jsonArrayText)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val typeStr = obj.optString("type", "CLICK")
                    val type = try {
                        StepType.valueOf(typeStr)
                    } catch (e: Exception) {
                        StepType.CLICK
                    }

                    val x = obj.optDouble("x", 0.0).toFloat()
                    val y = obj.optDouble("y", 0.0).toFloat()
                    val endX = obj.optDouble("endX", 0.0).toFloat()
                    val endY = obj.optDouble("endY", 0.0).toFloat()
                    val durationMs = obj.optLong("durationMs", 300)
                    val delayMs = obj.optLong("delayMs", 1000)
                    val packageName = obj.optString("packageName", "")

                    steps.add(
                        MacroStep(
                            type = type,
                            x = x,
                            y = y,
                            endX = endX,
                            endY = endY,
                            durationMs = durationMs,
                            delayMs = delayMs,
                            packageName = packageName
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi phân biệt danh sách cú pháp bước: ${e.message}")
        }
        return steps
    }
}

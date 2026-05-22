package com.example.core.agent

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.example.model.HermesState
import com.example.service.HermesAccessibilityService
import com.example.service.MacroStep
import com.example.service.StepType
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.resume

class ToolRegistry(
    private val context: Context,
    private val memoryStore: MemoryStore,
    private val skillsEngine: SkillsEngine
) {
    private val registry = mutableMapOf<String, Tool>()

    init {
        registerTool(TerminalTool())
        registerTool(WebSearchTool())
        registerTool(DeviceGestureTool())
        registerTool(MemoryTool())
        registerTool(SkillsTool())
    }

    fun registerTool(tool: Tool) {
        registry[tool.name] = tool
    }

    fun getActiveTools(): List<Tool> {
        return registry.values.toList()
    }

    fun getTool(name: String): Tool? {
        return registry[name]
    }

    suspend fun execute(name: String, parameters: JSONObject): ToolResponse {
        val tool = getTool(name) ?: return ToolResponse(false, "Không tìm thấy công cụ: $name")
        return try {
            tool.execute(parameters)
        } catch (e: Exception) {
            ToolResponse(false, "Lỗi thực thi công cụ $name: ${e.message}")
        }
    }

    // 1. Terminal Tool
    inner class TerminalTool : Tool {
        override val name = "terminal_command"
        override val description = "Thực thi lệnh shell cục bộ trên hệ thống Android (ví dụ: ping, echo, ls, uname)."
        override val parametersJson = "{\"command\": \"Lệnh shell cần chạy (String)\"}"

        override suspend fun execute(parameters: JSONObject): ToolResponse {
            val command = parameters.optString("command")
            if (command.isNullOrBlank()) {
                return ToolResponse(false, "Tham số command bị trống!")
            }

            return try {
                val process = ProcessBuilder("/system/bin/sh", "-c", command)
                    .redirectErrorStream(true)
                    .start()

                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val output = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    output.append(line).append("\n")
                }
                val exitCode = process.waitFor()
                ToolResponse(true, "Kết quả chạy (Exit code $exitCode):\n${output.toString()}")
            } catch (e: Exception) {
                ToolResponse(false, "Lỗi chạy shell command: ${e.message}")
            }
        }
    }

    // 2. Web Search / Extract Tool
    inner class WebSearchTool : Tool {
        override val name = "web_search"
        override val description = "Tìm kiếm thông tin trực tiếp trên Internet qua cổng DuckDuckGo hoặc trích xuất nội dung từ một liên kết trang web HTML."
        override val parametersJson = "{\"action\": \"'search' hoặc 'extract'\", \"query_or_url\": \"Từ khóa tìm kiếm hoặc đường dẫn URL đầy đủ\"}"

        override suspend fun execute(parameters: JSONObject): ToolResponse {
            val action = parameters.optString("action", "search")
            val target = parameters.optString("query_or_url")

            if (target.isNullOrBlank()) {
                return ToolResponse(false, "Tham số query_or_url không được để trống!")
            }

            return if (action == "search") {
                try {
                    val encoded = URLEncoder.encode(target, "UTF-8")
                    val url = URL("https://html.duckduckgo.com/html/?q=$encoded")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    conn.connectTimeout = 10000
                    conn.readTimeout = 10000

                    val reader = BufferedReader(InputStreamReader(conn.inputStream))
                    val html = StringBuilder()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        html.append(line)
                    }

                    // A very simple html parse for search results
                    val rawHtml = html.toString()
                    val searchResult = StringBuilder()
                    searchResult.append("Kết quả từ DuckDuckGo cho: $target\n\n")
                    
                    var index = 0
                    var foundCount = 0
                    while (index < rawHtml.length && foundCount < 5) {
                        val resultStart = rawHtml.indexOf("class=\"result__snippet\"", index)
                        if (resultStart == -1) break
                        
                        val tagContentStart = rawHtml.indexOf(">", resultStart) + 1
                        val tagContentEnd = rawHtml.indexOf("</a>", tagContentStart)
                        if (tagContentEnd == -1) break

                        val snippet = rawHtml.substring(tagContentStart, tagContentEnd)
                            .replace("<[^>]*>".toRegex(), "") // Strip inner tags
                            .trim()

                        searchResult.append("- ${foundCount + 1}: $snippet\n")
                        index = tagContentEnd
                        foundCount++
                    }

                    if (foundCount == 0) {
                        ToolResponse(true, "Tìm kiếm hoàn tất nhưng không trích xuất được kết quả snippet dạng thô. Hãy thử một hành động khác.")
                    } else {
                        ToolResponse(true, searchResult.toString())
                    }
                } catch (e: Exception) {
                    ToolResponse(false, "Lỗi tìm kiếm DuckDuckGo: ${e.message}")
                }
            } else {
                // Extract
                try {
                    val url = URL(target)
                    val conn = url.openConnection() as HttpURLConnection
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                    conn.connectTimeout = 10000
                    conn.readTimeout = 10000

                    val reader = BufferedReader(InputStreamReader(conn.inputStream))
                    val fullText = StringBuilder()
                    var line: String?
                    var parsedLength = 0
                    while (reader.readLine().also { line = it } != null && parsedLength < 3000) {
                        val cleanLine = line!!.replace("<[^>]*>".toRegex(), "").trim()
                        if (cleanLine.isNotEmpty()) {
                            fullText.append(cleanLine).append("\n")
                            parsedLength += cleanLine.length
                        }
                    }
                    ToolResponse(true, "Trích xuất thành công 3000 kí tự từ trang web:\n\n${fullText.toString()}")
                } catch (e: Exception) {
                    ToolResponse(false, "Lỗi trích xuất trang web: ${e.message}")
                }
            }
        }
    }

    // 3. Device Gesture / Action Tool
    inner class DeviceGestureTool : Tool {
        override val name = "device_gesture"
        override val description = "Thao tác trực tiếp trên thiết bị bao gồm chạm (CLICK), vuốt (SWIPE), phím hệ thống (BACK, HOME, RECENTS, NOTIFICATIONS, OPEN_APP)."
        override val parametersJson = "{\"gesture_type\": \"'CLICK', 'SWIPE', 'BACK', 'HOME', 'RECENTS', 'NOTIFICATIONS', 'OPEN_APP'\", \"x\": Float, \"y\": Float, \"endX\": Float, \"endY\": Float, \"packageName\": \"com.android.settings\"}"

        override suspend fun execute(parameters: JSONObject): ToolResponse {
            val typeStr = parameters.optString("gesture_type", "BACK").uppercase()
            val x = parameters.optDouble("x", 0.0).toFloat()
            val y = parameters.optDouble("y", 0.0).toFloat()
            val endX = parameters.optDouble("endX", 0.0).toFloat()
            val endY = parameters.optDouble("endY", 0.0).toFloat()
            val packageName = parameters.optString("packageName", "")

            val stepType = try {
                when (typeStr) {
                    "CLICK" -> StepType.CLICK
                    "SWIPE" -> StepType.SWIPE
                    "BACK" -> StepType.ACTION_BACK
                    "HOME" -> StepType.ACTION_HOME
                    "RECENTS" -> StepType.ACTION_RECENTS
                    "NOTIFICATIONS" -> StepType.ACTION_NOTIFICATIONS
                    "OPEN_APP" -> StepType.OPEN_APP
                    else -> StepType.ACTION_BACK
                }
            } catch (e: Exception) {
                StepType.ACTION_BACK
            }

            val step = MacroStep(
                type = stepType,
                x = x,
                y = y,
                endX = endX,
                endY = endY,
                durationMs = 300,
                delayMs = 1200,
                packageName = packageName
            )

            val service = HermesAccessibilityService.instance
            if (service == null) {
                return ToolResponse(false, "Dịch vụ Trợ Năng (Accessibility Service) chưa được bật! Vui lòng bật nó ở màn hình Tự Kiểm Thử.")
            }

            return suspendCancellableCoroutineWithTimeout(6000) { continuation ->
                Handler(Looper.getMainLooper()).post {
                    service.activeMacroSteps.clear()
                    service.activeMacroSteps.add(step)
                    service.runMacroSequence(
                        onStepChanged = {
                            // Step started
                        },
                        onFinished = { success, msg ->
                            try {
                                if (continuation.isActive) {
                                    val response = if (success) {
                                        ToolResponse(true, "Đã thực thi cử chỉ hệ thống thành công: $typeStr")
                                    } else {
                                        ToolResponse(false, "Lỗi thực thi cử chỉ: $msg")
                                    }
                                    continuation.resumeWith(Result.success(response))
                                }
                            } catch (e: Exception) {
                                Log.e("ToolRegistry", "Error resuming continuation", e)
                            }
                        }
                    )
                }
            }
        }

        private suspend fun <T> suspendCancellableCoroutineWithTimeout(
            timeoutMs: Long,
            block: (kotlinx.coroutines.CancellableContinuation<T>) -> Unit
        ): T = kotlinx.coroutines.withTimeout(timeoutMs) {
            kotlinx.coroutines.suspendCancellableCoroutine(block)
        }
    }

    // 4. Memory Store Tool
    inner class MemoryTool : Tool {
        override val name = "manage_memories"
        override val description = "Đọc, thêm mới hoặc ghi đè nội dung ký ức vào MEMORY.md hoặc USER.md."
        override val parametersJson = "{\"action\": \"'get', 'add', 'replace', 'clear'\", \"target\": \"'MEMORY' hoặc 'USER'\", \"content\": \"Nội dung ký ức cần thêm mới hoặc cập nhật\", \"old_content\": \"Nội dung cũ cần thay thế (nếu chọn 'replace')\"}"

        override suspend fun execute(parameters: JSONObject): ToolResponse {
            val action = parameters.optString("action", "get")
            val targetStr = parameters.optString("target", "MEMORY").uppercase()
            val content = parameters.optString("content", "")
            val oldContent = parameters.optString("old_content", "")

            val target = if (targetStr == "USER") MemoryTarget.USER else MemoryTarget.MEMORY

            return when (action) {
                "get" -> {
                    val text = if (target == MemoryTarget.USER) memoryStore.getUserProfilePrompt() else memoryStore.getMemoryPrompt()
                    ToolResponse(true, text)
                }
                "add" -> {
                    if (content.isBlank()) return ToolResponse(false, "Nội dung trống không thể lưu!")
                    val result = memoryStore.addEntry(target, content)
                    ToolResponse(true, result)
                }
                "replace" -> {
                    if (oldContent.isBlank() || content.isBlank()) {
                        return ToolResponse(false, "Vui lòng nhập tham số old_content và content đầy đủ để thay thế!")
                    }
                    val result = memoryStore.replaceEntry(target, oldContent, content)
                    ToolResponse(true, result)
                }
                "clear" -> {
                    val result = memoryStore.clearMemory(target)
                    ToolResponse(true, result)
                }
                else -> ToolResponse(false, "Không hỗ trợ hành động $action.")
            }
        }
    }

    // 5. Skills Manager Tool
    inner class SkillsTool : Tool {
        override val name = "manage_skills"
        override val description = "Duyệt danh sách các quy trình skill có sẵn, xem chi tiết hoặc tạo mới một skill manual."
        override val parametersJson = "{\"action\": \"'list', 'view', 'create'\", \"skill_name\": \"Tên skill dạng rắn zalo_login\", \"content\": \"Nội dung skill định dạng Markdown\"}"

        override suspend fun execute(parameters: JSONObject): ToolResponse {
            val action = parameters.optString("action", "list")
            val skillName = parameters.optString("skill_name", "")
            val content = parameters.optString("content", "")

            return when (action) {
                "list" -> {
                    val list = skillsEngine.listSkills().map { "${it.name}: ${it.description} (tags: ${it.tags})" }
                    ToolResponse(true, "Danh sách kịch bản kỹ năng có sẵn:\n" + list.joinToString("\n"))
                }
                "view" -> {
                    if (skillName.isBlank()) return ToolResponse(false, "Vui lòng chỉ định skill_name cần xem!")
                    val body = skillsEngine.loadSkillContent(skillName)
                    if (body != null) {
                        ToolResponse(true, body)
                    } else {
                        ToolResponse(false, "Không tìm thấy skill có tên $skillName")
                    }
                }
                "create" -> {
                    if (skillName.isBlank() || content.isBlank()) {
                        return ToolResponse(false, "Vui lòng truyền đủ tham số skill_name và content để tạo mới!")
                    }
                    val success = skillsEngine.createSkill(skillName, content)
                    if (success) {
                        ToolResponse(true, "Đã tạo cấu hình kịch bản kỹ năng mới: $skillName")
                    } else {
                        ToolResponse(false, "Không thể tạo file skill.")
                    }
                }
                else -> ToolResponse(false, "Không hỗ trợ hành động $action.")
            }
        }
    }
}

// Extensions for prompt mapping in MemoryStore
fun MemoryStore.getMemoryPrompt(): String {
    return "MEMORY (Ghi chú về thói quen):\n${this.getMemoryText()}"
}

fun MemoryStore.getUserProfilePrompt(): String {
    return "USER PROFILE (Cấu hình người dùng):\n${this.getUserText()}"
}

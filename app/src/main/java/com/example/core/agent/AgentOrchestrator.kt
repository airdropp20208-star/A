package com.example.core.agent

import android.content.Context
import android.util.Log
import com.example.model.HermesState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class AgentOrchestrator(
    private val context: Context,
    private val toolRegistry: ToolRegistry,
    private val llmRouter: LLMRouter,
    private val memoryStore: MemoryStore,
    private val skillsEngine: SkillsEngine
) {
    private val TAG = "AgentOrchestrator"

    suspend fun runLoop(
        goal: String,
        maxIterations: Int = 5,
        onThought: (String) -> Unit = {},
        onAction: (String) -> Unit = {},
        onObservation: (String) -> Unit = {}
    ): String = withContext(Dispatchers.IO) {
        
        HermesState.addLog("[AGENT-LOOP] Bắt đầu tự động tối ưu hóa nhiệm vụ: \"$goal\"")
        
        // 1. Match relevant skills and build prompt
        val relevantSkills = skillsEngine.getRelevantSkills(goal)
        val skillsHeader = if (relevantSkills.isNotEmpty()) {
            "BẠN CÓ CÁC KỊCH BẢN KỸ NĂNG LIÊN QUAN SAU:\n" + relevantSkills.joinToString("\n\n")
        } else ""

        // 2. Load memories (MEMORY.md + USER.md)
        val memoryPrompt = """
            ══════════════════════════════════════════════
            BỘ NHỚ QUÁ KHỨ (MEMORY.md)
            ══════════════════════════════════════════════
            ${memoryStore.getMemoryText()}

            ══════════════════════════════════════════════
            THÔNG TIN NGƯỜI DÙNG (USER.md)
            ══════════════════════════════════════════════
            ${memoryStore.getUserText()}
        """.trimIndent()

        // 3. Build unified dynamic system instruction
        val masterSystemPrompt = """
            Bạn là "Hermes Super-Agent", hệ điều hành tác vụ AI tân tiến nhất chạy ngay trên lớp di động Android của người dùng.
            Hệ sinh thái tư duy của bạn mô phỏng theo mô hình ReAct (Reason -> Act -> Observe).
            
            $memoryPrompt
            
            $skillsHeader
            
            ═══════════════════════════════════════
            HƯỚNG DẪN HOẠT ĐỘNG
            ═══════════════════════════════════════
            Khi xử lý yêu cầu cá nhân của người dùng, bạn cần:
            1. Suy nghĩ thật kỹ và trả lời bằng Tiếng Việt.
            2. Nếu cần hành động trên màn hình hoặc công cụ hệ thống, bạn PHẢI kích hoạt thẻ kịch bản hành động `<hermes_actions>` chứa danh sách các hành động có cấu trúc dạng JSON array.
            
            Thẻ `<hermes_actions>` dùng cho cử chỉ thiết bị:
            [
              {"type": "CLICK", "x": Float, "y": Float, "delayMs": Long},
              {"type": "SWIPE", "x": Float, "y": Float, "endX": Float, "endY": Float, "durationMs": Long, "delayMs": Long},
              {"type": "ACTION_BACK", "delayMs": Long},
              {"type": "ACTION_HOME", "delayMs": Long},
              {"type": "ACTION_RECENTS", "delayMs": Long},
              {"type": "ACTION_NOTIFICATIONS", "delayMs": Long},
              {"type": "OPEN_APP", "packageName": "String", "delayMs": Long}
            ]

            NGOÀI RA, bạn có thể gọi các TOOLSET nâng cao bằng cách cấu trúc tool_call vào thẻ `<hermes_actions>` với dạng đặc biệt này:
            - Gọi local terminal command: {"tool_name": "terminal_command", "command": "lệnh shell thô"}
            - Gọi tìm kiếm internet: {"tool_name": "web_search", "action": "search", "query_or_url": "từ khóa"}
            - Ghi nhớ ký ức: {"tool_name": "manage_memories", "action": "add", "target": "MEMORY", "content": "Sự kiện cần nhớ"}

            Ví dụ:
            <hermes_actions>
            [
              {"tool_name": "terminal_command", "command": "uname -a"}
            ]
            </hermes_actions>

            Hãy làm việc khách quan và hiệu quả để đáp ứng mong muốn hoàn tất của người dùng.
        """.trimIndent()

        var currentHistory = mutableListOf<Pair<String, Boolean>>()
        var iteration = 0
        var finalResult = "Chưa hoàn tất tác vụ"

        while (iteration < maxIterations) {
            iteration++
            HermesState.addLog("[REASONING] Đang phân tích bước suy luận số $iteration/$maxIterations...")
            onThought("Đang phân tích bước suy luận $iteration...")

            // Invoke Model
            val response = llmRouter.complete(
                systemPrompt = masterSystemPrompt,
                prompt = goal,
                history = currentHistory,
                temperature = 0.3f
            )

            val textOutput = response.content
            onThought(textOutput)
            currentHistory.add(Pair(textOutput, false))

            // Check if there are tool calls inside the response
            val toolJsonStr = response.toolCallsJson
            if (toolJsonStr != null) {
                try {
                    val array = JSONArray(toolJsonStr)
                    HermesState.addLog("[ACTING] Phát hiện ${array.length()} hành động trong kế hoạch!")
                    
                    val observationsBuffer = StringBuilder()
                    
                    for (i in 0 until array.length()) {
                        val actionObj = array.getJSONObject(i)
                        
                        // Check if it's a specific custom tool call
                        if (actionObj.has("tool_name")) {
                            val toolName = actionObj.getString("tool_name")
                            onAction("Đang gọi công cụ: $toolName")
                            HermesState.addLog("[TOOL-CALL] Thực thi công cụ: $toolName...")
                            
                            val toolResult = toolRegistry.execute(toolName, actionObj)
                            val observationText = "Kết quả từ $toolName: ${toolResult.output}"
                            
                            observationsBuffer.append(observationText).append("\n")
                            onObservation(observationText)
                            HermesState.addLog("[OBSERVATION] Nhận kết quả: ${toolResult.output.take(150)}...")
                        } else {
                            // It's a standard device gesture tool action
                            onAction("Thao tác cử chỉ hệ thống")
                            val gestureResult = toolRegistry.execute("device_gesture", actionObj.put("gesture_type", actionObj.optString("type", "BACK")))
                            val observationText = "Cử chỉ ${actionObj.optString("type")}: ${if (gestureResult.success) "Thành công" else "Thất bại - " + gestureResult.output}"
                            
                            observationsBuffer.append(observationText).append("\n")
                            onObservation(observationText)
                        }
                        
                        delay(1200) // Small settle delay
                    }

                    // Feed the observations back in a new round
                    currentHistory.add(Pair("[OBSERVATIONS ROUND $iteration]:\n" + observationsBuffer.toString(), true))
                    finalResult = textOutput

                } catch (e: Exception) {
                    val errMsg = "Lỗi khi xử lý chuỗi hành động: ${e.message}"
                    Log.e(TAG, errMsg)
                    onObservation(errMsg)
                    HermesState.addLog("[ERROR] $errMsg")
                    break
                }
            } else {
                // No actions returned, task completed!
                finalResult = textOutput
                HermesState.addLog("[COMPLETED] Đại diện thông minh đã đưa ra phản hồi cuối cùng.")
                break
            }
        }

        // Automatic memory learning (nudge)
        try {
            if (currentHistory.size >= 2) {
                val lastUserMsg = goal
                val lastLlmMsg = finalResult
                if (lastUserMsg.lowercase().contains("nhớ") || lastUserMsg.lowercase().contains("lưu")) {
                    memoryStore.addEntry(MemoryTarget.MEMORY, "Đã thực hiện: $lastUserMsg")
                    HermesState.addLog("[PERSISTENCE] Tự động cập nhật ký ức sâu!")
                }
            }
        } catch (e: Exception) {}

        return@withContext finalResult
    }
}

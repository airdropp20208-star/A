package com.example.core.agent

import android.content.Context
import android.util.Log
import java.io.File

data class SkillMeta(
    val name: String,
    val description: String,
    val version: String = "1.0.0",
    val category: String = "general",
    val tags: List<String> = emptyList()
)

class SkillsEngine(private val context: Context) {
    private val skillsDir = File(context.filesDir, "skills")

    init {
        if (!skillsDir.exists()) {
            skillsDir.mkdirs()
        }
        setupDefaultSkills()
    }

    private fun setupDefaultSkills() {
        // Create an initial custom skill
        val defaultSkillName = "zalo_auto_login"
        val defaultSkillFolder = File(skillsDir, defaultSkillName)
        if (!defaultSkillFolder.exists()) {
            defaultSkillFolder.mkdirs()
            val skillFile = File(defaultSkillFolder, "SKILL.md")
            val content = """---
name: zalo-login
description: Tự động chạy và điều hướng đăng nhập Zalo
version: 1.0.0
category: social
tags: [zalo, login, automation]
---

# Zalo Auto Login Skill

## Khi nào dùng
Dùng khi người dùng yêu cầu đăng nhập Zalo hoặc kích hoạt ứng dụng Zalo tự động.

## Các bước thực hiện
1. Thực hiện mở ứng dụng Zalo: OPEN_APP với `com.zing.zalo`
2. Đợi 2 giây cho ứng dụng tải xong.
3. Kích vào nút đăng nhập (tương ứng với tọa độ hoặc nút trên màn hình).
4. Nhập số điện thoại.
5. Gửi mã OTP hoặc lấy mã OTP từ bộ lọc thông báo nếu được cấp quyền.

## Pitfalls
- Nếu Zalo đã được tự động đăng nhập trước đó, bỏ qua các bước gõ mật khẩu.
- Chú ý các lớp bảo mật OTP đặc biệt.
"""
            skillFile.writeText(content)
        }

        val settingsSkillName = "open_settings_and_search"
        val settingsFolder = File(skillsDir, settingsSkillName)
        if (!settingsFolder.exists()) {
            settingsFolder.mkdirs()
            val file = File(settingsFolder, "SKILL.md")
            val content = """---
name: open-settings-search
description: Mở cài đặt hệ thống và cuộn tìm kiếm cổng thông tin
version: 1.0.0
category: system
tags: [settings, search, scroll]
---

# Quy trình mở Cài Đặt và tìm kiếm cấu hình

## Các bước thực hiện
1. Gọi mở gói OPEN_APP `com.android.settings` dãn cách 1500ms.
2. Thực hiện hành động vuốt màn hình SWIPE từ (x=500, y=1500) lên (x=500, y=500) để cuộn tìm kiếm.
3. Chạm CLICK vào ô tìm kiếm hoặc trường thiết lập mong muốn.
"""
            file.writeText(content)
        }
    }

    // List all skills as meta headers
    fun listSkills(): List<SkillMeta> {
        val list = mutableListOf<SkillMeta>()
        try {
            skillsDir.listFiles()?.forEach { subFolder ->
                if (subFolder.isDirectory) {
                    val skillFile = File(subFolder, "SKILL.md")
                    if (skillFile.exists()) {
                        list.add(parseSkillHeader(skillFile.nameWithoutExtension, skillFile.readText()))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("SkillsEngine", "Error listing skills: ${e.message}")
        }
        return list
    }

    fun loadSkillContent(folderName: String): String? {
        val folder = File(skillsDir, folderName)
        val file = File(folder, "SKILL.md")
        return if (file.exists()) file.readText() else null
    }

    fun createSkill(name: String, content: String): Boolean {
        return try {
            val sanitized = name.lowercase().replace(" ", "_").replace("[^a-z0-9_]".toRegex(), "")
            val folder = File(skillsDir, sanitized)
            if (!folder.exists()) folder.mkdirs()
            val file = File(folder, "SKILL.md")
            file.writeText(content)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun improveSkill(name: String, feedback: String, existingContent: String): String {
        // Simulation of skill improvement. In real loop, LLM computes this,
        // but we can provide helper to write it down.
        val sanitized = name.lowercase().replace(" ", "_").replace("[^a-z0-9_]".toRegex(), "")
        val folder = File(skillsDir, sanitized)
        if (!folder.exists()) folder.mkdirs()
        val file = File(folder, "SKILL.md")
        
        val newContent = "$existingContent\n\n## Cập nhật phản hồi sửa đổi\n- $feedback"
        file.writeText(newContent)
        return newContent
    }

    // Auto find skills relevant to the current user goal
    fun getRelevantSkills(goal: String): List<String> {
        val result = mutableListOf<String>()
        val lowercaseGoal = goal.lowercase()
        listSkills().forEach { meta ->
            val hasKeyword = lowercaseGoal.contains(meta.name.lowercase()) ||
                    lowercaseGoal.contains(meta.description.lowercase()) ||
                    meta.tags.any { tag -> lowercaseGoal.contains(tag.lowercase()) }
            if (hasKeyword) {
                loadSkillContent(meta.name.lowercase().replace(" ", "_").replace("[^a-z0-9_]".toRegex(), ""))?.let { content ->
                    result.add(content)
                }
            }
        }
        return result
    }

    private fun parseSkillHeader(fallbackName: String, text: String): SkillMeta {
        var name = fallbackName
        var description = "No description"
        var version = "1.0.0"
        var category = "general"
        val tags = mutableListOf<String>()

        try {
            if (text.startsWith("---")) {
                val endYaml = text.indexOf("---", 3)
                if (endYaml != -1) {
                    val yamlSection = text.substring(3, endYaml)
                    yamlSection.split("\n").forEach { line ->
                        val parts = line.split(":", limit = 2)
                        if (parts.size == 2) {
                            val key = parts[0].trim()
                            val value = parts[1].trim()
                            when (key) {
                                "name" -> name = value
                                "description" -> description = value
                                "version" -> version = value
                                "category" -> category = value
                                "tags" -> {
                                    val tokenized = value.replace("[", "").replace("]", "").split(",")
                                    tokenized.forEach { t -> if (t.trim().isNotEmpty()) tags.add(t.trim()) }
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("SkillsEngine", "Error parsing metadata header: ${e.message}")
        }
        return SkillMeta(name, description, version, category, tags)
    }
}

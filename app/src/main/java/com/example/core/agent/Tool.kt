package com.example.core.agent

import org.json.JSONObject

interface Tool {
    val name: String
    val description: String
    val parametersJson: String // Human readable description of parameters or JSON Schema
    suspend fun execute(parameters: JSONObject): ToolResponse
}

data class ToolResponse(
    val success: Boolean,
    val output: String,
    val errorCode: Int = 0
)

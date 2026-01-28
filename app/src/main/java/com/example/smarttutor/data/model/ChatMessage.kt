package com.example.smarttutor.data.model

data class ChatMessage(
    val id: String = "",
    val role: String = "",
    val content: String = "",
    val createdAtMillis: Long = System.currentTimeMillis()
)

package com.example.smarttutor.data.model

data class ChatThread(
    val id: String = "",
    val title: String = "",
    val lastMessage: String = "",
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis()
)

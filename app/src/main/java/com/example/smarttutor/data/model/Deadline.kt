package com.example.smarttutor.data.model

data class Deadline(
    val id: String = "",
    val title: String = "",
    val chatId: String = "",
    val taskText: String = "",
    val sourceMessageId: String = "",
    val dueAtMillis: Long = 0L,
    val progressPercent: Int = 0,
    val isCompleted: Boolean = false,
    val createdAtMillis: Long = System.currentTimeMillis()
)

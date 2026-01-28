package com.example.smarttutor.data.model

data class Note(
    val id: String = "",
    val title: String = "",
    val content: String = "",
    val createdAtMillis: Long = System.currentTimeMillis()
)

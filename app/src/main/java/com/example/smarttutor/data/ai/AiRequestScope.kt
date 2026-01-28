package com.example.smarttutor.data.ai

import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import okhttp3.Call
import java.util.concurrent.ConcurrentHashMap

object AiRequestScope {
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}

object AiRequestTracker {
    private val sendingMap = mutableStateMapOf<String, Boolean>()
    private val callMap = ConcurrentHashMap<String, Call>()
    private val jobMap = ConcurrentHashMap<String, Job>()

    fun isSending(chatId: String?): Boolean {
        return chatId?.let { sendingMap[it] == true } ?: false
    }

    fun setSending(chatId: String, sending: Boolean) {
        sendingMap[chatId] = sending
        if (!sending) {
            callMap.remove(chatId)
            jobMap.remove(chatId)
        }
    }

    fun setCall(chatId: String, call: Call) {
        callMap[chatId] = call
    }

    fun setJob(chatId: String, job: Job) {
        jobMap[chatId] = job
    }

    fun cancel(chatId: String?) {
        val id = chatId ?: return
        callMap[id]?.cancel()
        jobMap[id]?.cancel()
        setSending(id, false)
    }
}

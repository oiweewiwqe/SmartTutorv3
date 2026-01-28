package com.example.smarttutor.ui.main.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.smarttutor.data.model.Deadline
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RemindersScreen(
    modifier: Modifier = Modifier,
    onOpenChat: (String, String?, String?) -> Unit
) {
    val auth = remember { FirebaseAuth.getInstance() }
    val firestore = remember { FirebaseFirestore.getInstance() }
    val user = auth.currentUser
    val reminders = remember { mutableStateListOf<Deadline>() }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val formatter = remember { SimpleDateFormat("dd.MM.yyyy", Locale("ru")) }

    DisposableEffect(user?.uid) {
        if (user?.uid == null) {
            onDispose {}
        } else {
            val registration = firestore.collection("users").document(user.uid)
                .collection("deadlines")
                .orderBy("dueAtMillis", Query.Direction.ASCENDING)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        errorMessage = "Не удалось загрузить дедлайны."
                        return@addSnapshotListener
                    }
                    val items = snapshot?.documents?.map { doc ->
                        val progress = doc.getLong("progressPercent")?.toInt()
                        val isCompleted = doc.getBoolean("isCompleted") ?: false
                        Deadline(
                            id = doc.id,
                            title = doc.getString("title") ?: "",
                            chatId = doc.getString("chatId") ?: "",
                            taskText = doc.getString("taskText") ?: "",
                            sourceMessageId = doc.getString("sourceMessageId") ?: "",
                            dueAtMillis = doc.getLong("dueAtMillis") ?: 0L,
                            progressPercent = progress ?: if (isCompleted) 100 else 0,
                            isCompleted = isCompleted,
                            createdAtMillis = doc.getLong("createdAtMillis") ?: 0L
                        )
                    } ?: emptyList()
                    reminders.clear()
                    reminders.addAll(items)
                }
            onDispose { registration.remove() }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (user == null) {
            Text("Нужно войти, чтобы видеть дедлайны.")
            return@Column
        }

        Text(text = "Напоминания", style = MaterialTheme.typography.titleMedium)

        if (reminders.isEmpty()) {
            Text("Напоминаний пока нет.")
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
            ) {
                items(reminders) { reminder ->
                    val createdText = if (reminder.createdAtMillis > 0L) {
                        formatter.format(Date(reminder.createdAtMillis))
                    } else {
                        "—"
                    }
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(reminder.title)
                                IconButton(onClick = {
                                    firestore.collection("users").document(user.uid)
                                        .collection("deadlines").document(reminder.id)
                                        .delete()
                                }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Удалить напоминание")
                                }
                            }
                            Text("Создано: $createdText", style = MaterialTheme.typography.labelSmall)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                if (reminder.chatId.isNotBlank()) {
                                    TextButton(onClick = {
                                        onOpenChat(reminder.chatId, reminder.id, reminder.sourceMessageId)
                                    }) {
                                        Text("Открыть чат")
                                    }
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                }
            }
        }

        if (errorMessage != null) {
            Text(text = errorMessage ?: "", color = MaterialTheme.colorScheme.error)
        }
    }

}

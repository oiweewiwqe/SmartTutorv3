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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.smarttutor.data.model.ChatMessage
import com.example.smarttutor.data.model.ChatThread
import com.example.smarttutor.ui.main.components.MarkdownText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ChatsScreen(
    modifier: Modifier = Modifier,
    selectedChatId: String?,
    onChatSelected: (String, String) -> Unit,
    onChatRenamed: (String, String) -> Unit
) {
    val auth = remember { FirebaseAuth.getInstance() }
    val firestore = remember { FirebaseFirestore.getInstance() }
    val user = auth.currentUser
    val chats = remember { mutableStateListOf<ChatThread>() }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameChatId by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }
    var newChatTitle by remember { mutableStateOf("") }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var deleteChatId by remember { mutableStateOf<String?>(null) }
    var deleteChatTitle by remember { mutableStateOf("") }
    val formatter = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("ru")) }

    DisposableEffect(user?.uid) {
        if (user?.uid == null) {
            onDispose {}
        } else {
            val registration = firestore.collection("users").document(user.uid)
                .collection("chats")
                .orderBy("updatedAtMillis", Query.Direction.DESCENDING)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        errorMessage = "Не удалось загрузить список чатов."
                        return@addSnapshotListener
                    }
                    val items = snapshot?.documents?.map { doc ->
                        ChatThread(
                            id = doc.id,
                            title = doc.getString("title") ?: "Без названия",
                            lastMessage = doc.getString("lastMessage") ?: "",
                            createdAtMillis = doc.getLong("createdAtMillis") ?: 0L,
                            updatedAtMillis = doc.getLong("updatedAtMillis") ?: 0L
                        )
                    } ?: emptyList()
                    chats.clear()
                    chats.addAll(items)
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
            Text("Нужно войти, чтобы видеть историю.")
            return@Column
        }

        Text(text = "Чаты", style = MaterialTheme.typography.titleMedium)

        OutlinedTextField(
            value = newChatTitle,
            onValueChange = { newChatTitle = it },
            label = { Text("Название нового чата") },
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = {
                val title = newChatTitle.trim()
                if (title.isEmpty()) {
                    errorMessage = "Введите название чата."
                    return@Button
                }
                val data = mapOf(
                    "title" to title,
                    "createdAtMillis" to System.currentTimeMillis(),
                    "updatedAtMillis" to System.currentTimeMillis()
                )
                firestore.collection("users").document(user.uid)
                    .collection("chats")
                    .add(data)
                    .addOnSuccessListener { doc ->
                        onChatSelected(doc.id, title)
                        newChatTitle = ""
                        errorMessage = null
                    }
                    .addOnFailureListener {
                        errorMessage = "Не удалось создать чат."
                    }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Создать чат")
        }

        if (chats.isEmpty()) {
            Text("Чатов пока нет.")
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(chats) { chat ->
                    val isSelected = chat.id == selectedChatId
                    val updatedText = if (chat.updatedAtMillis > 0L) {
                        formatter.format(Date(chat.updatedAtMillis))
                    } else {
                        "—"
                    }
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            }
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                TextButton(onClick = { onChatSelected(chat.id, chat.title) }) {
                                    Text(chat.title)
                                }
                                Row {
                                    IconButton(onClick = {
                                        renameChatId = chat.id
                                        renameText = chat.title
                                        showRenameDialog = true
                                    }) {
                                        Icon(Icons.Default.Edit, contentDescription = "Переименовать")
                                    }
                                    IconButton(onClick = {
                                        deleteChatId = chat.id
                                        deleteChatTitle = chat.title
                                        showDeleteDialog = true
                                    }) {
                                        Icon(Icons.Default.Delete, contentDescription = "Удалить")
                                    }
                                }
                            }
                            if (chat.lastMessage.isNotBlank()) {
                                Text(
                                    text = chat.lastMessage,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Обновлено: $updatedText",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }
        }

        if (errorMessage != null) {
            Text(text = errorMessage ?: "", color = MaterialTheme.colorScheme.error)
        }
    }

    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            confirmButton = {
                TextButton(onClick = {
                    val id = renameChatId ?: return@TextButton
                    val title = renameText.trim()
                    if (title.isEmpty()) {
                        errorMessage = "Название не может быть пустым."
                        return@TextButton
                    }
                    firestore.collection("users").document(user?.uid ?: return@TextButton)
                        .collection("chats").document(id)
                        .update(
                            mapOf(
                                "title" to title,
                                "updatedAtMillis" to System.currentTimeMillis()
                            )
                        )
                        .addOnSuccessListener {
                            onChatRenamed(id, title)
                            showRenameDialog = false
                        }
                        .addOnFailureListener {
                            errorMessage = "Не удалось переименовать чат."
                        }
                }) {
                    Text("Сохранить")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text("Отмена")
                }
            },
            title = { Text("Переименовать чат") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("Название") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            confirmButton = {
                TextButton(onClick = {
                    val id = deleteChatId ?: return@TextButton
                    val uid = user?.uid ?: return@TextButton
                    firestore.collection("users").document(uid)
                        .collection("chats").document(id)
                        .collection("messages")
                        .get()
                        .addOnSuccessListener { snapshot ->
                            snapshot.documents.forEach { doc ->
                                doc.reference.delete()
                            }
                            firestore.collection("users").document(uid)
                                .collection("chats").document(id)
                                .delete()
                            firestore.collection("users").document(uid)
                                .collection("learningPlans")
                                .whereEqualTo("sourceChatId", id)
                                .get()
                                .addOnSuccessListener { plansSnapshot ->
                                    plansSnapshot.documents.forEach { planDoc ->
                                        val planId = planDoc.id
                                        firestore.collection("users").document(uid)
                                            .collection("deadlines")
                                            .whereEqualTo("planId", planId)
                                            .get()
                                            .addOnSuccessListener { remindersSnapshot ->
                                                remindersSnapshot.documents.forEach { reminderDoc ->
                                                    reminderDoc.reference.delete()
                                                }
                                            }
                                        planDoc.reference.delete()
                                    }
                                }
                            firestore.collection("users").document(uid)
                                .collection("deadlines")
                                .whereEqualTo("chatId", id)
                                .get()
                                .addOnSuccessListener { remindersSnapshot ->
                                    remindersSnapshot.documents.forEach { reminderDoc ->
                                        reminderDoc.reference.delete()
                                    }
                                }
                            if (selectedChatId == id) {
                                onChatSelected("", "")
                            }
                            showDeleteDialog = false
                        }
                        .addOnFailureListener {
                            errorMessage = "Не удалось удалить чат."
                        }
                }) {
                    Text("Удалить")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Отмена")
                }
            },
            title = { Text("Удалить чат") },
            text = { Text("Удалить чат «$deleteChatTitle» и все сообщения?") }
        )
    }
}

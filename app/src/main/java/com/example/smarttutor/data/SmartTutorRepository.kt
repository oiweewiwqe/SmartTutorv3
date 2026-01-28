package com.example.smarttutor.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query

class SmartTutorRepository(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
) {
    private fun requireUserId(): String = auth.currentUser?.uid
        ?: error("User must be signed in to access SmartTutor data.")

    fun notesQuery(): Query =
        firestore.collection("users").document(requireUserId())
            .collection("notes")
            .orderBy("createdAtMillis", Query.Direction.DESCENDING)

    fun chatHistoryQuery(): Query =
        firestore.collection("users").document(requireUserId())
            .collection("chatHistory")
            .orderBy("createdAtMillis", Query.Direction.DESCENDING)

    fun deadlinesQuery(): Query =
        firestore.collection("users").document(requireUserId())
            .collection("deadlines")
            .orderBy("dueAtMillis", Query.Direction.ASCENDING)

    fun learningPathDoc() =
        firestore.collection("users").document(requireUserId())
            .collection("learningPath")
            .document("current")

    fun learningPlansQuery() =
        firestore.collection("users").document(requireUserId())
            .collection("learningPlans")
            .orderBy("createdAtMillis", Query.Direction.DESCENDING)
}

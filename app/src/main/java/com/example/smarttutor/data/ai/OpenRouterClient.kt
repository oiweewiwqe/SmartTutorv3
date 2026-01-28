package com.example.smarttutor.data.ai

import com.example.smarttutor.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.SocketTimeoutException
import java.io.IOException
import java.util.concurrent.TimeUnit
import com.example.smarttutor.data.model.LearningPath
import com.example.smarttutor.data.model.LearningStep

class OpenRouterClient(
    private val apiKey: String = BuildConfig.OPENROUTER_API_KEY,
    private val model: String = BuildConfig.OPENROUTER_MODEL,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(45, TimeUnit.SECONDS)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()
) {
    data class AnswerEvaluation(
        val progressPercent: Int,
        val feedback: String
    )

    suspend fun generateResponse(
        messages: List<Map<String, String>>,
        includePlanOffer: Boolean = true,
        followUpMode: Boolean = false,
        onCallReady: (Call) -> Unit = {}
    ): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext "Ключ OpenRouter не задан. Добавьте OPENROUTER_API_KEY в local.properties."
        }
        if (model.isBlank()) {
            return@withContext "Модель OpenRouter не задана. Добавьте OPENROUTER_MODEL в local.properties."
        }

        val basePrompt = """
            Ты — Smart Tutor, дружелюбный помощник для студентов.
            Отвечай только на русском языке.
            Пиши коротко и по делу, без воды. Проверяй, что ответ точно соответствует вопросу.
            Не повторяй одни и те же фразы или строки. Никакого спама и дублирования.
            Если ученик просит пример или уточнение по той же теме, не повторяй весь ответ —
            дай краткое дополнение и новые примеры или углубление.
            Если информации недостаточно, скажи об этом и задай ОДИН уточняющий вопрос.
            Всегда соблюдай аккуратное форматирование в Markdown.
            Объясняй через понятия и пункты, а не сплошным текстом.
            Дай 1–2 коротких примера и кратко поясни их. Если тема про программирование —
            обязательно показывай код в блоках ``` ```.
            Внутри кода используй ТОЛЬКО английские имена переменных и функций.
            Если тема не про программирование — используй псевдокод, формулу или мини-пример
            с данными, также в ``` ``` блоке (имена переменных — только на английском).
            Обязательная структура ответа:
            1) Ключевые понятия (3–5 коротких пунктов).
            2) Что это и зачем нужно (2–4 коротких предложения).
            3) Пример(ы).
        """.trimIndent()
        val systemPrompt = when {
            followUpMode -> {
                """
                Ты продолжаешь ответ в том же чате по уже объясненной теме.
                Отвечай кратко, только по уточнению ученика, без полного повтора структуры.
                Добавляй новые примеры или детали, но не дублируй прошлый ответ.
                Не предлагай план обучения и не задавай этот вопрос.
                """.trimIndent()
            }
            includePlanOffer -> {
                basePrompt + "\n4) Вопрос: \"Хочешь, я создам план обучения, который будет в профиле?\""
            }
            else -> {
                basePrompt + "\nНе предлагай план обучения и не задавай этот вопрос."
            }
        }

        val payload = JSONObject(
            mapOf(
                "model" to model,
                "messages" to listOf(
                    mapOf(
                        "role" to "system",
                        "content" to systemPrompt
                    )
                ) + messages,
                "temperature" to 0.4,
                "max_tokens" to 800
            )
        )

        val request = Request.Builder()
            .url("https://openrouter.ai/api/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .addHeader("HTTP-Referer", "https://smarttutor.local")
            .addHeader("X-Title", "SmartTutor")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        suspendCancellableCoroutine { continuation ->
            val call = httpClient.newCall(request)
            onCallReady(call)
            continuation.invokeOnCancellation { call.cancel() }

            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isCancelled) return
                    val message = if (e is SocketTimeoutException) {
                        "Ответ от ИИ слишком долго не приходит. Попробуйте еще раз."
                    } else {
                        "Не удалось связаться с ИИ. Проверьте интернет и повторите попытку."
                    }
                    continuation.resume(message, onCancellation = null)
                }

                override fun onResponse(call: Call, response: okhttp3.Response) {
                    if (continuation.isCancelled) return
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        val errorMessage = runCatching {
                            JSONObject(body).optJSONObject("error")?.optString("message")
                        }.getOrNull()
                        continuation.resume(
                            errorMessage?.takeIf { it.isNotBlank() } ?: "Ошибка сервиса: ${response.code}",
                            onCancellation = null
                        )
                        return
                    }
                    val text = runCatching {
                        val root = JSONObject(body)
                        val choices = root.getJSONArray("choices")
                        choices.getJSONObject(0)
                            .getJSONObject("message")
                            .getString("content")
                    }.getOrNull()
                    continuation.resume(
                        text?.trim().orEmpty().ifEmpty { "Извините, я не смог сформировать ответ." },
                        onCancellation = null
                    )
                }
            })
        }
    }

    suspend fun generateChatTitle(question: String, answer: String, onCallReady: (Call) -> Unit = {}): String =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank() || model.isBlank()) {
                return@withContext ""
            }

            val systemPrompt = """
                Ты генерируешь короткое название чата на русском.
                Название — 3–6 слов, без кавычек и без точки в конце.
            """.trimIndent()
            val userPrompt = """
                Вопрос ученика: $question
                Краткий ответ: $answer
                Название:
            """.trimIndent()

            val payload = JSONObject(
                mapOf(
                    "model" to model,
                    "messages" to listOf(
                        mapOf("role" to "system", "content" to systemPrompt),
                        mapOf("role" to "user", "content" to userPrompt)
                    ),
                    "temperature" to 0.2,
                    "max_tokens" to 32
                )
            )

            val request = Request.Builder()
                .url("https://openrouter.ai/api/v1/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .addHeader("HTTP-Referer", "https://smarttutor.local")
                .addHeader("X-Title", "SmartTutor")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            suspendCancellableCoroutine { continuation ->
                val call = httpClient.newCall(request)
                onCallReady(call)
                continuation.invokeOnCancellation { call.cancel() }

                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isCancelled) return
                        continuation.resume("", onCancellation = null)
                    }

                    override fun onResponse(call: Call, response: okhttp3.Response) {
                        if (continuation.isCancelled) return
                        val body = response.body?.string().orEmpty()
                        if (!response.isSuccessful) {
                            continuation.resume("", onCancellation = null)
                            return
                        }
                        val text = runCatching {
                            val root = JSONObject(body)
                            val choices = root.getJSONArray("choices")
                            choices.getJSONObject(0)
                                .getJSONObject("message")
                                .getString("content")
                        }.getOrNull()
                        continuation.resume(text?.trim().orEmpty(), onCancellation = null)
                    }
                })
            }
        }

    suspend fun generateReminderTitle(task: String, onCallReady: (Call) -> Unit = {}): String =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank() || model.isBlank()) {
                return@withContext ""
            }

            val systemPrompt = """
                Ты генерируешь короткое название напоминания на русском.
                Название — 3–6 слов, без кавычек и без точки в конце.
            """.trimIndent()
            val userPrompt = """
                Задание/задача: $task
                Название:
            """.trimIndent()

            val payload = JSONObject(
                mapOf(
                    "model" to model,
                    "messages" to listOf(
                        mapOf("role" to "system", "content" to systemPrompt),
                        mapOf("role" to "user", "content" to userPrompt)
                    ),
                    "temperature" to 0.2,
                    "max_tokens" to 24
                )
            )

            val request = Request.Builder()
                .url("https://openrouter.ai/api/v1/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .addHeader("HTTP-Referer", "https://smarttutor.local")
                .addHeader("X-Title", "SmartTutor")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            suspendCancellableCoroutine { continuation ->
                val call = httpClient.newCall(request)
                onCallReady(call)
                continuation.invokeOnCancellation { call.cancel() }

                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isCancelled) return
                        continuation.resume("", onCancellation = null)
                    }

                    override fun onResponse(call: Call, response: okhttp3.Response) {
                        if (continuation.isCancelled) return
                        val body = response.body?.string().orEmpty()
                        if (!response.isSuccessful) {
                            continuation.resume("", onCancellation = null)
                            return
                        }
                        val text = runCatching {
                            val root = JSONObject(body)
                            val choices = root.getJSONArray("choices")
                            choices.getJSONObject(0)
                                .getJSONObject("message")
                                .getString("content")
                        }.getOrNull()
                        continuation.resume(text?.trim().orEmpty(), onCancellation = null)
                    }
                })
            }
        }

    suspend fun generatePlanStepMessage(
        goal: String,
        stepTitle: String,
        stepKind: String,
        stepDescription: String?,
        onCallReady: (Call) -> Unit = {}
    ): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || model.isBlank()) {
            return@withContext "Не удалось подготовить шаг: ключ или модель не заданы."
        }

        val systemPrompt = """
            Ты ведешь ученика строго по плану обучения.
            Отвечай только на русском языке.
            Используй Markdown, структурируй ответ.
            Следуй только текущему шагу и теме плана, не отклоняйся.
            Никаких дополнительных вопросов, советов и мотивации вне шага.
            Не спрашивай уровень, цели, сроки. Не предлагай другие темы.
            Если показываешь код — только в ``` ``` блоках, имена переменных и функций ТОЛЬКО на английском.
            Не задавай лишних вопросов и не проси подтверждений.
            Формат зависит от типа шага:
            - explanation: только короткое объяснение + 1–2 примера, без заданий.
            - task: только одно короткое задание без решения.
            - test: только 3–5 коротких вопросов, без объяснений.
            - final_exam: только 5–7 вопросов/заданий, без ответов и пояснений.
        """.trimIndent()

        val userPrompt = """
            Тема плана: $goal
            Шаг: $stepTitle
            Тип: $stepKind
            Описание: ${stepDescription ?: "нет"}
        """.trimIndent()

        val payload = JSONObject(
            mapOf(
                "model" to model,
                "messages" to listOf(
                    mapOf("role" to "system", "content" to systemPrompt),
                    mapOf("role" to "user", "content" to userPrompt)
                ),
                "temperature" to 0.3,
                "max_tokens" to 600
            )
        )

        val request = Request.Builder()
            .url("https://openrouter.ai/api/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .addHeader("HTTP-Referer", "https://smarttutor.local")
            .addHeader("X-Title", "SmartTutor")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        suspendCancellableCoroutine { continuation ->
            val call = httpClient.newCall(request)
            onCallReady(call)
            continuation.invokeOnCancellation { call.cancel() }

            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isCancelled) return
                    continuation.resume(
                        "Не удалось подготовить шаг. Проверьте интернет и повторите попытку.",
                        onCancellation = null
                    )
                }

                override fun onResponse(call: Call, response: okhttp3.Response) {
                    if (continuation.isCancelled) return
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        continuation.resume(
                            "Ошибка сервиса: ${response.code}",
                            onCancellation = null
                        )
                        return
                    }
                    val text = runCatching {
                        val root = JSONObject(body)
                        val choices = root.getJSONArray("choices")
                        choices.getJSONObject(0)
                            .getJSONObject("message")
                            .getString("content")
                    }.getOrNull()
                    continuation.resume(
                        text?.trim().orEmpty().ifEmpty { "Не удалось подготовить шаг." },
                        onCancellation = null
                    )
                }
            })
        }
    }

    suspend fun generateLearningPath(topic: String, onCallReady: (Call) -> Unit = {}): LearningPath? =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank() || model.isBlank()) {
                return@withContext null
            }

            val systemPrompt = """
                Ты создаёшь краткий план обучения для ученика.
                Верни ТОЛЬКО JSON:
                {"goal":"строка","steps":[{"title":"строка","kind":"explanation|task|test|final_exam","description":"строка"}]}
                Правила:
                - Все поля обязательны.
                - Ровно 5 шагов.
                - Включи объяснения, задания, тесты и финальный экзамен.
                - Пиши по-русски, кратко и понятно.
                - Никаких лишних символов вокруг JSON.
            """.trimIndent()
            val userPrompt = """
                Тема: $topic
                JSON:
            """.trimIndent()

            val payload = JSONObject(
                mapOf(
                    "model" to model,
                    "messages" to listOf(
                        mapOf("role" to "system", "content" to systemPrompt),
                        mapOf("role" to "user", "content" to userPrompt)
                    ),
                    "temperature" to 0.3,
                    "max_tokens" to 500
                )
            )

            val request = Request.Builder()
                .url("https://openrouter.ai/api/v1/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .addHeader("HTTP-Referer", "https://smarttutor.local")
                .addHeader("X-Title", "SmartTutor")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            suspendCancellableCoroutine { continuation ->
                val call = httpClient.newCall(request)
                onCallReady(call)
                continuation.invokeOnCancellation { call.cancel() }

                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isCancelled) return
                        continuation.resume(null, onCancellation = null)
                    }

                    override fun onResponse(call: Call, response: okhttp3.Response) {
                        if (continuation.isCancelled) return
                        val body = response.body?.string().orEmpty()
                        if (!response.isSuccessful) {
                            continuation.resume(null, onCancellation = null)
                            return
                        }
                        val path = runCatching {
                            val content = JSONObject(body)
                                .getJSONArray("choices")
                                .getJSONObject(0)
                                .getJSONObject("message")
                                .getString("content")
                            val jsonText = content.substringAfter("{", "")
                                .let { "{" + it }
                                .substringBeforeLast("}") + "}"
                            val json = JSONObject(jsonText)
                            val goal = json.optString("goal", "").trim()
                            val stepsJson = json.optJSONArray("steps")
                            val steps = mutableListOf<LearningStep>()
                            if (stepsJson != null) {
                                for (i in 0 until stepsJson.length()) {
                                    val item = stepsJson.optJSONObject(i) ?: continue
                                    val title = item.optString("title", "").trim()
                                    if (title.isBlank()) continue
                                    val kind = item.optString("kind", "step").trim().ifBlank { "step" }
                                    val description = item.optString("description", "").trim()
                                    steps += LearningStep(
                                        title = title,
                                        order = i,
                                        kind = kind,
                                        description = description.ifBlank { null }
                                    )
                                }
                            }
                            LearningPath(
                                goal = goal,
                                steps = steps,
                                createdAtMillis = System.currentTimeMillis(),
                                updatedAtMillis = System.currentTimeMillis()
                            )
                        }.getOrNull()
                        val resolved = if (path == null || path.goal.isBlank() || path.steps.isEmpty()) {
                            fallbackLearningPath(topic)
                        } else {
                            path
                        }
                        continuation.resume(resolved, onCancellation = null)
                    }
                })
            }
        }

    private fun fallbackLearningPath(topic: String): LearningPath {
        val goal = "Изучить тему: ${topic.trim().ifBlank { "основы" }}"
        val steps = listOf(
            LearningStep(
                title = "Что это такое и где применяется",
                order = 0,
                kind = "explanation",
                description = "Краткое объяснение и практические применения."
            ),
            LearningStep(
                title = "Практика: простое задание",
                order = 1,
                kind = "task",
                description = "Сделай короткое задание по теме."
            ),
            LearningStep(
                title = "Мини‑тест",
                order = 2,
                kind = "test",
                description = "2–3 коротких вопроса по теме."
            ),
            LearningStep(
                title = "Практика: задача средней сложности",
                order = 3,
                kind = "task",
                description = "Немного более сложное задание по теме."
            ),
            LearningStep(
                title = "Итоговый экзамен",
                order = 4,
                kind = "final_exam",
                description = "Проверка ключевых навыков по теме."
            )
        )
        val now = System.currentTimeMillis()
        return LearningPath(
            goal = goal,
            steps = steps,
            createdAtMillis = now,
            updatedAtMillis = now
        )
    }

    suspend fun evaluateAnswer(task: String, answer: String, onCallReady: (Call) -> Unit = {}): AnswerEvaluation =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank() || model.isBlank()) {
                return@withContext AnswerEvaluation(
                    progressPercent = 0,
                    feedback = "Не удалось проверить ответ: ключ или модель не заданы."
                )
            }

            val systemPrompt = """
                Ты проверяешь ответ ученика на задание.
                Верни ТОЛЬКО JSON вида: {"progress": 0|50|100, "feedback": "текст"}.
                Будь доброжелательным и не слишком строгим.
                Если ответ неверный или не по задаче — progress = 0 и 1–3 практичных подсказки,
                которые помогают прийти к верному решению (не просто "неверно").
                Если ответ частично верный — progress = 50 и что нужно поправить + 1 подсказка.
                Если ответ полностью верный — progress = 100 и короткое подтверждение.
                Если ученик просит ответ (в любой формулировке и на любом языке) —
                дай правильный ответ кратко и понятно.
                Не добавляй новые задания, вопросы или темы — только проверка текущего задания.
                feedback всегда на русском. Кратко: 1–3 предложения, без лишних символов.
                Формат — аккуратный Markdown. Если есть код/формула — оборачивай в ``` ``` блок.
            """.trimIndent()
            val userPrompt = """
                Задание: $task
                Ответ ученика: $answer
                JSON:
            """.trimIndent()

            val payload = JSONObject(
                mapOf(
                    "model" to model,
                    "messages" to listOf(
                        mapOf("role" to "system", "content" to systemPrompt),
                        mapOf("role" to "user", "content" to userPrompt)
                    ),
                    "temperature" to 0.2,
                    "max_tokens" to 120
                )
            )

            val request = Request.Builder()
                .url("https://openrouter.ai/api/v1/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .addHeader("HTTP-Referer", "https://smarttutor.local")
                .addHeader("X-Title", "SmartTutor")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            suspendCancellableCoroutine { continuation ->
                val call = httpClient.newCall(request)
                onCallReady(call)
                continuation.invokeOnCancellation { call.cancel() }

                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isCancelled) return
                        continuation.resume(
                            AnswerEvaluation(0, "Не удалось проверить ответ. Попробуйте еще раз."),
                            onCancellation = null
                        )
                    }

                    override fun onResponse(call: Call, response: okhttp3.Response) {
                        if (continuation.isCancelled) return
                        val body = response.body?.string().orEmpty()
                        if (!response.isSuccessful) {
                            continuation.resume(
                                AnswerEvaluation(0, "Ошибка проверки ответа: ${response.code}"),
                                onCancellation = null
                            )
                            return
                        }
                        val evaluation = runCatching {
                            val content = JSONObject(body)
                                .getJSONArray("choices")
                                .getJSONObject(0)
                                .getJSONObject("message")
                                .getString("content")
                            val jsonText = content.substringAfter("{", "")
                                .let { "{" + it }
                                .substringBeforeLast("}") + "}"
                            val json = JSONObject(jsonText)
                            val rawProgress = json.optInt("progress", 0)
                            val progress = when {
                                rawProgress >= 100 -> 100
                                rawProgress >= 50 -> 50
                                else -> 0
                            }
                            val feedback = json.optString("feedback", "").trim()
                            AnswerEvaluation(progress, feedback)
                        }.getOrNull()

                        continuation.resume(
                            evaluation ?: AnswerEvaluation(0, "Не удалось разобрать ответ проверки."),
                            onCancellation = null
                        )
                    }
                })
            }
        }

    suspend fun generateTaskAnswer(task: String, onCallReady: (Call) -> Unit = {}): String =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank() || model.isBlank()) {
                return@withContext "Не удалось выдать ответ: ключ или модель не заданы."
            }

            val systemPrompt = """
                Ты даешь готовый ответ на задание ученика.
                Отвечай только по задаче, без отказов и без лишних объяснений.
                Пиши по-русски, кратко и ясно.
                Если это программирование — показывай код в ``` ``` блоках,
                имена переменных и функций ТОЛЬКО на английском.
            """.trimIndent()
            val userPrompt = """
                Задание: $task
                Ответ:
            """.trimIndent()

            val payload = JSONObject(
                mapOf(
                    "model" to model,
                    "messages" to listOf(
                        mapOf("role" to "system", "content" to systemPrompt),
                        mapOf("role" to "user", "content" to userPrompt)
                    ),
                    "temperature" to 0.2,
                    "max_tokens" to 200
                )
            )

            val request = Request.Builder()
                .url("https://openrouter.ai/api/v1/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .addHeader("HTTP-Referer", "https://smarttutor.local")
                .addHeader("X-Title", "SmartTutor")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            suspendCancellableCoroutine { continuation ->
                val call = httpClient.newCall(request)
                onCallReady(call)
                continuation.invokeOnCancellation { call.cancel() }

                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isCancelled) return
                        continuation.resume(
                            "Не удалось получить ответ. Попробуйте еще раз.",
                            onCancellation = null
                        )
                    }

                    override fun onResponse(call: Call, response: okhttp3.Response) {
                        if (continuation.isCancelled) return
                        val body = response.body?.string().orEmpty()
                        if (!response.isSuccessful) {
                            continuation.resume(
                                "Ошибка сервиса: ${response.code}",
                                onCancellation = null
                            )
                            return
                        }
                        val text = runCatching {
                            val root = JSONObject(body)
                            val choices = root.getJSONArray("choices")
                            choices.getJSONObject(0)
                                .getJSONObject("message")
                                .getString("content")
                        }.getOrNull()
                        continuation.resume(
                            text?.trim().orEmpty().ifEmpty { "Не удалось сформировать ответ." },
                            onCancellation = null
                        )
                    }
                })
            }
        }
}

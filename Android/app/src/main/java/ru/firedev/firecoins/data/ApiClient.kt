package ru.firedev.firecoins.data

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ApiClient(context: Context) {
    private val sessionStore = SessionStore(context.applicationContext)
    private val refreshMutex = Mutex()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private data class HttpResult(
        val statusCode: Int,
        val contentType: String?,
        val body: ByteArray?,
        val errorBody: String
    )

    val hasSession: Boolean
        get() = !sessionStore.refreshToken.isNullOrBlank()

    suspend fun signIn(invitationCode: String): JSONObject {
        val response = call(
            path = "/api/mobile/auth",
            method = "POST",
            payload = JSONObject().put("invitation_code", invitationCode),
            authenticated = false
        )
        val user = response.optJSONObject("user") ?: throw IOException("Сервер не вернул аккаунт.")
        val session = response.optJSONObject("session") ?: throw IOException("Сервер не вернул сессию.")
        storeSession(session)
        return user
    }

    suspend fun reissueInvitation(expectedRole: String): String {
        val response = call(
            path = "/api/mobile/invitations/reissue",
            method = "POST",
            payload = JSONObject()
        )
        val invitation = response.optJSONObject("invitation")
            ?: throw IOException("Сервер не вернул новое приглашение.")
        if (invitation.optString("role") != expectedRole) {
            throw IOException("Сервер вернул приглашение для другого аккаунта.")
        }
        return invitation.optString("code").takeIf { it.isNotBlank() }
            ?: throw IOException("Сервер не вернул код приглашения.")
    }

    suspend fun loadSettings(): JSONObject = call("/api/settings")

    suspend fun setSandboxMode(enabled: Boolean): JSONObject =
        call("/api/settings/sandbox", method = "POST", payload = JSONObject().put("enabled", enabled))

    suspend fun issueDatabaseResetChallenge(): String {
        val response = call("/api/settings/database/reset/challenge", method = "POST", payload = JSONObject())
        return response.optString("reset_token").takeIf { it.isNotBlank() }
            ?: throw IOException("Сервер не выдал подтверждение сброса.")
    }

    suspend fun resetCurrentDatabase(resetToken: String, confirmation: String) {
        call(
            "/api/settings/database/reset",
            method = "POST",
            payload = JSONObject()
                .put("reset_token", resetToken)
                .put("confirmation", confirmation)
        )
    }

    suspend fun registerPushToken(token: String, deviceId: String) {
        call(
            "/api/mobile/push/register",
            method = "POST",
            payload = JSONObject()
                .put("token", token)
                .put("platform", "android")
                .put("device_id", deviceId)
        )
    }

    suspend fun unregisterPushToken(token: String) {
        if (token.isBlank()) return
        call(
            "/api/mobile/push/unregister",
            method = "POST",
            payload = JSONObject().put("token", token)
        )
    }

    suspend fun restartBackend() {
        call("/api/settings/backend/restart", method = "POST", payload = JSONObject())
    }

    suspend fun restoreSession(): JSONObject? {
        if (!hasSession) return null
        refreshSession()
        val response = call("/api/me")
        return response.optJSONObject("user")
    }

    suspend fun logout() {
        val refresh = sessionStore.refreshToken
        if (!refresh.isNullOrBlank()) {
            runCatching {
                call(
                    path = "/api/mobile/logout",
                    method = "POST",
                    payload = JSONObject().put("refresh_token", refresh),
                    authenticated = false,
                    retryAfterRefresh = false
                )
            }
        }
        sessionStore.clear()
    }

    suspend fun get(path: String): JSONObject = call(path)

    suspend fun post(path: String, payload: JSONObject): JSONObject =
        call(path, method = "POST", payload = payload)

    suspend fun uploadProof(imageBytes: ByteArray, reason: String): String {
        require(imageBytes.isNotEmpty() && imageBytes.size.toLong() <= MAX_PROOF_BYTES) {
            "Фотография пустая или превышает допустимый размер."
        }
        return uploadProofInternal(imageBytes, reason, retryAfterRefresh = true)
    }

    private suspend fun uploadProofInternal(
        imageBytes: ByteArray,
        reason: String,
        retryAfterRefresh: Boolean
    ): String {
        val accessToken = sessionStore.accessToken
            ?: throw IOException("Нет активной сессии.")
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("reason", reason)
            .addFormDataPart(
                "photo",
                "proof.jpg",
                imageBytes.toRequestBody("image/jpeg".toMediaType())
            )
            .build()
        val request = Request.Builder()
            .url("$BASE_URL/api/upload-proof")
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
            .post(body)
            .build()

        val result = awaitHttpResponse(request)
        if (result.statusCode == 401 && retryAfterRefresh) {
            refreshSession()
            return uploadProofInternal(imageBytes, reason, retryAfterRefresh = false)
        }
        if (result.statusCode !in 200..299) {
            throw IOException(parseError(result.errorBody, result.statusCode))
        }
        val json = JSONObject(result.body?.decodeToString().orEmpty())
        return json.optString("proof_file_id").takeIf { it.isNotBlank() }
            ?: throw IOException("Сервер не вернул идентификатор фотографии.")
    }

    suspend fun downloadProof(transactionId: Long): ByteArray =
        downloadProofInternal(transactionId, retryAfterRefresh = true)

    private suspend fun downloadProofInternal(
        transactionId: Long,
        retryAfterRefresh: Boolean
    ): ByteArray {
        require(transactionId > 0) { "Некорректный идентификатор фотографии." }
        val accessToken = sessionStore.accessToken
            ?: throw IOException("Нет активной сессии.")

        val request = Request.Builder()
            .url("$BASE_URL/api/proof/$transactionId")
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "image/*")
            .header("Accept-Encoding", "identity")
            .header("Cache-Control", "no-cache")
            .get()
            .build()

        val result = awaitHttpResponse(request)

        if (result.statusCode == 401 && retryAfterRefresh) {
            refreshSession()
            return downloadProofInternal(transactionId, retryAfterRefresh = false)
        }
        if (result.statusCode !in 200..299) {
            throw IOException(parseError(result.errorBody, result.statusCode))
        }

        val bytes = result.body
            ?: throw IOException("Сервер вернул пустую фотографию.")
        if (bytes.isEmpty()) throw IOException("Сервер вернул пустую фотографию.")
        if (bytes.size.toLong() > MAX_PROOF_BYTES) {
            throw IOException("Фотография слишком большая для просмотра.")
        }
        if (!result.contentType.orEmpty().startsWith("image/") || !isSupportedImage(bytes)) {
            throw IOException("Сервер вернул некорректный файл фотографии.")
        }
        return bytes
    }

    private suspend fun awaitHttpResponse(request: Request): HttpResult =
        suspendCancellableCoroutine { continuation ->
            val call = httpClient.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }

            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(error)
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use { current ->
                            val responseBody = current.body
                            if (current.code in 200..299) {
                                val body = responseBody
                                    ?: throw IOException("Сервер вернул пустой ответ с фотографией.")
                                val declaredLength = body.contentLength()
                                if (declaredLength > MAX_PROOF_BYTES) {
                                    throw IOException("Фотография слишком большая для просмотра.")
                                }

                                val contentType = body.contentType()?.let { "${it.type}/${it.subtype}" }
                                val bytes = body.bytes()
                                if (declaredLength >= 0 && bytes.size.toLong() != declaredLength) {
                                    throw IOException("Фотография загрузилась не полностью.")
                                }
                                HttpResult(current.code, contentType, bytes, "")
                            } else {
                                HttpResult(
                                    current.code,
                                    null,
                                    null,
                                    responseBody?.string().orEmpty()
                                )
                            }
                        }

                        if (continuation.isActive) {
                            continuation.resume(result)
                        }
                    } catch (error: Exception) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(error)
                        }
                    }
                }
            })
        }

    private suspend fun call(
        path: String,
        method: String = "GET",
        payload: JSONObject? = null,
        authenticated: Boolean = true,
        retryAfterRefresh: Boolean = true
    ): JSONObject {
        val builder = Request.Builder()
            .url("$BASE_URL$path")
            .header("Accept", "application/json")

        if (authenticated) {
            val token = sessionStore.accessToken ?: throw IOException("Нет активной сессии.")
            builder.header("Authorization", "Bearer $token")
        }

        val body = payload?.toString()?.toRequestBody(JSON_MEDIA_TYPE)
        if (method == "GET" && body == null) {
            builder.get()
        } else {
            builder.method(method, body)
        }

        val result = awaitHttpResponse(builder.build())
        if (result.statusCode == 401 && authenticated && retryAfterRefresh) {
            refreshSession()
            return call(path, method, payload, authenticated, retryAfterRefresh = false)
        }
        if (result.statusCode !in 200..299) {
            throw IOException(parseError(result.errorBody, result.statusCode))
        }

        val responseText = result.body?.decodeToString().orEmpty()
        return if (responseText.isBlank()) JSONObject() else try {
            JSONObject(responseText)
        } catch (_: Exception) {
            throw IOException("Сервер вернул некорректный ответ.")
        }
    }

    private suspend fun refreshSession() = refreshMutex.withLock {
        val refresh = sessionStore.refreshToken ?: run {
            sessionStore.clear()
            throw IOException("Сессия истекла. Войдите по приглашению ещё раз.")
        }

        val body = JSONObject()
            .put("refresh_token", refresh)
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url("$BASE_URL/api/mobile/refresh")
            .header("Accept", "application/json")
            .post(body)
            .build()
        val result = awaitHttpResponse(request)

        if (result.statusCode == 401) {
            sessionStore.clear()
            throw IOException("Сессия истекла. Войдите по приглашению ещё раз.")
        }
        if (result.statusCode !in 200..299) {
            throw IOException(
                parseError(result.errorBody, result.statusCode)
                    .ifBlank { "Не удалось обновить сессию. Проверьте соединение." }
            )
        }

        val json = try {
            JSONObject(result.body?.decodeToString().orEmpty())
        } catch (_: Exception) {
            throw IOException("Сервер вернул некорректную сессию.")
        }
        val session = json.optJSONObject("session")
            ?: throw IOException("Сервер вернул некорректную сессию.")
        storeSession(session)
    }

    private fun storeSession(session: JSONObject) {
        val access = session.optString("access_token")
        val refresh = session.optString("refresh_token")
        if (access.isBlank() || refresh.isBlank()) {
            throw IOException("Сервер вернул неполную сессию.")
        }
        sessionStore.save(access, refresh)
    }

    private fun isSupportedImage(bytes: ByteArray): Boolean {
        if (bytes.size >= 3 &&
            bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xD8.toByte() &&
            bytes[2] == 0xFF.toByte()
        ) return true

        if (bytes.size >= 8 &&
            bytes.copyOfRange(0, 8).contentEquals(
                byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
            )
        ) return true

        if (bytes.size >= 6) {
            val header = bytes.copyOfRange(0, 6).decodeToString()
            if (header == "GIF87a" || header == "GIF89a") return true
        }

        if (bytes.size >= 12 &&
            bytes.copyOfRange(0, 4).decodeToString() == "RIFF" &&
            bytes.copyOfRange(8, 12).decodeToString() == "WEBP"
        ) return true

        return bytes.size >= 12 &&
            bytes.copyOfRange(4, 8).decodeToString() == "ftyp"
    }

    private fun parseError(text: String, code: Int): String {
        return runCatching { JSONObject(text).optJSONObject("error")?.optString("message") }
            .getOrNull()?.takeIf { !it.isNullOrBlank() } ?: "Ошибка сервера ($code)."
    }

    private companion object {
        const val BASE_URL = "https://app.firegames-it.tech"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val MAX_PROOF_BYTES = 12L * 1024L * 1024L
    }
}

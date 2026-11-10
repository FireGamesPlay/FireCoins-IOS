package ru.firedev.firecoins.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class ApiClient(context: Context) {
    private val sessionStore = SessionStore(context.applicationContext)
    private val refreshMutex = Mutex()

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

    suspend fun reissueAdminInvitation(): String {
        val response = call(
            path = "/api/mobile/invitations/reissue",
            method = "POST",
            payload = JSONObject()
        )
        val invitation = response.optJSONObject("invitation")
            ?: throw IOException("Сервер не вернул новое приглашение.")
        if (invitation.optString("role") != "father") {
            throw IOException("Сервер вернул приглашение для неизвестной роли.")
        }
        return invitation.optString("code").takeIf { it.isNotBlank() }
            ?: throw IOException("Сервер не вернул код приглашения.")
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
        val boundary = "FireCoins-${UUID.randomUUID()}"
        val response = withContext(Dispatchers.IO) {
            val connection = openConnection("/api/upload-proof", "POST", authenticated = true)
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            connection.doOutput = true

            connection.outputStream.use { output ->
                output.write("--$boundary\r\n".toByteArray())
                output.write("Content-Disposition: form-data; name=\"reason\"\r\n\r\n".toByteArray())
                output.write(reason.toByteArray(Charsets.UTF_8))
                output.write("\r\n--$boundary\r\n".toByteArray())
                output.write("Content-Disposition: form-data; name=\"photo\"; filename=\"proof.jpg\"\r\n".toByteArray())
                output.write("Content-Type: image/jpeg\r\n\r\n".toByteArray())
                output.write(imageBytes)
                output.write("\r\n--$boundary--\r\n".toByteArray())
            }

            readResponse(connection)
        }
        if (response.first !in 200..299) {
            throw IOException(response.second.optJSONObject("error")?.optString("message").orEmpty().ifBlank { "Не удалось загрузить фотографию." })
        }
        return response.second.optString("proof_file_id").takeIf { it.isNotBlank() }
            ?: throw IOException("Сервер не вернул идентификатор фотографии.")
    }

    suspend fun downloadProof(transactionId: Long): ByteArray {
        return withContext(Dispatchers.IO) {
            val connection = openConnection("/api/proof/$transactionId", "GET", authenticated = true)
            val code = connection.responseCode
            if (code !in 200..299) {
                val text = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw IOException(parseError(text, code))
            }
            connection.inputStream.use { input -> input.readBytes() }
        }
    }

    private suspend fun call(
        path: String,
        method: String = "GET",
        payload: JSONObject? = null,
        authenticated: Boolean = true,
        retryAfterRefresh: Boolean = true
    ): JSONObject {
        val result = withContext(Dispatchers.IO) {
            val connection = openConnection(path, method, authenticated)
            if (payload != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            }
            readResponse(connection)
        }

        if (result.first == 401 && authenticated && retryAfterRefresh) {
            refreshSession()
            return call(path, method, payload, authenticated, retryAfterRefresh = false)
        }

        if (result.first !in 200..299) {
            val message = result.second.optJSONObject("error")?.optString("message")
                ?.takeIf { !it.isNullOrBlank() } ?: "Ошибка сервера (${result.first})."
            throw IOException(message)
        }
        return result.second
    }

    private suspend fun refreshSession() = refreshMutex.withLock {
        val refresh = sessionStore.refreshToken ?: run {
            sessionStore.clear()
            throw IOException("Сессия истекла. Войдите по приглашению ещё раз.")
        }

        val result = withContext(Dispatchers.IO) {
            val connection = openConnection("/api/mobile/refresh", "POST", authenticated = false)
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.outputStream.use {
                it.write(JSONObject().put("refresh_token", refresh).toString().toByteArray(Charsets.UTF_8))
            }
            readResponse(connection)
        }

        if (result.first == 401) {
            sessionStore.clear()
            throw IOException("Сессия истекла. Войдите по приглашению ещё раз.")
        }
        if (result.first !in 200..299) {
            throw IOException(result.second.optJSONObject("error")?.optString("message")
                .orEmpty().ifBlank { "Не удалось обновить сессию. Проверьте соединение." })
        }
        val session = result.second.optJSONObject("session")
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

    private fun openConnection(path: String, method: String, authenticated: Boolean): HttpURLConnection {
        val url = URL("$BASE_URL$path")
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 25_000
            useCaches = false
            setRequestProperty("Accept", "application/json")
            if (authenticated) {
                val token = sessionStore.accessToken ?: throw IOException("Нет активной сессии.")
                setRequestProperty("Authorization", "Bearer $token")
            }
        }
    }

    private fun readResponse(connection: HttpURLConnection): Pair<Int, JSONObject> {
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val json = if (text.isBlank()) JSONObject() else runCatching { JSONObject(text) }.getOrElse {
                JSONObject().put("error", JSONObject().put("message", "Сервер вернул некорректный ответ."))
            }
            return code to json
        } finally {
            connection.disconnect()
        }
    }

    private fun parseError(text: String, code: Int): String {
        return runCatching { JSONObject(text).optJSONObject("error")?.optString("message") }
            .getOrNull()?.takeIf { !it.isNullOrBlank() } ?: "Ошибка сервера ($code)."
    }

    private companion object {
        const val BASE_URL = "https://app.firegames-it.tech"
    }
}

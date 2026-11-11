package ru.firedev.firecoins.data

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject

data class FcTransaction(
    val id: Long,
    val amount: Int,
    val type: String,
    val reason: String,
    val timestamp: String,
    val disputeStatus: String,
    val hasProof: Boolean
)

data class FcWithdrawal(
    val id: Long,
    val amount: Int,
    val timestamp: String,
    val status: String
)

data class FcNote(
    val id: String,
    val senderRole: String,
    val senderName: String,
    val text: String,
    val timestamp: String
)

data class FcCasinoRound(
    val id: Long,
    val bet: Int,
    val won: Boolean,
    val payout: Int,
    val timestamp: String
)

data class FireCoinsSnapshot(
    val role: String,
    val balance: Int,
    val transactions: List<FcTransaction>,
    val withdrawals: List<FcWithdrawal>,
    val notes: List<FcNote>,
    val casinoRounds: List<FcCasinoRound>,
    val spinsLeft: Int,
    val pendingDisputes: List<FcTransaction>
)

class FireCoinsRepository(private val api: ApiClient) {
    suspend fun signIn(invitationCode: String): String {
        return api.signIn(invitationCode).optString("role").takeIf { it == "father" || it == "artemiy" }
            ?: throw IllegalStateException("Сервер вернул неизвестную роль.")
    }

    suspend fun restoreSession(): String? {
        val user = api.restoreSession() ?: return null
        return user.optString("role").takeIf { it == "father" || it == "artemiy" }
    }

    suspend fun logout() = api.logout()

    suspend fun loadSettings(): JSONObject = api.loadSettings()
    suspend fun setSandboxMode(enabled: Boolean): JSONObject = api.setSandboxMode(enabled)
    suspend fun issueDatabaseResetChallenge(): String = api.issueDatabaseResetChallenge()
    suspend fun resetCurrentDatabase(token: String, confirmation: String) =
        api.resetCurrentDatabase(token, confirmation)
    suspend fun registerPushToken(token: String, deviceId: String) = api.registerPushToken(token, deviceId)
    suspend fun restartBackend() = api.restartBackend()
    suspend fun unregisterPushToken(token: String) = api.unregisterPushToken(token)
    suspend fun reissueInvitation(role: String): String = api.reissueInvitation(role)


    fun hasStoredSession(): Boolean = api.hasSession

    suspend fun loadSnapshot(role: String): FireCoinsSnapshot = coroutineScope {
        val dashboardRequest = async { api.get("/api/dashboard") }
        val historyRequest = async { api.get("/api/history?period=all&limit=200") }
        val withdrawalsRequest = async { api.get("/api/withdrawals") }
        val notesRequest = async { api.get("/api/notes?limit=100") }
        val casinoRequest = if (role == "artemiy") async { api.get("/api/casino/status") } else null
        val disputesRequest = if (role == "father") async { api.get("/api/disputes/pending") } else null

        val dashboard = dashboardRequest.await()
        val data = dashboard.optJSONObject("data") ?: JSONObject()
        val balanceObject = data.optJSONObject("balance") ?: JSONObject()
        val balance = balanceObject.optInt("balance", 0)

        val transactions = historyRequest.await().optJSONArray("items").toTransactionList()
        val withdrawals = withdrawalsRequest.await().optJSONArray("items").toWithdrawalList()
        val notes = notesRequest.await().optJSONArray("items").toNoteList()

        val casino = casinoRequest?.await()?.optJSONObject("casino") ?: JSONObject()
        val spinsLeft = casino.optInt("spins_left", 0)
        val rounds = casino.optJSONArray("rounds").toCasinoList()
        val pendingDisputes = disputesRequest?.await()?.optJSONArray("items").toTransactionList()
            ?: emptyList()

        FireCoinsSnapshot(
            role = role,
            balance = balance,
            transactions = transactions,
            withdrawals = withdrawals,
            notes = notes,
            casinoRounds = rounds,
            spinsLeft = spinsLeft,
            pendingDisputes = pendingDisputes
        )
    }

    suspend fun applyTransaction(
        amount: Int,
        action: String,
        reason: String,
        proofBytes: ByteArray?
    ) {
        require(amount > 0) { "Введите положительное количество баллов." }
        val payload = JSONObject()
            .put("amount", amount)
            .put("reason", reason)

        if (proofBytes != null) {
            payload.put("proof_file_id", api.uploadProof(proofBytes, reason))
        }
        api.post(if (action == "add") "/api/add" else "/api/remove", payload)
    }

    suspend fun dispute(transactionId: Long) {
        api.post("/api/dispute", JSONObject().put("transaction_id", transactionId))
    }

    suspend fun resolveDispute(transactionId: Long, approve: Boolean) {
        api.post(
            "/api/disputes/$transactionId/resolve",
            JSONObject().put("action", if (approve) "approve" else "reject")
        )
    }

    suspend fun requestWithdrawal(amount: Int) {
        require(amount > 0) { "Введите положительное количество баллов." }
        api.post("/api/withdraw", JSONObject().put("amount", amount))
    }

    suspend fun resolveWithdrawal(id: Long, approve: Boolean) {
        api.post(
            "/api/withdrawals/$id/resolve",
            JSONObject().put("action", if (approve) "approve" else "reject")
        )
    }

    suspend fun playCasino(amount: Int): JSONObject {
        require(amount > 0) { "Введите положительную ставку." }
        return api.post("/api/casino", JSONObject().put("amount", amount))
            .optJSONObject("result") ?: JSONObject()
    }

    suspend fun sendNote(text: String) {
        api.post("/api/note", JSONObject().put("text", text))
    }

    suspend fun downloadProof(transactionId: Long): ByteArray = api.downloadProof(transactionId)
}

private fun org.json.JSONArray?.toTransactionList(): List<FcTransaction> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            val item = optJSONObject(index) ?: continue
            add(
                FcTransaction(
                    id = item.optLong("id"),
                    amount = item.optInt("amount"),
                    type = item.optString("type"),
                    reason = item.optString("reason"),
                    timestamp = item.optString("timestamp"),
                    disputeStatus = item.optString("dispute_status", "none"),
                    hasProof = item.optBoolean("has_proof")
                )
            )
        }
    }
}

private fun org.json.JSONArray?.toWithdrawalList(): List<FcWithdrawal> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            val item = optJSONObject(index) ?: continue
            add(
                FcWithdrawal(
                    id = item.optLong("id"),
                    amount = item.optInt("amount"),
                    timestamp = item.optString("timestamp"),
                    status = item.optString("status")
                )
            )
        }
    }
}

private fun org.json.JSONArray?.toNoteList(): List<FcNote> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            val item = optJSONObject(index) ?: continue
            add(
                FcNote(
                    id = item.optString("id"),
                    senderRole = item.optString("sender_role"),
                    senderName = item.optString("sender_name"),
                    text = item.optString("text"),
                    timestamp = item.optString("timestamp")
                )
            )
        }
    }
}

private fun org.json.JSONArray?.toCasinoList(): List<FcCasinoRound> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            val item = optJSONObject(index) ?: continue
            add(
                FcCasinoRound(
                    id = item.optLong("id"),
                    bet = item.optInt("bet"),
                    won = item.optBoolean("won"),
                    payout = item.optInt("payout"),
                    timestamp = item.optString("timestamp")
                )
            )
        }
    }
}

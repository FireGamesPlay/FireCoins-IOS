package ru.firedev.firecoins.ui

import android.app.Application
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import ru.firedev.firecoins.data.ApiClient
import ru.firedev.firecoins.data.FireCoinsRepository
import ru.firedev.firecoins.data.FireCoinsSnapshot
import ru.firedev.firecoins.push.PushDeviceIdentity

data class RouletteResult(
    val won: Boolean,
    val bet: Int,
    val payout: Int
)

data class AppUiState(
    val isLoading: Boolean = true,
    val role: String? = null,
    val balance: Int = 0,
    val transactions: List<ru.firedev.firecoins.data.FcTransaction> = emptyList(),
    val withdrawals: List<ru.firedev.firecoins.data.FcWithdrawal> = emptyList(),
    val notes: List<ru.firedev.firecoins.data.FcNote> = emptyList(),
    val casinoRounds: List<ru.firedev.firecoins.data.FcCasinoRound> = emptyList(),
    val spinsLeft: Int = 0,
    val pendingDisputes: List<ru.firedev.firecoins.data.FcTransaction> = emptyList(),
    val rouletteResult: RouletteResult? = null,
    val sessionRecovery: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    val reissuedInvitationCode: String? = null,
    val isSandbox: Boolean = false,
    val canResetDatabase: Boolean = false,
    val pushConfigured: Boolean = false,
    val avatarBytes: ByteArray? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = FireCoinsRepository(ApiClient(application.applicationContext))

    var state = androidx.compose.runtime.mutableStateOf(AppUiState())
        private set

    init {
        restoreSession()
    }

    fun login(invitationCode: String) {
        viewModelScope.launch {
            state.value = state.value.copy(isLoading = true, error = null, sessionRecovery = false)
            try {
                val role = repository.signIn(invitationCode)
                // Keep the authenticated role as soon as the single-use invitation is accepted.
                state.value = AppUiState(isLoading = true, role = role)
                val snapshot = repository.loadSnapshot(role)
                state.value = snapshot.toUiState(isLoading = false).copy(message = "Вход выполнен.")
                refreshProfileData(role)
                registerPushDevice()
            } catch (error: Exception) {
                state.value = state.value.copy(
                    isLoading = false,
                    error = error.message ?: "Не удалось войти.",
                    sessionRecovery = state.value.role == null && repository.hasStoredSession()
                )
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            state.value = state.value.copy(isLoading = true)
            runCatching {
                val token = PushDeviceIdentity.token(getApplication())
                if (!token.isNullOrBlank()) repository.unregisterPushToken(token)
            }
            runCatching { repository.logout() }
            state.value = AppUiState(isLoading = false)
        }
    }

    fun refresh() {
        val role = state.value.role ?: return
        viewModelScope.launch {
            try {
                val snapshot = repository.loadSnapshot(role)
                applySnapshot(snapshot)
                refreshProfileData(role)
            } catch (error: Exception) {
                state.value = state.value.copy(error = error.message ?: "Не удалось обновить данные.")
            }
        }
    }

    fun addTransaction(amount: String, action: String, reason: String, proofBytes: ByteArray?) {
        execute("Операция сохранена.") {
            repository.applyTransaction(amount.toIntOrNull() ?: 0, action, reason, proofBytes)
        }
    }

    fun dispute(transactionId: Long) {
        execute("Операция отправлена на оспаривание.") { repository.dispute(transactionId) }
    }

    fun resolveDispute(transactionId: Long, approve: Boolean) {
        execute(if (approve) "Спор одобрен." else "Спор отклонён.") {
            repository.resolveDispute(transactionId, approve)
        }
    }

    fun requestWithdrawal(amount: String) {
        execute("Заявка отправлена.") {
            repository.requestWithdrawal(amount.toIntOrNull() ?: 0)
        }
    }

    fun resolveWithdrawal(id: Long, approve: Boolean) {
        execute(if (approve) "Заявка одобрена." else "Заявка отклонена.") {
            repository.resolveWithdrawal(id, approve)
        }
    }

    fun playCasino(amount: String) {
        viewModelScope.launch {
            state.value = state.value.copy(isLoading = true, error = null, rouletteResult = null)
            try {
                val result = repository.playCasino(amount.toIntOrNull() ?: 0)
                val role = state.value.role ?: return@launch
                val snapshot = repository.loadSnapshot(role)
                state.value = snapshot.toUiState(isLoading = false).copy(
                    isSandbox = state.value.isSandbox,
                    canResetDatabase = state.value.canResetDatabase,
                    pushConfigured = state.value.pushConfigured,
                    avatarBytes = state.value.avatarBytes,
                    rouletteResult = RouletteResult(
                        won = result.optBoolean("win"),
                        bet = result.optInt("bet"),
                        payout = result.optInt("payout")
                    )
                )
            } catch (error: Exception) {
                state.value = state.value.copy(isLoading = false, error = error.message ?: "Не удалось сыграть.")
            }
        }
    }

    fun sendNote(text: String) {
        execute("Заметка отправлена.") { repository.sendNote(text) }
    }

    suspend fun downloadProof(transactionId: Long): ByteArray {
        return repository.downloadProof(transactionId)
    }

    fun reissueInvitation() {
        val role = state.value.role ?: return
        viewModelScope.launch {
            state.value = state.value.copy(isLoading = true, error = null, message = null, reissuedInvitationCode = null)
            try {
                val code = repository.reissueInvitation(role)
                state.value = state.value.copy(
                    isLoading = false,
                    reissuedInvitationCode = code,
                    message = "Новый код создан. Сохраните его: он будет показан только сейчас."
                )
            } catch (error: Exception) {
                state.value = state.value.copy(isLoading = false, error = error.message ?: "Не удалось перевыпустить код.")
            }
        }
    }

    fun toggleSandbox(enabled: Boolean) {
        val role = state.value.role ?: return
        viewModelScope.launch {
            state.value = state.value.copy(isLoading = true, error = null, message = null)
            try {
                val settings = repository.setSandboxMode(enabled)
                val snapshot = repository.loadSnapshot(role)
                val environment = settings.optJSONObject("environment")
                state.value = snapshot.toUiState(isLoading = false).copy(
                    isSandbox = environment?.optBoolean("is_sandbox", enabled) ?: enabled,
                    canResetDatabase = settings.optBoolean("can_reset_database", role == "father"),
                    pushConfigured = settings.optBoolean("push_configured"),
                    avatarBytes = repository.downloadAvatar(role),
                    message = if (enabled) "Тестовая среда включена. Рабочая база не изменена."
                        else "Тестовые данные удалены. Рабочая среда восстановлена."
                )
            } catch (error: Exception) {
                state.value = state.value.copy(isLoading = false, error = error.message ?: "Не удалось переключить среду.")
                refreshProfileData(role)
            }
        }
    }

    fun resetCurrentDatabase(confirmation: String) {
        if (state.value.role != "father" || confirmation != "УДАЛИТЬ БД") return
        val role = state.value.role ?: return
        viewModelScope.launch {
            state.value = state.value.copy(isLoading = true, error = null, message = null)
            try {
                val challenge = repository.issueDatabaseResetChallenge()
                repository.resetCurrentDatabase(challenge, confirmation)
                val snapshot = repository.loadSnapshot(role)
                state.value = snapshot.toUiState(isLoading = false).copy(
                    message = "Текущая база очищена. Схема сохранена, баланс установлен в 0."
                )
                refreshProfileData(role)
            } catch (error: Exception) {
                state.value = state.value.copy(isLoading = false, error = error.message ?: "Не удалось сбросить базу.")
            }
        }
    }

    fun uploadAvatar(bytes: ByteArray) {
        val role = state.value.role ?: return
        viewModelScope.launch {
            state.value = state.value.copy(isLoading = true, error = null, message = null)
            try {
                repository.uploadAvatar(bytes)
                state.value = state.value.copy(
                    isLoading = false,
                    avatarBytes = repository.downloadAvatar(role),
                    message = "Аватар обновлён."
                )
            } catch (error: Exception) {
                state.value = state.value.copy(isLoading = false, error = error.message ?: "Не удалось загрузить аватар.")
            }
        }
    }

    fun restartBackend() {
        if (state.value.role != "father") return
        viewModelScope.launch {
            state.value = state.value.copy(isLoading = true, error = null, message = null)
            try {
                repository.restartBackend()
                state.value = state.value.copy(
                    isLoading = false,
                    message = "Перезапуск Backend запланирован. Приложение переподключится автоматически."
                )
            } catch (error: Exception) {
                state.value = state.value.copy(
                    isLoading = false,
                    error = error.message ?: "Не удалось перезапустить Backend."
                )
            }
        }
    }

    fun registerPushDevice() {
        if (state.value.role == null) return
        viewModelScope.launch {
            try {
                if (FirebaseApp.getApps(getApplication()).isEmpty()) return@launch
                val token = FirebaseMessaging.getInstance().token.await()
                if (token.isNullOrBlank()) return@launch
                val previous = PushDeviceIdentity.token(getApplication())
                if (!previous.isNullOrBlank() && previous != token) {
                    runCatching { repository.unregisterPushToken(previous) }
                }
                repository.registerPushToken(token, PushDeviceIdentity.deviceId(getApplication()))
                PushDeviceIdentity.saveToken(getApplication(), token)
            } catch (_: Exception) {
                // Firebase is optional until the project configuration is installed.
            }
        }
    }

    private suspend fun refreshProfileData(role: String) {
        try {
            val settings = repository.loadSettings()
            val environment = settings.optJSONObject("environment")
            state.value = state.value.copy(
                isSandbox = environment?.optBoolean("is_sandbox", false) ?: false,
                canResetDatabase = settings.optBoolean("can_reset_database", role == "father"),
                pushConfigured = settings.optBoolean("push_configured"),
                avatarBytes = repository.downloadAvatar(role)
            )
        } catch (_: Exception) {
            // Keep account screens available if an optional profile request fails.
        }
    }

    private fun applySnapshot(snapshot: FireCoinsSnapshot) {
        val previous = state.value
        state.value = snapshot.toUiState(isLoading = false).copy(
            isSandbox = previous.isSandbox,
            canResetDatabase = previous.canResetDatabase,
            pushConfigured = previous.pushConfigured,
            avatarBytes = previous.avatarBytes,
            reissuedInvitationCode = previous.reissuedInvitationCode
        )
    }

    fun clearMessage() {
        state.value = state.value.copy(message = null, error = null)
    }

    fun retrySession() {
        restoreSession()
    }

    private fun restoreSession() {
        viewModelScope.launch {
            state.value = state.value.copy(isLoading = true, error = null)
            try {
                val role = repository.restoreSession()
                if (role == null) {
                    state.value = AppUiState(isLoading = false)
                } else {
                    // Authentication succeeded. A later snapshot outage must not discard the role.
                    state.value = AppUiState(isLoading = true, role = role)
                    state.value = repository.loadSnapshot(role).toUiState(isLoading = false)
                    refreshProfileData(role)
                    registerPushDevice()
                }
            } catch (error: Exception) {
                val message = error.message ?: "Не удалось восстановить сессию."
                val current = state.value
                state.value = if (current.role != null) {
                    current.copy(isLoading = false, error = message)
                } else {
                    AppUiState(
                        isLoading = false,
                        sessionRecovery = repository.hasStoredSession(),
                        error = message
                    )
                }
            }
        }
    }

    private fun execute(successMessage: String, action: suspend () -> Unit) {
        viewModelScope.launch {
            state.value = state.value.copy(isLoading = true, error = null, message = null)
            try {
                action()
                val role = state.value.role ?: return@launch
                val snapshot = repository.loadSnapshot(role)
                applySnapshot(snapshot)
                state.value = state.value.copy(message = successMessage)
            } catch (error: Exception) {
                state.value = state.value.copy(isLoading = false, error = error.message ?: "Операция не выполнена.")
            }
        }
    }

    private fun FireCoinsSnapshot.toUiState(isLoading: Boolean): AppUiState = AppUiState(
        isLoading = isLoading,
        role = role,
        balance = balance,
        transactions = transactions,
        withdrawals = withdrawals,
        notes = notes,
        casinoRounds = casinoRounds,
        spinsLeft = spinsLeft,
        pendingDisputes = pendingDisputes
    )
}

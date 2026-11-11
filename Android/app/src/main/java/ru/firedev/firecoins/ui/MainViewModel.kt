package ru.firedev.firecoins.ui

import android.app.Application
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
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
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = FireCoinsRepository(ApiClient(application.applicationContext))

    var state = androidx.compose.runtime.mutableStateOf(AppUiState())
        private set

    // The root screen can start overlapping operations. Keep the blocking
    // overlay visible until every operation has reached its finally block.
    private var activeLoadingOperations = 0

    private fun beginLoading() {
        activeLoadingOperations += 1
        state.value = state.value.copy(isLoading = true)
    }

    private fun endLoading() {
        activeLoadingOperations = (activeLoadingOperations - 1).coerceAtLeast(0)
        state.value = state.value.copy(isLoading = activeLoadingOperations > 0)
    }

    init {
        restoreSession()
    }

    fun login(invitationCode: String) {
        viewModelScope.launch {
            beginLoading()
            state.value = state.value.copy(error = null, sessionRecovery = false)
            try {
                val role = repository.signIn(invitationCode)
                // Keep the authenticated role as soon as the single-use invitation is accepted.
                state.value = AppUiState(isLoading = activeLoadingOperations > 0, role = role)
                val snapshot = withTimeout(25_000) { repository.loadSnapshot(role) }
                state.value = snapshot.toUiState(isLoading = activeLoadingOperations > 0).copy(message = "Вход выполнен.")
                refreshProfileData(role)
                registerPushDevice()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.value = state.value.copy(
                    error = error.message ?: "Не удалось войти.",
                    sessionRecovery = state.value.role == null && repository.hasStoredSession()
                )
            } finally {
                endLoading()
            }
        }
    }

        fun logout() {
        viewModelScope.launch {
            beginLoading()
            try {
                runCatching {
                    val token = PushDeviceIdentity.token(getApplication())
                    if (!token.isNullOrBlank()) repository.unregisterPushToken(token)
                }
                runCatching { repository.logout() }
                state.value = AppUiState(isLoading = activeLoadingOperations > 0)
            } finally {
                endLoading()
            }
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

    fun addTransaction(
        amount: String,
        action: String,
        reason: String,
        proofBytes: ByteArray?,
        onSuccess: () -> Unit = {}
    ) {
        execute("Операция сохранена.", onSuccess) {
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
            beginLoading()
            state.value = state.value.copy(error = null, rouletteResult = null)
            try {
                val result = withTimeout(25_000) { repository.playCasino(amount.toIntOrNull() ?: 0) }
                val role = state.value.role
                if (role != null) {
                    val snapshot = withTimeout(25_000) { repository.loadSnapshot(role) }
                    state.value = snapshot.toUiState(isLoading = activeLoadingOperations > 0).copy(
                        isSandbox = state.value.isSandbox,
                        canResetDatabase = state.value.canResetDatabase,
                        pushConfigured = state.value.pushConfigured,
                        rouletteResult = RouletteResult(
                            won = result.optBoolean("win"),
                            bet = result.optInt("bet"),
                            payout = result.optInt("payout")
                        )
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.value = state.value.copy(error = error.message ?: "Не удалось сыграть.")
            } finally {
                endLoading()
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
            beginLoading()
            state.value = state.value.copy(error = null, message = null, reissuedInvitationCode = null)
            try {
                val code = withTimeout(20_000) { repository.reissueInvitation(role) }
                state.value = state.value.copy(
                    reissuedInvitationCode = code,
                    message = "Новый код создан. Сохраните его: он будет показан только сейчас."
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.value = state.value.copy(error = error.message ?: "Не удалось перевыпустить код.")
            } finally {
                endLoading()
            }
        }
    }

        fun toggleSandbox(enabled: Boolean) {
        val role = state.value.role ?: return
        viewModelScope.launch {
            beginLoading()
            state.value = state.value.copy(error = null, message = null)
            try {
                val settings = withTimeout(20_000) { repository.setSandboxMode(enabled) }
                val snapshot = withTimeout(25_000) { repository.loadSnapshot(role) }
                val environment = settings.optJSONObject("environment")
                state.value = snapshot.toUiState(isLoading = activeLoadingOperations > 0).copy(
                    isSandbox = environment?.optBoolean("is_sandbox", enabled) ?: enabled,
                    canResetDatabase = settings.optBoolean("can_reset_database", role == "father"),
                    pushConfigured = settings.optBoolean("push_configured"),
                    message = if (enabled) "Тестовая среда включена. Рабочая база не изменена."
                        else "Тестовые данные удалены. Рабочая среда восстановлена."
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.value = state.value.copy(error = error.message ?: "Не удалось переключить среду.")
                refreshProfileData(role)
            } finally {
                endLoading()
            }
        }
    }

        fun resetCurrentDatabase(confirmation: String) {
        if (state.value.role != "father" || confirmation != "УДАЛИТЬ БД") return
        val role = state.value.role ?: return
        viewModelScope.launch {
            beginLoading()
            state.value = state.value.copy(error = null, message = null)
            try {
                val challenge = withTimeout(15_000) { repository.issueDatabaseResetChallenge() }
                withTimeout(20_000) { repository.resetCurrentDatabase(challenge, confirmation) }
                val snapshot = withTimeout(25_000) { repository.loadSnapshot(role) }
                state.value = snapshot.toUiState(isLoading = activeLoadingOperations > 0).copy(
                    message = "Текущая база очищена. Схема сохранена, баланс установлен в 0."
                )
                refreshProfileData(role)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.value = state.value.copy(error = error.message ?: "Не удалось сбросить базу.")
            } finally {
                endLoading()
            }
        }
    }

    

        fun restartBackend() {
        if (state.value.role != "father") return
        viewModelScope.launch {
            beginLoading()
            state.value = state.value.copy(error = null, message = null)
            try {
                withTimeout(15_000) { repository.restartBackend() }
                state.value = state.value.copy(
                    message = "Перезапуск Backend запланирован. Приложение переподключится автоматически."
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.value = state.value.copy(error = error.message ?: "Не удалось перезапустить Backend.")
            } finally {
                endLoading()
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
                pushConfigured = settings.optBoolean("push_configured")
            )
        } catch (_: Exception) {
            // Keep account screens available if an optional settings request fails.
        }
    }

        private fun applySnapshot(snapshot: FireCoinsSnapshot) {
        val previous = state.value
        state.value = snapshot.toUiState(isLoading = activeLoadingOperations > 0).copy(
            isSandbox = previous.isSandbox,
            canResetDatabase = previous.canResetDatabase,
            pushConfigured = previous.pushConfigured,
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
            beginLoading()
            state.value = state.value.copy(error = null)
            try {
                val role = withTimeout(20_000) { repository.restoreSession() }
                if (role == null) {
                    state.value = AppUiState(isLoading = activeLoadingOperations > 0)
                } else {
                    // Authentication succeeded. A later snapshot outage must not discard the role.
                    state.value = AppUiState(isLoading = activeLoadingOperations > 0, role = role)
                    val snapshot = withTimeout(25_000) { repository.loadSnapshot(role) }
                    state.value = snapshot.toUiState(isLoading = activeLoadingOperations > 0)
                    refreshProfileData(role)
                    registerPushDevice()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val message = error.message ?: "Не удалось восстановить сессию."
                val current = state.value
                state.value = if (current.role != null) {
                    current.copy(error = message)
                } else {
                    AppUiState(
                        isLoading = activeLoadingOperations > 0,
                        sessionRecovery = repository.hasStoredSession(),
                        error = message
                    )
                }
            } finally {
                endLoading()
            }
        }
    }

        private fun execute(
        successMessage: String,
        onSuccess: () -> Unit = {},
        action: suspend () -> Unit
    ) {
        viewModelScope.launch {
            beginLoading()
            state.value = state.value.copy(error = null, message = null)
            try {
                withTimeout(25_000) { action() }

                // The mutation is already committed. Clear the form before the
                // optional follow-up snapshot request.
                onSuccess()
                state.value = state.value.copy(message = successMessage)

                val role = state.value.role
                if (role != null) {
                    try {
                        val snapshot = withTimeout(25_000) {
                            repository.loadSnapshot(role)
                        }
                        applySnapshot(snapshot)
                        state.value = state.value.copy(message = successMessage, error = null)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        state.value = state.value.copy(
                            message = successMessage,
                            error = "Операция выполнена, но данные не обновились: ${error.message ?: "ошибка сети"}"
                        )
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.value = state.value.copy(
                    error = error.message ?: "Операция не выполнена."
                )
            } finally {
                endLoading()
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

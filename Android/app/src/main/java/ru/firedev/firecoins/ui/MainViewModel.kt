package ru.firedev.firecoins.ui

import android.app.Application
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
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
    val activeOperations: Int = 0
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = FireCoinsRepository(ApiClient(application.applicationContext))
    private var activeOperationCount = 0

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
                // The one-time invite is consumed at this point. Keep the authenticated role
                // even if loading the first snapshot is temporarily unavailable.
                state.value = AppUiState(isLoading = true, role = role)
                val snapshot = withTimeout(SNAPSHOT_TIMEOUT_MS) { repository.loadSnapshot(role) }
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
            try {
                runCatching {
                    val token = PushDeviceIdentity.token(getApplication())
                    if (!token.isNullOrBlank()) repository.unregisterPushToken(token)
                }
                runCatching { repository.logout() }
            } finally {
                activeOperationCount = 0
                state.value = AppUiState(isLoading = false)
            }
        }
    }

    fun refresh() {
        val role = state.value.role ?: return
        viewModelScope.launch {
            try {
                val snapshot = withTimeout(SNAPSHOT_TIMEOUT_MS) { repository.loadSnapshot(role) }
                if (state.value.role == role) applySnapshot(snapshot)
                refreshProfileData(role)
            } catch (error: CancellationException) {
                throw error
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
        val role = state.value.role ?: return
        beginOperation()
        state.value = state.value.copy(rouletteResult = null)
        viewModelScope.launch {
            try {
                val result = repository.playCasino(amount.toIntOrNull() ?: 0)
                // The casino response confirms the round. Preserve the result even if a
                // later snapshot refresh fails, so the UI never looks like the action froze.
                val round = RouletteResult(
                    won = result.optBoolean("win"),
                    bet = result.optInt("bet"),
                    payout = result.optInt("payout")
                )
                state.value = state.value.copy(rouletteResult = round)
                try {
                    val snapshot = withTimeout(SNAPSHOT_TIMEOUT_MS) { repository.loadSnapshot(role) }
                    if (state.value.role == role) applySnapshot(snapshot)
                    state.value = state.value.copy(rouletteResult = round, message = "Раунд завершён.")
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    state.value = state.value.copy(
                        rouletteResult = round,
                        message = "Раунд завершён.",
                        error = "Результат сохранён, но обновить баланс не удалось. Обновите экран."
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.value = state.value.copy(error = error.message ?: "Не удалось сыграть.")
            } finally {
                endOperation()
            }
        }
    }

    fun sendNote(text: String) {
        execute("Заметка отправлена.") { repository.sendNote(text) }
    }

    suspend fun downloadProof(transactionId: Long): ByteArray = repository.downloadProof(transactionId)

    fun reissueInvitation() {
        val role = state.value.role ?: return
        beginOperation()
        state.value = state.value.copy(reissuedInvitationCode = null)
        viewModelScope.launch {
            try {
                val code = repository.reissueInvitation(role)
                state.value = state.value.copy(
                    reissuedInvitationCode = code,
                    message = "Новый код создан. Сохраните его: он будет показан только сейчас."
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.value = state.value.copy(error = error.message ?: "Не удалось перевыпустить код.")
            } finally {
                endOperation()
            }
        }
    }

    fun toggleSandbox(enabled: Boolean) {
        val role = state.value.role ?: return
        beginOperation()
        viewModelScope.launch {
            try {
                val settings = repository.setSandboxMode(enabled)
                val environment = settings.optJSONObject("environment")
                val isSandbox = environment?.optBoolean("is_sandbox", enabled) ?: enabled
                val successMessage = if (enabled) {
                    "Тестовая среда включена. Рабочая база не изменена."
                } else {
                    "Тестовые данные удалены. Рабочая среда восстановлена."
                }
                state.value = state.value.copy(
                    isSandbox = isSandbox,
                    canResetDatabase = settings.optBoolean("can_reset_database", role == "father"),
                    pushConfigured = settings.optBoolean("push_configured"),
                    message = successMessage
                )

                // Switching the environment is already committed. Refreshing its data is
                // best-effort and must not keep the whole application behind a spinner.
                try {
                    val snapshot = withTimeout(SNAPSHOT_TIMEOUT_MS) { repository.loadSnapshot(role) }
                    if (state.value.role == role) applySnapshot(snapshot)
                    state.value = state.value.copy(
                        isSandbox = isSandbox,
                        canResetDatabase = settings.optBoolean("can_reset_database", role == "father"),
                        pushConfigured = settings.optBoolean("push_configured"),
                        message = successMessage
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    state.value = state.value.copy(
                        message = successMessage,
                        error = "Режим переключён, но обновить данные не удалось. Нажмите «Обновить»."
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.value = state.value.copy(error = error.message ?: "Не удалось переключить среду.")
                refreshProfileData(role)
            } finally {
                endOperation()
            }
        }
    }

    fun resetCurrentDatabase(confirmation: String) {
        if (state.value.role != "father" || confirmation != "УДАЛИТЬ БД") return
        val role = state.value.role ?: return
        beginOperation()
        viewModelScope.launch {
            var resetCommitted = false
            val successMessage = "Текущая база очищена. Схема сохранена, баланс установлен в 0."
            try {
                val challenge = repository.issueDatabaseResetChallenge()
                repository.resetCurrentDatabase(challenge, confirmation)
                resetCommitted = true
                state.value = state.value.copy(message = successMessage)

                try {
                    val snapshot = withTimeout(SNAPSHOT_TIMEOUT_MS) { repository.loadSnapshot(role) }
                    if (state.value.role == role) applySnapshot(snapshot)
                    state.value = state.value.copy(message = successMessage)
                    refreshProfileData(role)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    state.value = state.value.copy(
                        message = successMessage,
                        error = "Сброс выполнен. Не удалось загрузить обновлённую историю; обновите экран."
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.value = if (resetCommitted) {
                    state.value.copy(
                        message = successMessage,
                        error = "Сброс выполнен. Не удалось обновить данные; обновите экран."
                    )
                } else {
                    state.value.copy(error = error.message ?: "Не удалось сбросить базу.")
                }
            } finally {
                endOperation()
            }
        }
    }

    fun restartBackend() {
        if (state.value.role != "father") return
        beginOperation()
        viewModelScope.launch {
            try {
                repository.restartBackend()
                state.value = state.value.copy(
                    message = "Перезапуск Backend запланирован. Приложение переподключится автоматически."
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.value = state.value.copy(
                    error = error.message ?: "Не удалось перезапустить Backend."
                )
            } finally {
                endOperation()
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
            val settings = withTimeout(15_000) { repository.loadSettings() }
            val environment = settings.optJSONObject("environment")
            state.value = state.value.copy(
                isSandbox = environment?.optBoolean("is_sandbox", false) ?: false,
                canResetDatabase = settings.optBoolean("can_reset_database", role == "father"),
                pushConfigured = settings.optBoolean("push_configured")
            )
        } catch (_: CancellationException) {
            throw
        } catch (_: Exception) {
            // Keep account screens usable if optional settings are unavailable.
        }
    }

    private fun applySnapshot(snapshot: FireCoinsSnapshot) {
        val previous = state.value
        state.value = snapshot.toUiState(isLoading = false).copy(
            isSandbox = previous.isSandbox,
            canResetDatabase = previous.canResetDatabase,
            pushConfigured = previous.pushConfigured,
            activeOperations = activeOperationCount,
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
                    val snapshot = withTimeout(SNAPSHOT_TIMEOUT_MS) { repository.loadSnapshot(role) }
                    state.value = snapshot.toUiState(isLoading = false)
                    refreshProfileData(role)
                    registerPushDevice()
                }
            } catch (error: CancellationException) {
                throw error
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

    private fun execute(
        successMessage: String,
        onSuccess: () -> Unit = {},
        action: suspend () -> Unit
    ) {
        beginOperation()
        viewModelScope.launch {
            try {
                action()
                // The mutation has been accepted by the server. Clear the form now;
                // snapshot refresh failure must not invite the same payment/transaction again.
                onSuccess()
                val role = state.value.role
                if (role != null) {
                    try {
                        val snapshot = withTimeout(SNAPSHOT_TIMEOUT_MS) { repository.loadSnapshot(role) }
                        if (state.value.role == role) applySnapshot(snapshot)
                        state.value = state.value.copy(message = successMessage)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        state.value = state.value.copy(
                            message = successMessage,
                            error = "Операция сохранена, но обновить историю не удалось. Нажмите «Обновить»."
                        )
                    }
                } else {
                    state.value = state.value.copy(message = successMessage)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.value = state.value.copy(error = error.message ?: "Операция не выполнена.")
            } finally {
                endOperation()
            }
        }
    }

    private fun beginOperation() {
        activeOperationCount += 1
        state.value = state.value.copy(
            activeOperations = activeOperationCount,
            error = null,
            message = null
        )
    }

    private fun endOperation() {
        activeOperationCount = (activeOperationCount - 1).coerceAtLeast(0)
        state.value = state.value.copy(activeOperations = activeOperationCount)
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
        pendingDisputes = pendingDisputes,
        activeOperations = activeOperationCount
    )

    private companion object {
        const val SNAPSHOT_TIMEOUT_MS = 25_000L
    }
}

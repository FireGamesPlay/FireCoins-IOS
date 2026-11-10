package ru.firedev.firecoins.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import ru.firedev.firecoins.data.ApiClient
import ru.firedev.firecoins.data.FireCoinsRepository
import ru.firedev.firecoins.data.FireCoinsSnapshot

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
    val message: String? = null,
    val error: String? = null
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
            state.value = state.value.copy(isLoading = true, error = null)
            try {
                val role = repository.signIn(invitationCode)
                val snapshot = repository.loadSnapshot(role)
                state.value = snapshot.toUiState(isLoading = false).copy(message = "Вход выполнен.")
            } catch (error: Exception) {
                state.value = state.value.copy(isLoading = false, error = error.message ?: "Не удалось войти.")
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            state.value = state.value.copy(isLoading = true)
            runCatching { repository.logout() }
            state.value = AppUiState(isLoading = false)
        }
    }

    fun refresh() {
        val role = state.value.role ?: return
        viewModelScope.launch {
            try {
                val snapshot = repository.loadSnapshot(role)
                state.value = snapshot.toUiState(isLoading = false).copy(message = state.value.message)
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

    fun clearMessage() {
        state.value = state.value.copy(message = null, error = null)
    }

    private fun restoreSession() {
        viewModelScope.launch {
            try {
                val role = repository.restoreSession()
                if (role == null) {
                    state.value = AppUiState(isLoading = false)
                } else {
                    state.value = repository.loadSnapshot(role).toUiState(isLoading = false)
                }
            } catch (error: Exception) {
                state.value = AppUiState(isLoading = false, error = error.message)
            }
        }
    }

    private fun execute(successMessage: String, action: suspend () -> Unit) {
        viewModelScope.launch {
            state.value = state.value.copy(isLoading = true, error = null, message = null)
            try {
                action()
                val role = state.value.role ?: return@launch
                state.value = repository.loadSnapshot(role).toUiState(isLoading = false).copy(message = successMessage)
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

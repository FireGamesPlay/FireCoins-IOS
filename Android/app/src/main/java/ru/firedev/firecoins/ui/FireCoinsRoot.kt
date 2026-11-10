package ru.firedev.firecoins.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import ru.firedev.firecoins.data.FcCasinoRound
import ru.firedev.firecoins.data.FcNote
import ru.firedev.firecoins.data.FcTransaction
import ru.firedev.firecoins.data.FcWithdrawal
import ru.firedev.firecoins.ui.theme.FcAccent
import ru.firedev.firecoins.ui.theme.FcAccentLight
import ru.firedev.firecoins.ui.theme.FcBackground
import ru.firedev.firecoins.ui.theme.FcBorder
import ru.firedev.firecoins.ui.theme.FcMuted
import ru.firedev.firecoins.ui.theme.FcNegative
import ru.firedev.firecoins.ui.theme.FcPositive
import ru.firedev.firecoins.ui.theme.FcSurface
import ru.firedev.firecoins.ui.theme.FcSurfaceElevated
import ru.firedev.firecoins.ui.theme.FcText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FireCoinsRoot(viewModel: MainViewModel) {
    val state = viewModel.state.value
    var selectedTab by remember { mutableStateOf("home") }
    val snackbarHost = remember { SnackbarHostState() }

    LaunchedEffect(state.message, state.error) {
        val message = state.error ?: state.message
        if (!message.isNullOrBlank()) snackbarHost.showSnackbar(message)
        viewModel.clearMessage()
    }

    if (state.role == null) {
        if (state.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().background(FcBackground),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = FcAccent)
            }
        } else {
            InvitationScreen(
                isLoading = state.isLoading,
                error = state.error,
                onLogin = viewModel::login
            )
        }
        return
    }

    val isFather = state.role == "father"
    val tab = when {
        selectedTab == "casino" && isFather -> "admin"
        selectedTab == "admin" && !isFather -> "casino"
        else -> selectedTab
    }

    Scaffold(
        containerColor = FcBackground,
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("FIRE COINS", fontWeight = FontWeight.Bold, letterSpacing = 2.sp, color = FcAccent)
                        Text(if (isFather) "Семейный счёт" else "Личный кабинет", fontSize = 11.sp, color = FcMuted)
                    }
                },
                actions = {
                    TextButton(onClick = viewModel::refresh) {
                        Text("Обновить", color = FcAccent)
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar(containerColor = FcSurface) {
                NavItem("home", "Главная", tab == "home") { selectedTab = "home" }
                NavItem("history", "История", tab == "history") { selectedTab = "history" }
                NavItem(if (isFather) "admin" else "casino", if (isFather) "Управление" else "Рулетка", tab == "admin" || tab == "casino") {
                    selectedTab = if (isFather) "admin" else "casino"
                }
                NavItem("wallet", "Кошелёк", tab == "wallet") { selectedTab = "wallet" }
                NavItem("profile", "Профиль", tab == "profile") { selectedTab = "profile" }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                "home" -> HomeScreen(state.balance, state.role, state.transactions)
                "history" -> HistoryScreen(state.transactions, state.role, viewModel)
                "casino" -> CasinoScreen(state, viewModel)
                "admin" -> AdminScreen(state, viewModel)
                "wallet" -> WalletScreen(state.balance, state.role, state.withdrawals, viewModel)
                "profile" -> ProfileScreen(state, viewModel)
            }
            if (state.isLoading) {
                Box(
                    Modifier.fillMaxSize().background(FcBackground.copy(alpha = 0.48f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = FcAccent)
                }
            }
        }
    }
}

@Composable
private fun NavItem(
    key: String,
    title: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = { Text(title.take(1), fontWeight = FontWeight.Bold) },
        label = { Text(title, fontSize = 9.sp) }
    )
}

@Composable
private fun InvitationScreen(
    isLoading: Boolean,
    error: String?,
    onLogin: (String) -> Unit
) {
    var code by remember { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxSize().background(FcBackground).padding(horizontal = 26.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            modifier = Modifier.size(58.dp),
            shape = RoundedCornerShape(19.dp),
            color = FcAccent
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("F", fontSize = 28.sp, fontWeight = FontWeight.Black, color = Color(0xFF211A14))
            }
        }
        Spacer(Modifier.height(28.dp))
        Text("FIRE COINS", color = FcAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        Text("Свои правила.\nСвоя система.", color = FcText, fontSize = 34.sp, fontWeight = FontWeight.SemiBold, lineHeight = 39.sp)
        Spacer(Modifier.height(12.dp))
        Text(
            "Закрытое пространство для двух аккаунтов. Вход доступен только по персональному приглашению.",
            color = FcMuted,
            fontSize = 14.sp,
            lineHeight = 21.sp
        )
        Spacer(Modifier.height(30.dp))
        Text("КОД ПРИГЛАШЕНИЯ", color = FcMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
        Spacer(Modifier.height(8.dp))
        FcTextField(
            value = code,
            onValueChange = { code = it.uppercase() },
            label = "FC-XXXX-XXXX-XXXX-XXXX"
        )
        if (!error.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(error, color = FcNegative, fontSize = 12.sp)
        }
        Spacer(Modifier.height(18.dp))
        FcPrimaryButton("Продолжить", enabled = code.isNotBlank() && !isLoading) {
            onLogin(code.trim())
        }
        Spacer(Modifier.height(20.dp))
        Text("Доступ только по приглашению", color = FcMuted, fontSize = 11.sp)
    }
}

@Composable
private fun HomeScreen(balance: Int, role: String, transactions: List<FcTransaction>) {
    ScrollColumn {
        Text(if (role == "father") "Добро пожаловать, папа" else "Твой личный счёт", color = FcMuted, fontSize = 13.sp)
        SectionTitle("Текущий баланс")
        SurfaceCard {
            Text("FC ACCOUNT", color = FcAccentLight, fontSize = 10.sp, letterSpacing = 1.5.sp)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(balance.toString(), color = FcText, fontSize = 42.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.SansSerif)
                Spacer(Modifier.width(8.dp))
                Text("FC", color = FcAccentLight, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            Text(if (role == "father") "Баланс аккаунта Артемия" else "Твои доступные баллы", color = FcMuted, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            Text("Персональная система · Закрытый доступ", color = FcMuted, fontSize = 10.sp)
        }
        SectionTitle("Последние операции")
        if (transactions.isEmpty()) EmptyMessage("Операций пока нет")
        transactions.take(5).forEach { TransactionCard(it) }
    }
}

@Composable
private fun HistoryScreen(
    transactions: List<FcTransaction>,
    role: String,
    viewModel: MainViewModel
) {
    val recentIDs = transactions.take(10).map { it.id }.toSet()
    ScrollColumn {
        ScreenHeading("История", "Каждое начисление и списание — в одном месте.")
        if (transactions.isEmpty()) EmptyMessage("Операций пока нет")
        transactions.forEach { transaction ->
            TransactionCard(transaction)
            if (role == "artemiy" && transaction.id in recentIDs && transaction.disputeStatus == "none") {
                FcSecondaryButton("Оспорить операцию") { viewModel.dispute(transaction.id) }
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun CasinoScreen(state: AppUiState, viewModel: MainViewModel) {
    var bet by remember { mutableStateOf("5") }
    ScrollColumn {
        ScreenHeading("Рулетка", "Немного удачи — и только в пределах лимита.")
        SurfaceCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("50 / 50", color = FcAccentLight, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text("ЛИМИТ НА 7 ДНЕЙ", color = FcMuted, fontSize = 10.sp)
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(state.spinsLeft.toString(), color = FcText, fontSize = 34.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Text("из 2 запусков осталось", color = FcMuted, fontSize = 12.sp)
            }
        }
        SectionTitle("Размер ставки")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("1", "5", "10").forEach { value ->
                OutlinedButton(
                    onClick = { bet = value },
                    border = BorderStroke(1.dp, if (bet == value) FcAccent else FcBorder)
                ) { Text("$value FC", color = if (bet == value) FcAccent else FcText) }
            }
        }
        FcTextField(bet, { bet = it }, "Или введи свою ставку", isNumber = true)
        FcPrimaryButton("Сделать ход", enabled = state.spinsLeft > 0) { viewModel.playCasino(bet) }
        state.rouletteResult?.let { result ->
            SurfaceCard {
                Text(if (result.won) "Сегодня твой день" else "В следующий раз", color = FcText, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (result.won) "Выигрыш: " + result.payout + " FC" else "Ставка " + result.bet + " FC списана",
                    color = if (result.won) FcPositive else FcMuted
                )
            }
        }
        SectionTitle("Последние раунды")
        if (state.casinoRounds.isEmpty()) EmptyMessage("Пока без ставок")
        state.casinoRounds.forEach { round ->
            SurfaceCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (round.won) "Выигрыш" else "Проигрыш", color = FcText, fontWeight = FontWeight.SemiBold)
                        Text(round.timestamp, color = FcMuted, fontSize = 11.sp)
                    }
                    Text(if (round.won) "+" + round.payout + " FC" else "−" + round.bet + " FC", color = if (round.won) FcPositive else FcNegative)
                }
            }
        }
    }
}

@Composable
private fun AdminScreen(state: AppUiState, viewModel: MainViewModel) {
    var amount by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("Хорошая оценка") }
    var proofBytes by remember { mutableStateOf<ByteArray?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                proofBytes = readBytes(context, uri)
            }
        }
    }

    ScrollColumn {
        ScreenHeading("Управление", "Администрирование счёта Артемия.")
        SurfaceCard {
            SectionTitle("Операция")
            FcTextField(amount, { amount = it }, "Количество баллов", isNumber = true)
            FcTextField(reason, { reason = it }, "Причина")
            FcSecondaryButton(if (proofBytes == null) "Добавить фотодоказательство" else "Фотография прикреплена") {
                photoPicker.launch("image/*")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FcPrimaryButton("Начислить", enabled = amount.isNotBlank()) {
                    viewModel.addTransaction(amount, "add", reason, null)
                    amount = ""
                }
                FcSecondaryButton("Списать", enabled = amount.isNotBlank()) {
                    viewModel.addTransaction(amount, "remove", reason, proofBytes)
                    amount = ""
                    proofBytes = null
                }
            }
            Text("Операция меняет баланс и сразу появляется в истории.", color = FcMuted, fontSize = 10.sp)
        }

        SectionTitle("Оспаривания")
        if (state.pendingDisputes.isEmpty()) EmptyMessage("Нет открытых споров")
        state.pendingDisputes.forEach { item ->
            SurfaceCard {
                Text(item.reason, color = FcText, fontWeight = FontWeight.SemiBold)
                Text("Операция #" + item.id + " · " + item.amount + " FC", color = FcMuted, fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FcSecondaryButton("Отклонить") { viewModel.resolveDispute(item.id, false) }
                    FcPrimaryButton("Одобрить возврат") { viewModel.resolveDispute(item.id, true) }
                }
            }
        }

        SectionTitle("Запросы на вывод")
        val pending = state.withdrawals.filter { it.status == "pending" }
        if (pending.isEmpty()) EmptyMessage("Нет активных заявок")
        pending.forEach { request ->
            SurfaceCard {
                Text(request.amount.toString() + " FC", color = FcText, fontWeight = FontWeight.SemiBold)
                Text(request.timestamp, color = FcMuted, fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FcSecondaryButton("Отклонить") { viewModel.resolveWithdrawal(request.id, false) }
                    FcPrimaryButton("Одобрить") { viewModel.resolveWithdrawal(request.id, true) }
                }
            }
        }
    }
}

@Composable
private fun WalletScreen(
    balance: Int,
    role: String,
    withdrawals: List<FcWithdrawal>,
    viewModel: MainViewModel
) {
    var amount by remember { mutableStateOf("") }
    ScrollColumn {
        ScreenHeading("Кошелёк", "Заявки на вывод баллов и их статус.")
        SurfaceCard {
            Text("ДОСТУПНО", color = FcMuted, fontSize = 10.sp, letterSpacing = 1.2.sp)
            Text("$balance FC", color = FcText, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
        }
        if (role == "artemiy") {
            SurfaceCard {
                SectionTitle("Новая заявка")
                FcTextField(amount, { amount = it }, "Сколько баллов вывести", isNumber = true)
                Text("Баллы спишутся только после одобрения заявки администратором.", color = FcMuted, fontSize = 11.sp)
                FcPrimaryButton("Отправить заявку", enabled = amount.isNotBlank()) {
                    viewModel.requestWithdrawal(amount)
                    amount = ""
                }
            }
        }
        SectionTitle("Заявки")
        if (withdrawals.isEmpty()) EmptyMessage("Заявок пока нет")
        withdrawals.forEach { request -> WithdrawalCard(request) }
    }
}

@Composable
private fun ProfileScreen(state: AppUiState, viewModel: MainViewModel) {
    var noteText by remember { mutableStateOf("") }
    val title = if (state.role == "father") "Папа" else "Артемий"
    ScrollColumn {
        ScreenHeading("Профиль", "Настройки аккаунта и связь внутри системы.")
        SurfaceCard {
            Text(title, color = FcText, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            Text(if (state.role == "father") "Администратор" else "Участник", color = FcMuted, fontSize = 12.sp)
            Text("АКТИВНЫЙ АККАУНТ", color = FcPositive, fontSize = 10.sp)
        }
        SurfaceCard {
            SectionTitle("Личные заметки")
            FcTextField(noteText, { noteText = it }, "Напиши сообщение…")
            FcPrimaryButton("Отправить заметку", enabled = noteText.isNotBlank()) {
                viewModel.sendNote(noteText)
                noteText = ""
            }
            if (state.notes.isEmpty()) EmptyMessage("Заметок пока нет")
            state.notes.take(20).forEach { note -> NoteCard(note) }
        }
        FcSecondaryButton("Выйти из аккаунта") { viewModel.logout() }
    }
}

@Composable
private fun TransactionCard(item: FcTransaction) {
    SurfaceCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.reason, color = FcText, fontWeight = FontWeight.Medium)
                Text(item.timestamp, color = FcMuted, fontSize = 10.sp)
                if (item.disputeStatus != "none") {
                    Text(
                        when (item.disputeStatus) {
                            "pending" -> "На рассмотрении"
                            "approved" -> "Баллы возвращены"
                            "rejected" -> "Отклонено"
                            else -> ""
                        },
                        color = FcAccent,
                        fontSize = 10.sp
                    )
                }
            }
            Text(
                (if (item.type == "add") "+" else "−") + item.amount,
                color = if (item.type == "add") FcPositive else FcNegative,
                fontWeight = FontWeight.SemiBold
            )
        }
        if (item.hasProof) Text("Прикреплено фото", color = FcMuted, fontSize = 10.sp)
    }
}

@Composable
private fun WithdrawalCard(item: FcWithdrawal) {
    SurfaceCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Вывод " + item.amount + " FC", color = FcText, fontWeight = FontWeight.SemiBold)
                Text(item.timestamp, color = FcMuted, fontSize = 10.sp)
            }
            Text(
                when (item.status) {
                    "pending" -> "Ожидает"
                    "approved" -> "Одобрено"
                    else -> "Отклонено"
                },
                color = when (item.status) {
                    "pending" -> FcAccent
                    "approved" -> FcPositive
                    else -> FcNegative
                },
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun NoteCard(note: FcNote) {
    SurfaceCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (note.senderRole == "father") "Папа" else "Артемий", color = FcAccent, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(note.timestamp, color = FcMuted, fontSize = 9.sp)
        }
        Text(note.text, color = FcText, fontSize = 13.sp)
    }
}

@Composable
private fun ScreenHeading(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(title, color = FcText, fontSize = 29.sp, fontWeight = FontWeight.SemiBold)
        Text(subtitle, color = FcMuted, fontSize = 13.sp)
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, color = FcText, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun EmptyMessage(message: String) {
    SurfaceCard {
        Text(message, color = FcMuted, fontSize = 13.sp)
    }
}

@Composable
private fun ScrollColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = content
    )
}

@Composable
private fun SurfaceCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = FcSurface),
        border = BorderStroke(1.dp, FcBorder)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
            content = content
        )
    }
}

@Composable
private fun FcTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    isNumber: Boolean = false
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = !label.contains("сообщение", ignoreCase = true),
        keyboardOptions = if (isNumber) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
        colors = TextFieldDefaults.colors(
            focusedTextColor = FcText,
            unfocusedTextColor = FcText,
            focusedContainerColor = FcSurfaceElevated,
            unfocusedContainerColor = FcSurfaceElevated,
            focusedIndicatorColor = FcAccent,
            unfocusedIndicatorColor = FcBorder,
            focusedLabelColor = FcAccent,
            unfocusedLabelColor = FcMuted,
            cursorColor = FcAccent
        )
    )
}

@Composable
private fun FcPrimaryButton(
    title: String,
    enabled: Boolean = true,
    action: () -> Unit
) {
    Button(
        onClick = action,
        enabled = enabled,
        modifier = Modifier.height(48.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = FcAccent,
            contentColor = Color(0xFF211A14),
            disabledContainerColor = FcAccent.copy(alpha = 0.35f)
        )
    ) {
        Text(title, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun FcSecondaryButton(
    title: String,
    enabled: Boolean = true,
    action: () -> Unit
) {
    OutlinedButton(
        onClick = action,
        enabled = enabled,
        modifier = Modifier.height(48.dp),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, FcBorder)
    ) {
        Text(title, color = if (enabled) FcAccentLight else FcMuted, fontWeight = FontWeight.SemiBold)
    }
}

private suspend fun readBytes(context: Context, uri: Uri): ByteArray? {
    return withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                throw IllegalArgumentException("Не удалось прочитать изображение.")
            }

            var sampleSize = 1
            val largestSide = maxOf(bounds.outWidth, bounds.outHeight)
            while (largestSide / sampleSize > 2048) sampleSize *= 2

            val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                ?: throw IllegalArgumentException("Не удалось открыть изображение.")

            try {
                var quality = 86
                var bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
                while (bytes.size > 7 * 1024 * 1024 && quality > 45) {
                    quality -= 8
                    bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
                }
                if (bytes.size > 8 * 1024 * 1024) {
                    throw IllegalArgumentException("Фотография слишком большая. Выберите другое изображение.")
                }
                bytes
            } finally {
                bitmap.recycle()
            }
        }.getOrNull()
    }
}

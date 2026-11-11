package ru.firedev.firecoins.ui

import android.content.Context
import android.content.pm.PackageManager
import android.Manifest
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import com.google.firebase.FirebaseApp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Casino
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.util.LinkedHashMap
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
    val scope = rememberCoroutineScope()

    val context = LocalContext.current
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.registerPushDevice()
    }
    LaunchedEffect(state.role) {
        if (state.role != null && FirebaseApp.getApps(context).isNotEmpty()) {
            viewModel.registerPushDevice()
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
    var welcomedRole by remember { mutableStateOf<String?>(null) }
    var showWelcome by remember { mutableStateOf(false) }
    var selectedProofBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var proofLoading by remember { mutableStateOf(false) }
    var proofError by remember { mutableStateOf<String?>(null) }
    var proofRequestJob by remember { mutableStateOf<Job?>(null) }
    // Cache only successfully decoded images so reopening the same proof is instant.
    val proofBitmapCache = remember {
        LinkedHashMap<Long, ImageBitmap>(8, 0.75f, true)
    }

    val openProof: (Long) -> Unit = proofClick@{ transactionId ->
        if (proofLoading || proofRequestJob?.isActive == true) return@proofClick

        proofBitmapCache[transactionId]?.let { cached ->
            selectedProofBitmap = cached
            proofError = null
            return@proofClick
        }

        proofRequestJob = scope.launch {
            proofLoading = true
            proofError = null
            selectedProofBitmap = null
            try {
                val bitmap = withTimeout(25_000) {
                    val bytes = viewModel.downloadProof(transactionId)
                    withContext(Dispatchers.Default) {
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                            throw IllegalStateException("Сервер вернул файл, который не удалось распознать как фотографию.")
                        }
                        var sampleSize = 1
                        while (bounds.outWidth / sampleSize > 2048 || bounds.outHeight / sampleSize > 2048) {
                            sampleSize *= 2
                        }
                        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
                            ?: throw IllegalStateException("Не удалось декодировать фотографию.")
                    }
                }

                proofBitmapCache[transactionId] = bitmap
                while (proofBitmapCache.size > 8) {
                    val oldestId = proofBitmapCache.keys.firstOrNull() ?: break
                    proofBitmapCache.remove(oldestId)
                }
                selectedProofBitmap = bitmap
            } catch (error: TimeoutCancellationException) {
                proofError = "Загрузка фотографии превысила 25 секунд. Попробуйте ещё раз."
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                proofError = error.message?.takeIf { it.isNotBlank() }
                    ?: "Не удалось загрузить фотографию."
            } finally {
                proofLoading = false
                proofRequestJob = null
            }
        }
    }

    LaunchedEffect(state.role, state.isLoading) {
        val currentRole = state.role
        if (currentRole == null) {
            welcomedRole = null
            showWelcome = false
        } else if (!state.isLoading && welcomedRole != currentRole) {
            welcomedRole = currentRole
            showWelcome = true
            delay(2_900)
            showWelcome = false
        }
    }

    if (showWelcome && state.role != null) {
        FireCoinsWelcomeScreen(role = state.role, onContinue = { showWelcome = false })
        return
    }

    LaunchedEffect(state.message, state.error, state.role) {
        // Keep login/recovery errors on the invitation screen; SnackbarHost exists only after login.
        if (state.role != null) {
            val message = state.error ?: state.message
            if (!message.isNullOrBlank() && !message.isTechnicalTimeout()) {
                snackbarHost.showSnackbar(message)
            }
            viewModel.clearMessage()
        }
    }

    LaunchedEffect(proofError) {
        val message = proofError
        if (!message.isNullOrBlank()) {
            snackbarHost.showSnackbar(message)
            proofError = null
        }
    }

    if (state.role == null) {
        if (state.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().background(FcBackground),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = FcAccent)
            }
        } else if (state.sessionRecovery) {
            SessionRecoveryScreen(
                message = state.error ?: "Сохранённая сессия пока недоступна.",
                onRetry = viewModel::retrySession,
                onReset = viewModel::logout
            )
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
        snackbarHost = {
            SnackbarHost(snackbarHost) { data ->
                Snackbar(
                    snackbarData = data,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    shape = RoundedCornerShape(16.dp),
                    containerColor = FcSurfaceElevated,
                    contentColor = FcText,
                    actionColor = FcAccentLight,
                    dismissActionContentColor = FcMuted
                )
            }
        },
        topBar = {
            Column {
                if (state.isSandbox) {
                    Surface(color = FcNegative, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "ТЕСТ РЕЖИМ",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 2.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp)
                        )
                    }
                }
                TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = FcBackground,
                    titleContentColor = FcText,
                    actionIconContentColor = FcAccent
                ),
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
            }
        },
        bottomBar = {
            Surface(
                color = FcSurface,
                shadowElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            width = 1.dp,
                            color = FcBorder,
                            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
                        )
                        .padding(horizontal = 5.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    NavItem("home", "Главная", tab == "home", Modifier.weight(1f)) { selectedTab = "home" }
                    NavItem("history", "История", tab == "history", Modifier.weight(1f)) { selectedTab = "history" }
                    NavItem(
                        if (isFather) "admin" else "casino",
                        if (isFather) "Управление" else "Рулетка",
                        tab == "admin" || tab == "casino",
                        Modifier.weight(1f)
                    ) {
                        selectedTab = if (isFather) "admin" else "casino"
                    }
                    NavItem("wallet", "Кошелёк", tab == "wallet", Modifier.weight(1f)) { selectedTab = "wallet" }
                    NavItem("profile", "Профиль", tab == "profile", Modifier.weight(1f)) { selectedTab = "profile" }
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                "home" -> HomeScreen(
                    state.balance,
                    state.role,
                    state.transactions,
                    openProof,
                    onSelectTab = { selectedTab = it }
                )
                "history" -> HistoryScreen(state.transactions, state.role, viewModel, openProof)
                "casino" -> CasinoScreen(state, viewModel)
                "admin" -> AdminScreen(state, viewModel, openProof)
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

    if (proofLoading) {
        Dialog(onDismissRequest = { proofRequestJob?.cancel() }) {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = FcSurface,
                border = BorderStroke(1.dp, FcBorder)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 26.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    CircularProgressIndicator(color = FcAccent)
                    Text("Открываем фотографию…", color = FcText, fontSize = 14.sp)
                    TextButton(onClick = { proofRequestJob?.cancel() }) {
                        Text("Отмена", color = FcAccentLight)
                    }
                }
            }
        }
    }

    selectedProofBitmap?.let { bitmap ->
        Dialog(onDismissRequest = { selectedProofBitmap = null }) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = FcSurface,
                border = BorderStroke(1.dp, FcBorder),
                shadowElevation = 12.dp
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Фотодоказательство",
                            color = FcText,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { selectedProofBitmap = null }) {
                            Text("Закрыть", color = FcAccentLight)
                        }
                    }
                    Image(
                        bitmap = bitmap,
                        contentDescription = "Прикреплённая фотография",
                        modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp)
                            .clip(RoundedCornerShape(14.dp)),
                        contentScale = ContentScale.Fit
                    )
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
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val icon = when (key) {
        "home" -> Icons.Outlined.Home
        "history" -> Icons.Outlined.History
        "admin" -> Icons.Outlined.Tune
        "casino" -> Icons.Outlined.Casino
        "wallet" -> Icons.Outlined.AccountBalanceWallet
        else -> Icons.Outlined.PersonOutline
    }
    Surface(
        modifier = modifier.padding(horizontal = 2.dp).clickable(onClick = onClick),
        color = if (selected) FcAccent.copy(alpha = 0.13f) else Color.Transparent,
        shape = RoundedCornerShape(13.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = if (selected) FcAccentLight else FcMuted,
                modifier = Modifier.size(21.dp)
            )
            Text(
                title,
                color = if (selected) FcAccentLight else FcMuted,
                fontSize = 9.sp,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun FireCoinsWelcomeScreen(
    role: String,
    onContinue: () -> Unit
) {
    var revealed by remember { mutableStateOf(false) }
    val entry by animateFloatAsState(
        targetValue = if (revealed) 1f else 0f,
        animationSpec = tween(durationMillis = 850),
        label = "welcomeEntrance"
    )
    val pulseTransition = rememberInfiniteTransition(label = "welcomePulse")
    val pulse by pulseTransition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.10f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2_100),
            repeatMode = RepeatMode.Reverse
        ),
        label = "haloScale"
    )

    LaunchedEffect(Unit) { revealed = true }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF17110D), FcBackground, Color(0xFF070809))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(252.dp)
                .graphicsLayer { scaleX = pulse; scaleY = pulse; alpha = 0.58f }
                .border(1.dp, FcAccent.copy(alpha = 0.32f), CircleShape)
        )
        Box(
            modifier = Modifier
                .size(212.dp)
                .graphicsLayer { scaleX = 1.08f / pulse; scaleY = 1.08f / pulse; alpha = 0.34f }
                .border(1.dp, FcAccentLight.copy(alpha = 0.28f), CircleShape)
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 34.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Image(
                painter = painterResource(id = ru.firedev.firecoins.R.drawable.firecoins_welcome),
                contentDescription = "Логотип Fire Coins",
                modifier = Modifier
                    .size(196.dp)
                    .graphicsLayer {
                        alpha = entry
                        scaleX = 0.70f + 0.30f * entry
                        scaleY = 0.70f + 0.30f * entry
                        rotationZ = -12f * (1f - entry)
                    }
            )
            Spacer(Modifier.height(24.dp))
            Text(
                "FIRE COINS",
                color = FcAccent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 4.sp,
                modifier = Modifier.graphicsLayer {
                    alpha = entry
                    translationY = 12.dp.toPx() * (1f - entry)
                }
            )
            Spacer(Modifier.height(14.dp))
            Text(
                if (role == "father") "Привет, Макс!" else "Привет, Артемий!",
                color = FcText,
                fontSize = 32.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.8).sp,
                modifier = Modifier.graphicsLayer {
                    alpha = entry
                    translationY = 18.dp.toPx() * (1f - entry)
                }
            )
            Spacer(Modifier.height(9.dp))
            Text(
                if (role == "father") "АДМИНИСТРАТОР" else "ЛИЧНЫЙ КАБИНЕТ",
                color = FcMuted,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 2.5.sp,
                modifier = Modifier.graphicsLayer { alpha = entry }
            )
            Button(
                onClick = onContinue,
                modifier = Modifier
                    .padding(top = 34.dp)
                    .fillMaxWidth(0.72f)
                    .graphicsLayer { alpha = entry },
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = FcAccent,
                    contentColor = Color(0xFF211A14)
                )
            ) {
                Text("Продолжить", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun SessionRecoveryScreen(
    message: String,
    onRetry: () -> Unit,
    onReset: () -> Unit
) {
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
        Text("Не удалось подключиться", color = FcText, fontSize = 27.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        Text(message, color = FcMuted, fontSize = 13.sp, lineHeight = 19.sp)
        Spacer(Modifier.height(20.dp))
        FcPrimaryButton("Повторить подключение", action = onRetry)
        TextButton(onClick = onReset) {
            Text("Сбросить сессию и войти заново", color = FcAccentLight)
        }
        Text(
            "Сохранённый код приглашения не требуется повторно вводить при временном сбое сети.",
            color = FcMuted,
            fontSize = 10.sp
        )
    }
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
private fun HomeScreen(
    balance: Int,
    role: String,
    transactions: List<FcTransaction>,
    onProofClick: (Long) -> Unit,
    onSelectTab: (String) -> Unit
) {
    ScrollColumn {
        Text(
            if (role == "father") "Добро пожаловать, Макс" else "Твой личный счёт",
            color = FcMuted,
            fontSize = 13.sp
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(25.dp))
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF34302B), Color(0xFF201D1B), Color(0xFF17191D))
                    )
                )
                .border(1.dp, Color.White.copy(alpha = 0.09f), RoundedCornerShape(25.dp))
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 18.dp, end = 18.dp)
                    .size(116.dp)
                    .border(1.dp, Color.White.copy(alpha = 0.07f), CircleShape)
            )
            Column(
                modifier = Modifier.fillMaxWidth().padding(21.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "ТЕКУЩИЙ БАЛАНС",
                        color = Color.White.copy(alpha = 0.68f),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.4.sp
                    )
                    Spacer(Modifier.weight(1f))
                    Surface(
                        color = FcAccentLight.copy(alpha = 0.13f),
                        shape = CircleShape
                    ) {
                        Text(
                            "FC ACCOUNT",
                            color = FcAccentLight,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        balance.toString(),
                        color = Color.White,
                        fontSize = 42.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.SansSerif
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("FC", color = Color.White.copy(alpha = 0.72f), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
                Text(
                    if (role == "father") "Баланс аккаунта Артемия" else "Твои доступные баллы",
                    color = Color.White.copy(alpha = 0.68f),
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(18.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.15f)))
                Spacer(Modifier.height(15.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.Home,
                        contentDescription = null,
                        tint = FcAccentLight,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(7.dp))
                    Text("Персональная система", color = Color.White.copy(alpha = 0.76f), fontSize = 10.sp)
                    Spacer(Modifier.weight(1f))
                    Text("Закрытый доступ", color = Color.White.copy(alpha = 0.62f), fontSize = 10.sp)
                }
            }
        }

        SectionTitle("Быстрый доступ")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HomeQuickAction(
                title = "История",
                subtitle = "Все операции",
                icon = Icons.Outlined.History,
                modifier = Modifier.weight(1f)
            ) { onSelectTab("history") }
            HomeQuickAction(
                title = if (role == "father") "Управление" else "Рулетка",
                subtitle = if (role == "father") "Панель Макса" else "2 запуска в неделю",
                icon = if (role == "father") Icons.Outlined.Tune else Icons.Outlined.Casino,
                modifier = Modifier.weight(1f)
            ) { onSelectTab(if (role == "father") "admin" else "casino") }
            HomeQuickAction(
                title = "Вывод",
                subtitle = "Заявки и статус",
                icon = Icons.Outlined.AccountBalanceWallet,
                modifier = Modifier.weight(1f)
            ) { onSelectTab("wallet") }
        }

        SectionTitle("Последние операции")
        if (transactions.isEmpty()) EmptyMessage("Операций пока нет")
        transactions.take(5).forEach { TransactionCard(it, onProofClick) }
    }
}

@Composable
private fun HomeQuickAction(
    title: String,
    subtitle: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        color = FcSurface,
        shape = RoundedCornerShape(17.dp),
        border = BorderStroke(1.dp, FcBorder)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(11.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = FcAccentLight,
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(FcAccent.copy(alpha = 0.12f))
                    .padding(7.dp)
            )
            Text(
                title,
                color = FcText,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
            Text(
                subtitle,
                color = FcMuted,
                fontSize = 9.sp,
                maxLines = 2,
                lineHeight = 12.sp
            )
        }
    }
}

@Composable
private fun HistoryScreen(
    transactions: List<FcTransaction>,
    role: String,
    viewModel: MainViewModel,
    onProofClick: (Long) -> Unit
) {
    val recentIDs = transactions.take(10).map { it.id }.toSet()
    ScrollColumn {
        ScreenHeading("История", "Каждое начисление и списание — в одном месте.")
        if (transactions.isEmpty()) EmptyMessage("Операций пока нет")
        transactions.forEach { transaction ->
            TransactionCard(transaction, onProofClick)
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
private fun AdminScreen(
    state: AppUiState,
    viewModel: MainViewModel,
    onProofClick: (Long) -> Unit
) {
    var amount by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("Хорошая оценка") }
    var proofBytes by remember { mutableStateOf<ByteArray?>(null) }
    var proofLoading by remember { mutableStateOf(false) }
    var proofError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                proofLoading = true
                proofError = null
                proofBytes = null
                try {
                    proofBytes = readProofBytes(context, uri)
                } catch (error: Exception) {
                    proofError = error.message ?: "Не удалось прочитать фотографию."
                } finally {
                    proofLoading = false
                }
            }
        }
    }

    fun submit(action: String) {
        viewModel.addTransaction(amount, action, reason, proofBytes) {
            amount = ""
            proofBytes = null
            proofError = null
        }
    }

    ScrollColumn {
        ScreenHeading("Управление", "Администрирование счёта Артемия.")
        SurfaceCard {
            SectionTitle("Операция")
            FcTextField(amount, { amount = it }, "Количество баллов", isNumber = true)
            FcTextField(reason, { reason = it }, "Причина")
            FcSecondaryButton(
                when {
                    proofLoading -> "Обрабатываем фотографию…"
                    proofBytes != null -> "Фотография прикреплена"
                    else -> "Добавить фотодоказательство"
                },
                enabled = !proofLoading && !state.isLoading
            ) {
                photoPicker.launch("image/*")
            }
            proofError?.let { Text(it, color = FcNegative, fontSize = 11.sp) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FcPrimaryButton(
                    "Начислить",
                    enabled = amount.isNotBlank() && !proofLoading && !state.isLoading
                ) { submit("add") }
                FcSecondaryButton(
                    "Списать",
                    enabled = amount.isNotBlank() && !proofLoading && !state.isLoading
                ) { submit("remove") }
            }
            Text("Операция меняет баланс и сразу появляется в истории.", color = FcMuted, fontSize = 10.sp)
        }

        SectionTitle("Оспаривания")
        if (state.pendingDisputes.isEmpty()) EmptyMessage("Нет открытых споров")
        state.pendingDisputes.forEach { item ->
            SurfaceCard {
                Text(item.reason, color = FcText, fontWeight = FontWeight.SemiBold)
                Text("Операция #" + item.id + " · " + item.amount + " FC", color = FcMuted, fontSize = 11.sp)
                if (item.hasProof) {
                    FcSecondaryButton("Посмотреть фото") { onProofClick(item.id) }
                }
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
    var settingsOpen by remember { mutableStateOf(false) }
    var resetOpen by remember { mutableStateOf(false) }
    val title = if (state.role == "father") "Макс" else "Артемий"

    ScrollColumn {
        ScreenHeading("Профиль", "Настройки аккаунта и связь внутри системы.")
        SurfaceCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier.size(66.dp).clip(CircleShape)
                        .background(FcSurfaceElevated).border(1.dp, FcBorder, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (state.role == "father") "М" else "А",
                        color = FcAccentLight,
                        fontSize = 25.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, color = FcText, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                    Text(if (state.role == "father") "Администратор" else "Участник", color = FcMuted, fontSize = 12.sp)
                    Text("АКТИВНЫЙ АККАУНТ", color = FcPositive, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                }
                IconButton(
                    onClick = { settingsOpen = true },
                    modifier = Modifier.size(42.dp).background(FcSurfaceElevated, CircleShape).border(1.dp, FcBorder, CircleShape)
                ) {
                    Icon(Icons.Outlined.Settings, contentDescription = "Настройки профиля", tint = FcAccentLight)
                }
            }
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

    if (settingsOpen) {
        ProfileSettingsDialog(state, viewModel, { settingsOpen = false }) { resetOpen = true }
    }
    if (resetOpen) {
        ResetDatabaseDialog(
            isBusy = state.isLoading,
            error = state.error,
            onDismiss = { resetOpen = false },
            onConfirm = { phrase ->
                resetOpen = false
                viewModel.resetCurrentDatabase(phrase)
            }
        )
    }
}

@Composable
private fun ProfileSettingsDialog(
    state: AppUiState,
    viewModel: MainViewModel,
    onDismiss: () -> Unit,
    onReset: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    var restartConfirmationOpen by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth().heightIn(max = 720.dp),
            shape = RoundedCornerShape(24.dp),
            color = FcBackground,
            border = BorderStroke(1.dp, FcBorder),
            shadowElevation = 16.dp
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(15.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Настройки", color = FcText, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                        Text("Аккаунт · " + if (state.role == "father") "Макс" else "Артемий", color = FcMuted, fontSize = 11.sp)
                    }
                    TextButton(onClick = onDismiss) { Text("Готово", color = FcAccentLight) }
                }
                SurfaceCard {
                    SectionTitle("Безопасность аккаунта")
                    Text(
                        "Перевыпуск создаёт новый одноразовый код для этого аккаунта. Старый код перестаёт работать, текущая сессия сохраняется.",
                        color = FcMuted, fontSize = 11.sp, lineHeight = 16.sp
                    )
                    FcPrimaryButton("Перевыпустить мой код", enabled = !state.isLoading) {
                        viewModel.reissueInvitation()
                    }
                    state.reissuedInvitationCode?.let { code ->
                        Text("НОВЫЙ КОД · ПОКАЖЕТСЯ ОДИН РАЗ", color = FcMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        Text(code, color = FcAccentLight, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        TextButton(onClick = { clipboard.setText(AnnotatedString(code)) }) {
                            Text("Скопировать код", color = FcAccentLight)
                        }
                    }
                }
                SurfaceCard {
                    SectionTitle("Среда приложения")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Тестовый режим", color = FcText, fontWeight = FontWeight.SemiBold)
                            Text(
                                "Отдельная пустая база. Операции приложения не затрагивают рабочие данные. Доступно обоим аккаунтам.",
                                color = FcMuted, fontSize = 10.sp, lineHeight = 15.sp
                            )
                        }
                        Switch(
                            checked = state.isSandbox,
                            onCheckedChange = viewModel::toggleSandbox,
                            enabled = !state.isLoading
                        )
                    }
                    if (state.isSandbox) {
                        Text("ТЕСТ РЕЖИМ АКТИВЕН", color = Color.Red, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                    } else {
                        Text("Сейчас используется рабочая база.", color = FcMuted, fontSize = 10.sp)
                    }
                }
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = FcSurface,
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.5.dp, FcNegative)
                ) {
                    Column(modifier = Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("⚠", color = FcNegative, fontSize = 18.sp)
                            Text("DANGER ZONE", color = FcNegative, fontSize = 12.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
                        }
                        Text("Операции в этом разделе могут привести к полной потере данных.", color = FcMuted, fontSize = 11.sp)
                        if (state.role == "father") {
                            Button(
                                onClick = onReset,
                                enabled = !state.isLoading,
                                colors = ButtonDefaults.buttonColors(containerColor = FcNegative.copy(alpha = 0.12f), contentColor = FcNegative),
                                border = BorderStroke(1.dp, FcNegative),
                                shape = RoundedCornerShape(13.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(horizontalAlignment = Alignment.Start, modifier = Modifier.weight(1f)) {
                                    Text("СБРОС ТЕКУЩЕЙ БД", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    Text("Полное удаление данных текущей среды", fontSize = 10.sp)
                                }
                            }
                            OutlinedButton(
                                onClick = { restartConfirmationOpen = true },
                                enabled = !state.isLoading,
                                modifier = Modifier.fillMaxWidth(),
                                border = BorderStroke(1.dp, FcNegative),
                                colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = FcNegative),
                                shape = RoundedCornerShape(13.dp)
                            ) {
                                Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                                    Text("ПЕРЕЗАГРУЗИТЬ BACKEND", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    Text("Мягкий перезапуск серверного API", fontSize = 10.sp)
                                }
                            }
                        } else {
                            Text("Сброс и перезапуск Backend доступны только администратору.", color = FcMuted, fontSize = 10.sp)
                        }
                        Text(
                            "В рабочем режиме будут удалены транзакции, заявки и баланс. Операция не создаёт резервную копию и не может быть отменена. В тестовом режиме очищается только песочница.",
                            color = FcNegative, fontSize = 10.sp, lineHeight = 15.sp
                        )
                    }
                }
                Text(
                    if (state.pushConfigured) "Push-сервис настроен на сервере."
                    else "Системные push-уведомления ожидают подключения Firebase и APNs.",
                    color = if (state.pushConfigured) FcPositive else FcMuted, fontSize = 10.sp
                )
                state.error?.let { Text(it, color = FcNegative, fontSize = 11.sp) }
                state.message?.let { Text(it, color = FcPositive, fontSize = 11.sp) }
            }
        }
    }
    if (restartConfirmationOpen) {
        AlertDialog(
            onDismissRequest = { if (!state.isLoading) restartConfirmationOpen = false },
            containerColor = FcSurface,
            title = { Text("Перезагрузить Backend?", color = FcText, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "API временно станет недоступен на несколько секунд. Данные и база не удаляются.",
                    color = FcMuted
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        restartConfirmationOpen = false
                        viewModel.restartBackend()
                    },
                    enabled = !state.isLoading
                ) { Text("Перезагрузить", color = FcNegative, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(
                    onClick = { restartConfirmationOpen = false },
                    enabled = !state.isLoading
                ) { Text("Отмена", color = FcMuted) }
            }
        )
    }
}

@Composable
private fun ResetDatabaseDialog(
    isBusy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var phrase by remember { mutableStateOf("") }
    val matches = phrase == "УДАЛИТЬ БД"
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = FcSurface,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("⚠", color = FcNegative, fontSize = 23.sp)
                Text("Необратимый сброс", color = FcText, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
                Text("Вы действительно хотите полностью удалить текущую базу данных без возможности восстановления?", color = FcText, fontSize = 13.sp)
                Text("Рабочая база не затрагивается, если включён тестовый режим.", color = FcNegative, fontSize = 11.sp)
                Text("Для подтверждения вручную введите:", color = FcMuted, fontSize = 11.sp)
                Text("УДАЛИТЬ БД", color = FcNegative, fontSize = 14.sp, fontWeight = FontWeight.Black)
                OutlinedTextField(
                    value = phrase,
                    onValueChange = { phrase = it },
                    singleLine = true,
                    label = { Text("Введите УДАЛИТЬ БД") },
                    enabled = !isBusy
                )
                error?.let { Text(it, color = FcNegative, fontSize = 11.sp) }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(phrase) }, enabled = matches && !isBusy) {
                Text("УДАЛИТЬ", color = FcNegative, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isBusy) { Text("Отмена", color = FcMuted) }
        }
    )
}

private suspend fun readProofBytes(context: Context, uri: Uri): ByteArray =
    readCompressedImage(
        context = context,
        uri = uri,
        maxDimension = 2048,
        maxBytes = 8 * 1024 * 1024,
        tooLargeMessage = "Фотография слишком большая. Выберите другое изображение."
    )

private suspend fun readCompressedImage(
    context: Context,
    uri: Uri,
    maxDimension: Int,
    maxBytes: Int,
    tooLargeMessage: String
): ByteArray = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    val boundsRead = resolver.openInputStream(uri)?.use { input ->
        BitmapFactory.decodeStream(input, null, bounds)
    }
    if (boundsRead == null && (bounds.outWidth <= 0 || bounds.outHeight <= 0)) {
        throw IllegalArgumentException("Не удалось прочитать выбранное изображение.")
    }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        throw IllegalArgumentException("Выбранный файл не является изображением.")
    }

    var sampleSize = 1
    while (
        maxOf(bounds.outWidth / sampleSize, bounds.outHeight / sampleSize) > maxDimension * 2
    ) {
        sampleSize *= 2
    }

    val bitmap = resolver.openInputStream(uri)?.use { input ->
        BitmapFactory.decodeStream(
            input,
            null,
            BitmapFactory.Options().apply { inSampleSize = sampleSize }
        )
    } ?: throw IllegalArgumentException("Не удалось открыть выбранное изображение.")

    try {
        val largestSide = maxOf(bitmap.width, bitmap.height)
        val scale = minOf(1f, maxDimension / largestSide.toFloat())
        val resized = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true
            )
        } else bitmap

        try {
            var quality = 86
            var bytes: ByteArray
            do {
                val output = ByteArrayOutputStream()
                if (!resized.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
                    throw IllegalStateException("Не удалось обработать изображение.")
                }
                bytes = output.toByteArray()
                if (bytes.size <= maxBytes || quality <= 46) break
                quality -= 8
            } while (true)

            if (bytes.isEmpty()) throw IllegalArgumentException("Получилось пустое изображение.")
            if (bytes.size > maxBytes) throw IllegalArgumentException(tooLargeMessage)
            bytes
        } finally {
            if (resized !== bitmap) resized.recycle()
        }
    } finally {
        bitmap.recycle()
    }
}

@Composable
private fun TransactionCard(
    item: FcTransaction,
    onProofClick: (Long) -> Unit = {}
) {
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
        if (item.hasProof) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onProofClick(item.id) }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.AttachFile,
                    contentDescription = null,
                    tint = FcAccentLight,
                    modifier = Modifier.size(16.dp)
                )
                Text("Открыть фотографию", color = FcAccentLight, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            }
        }
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
            Text(if (note.senderRole == "father") "Макс" else "Артемий", color = FcAccent, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(note.timestamp, color = FcMuted, fontSize = 9.sp)
        }
        Text(note.text, color = FcText, fontSize = 13.sp)
    }
}

@Composable
private fun ScreenHeading(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            title,
            color = FcText,
            fontSize = 29.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.5).sp
        )
        Text(subtitle, color = FcMuted, fontSize = 13.sp, lineHeight = 19.sp)
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
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = content
    )
}

@Composable
private fun SurfaceCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(23.dp),
        colors = CardDefaults.cardColors(containerColor = FcSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
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
        shape = RoundedCornerShape(14.dp),
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

private suspend fun readBytes(context: Context, uri: Uri): ByteArray =
    readProofBytes(context, uri)


private fun String.isTechnicalTimeout(): Boolean {
    val normalized = lowercase()
    return normalized.contains("timeout") ||
        normalized.contains("timed out") ||
        normalized.contains("sockettimeoutexception") ||
        normalized.contains("etimedout") ||
        normalized.contains("read timed out") ||
        normalized.contains("connect timed out")
}

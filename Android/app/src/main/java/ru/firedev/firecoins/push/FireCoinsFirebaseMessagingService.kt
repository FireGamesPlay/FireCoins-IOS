package ru.firedev.firecoins.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ru.firedev.firecoins.MainActivity
import ru.firedev.firecoins.data.ApiClient

class FireCoinsFirebaseMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val previous = PushDeviceIdentity.token(this)
        PushDeviceIdentity.saveToken(this, token)
        if (token.isBlank()) return

        serviceScope.launch(Dispatchers.IO) {
            val api = ApiClient(applicationContext)
            if (!api.hasSession) return@launch
            runCatching {
                if (!previous.isNullOrBlank() && previous != token) {
                    api.unregisterPushToken(previous)
                }
                api.registerPushToken(token, PushDeviceIdentity.deviceId(applicationContext))
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val title = message.notification?.title ?: message.data["title"] ?: "Fire Coins"
        val body = message.notification?.body ?: message.data["body"] ?: "Новое событие в аккаунте."
        showNotification(title, body, message.data["event"])
    }

    private fun showNotification(title: String, body: String, event: String?) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "События Fire Coins", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Заметки, баллы, заявки и оспаривания"
                    enableVibration(true)
                }
            )
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("firecoins_event", event.orEmpty())
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(ru.firedev.firecoins.R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        runCatching {
            NotificationManagerCompat.from(this).notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification)
        }
    }

    private companion object {
        const val CHANNEL_ID = "firecoins_events"
        val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

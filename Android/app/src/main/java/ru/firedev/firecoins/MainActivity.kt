package ru.firedev.firecoins

import android.os.Bundle
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.firedev.firecoins.ui.FireCoinsRoot
import ru.firedev.firecoins.ui.MainViewModel
import ru.firedev.firecoins.ui.theme.FireCoinsTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "firecoins_events",
                "События Fire Coins",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Заметки, баллы, заявки и оспаривания"
                enableVibration(true)
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
        setContent {
            FireCoinsTheme {
                val viewModel: MainViewModel = viewModel()
                FireCoinsRoot(viewModel)
            }
        }
    }
}

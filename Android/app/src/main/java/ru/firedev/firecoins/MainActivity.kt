package ru.firedev.firecoins

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.firedev.firecoins.ui.FireCoinsRoot
import ru.firedev.firecoins.ui.MainViewModel
import ru.firedev.firecoins.ui.theme.FireCoinsTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FireCoinsTheme {
                val viewModel: MainViewModel = viewModel()
                FireCoinsRoot(viewModel)
            }
        }
    }
}

package com.example.ptts.core.app

import androidx.compose.runtime.Composable
import com.example.ptts.core.navigation.PttsNavHost
import com.example.ptts.ui.theme.PttsTheme

@Composable
fun PttsApp() {
    PttsTheme {
        PttsNavHost()
    }
}

package com.example.peaking

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.peaking.ui.PeakingNavHost
import com.example.peaking.ui.theme.PEAKingTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PEAKingTheme {
                PeakingNavHost()
            }
        }
    }
}

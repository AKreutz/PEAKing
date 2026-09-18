package com.example.peaking.ui.mypeaks

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.example.peaking.ui.theme.PEAKingTheme

@Composable
fun MyPeaksScreen(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("My Peaks")
    }
}

@Preview(showBackground = true)
@Composable
private fun MyPeaksScreenPreview() {
    PEAKingTheme {
        MyPeaksScreen()
    }
}

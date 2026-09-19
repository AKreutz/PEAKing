package com.akreutz.peaking.ui.explore

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.akreutz.peaking.ui.map.PeakMap
import com.akreutz.peaking.ui.theme.PEAKingTheme

@Composable
fun ExploreScreen(modifier: Modifier = Modifier) {
    PeakMap(modifier = modifier.fillMaxSize())
}

@Preview(showBackground = true)
@Composable
private fun ExploreScreenPreview() {
    PEAKingTheme {
        ExploreScreen()
    }
}

package com.example.peaking.ui.explore

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.example.peaking.ui.theme.PEAKingTheme
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.rememberCameraPositionState

private val DefaultMapCenter = LatLng(47.3769, 8.5417) // Zurich, as a placeholder center
private const val DefaultZoom = 10f

@Composable
fun ExploreScreen(modifier: Modifier = Modifier) {
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(DefaultMapCenter, DefaultZoom)
    }

    GoogleMap(
        modifier = modifier.fillMaxSize(),
        cameraPositionState = cameraPositionState,
        uiSettings = MapUiSettings(zoomControlsEnabled = true, myLocationButtonEnabled = false)
    )
}

@Preview(showBackground = true)
@Composable
private fun ExploreScreenPreview() {
    PEAKingTheme {
        ExploreScreen()
    }
}

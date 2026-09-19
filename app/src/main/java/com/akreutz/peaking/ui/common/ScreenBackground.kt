package com.akreutz.peaking.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.akreutz.peaking.R

/**
 * Wraps [content] with a subtle mountain silhouette image anchored to the bottom of the screen,
 * tinted with the theme's dynamic primary color (the same source as button colors) so it stays
 * in sync with Material You on supported devices, with a separate untinted snow-highlight layer
 * on top so the caps stay bright regardless of the tint.
 */
@Composable
fun ScreenBackground(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(modifier = modifier.fillMaxSize()) {
        Image(
            painter = painterResource(R.drawable.bg_mountains),
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .align(Alignment.BottomCenter),
            contentScale = ContentScale.FillWidth,
            alignment = Alignment.BottomCenter,
            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary)
        )
        Image(
            painter = painterResource(R.drawable.bg_mountains_snow),
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .align(Alignment.BottomCenter),
            contentScale = ContentScale.FillWidth,
            alignment = Alignment.BottomCenter
        )
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 32.sp
            ),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 86.dp)
        )
        content()
    }
}

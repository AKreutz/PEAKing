package com.example.peaking.ui.explore

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material.icons.outlined.Terrain
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.peaking.R
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.peaking.BuildConfig
import com.example.peaking.ui.theme.PEAKingTheme
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer

private val DefaultMapCenter = LatLng(47.3769, 8.5417) // Zurich, as a placeholder center
private const val DefaultZoom = 10.0
private const val UserLocationZoom = 14.0

private fun maptilerStyleUrl(apiKey: String): String =
    "https://api.maptiler.com/maps/topo/style.json?key=$apiKey"

// Built-in peak label layers from the topo style. Each ships with a "rank == 1" filter and
// minzoom that only shows the most prominent peaks per tile at higher zooms; we relax both so
// every named peak/volcano point present in the loaded tile data renders at any zoom. The
// "-us" layers/features carry a "customary_ft" elevation (in feet) instead of "ele" (metres),
// so the has/!has split must be kept or features can end up rendered by the wrong layer with
// a missing elevation field.
private data class PeakLayerConfig(
    val peakClass: String,
    val hasCustomaryFt: Boolean,
    val elevationField: String,
    val elevationUnitSuffix: String
)

private val BuiltInPeakLayerConfigs = mapOf(
    "mountain_peak" to PeakLayerConfig("peak", hasCustomaryFt = false, elevationField = "ele", elevationUnitSuffix = "m"),
    "mountain_peak-us" to PeakLayerConfig("peak", hasCustomaryFt = true, elevationField = "ele_ft", elevationUnitSuffix = "ft"),
    "mountain_volcano" to PeakLayerConfig("volcano", hasCustomaryFt = false, elevationField = "ele", elevationUnitSuffix = "m"),
    "mountain_volcano-us" to PeakLayerConfig("volcano", hasCustomaryFt = true, elevationField = "ele_ft", elevationUnitSuffix = "ft")
)

// Name on its own line, then elevation ("1234m") on a second line only when the elevation
// field is actually present on the feature — some named peak nodes in OSM have no ele tag.
private fun peakTextFieldExpression(config: PeakLayerConfig): Expression =
    Expression.switchCase(
        Expression.has(config.elevationField),
        Expression.concat(
            Expression.get("name:latin"),
            Expression.literal("\n"),
            Expression.toString(Expression.get(config.elevationField)),
            Expression.literal(config.elevationUnitSuffix)
        ),
        Expression.get("name:latin")
    )

private const val PeakCrownIconId = "peak-crown-icon"

/**
 * Draws a three-pointed crown ("|\/\/|"-style silhouette: two tall outer spikes, a shorter
 * middle spike, and a base) since MapLibre symbol layers require a bitmap icon rather than a
 * vector shape. A halo (an oversized stroke of the same outline drawn first, in [haloColor])
 * sits behind the fill so the icon reads clearly against busy map backgrounds — MapLibre symbol
 * layers have no built-in icon-halo property like text layers do, so it's baked into the bitmap.
 */
private fun createCrownIcon(
    tintColor: Int,
    haloColor: Int,
    haloWidthPx: Float,
    sizePx: Int
): Bitmap {
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    // Inset the shape so the halo stroke (drawn centered on the path) doesn't get clipped
    // by the bitmap edges.
    val inset = haloWidthPx / 2f
    val w = sizePx - inset * 2f
    val h = sizePx - inset * 2f

    val path = Path().apply {
        moveTo(inset, inset + h)
        lineTo(inset, inset)
        lineTo(inset + w * 0.25f, inset + h * 0.5f)
        lineTo(inset + w * 0.5f, inset)
        lineTo(inset + w * 0.755f, inset + h * 0.5f)
        lineTo(inset + w, inset)
        lineTo(inset + w * 0.95f, inset + h)
        close()
    }

    val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = haloColor
        style = Paint.Style.STROKE
        strokeWidth = haloWidthPx
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    canvas.drawPath(path, haloPaint)

    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = tintColor
        style = Paint.Style.FILL
    }
    canvas.drawPath(path, fillPaint)

    return bitmap
}

@Composable
fun ExploreScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasLocationPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasLocationPermission) {
            permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    var mapLibreMap by remember { mutableStateOf<MapLibreMap?>(null) }
    var peaksVisible by remember { mutableStateOf(true) }

    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).apply {
            getMapAsync { map ->
                map.cameraPosition = CameraPosition.Builder()
                    .target(DefaultMapCenter)
                    .zoom(DefaultZoom)
                    .build()
                map.setStyle(maptilerStyleUrl(BuildConfig.MAPTILER_API_KEY)) { style ->
                    style.addImage(
                        PeakCrownIconId,
                        createCrownIcon(
                            tintColor = Color.rgb(155, 112, 87),
                            haloColor = Color.WHITE,
                            haloWidthPx = 6f,
                            sizePx = 72
                        )
                    )

                    BuiltInPeakLayerConfigs.forEach { (layerId, config) ->
                        val layer = style.getLayer(layerId) as? SymbolLayer ?: return@forEach
                        layer.minZoom = 0f
                        val customaryFtFilter = if (config.hasCustomaryFt) {
                            Expression.has("customary_ft")
                        } else {
                            Expression.not(Expression.has("customary_ft"))
                        }
                        layer.setFilter(
                            Expression.all(
                                Expression.eq(Expression.geometryType(), "Point"),
                                Expression.eq(Expression.get("class"), config.peakClass),
                                Expression.has("name"),
                                customaryFtFilter
                            )
                        )
                        layer.setProperties(
                            PropertyFactory.iconImage(PeakCrownIconId),
                            PropertyFactory.iconSize(0.6f),
                            PropertyFactory.iconAllowOverlap(true),
                            PropertyFactory.iconIgnorePlacement(true),
                            PropertyFactory.textField(peakTextFieldExpression(config)),
                            PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
                            PropertyFactory.textOffset(arrayOf(0f, 0.6f)),
                            PropertyFactory.textSize(13f),
                            PropertyFactory.textHaloWidth(1.2f),
                            PropertyFactory.textAllowOverlap(true),
                            PropertyFactory.textIgnorePlacement(true)
                        )

                        // Move to the top of the draw stack so peak icons/labels always render
                        // above every other layer (including other labels), instead of following
                        // this layer's original position in the style.
                        style.removeLayer(layer)
                        style.addLayer(layer)
                    }
                    mapLibreMap = map
                }
            }
        }
    }

    LaunchedEffect(hasLocationPermission, mapLibreMap) {
        val map = mapLibreMap ?: return@LaunchedEffect
        val style = map.style ?: return@LaunchedEffect
        if (!hasLocationPermission) return@LaunchedEffect

        map.locationComponent.apply {
            if (!isLocationComponentActivated) {
                activateLocationComponent(
                    LocationComponentActivationOptions.builder(context, style).build()
                )
            }
            isLocationComponentEnabled = true
            cameraMode = CameraMode.TRACKING
            renderMode = RenderMode.COMPASS
        }

        map.locationComponent.lastKnownLocation?.let { location ->
            map.easeCamera(
                CameraUpdateFactory.newLatLngZoom(
                    LatLng(location.latitude, location.longitude),
                    UserLocationZoom
                )
            )
        }
    }

    LaunchedEffect(mapLibreMap, peaksVisible) {
        val style = mapLibreMap?.style ?: return@LaunchedEffect
        val visibility = if (peaksVisible) Property.VISIBLE else Property.NONE
        BuiltInPeakLayerConfigs.keys.forEach { layerId ->
            (style.getLayer(layerId) as? SymbolLayer)?.setProperties(
                PropertyFactory.visibility(visibility)
            )
        }
    }

    fun centerOnCurrentLocation() {
        val map = mapLibreMap ?: return
        if (!hasLocationPermission) {
            permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            return
        }

        val location = map.locationComponent.lastKnownLocation
        if (location != null) {
            map.easeCamera(
                CameraUpdateFactory.newLatLngZoom(
                    LatLng(location.latitude, location.longitude),
                    UserLocationZoom
                )
            )
        }
    }

    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { mapView }
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            FloatingActionButton(
                onClick = { peaksVisible = !peaksVisible },
                containerColor = if (peaksVisible) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surface
                }
            ) {
                Icon(
                    imageVector = if (peaksVisible) Icons.Filled.Terrain else Icons.Outlined.Terrain,
                    contentDescription = stringResource(
                        if (peaksVisible) R.string.explore_hide_peaks else R.string.explore_show_peaks
                    )
                )
            }

            FloatingActionButton(
                onClick = { centerOnCurrentLocation() },
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                Icon(
                    imageVector = Icons.Default.MyLocation,
                    contentDescription = stringResource(R.string.explore_center_on_location)
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ExploreScreenPreview() {
    PEAKingTheme {
        ExploreScreen()
    }
}

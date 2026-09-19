package com.akreutz.peaking.ui.map

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.Path as ComposePath
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.akreutz.peaking.R
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.akreutz.peaking.BuildConfig
import com.akreutz.peaking.data.peak.VisitedPeakRepository
import com.akreutz.peaking.data.peak.visitedPeakId
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point as GeoJsonPoint
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

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

private const val MetersPerFoot = 0.3048

/**
 * Renders a stored elevation (always metres - see [MetersPerFoot]) for display, rounded to the
 * nearest metre since the source data has no finer precision worth showing.
 */
fun formatElevationMeters(elevationMeters: Double): String = "${elevationMeters.roundToInt()}m"

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
private const val VisitedPeakCrownIconId = "visited-peak-crown-icon"
private const val VisitedPeaksSourceId = "visited-peaks-source"
private const val VisitedPeaksLayerId = "visited-peaks-layer"

private data class SelectedPeak(
    val name: String,
    val elevationMeters: Double?,
    val position: LatLng
)

/**
 * A peak tapped and selected while on a hike, before it has been persisted as visited.
 */
data class HikeSelectedPeak(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val elevationMeters: Double?
)

/**
 * Queries the built-in peak layers at the tapped screen point (with a small touch-target
 * padding, since the crown icon bitmap is smaller than a comfortable tap area) and extracts
 * the name/elevation from whichever feature is on top, if any.
 */
private fun queryTappedPeak(map: MapLibreMap, screenPoint: android.graphics.PointF): SelectedPeak? {
    val touchRadiusPx = 24f
    val box = android.graphics.RectF(
        screenPoint.x - touchRadiusPx,
        screenPoint.y - touchRadiusPx,
        screenPoint.x + touchRadiusPx,
        screenPoint.y + touchRadiusPx
    )
    val layerIds = BuiltInPeakLayerConfigs.keys.toTypedArray()
    val features = map.queryRenderedFeatures(box, *layerIds)
    val feature = features.firstOrNull() ?: return null

    val name = feature.getStringProperty("name:latin")
        ?: feature.getStringProperty("name")
        ?: return null

    // Elevation field differs per built-in layer ("ele" in metres vs. "ele_ft" for the "-us"
    // layers), so check whichever one is actually present on this feature.
    val elevationConfig = BuiltInPeakLayerConfigs.values.firstOrNull { config ->
        feature.hasNonNullValueForProperty(config.elevationField)
    }
    val elevationMeters = elevationConfig?.let {
        val value = feature.getNumberProperty(it.elevationField).toDouble()
        if (it.hasCustomaryFt) value * MetersPerFoot else value
    }

    val position = (feature.geometry() as? org.maplibre.geojson.Point)?.let {
        LatLng(it.latitude(), it.longitude())
    } ?: return null

    return SelectedPeak(name = name, elevationMeters = elevationMeters, position = position)
}

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

/**
 * Compose rendition of the same three-pointed crown silhouette used for the map's peak markers
 * (see [createCrownIcon]), for use in ordinary UI (e.g. list rows) where a bitmap icon isn't
 * needed. Tinted [tint], with no halo since it isn't drawn over map imagery here.
 */
@Composable
fun CrownIcon(modifier: Modifier = Modifier, tint: ComposeColor = ComposeColor(0xFFE67E22)) {
    ComposeCanvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val path = ComposePath().apply {
            moveTo(0f, h)
            lineTo(0f, 0f)
            lineTo(w * 0.25f, h * 0.5f)
            lineTo(w * 0.5f, 0f)
            lineTo(w * 0.755f, h * 0.5f)
            lineTo(w, 0f)
            lineTo(w * 0.95f, h)
            close()
        }
        drawPath(path, color = tint)
    }
}

/**
 * The shared peak map: MapTiler topo style with tappable peak markers, visited-peak overlay,
 * and user-location tracking. Used by both the Explore tab and the in-progress hike view.
 *
 * When [selectionMode] is true (during an in-progress hike), tapping a peak marker toggles its
 * membership in [selectedPeaks] via [onPeakToggled] instead of opening the info speech bubble,
 * and selected peaks are shown with the same orange tint as already-visited ones.
 */
@Composable
fun PeakMap(
    modifier: Modifier = Modifier,
    selectionMode: Boolean = false,
    selectedPeaks: Set<HikeSelectedPeak> = emptySet(),
    onPeakToggled: (HikeSelectedPeak) -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val repository = remember { VisitedPeakRepository(context) }
    val visitedPeaks by repository.observeVisitedPeaks().collectAsState(initial = emptyList())

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
    var selectedPeak by remember { mutableStateOf<SelectedPeak?>(null) }
    var selectedPeakScreenPos by remember { mutableStateOf<Offset?>(null) }

    val currentSelectionMode by rememberUpdatedState(selectionMode)
    val currentOnPeakToggled by rememberUpdatedState(onPeakToggled)

    val mapView = remember {
        MapLibre.getInstance(context)
        // TextureView instead of the default GLSurfaceView: a SurfaceView renders into its own
        // window layer outside normal view z-ordering, so switching away from this tab could
        // leave a stale map frame briefly compositing on top of the next tab's content.
        // TextureView is an ordinary View and composites in-order, avoiding that overlay glitch.
        MapView(context, MapLibreMapOptions.createFromAttributes(context, null).textureMode(true)).apply {
            getMapAsync { map ->
                map.cameraPosition = CameraPosition.Builder()
                    .target(DefaultMapCenter)
                    .zoom(DefaultZoom)
                    .build()
                map.addOnMapClickListener { latLng ->
                    val screenPoint = map.projection.toScreenLocation(latLng)
                    val peak = queryTappedPeak(map, screenPoint)
                    if (currentSelectionMode) {
                        if (peak != null) {
                            currentOnPeakToggled(
                                HikeSelectedPeak(
                                    id = visitedPeakId(peak.name, peak.position.latitude, peak.position.longitude),
                                    name = peak.name,
                                    latitude = peak.position.latitude,
                                    longitude = peak.position.longitude,
                                    elevationMeters = peak.elevationMeters
                                )
                            )
                        }
                        return@addOnMapClickListener peak != null
                    }
                    selectedPeak = peak
                    selectedPeakScreenPos = peak?.let {
                        val p = map.projection.toScreenLocation(it.position)
                        Offset(p.x, p.y)
                    }
                    peak != null
                }
                map.addOnCameraMoveListener {
                    val peak = selectedPeak ?: return@addOnCameraMoveListener
                    val p = map.projection.toScreenLocation(peak.position)
                    selectedPeakScreenPos = Offset(p.x, p.y)
                }
                map.setStyle(maptilerStyleUrl(BuildConfig.MAPTILER_API_KEY)) { style ->
                    style.addImage(
                        PeakCrownIconId,
                        createCrownIcon(
                            tintColor = Color.rgb(66, 66, 66),
                            haloColor = Color.WHITE,
                            haloWidthPx = 6f,
                            sizePx = 72
                        )
                    )
                    style.addImage(
                        VisitedPeakCrownIconId,
                        createCrownIcon(
                            tintColor = Color.rgb(230, 126, 34),
                            haloColor = Color.WHITE,
                            haloWidthPx = 6f,
                            sizePx = 72
                        )
                    )

                    style.addSource(GeoJsonSource(VisitedPeaksSourceId))
                    style.addLayer(
                        SymbolLayer(VisitedPeaksLayerId, VisitedPeaksSourceId).withProperties(
                            PropertyFactory.iconImage(VisitedPeakCrownIconId),
                            PropertyFactory.iconSize(0.6f),
                            PropertyFactory.iconAllowOverlap(true),
                            PropertyFactory.iconIgnorePlacement(true)
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
                            // Let MapLibre's collision detection hide overlapping icons/labels
                            // instead of forcing every peak to render (allowOverlap = false), so
                            // at low zoom only one marker per cluster of nearby peaks is shown.
                            PropertyFactory.iconAllowOverlap(false),
                            PropertyFactory.iconIgnorePlacement(false),
                            PropertyFactory.textField(peakTextFieldExpression(config)),
                            PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
                            PropertyFactory.textOffset(arrayOf(0f, 0.6f)),
                            PropertyFactory.textSize(13f),
                            PropertyFactory.textColor(Color.rgb(66, 66, 66)),
                            PropertyFactory.textHaloWidth(1.2f),
                            PropertyFactory.textAllowOverlap(false),
                            PropertyFactory.textIgnorePlacement(false),
                            PropertyFactory.textOptional(true)
                        )

                        // Move to the top of the draw stack so peak icons/labels always render
                        // above every other layer (including other labels), instead of following
                        // this layer's original position in the style.
                        style.removeLayer(layer)
                        style.addLayer(layer)
                    }

                    // Re-raise the visited-peaks overlay above the built-in layers, which were
                    // just moved to the top of the draw stack above.
                    (style.getLayer(VisitedPeaksLayerId))?.let { visitedLayer ->
                        style.removeLayer(visitedLayer)
                        style.addLayer(visitedLayer)
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

    LaunchedEffect(mapLibreMap, visitedPeaks, selectedPeaks) {
        val style = mapLibreMap?.style ?: return@LaunchedEffect
        val source = style.getSourceAs<GeoJsonSource>(VisitedPeaksSourceId) ?: return@LaunchedEffect
        // Selected-but-not-yet-persisted peaks (picked during an in-progress hike) get the same
        // orange tint as already-visited ones, so they're merged into the same overlay source,
        // deduplicated by id since a peak can be both already visited and re-tapped mid-hike.
        val highlightedPositions = visitedPeaks.associate { it.peakId to (it.latitude to it.longitude) } +
            selectedPeaks.associate { it.id to (it.latitude to it.longitude) }
        val features = highlightedPositions.values.map { (latitude, longitude) ->
            Feature.fromGeometry(GeoJsonPoint.fromLngLat(longitude, latitude))
        }
        source.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    LaunchedEffect(mapLibreMap, peaksVisible) {
        val style = mapLibreMap?.style ?: return@LaunchedEffect
        val visibility = if (peaksVisible) Property.VISIBLE else Property.NONE
        BuiltInPeakLayerConfigs.keys.forEach { layerId ->
            (style.getLayer(layerId) as? SymbolLayer)?.setProperties(
                PropertyFactory.visibility(visibility)
            )
        }
        (style.getLayer(VisitedPeaksLayerId) as? SymbolLayer)?.setProperties(
            PropertyFactory.visibility(visibility)
        )
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
            // The location component runs compass-driven animators fed by sensor callbacks;
            // if one of those callbacks fires after the map's style has been torn down (which
            // onDestroy does asynchronously/immediately depending on GL thread timing), it
            // crashes trying to look up a source on an invalid style. Disabling it first cancels
            // those animators/listeners synchronously so no stray callback can touch the style.
            mapLibreMap?.locationComponent?.let { locationComponent ->
                if (locationComponent.isLocationComponentActivated) {
                    locationComponent.isLocationComponentEnabled = false
                }
            }
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

        val peak = selectedPeak
        val screenPos = selectedPeakScreenPos
        if (peak != null && screenPos != null) {
            val density = LocalDensity.current
            val bubbleGapPx = with(density) { PeakBubbleMarkerGap.toPx() }
            val peakId = remember(peak) { visitedPeakId(peak.name, peak.position.latitude, peak.position.longitude) }
            val visitDates = remember(peakId, visitedPeaks) {
                visitedPeaks
                    .filter { it.peakId == peakId }
                    .map { it.visitDateEpochMillis }
                    .sortedDescending()
            }
            PeakSpeechBubble(
                peak = peak,
                visitDates = visitDates,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        layout(placeable.width, placeable.height) {
                            placeable.place(
                                x = (screenPos.x - placeable.width / 2f).toInt(),
                                y = (screenPos.y - bubbleGapPx).toInt() - placeable.height
                            )
                        }
                    }
            )
        }
    }
}

private val PeakBubbleMarkerGap = 28.dp
private val PeakBubbleTailWidth = 16.dp
private val PeakBubbleTailHeight = 8.dp
private val PeakBubbleCornerRadius = 12.dp

/**
 * A speech-bubble card anchored above a peak marker: positioned so its tail tip sits at the
 * marker's screen location (x-centered, offset up by [PeakBubbleMarkerGap] above the marker so
 * the tail doesn't overlap the icon), with the bubble body growing upward from there.
 */
@Composable
private fun PeakSpeechBubble(
    peak: SelectedPeak,
    visitDates: List<Long>,
    modifier: Modifier = Modifier
) {
    val bubbleColor = MaterialTheme.colorScheme.surface
    val dateFormat = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
    Column(
        modifier = modifier
            .drawBehind {
                val cornerRadiusPx = PeakBubbleCornerRadius.toPx()
                val tailWidthPx = PeakBubbleTailWidth.toPx()
                val tailHeightPx = PeakBubbleTailHeight.toPx()
                val bodyBottom = size.height - tailHeightPx

                val path = ComposePath().apply {
                    addRoundRect(
                        androidx.compose.ui.geometry.RoundRect(
                            left = 0f,
                            top = 0f,
                            right = size.width,
                            bottom = bodyBottom,
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerRadiusPx, cornerRadiusPx)
                        )
                    )
                    val tailCenterX = size.width / 2f
                    moveTo(tailCenterX - tailWidthPx / 2f, bodyBottom)
                    lineTo(tailCenterX, bodyBottom + tailHeightPx)
                    lineTo(tailCenterX + tailWidthPx / 2f, bodyBottom)
                    close()
                }
                drawPath(path, color = bubbleColor)
            }
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .padding(bottom = PeakBubbleTailHeight)
    ) {
        Text(
            text = peak.name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (peak.elevationMeters != null) {
            Text(
                text = stringResource(R.string.peak_detail_elevation, formatElevationMeters(peak.elevationMeters)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        if (visitDates.isNotEmpty()) {
            Text(
                text = stringResource(
                    R.string.peak_detail_visited_on,
                    visitDates.joinToString(", ") { dateFormat.format(Date(it)) }
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

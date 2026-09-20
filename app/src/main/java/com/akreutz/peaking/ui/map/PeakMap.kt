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
import org.maplibre.android.style.sources.GeoJsonOptions
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

private const val PeakCrownIconId = "peak-crown-icon"
private const val VisitedPeakCrownIconId = "visited-peak-crown-icon"
private const val VisitedPeaksSourceId = "visited-peaks-source"
private const val VisitedPeaksLayerId = "visited-peaks-layer"

// Derived source/layers that MapLibre's own overlap clustering renders from, replacing the
// built-in vector-tile layers' own icon/text (which are hidden, see PeakMap's style setup) —
// the built-in layers are kept only as the *data* MapLibre queries to rebuild this source.
private const val PeakClusterIconId = "peak-cluster-icon"
private const val TransparentIconId = "transparent-icon"
private const val PeaksSourceId = "peaks-source"
private const val SinglePeaksLayerId = "single-peaks-layer"
private const val PeakClustersLayerId = "peak-clusters-layer"

// MapLibre's own cluster property (present only on cluster features once clustering is
// enabled on a GeoJsonSource) plus the properties this file adds to unclustered peak features.
private const val ClusterProperty = "cluster"
private const val ClusterCountProperty = "point_count"
private const val PeakNameProperty = "name"
private const val PeakElevationProperty = "elevation_m"
private const val PeakLabelProperty = "label"

// Tunable clustering heuristics: how close (in screen pixels) two peaks need to render before
// MapLibre merges them into one cluster, and above which zoom clustering stops altogether.
private const val ClusterRadiusPx = 60
private const val ClusterMaxZoom = 17
// Camera zoom-in step applied per cluster tap; not computed to precisely "split" a given
// cluster, since MapTiler's vector tiles can reveal additional lower-rank peaks on zoom-in
// that weren't in the data at all before (see the cluster count caveat below).
private const val ClusterZoomStep = 2.0

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
 * Extracts a [SelectedPeak] (name/elevation/position) from a raw built-in vector-tile peak
 * feature, or null if the feature is missing a name or usable geometry. Shared by the tap
 * lookup and the clustering source rebuild, since both read the same built-in layers.
 */
private fun extractPeak(feature: Feature): SelectedPeak? {
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

    val position = (feature.geometry() as? GeoJsonPoint)?.let {
        LatLng(it.latitude(), it.longitude())
    } ?: return null

    return SelectedPeak(name = name, elevationMeters = elevationMeters, position = position)
}

// Padding applied outward from the screen edges when querying rendered peak features. A peak
// marker can visually straddle the screen edge with its anchor point just outside it — the icon
// and part of its label still fully visible on screen — since MapLibre places/renders a symbol
// whenever any part of it would be visible, not just when its anchor point is within bounds.
// queryRenderedFeatures only matches features whose anchor falls inside the query box, so a
// tight edge-to-edge box misses those without this margin (this is what caused a visited peak at
// the screen edge to never turn orange, even after every render/tile-load event had settled).
private const val ViewportQueryPaddingPx = 120f

private fun fullViewportBox(map: MapLibreMap): android.graphics.RectF =
    android.graphics.RectF(
        -ViewportQueryPaddingPx,
        -ViewportQueryPaddingPx,
        map.width + ViewportQueryPaddingPx,
        map.height + ViewportQueryPaddingPx
    )

/**
 * Queries every built-in peak feature currently rendered in the viewport and rebuilds them as
 * an unclustered GeoJSON [FeatureCollection] to feed into [PeaksSourceId]. MapLibre's own
 * clustering (enabled on that source, see [PeakMap]) then groups overlapping points itself; no
 * clustering math happens here. Peaks are deduplicated by [visitedPeakId] since vector tiles can
 * return the same point twice near tile-buffer edges.
 */
private fun buildPeaksGeoJson(map: MapLibreMap): FeatureCollection {
    val layerIds = BuiltInPeakLayerConfigs.keys.toTypedArray()
    val rendered = map.queryRenderedFeatures(fullViewportBox(map), *layerIds)

    val peaksById = LinkedHashMap<String, SelectedPeak>()
    rendered.forEach { feature ->
        val peak = extractPeak(feature) ?: return@forEach
        val id = visitedPeakId(peak.name, peak.position.latitude, peak.position.longitude)
        peaksById.putIfAbsent(id, peak)
    }

    val features = peaksById.values.map { peak ->
        Feature.fromGeometry(
            GeoJsonPoint.fromLngLat(peak.position.longitude, peak.position.latitude)
        ).apply {
            addStringProperty(PeakNameProperty, peak.name)
            if (peak.elevationMeters != null) {
                addNumberProperty(PeakElevationProperty, peak.elevationMeters)
            }
            addStringProperty(PeakLabelProperty, peakLabel(peak))
        }
    }
    return FeatureCollection.fromFeatures(features)
}

// Same text shape as the old vector-tile textField expression: name, then elevation on a
// second line only when known.
private fun peakLabel(peak: SelectedPeak): String =
    if (peak.elevationMeters != null) {
        "${peak.name}\n${formatElevationMeters(peak.elevationMeters)}"
    } else {
        peak.name
    }

/**
 * Ids ([visitedPeakId]) of peaks currently rendered on their own in [SinglePeaksLayerId], i.e.
 * not currently absorbed into a cluster. Used to suppress the visited-peak overlay for peaks
 * that are clustered right now, since drawing their orange marker separately would float
 * disconnected from the neutral cluster circle.
 */
private fun querySinglePeakIds(map: MapLibreMap): Set<String> {
    val rendered = map.queryRenderedFeatures(fullViewportBox(map), SinglePeaksLayerId)
    return rendered.mapNotNullTo(mutableSetOf()) { feature ->
        val name = feature.getStringProperty(PeakNameProperty) ?: return@mapNotNullTo null
        val point = feature.geometry() as? GeoJsonPoint ?: return@mapNotNullTo null
        visitedPeakId(name, point.latitude(), point.longitude())
    }
}

/**
 * The result of a map tap that landed on a peak marker: either a single (unclustered) peak, or
 * a cluster of overlapping peaks at [Cluster.centroid].
 */
private sealed class PeakTapResult {
    data class Single(val peak: SelectedPeak) : PeakTapResult()
    data class Cluster(val centroid: LatLng) : PeakTapResult()
}

/**
 * Queries the derived single-peak/cluster layers at the tapped screen point (with a small
 * touch-target padding, since marker bitmaps are smaller than a comfortable tap area) and
 * returns whichever feature is on top, distinguishing a single peak from a cluster via
 * MapLibre's own "cluster" property (present only on cluster features).
 */
private fun queryTappedPeakFeature(map: MapLibreMap, screenPoint: android.graphics.PointF): PeakTapResult? {
    val touchRadiusPx = 24f
    val box = android.graphics.RectF(
        screenPoint.x - touchRadiusPx,
        screenPoint.y - touchRadiusPx,
        screenPoint.x + touchRadiusPx,
        screenPoint.y + touchRadiusPx
    )
    val features = map.queryRenderedFeatures(box, SinglePeaksLayerId, PeakClustersLayerId)
    val feature = features.firstOrNull() ?: return null
    val position = (feature.geometry() as? GeoJsonPoint)?.let {
        LatLng(it.latitude(), it.longitude())
    } ?: return null

    val isCluster = feature.hasProperty(ClusterProperty)
    return if (isCluster) {
        PeakTapResult.Cluster(centroid = position)
    } else {
        val name = feature.getStringProperty(PeakNameProperty) ?: return null
        val elevationMeters = if (feature.hasNonNullValueForProperty(PeakElevationProperty)) {
            feature.getNumberProperty(PeakElevationProperty).toDouble()
        } else {
            null
        }
        PeakTapResult.Single(SelectedPeak(name = name, elevationMeters = elevationMeters, position = position))
    }
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
 * A fully transparent 1x1 bitmap, used as the built-in vector-tile peak layers' icon so they
 * still go through normal symbol placement (and stay findable by queryRenderedFeatures) without
 * painting anything visible — the derived [SinglePeaksLayerId]/[PeakClustersLayerId] layers do
 * the actual visible rendering. Setting icon/text opacity to 0 instead does *not* work: MapLibre
 * excludes opacity-0 symbols from queryRenderedFeatures, leaving nothing for those derived
 * layers to be built from.
 */
private fun createTransparentIcon(): Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

/**
 * Draws a plain filled circle with a halo stroke, in the same halo-then-fill Canvas/Paint style
 * as [createCrownIcon], for the neutral cluster marker. The peak count itself isn't baked in
 * here since it varies per cluster — it's rendered on top by the cluster layer's own text field,
 * reading MapLibre's "point_count" cluster property directly (the standard MapLibre pattern).
 */
private fun createClusterIcon(
    fillColor: Int,
    haloColor: Int,
    haloWidthPx: Float,
    sizePx: Int
): Bitmap {
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val center = sizePx / 2f
    val radius = center - haloWidthPx / 2f

    val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = haloColor
        style = Paint.Style.STROKE
        strokeWidth = haloWidthPx
    }
    canvas.drawCircle(center, center, radius, haloPaint)

    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = fillColor
        style = Paint.Style.FILL
    }
    canvas.drawCircle(center, center, radius, fillPaint)

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
    // Ids of peaks currently rendered as their own single (unclustered) marker, refreshed
    // whenever the clustering source is rebuilt. Used to suppress a visited peak's individual
    // orange overlay while it's absorbed into a cluster, since drawing it separately there would
    // float disconnected from the neutral cluster marker.
    var visiblePeakIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    val currentSelectionMode by rememberUpdatedState(selectionMode)
    val currentOnPeakToggled by rememberUpdatedState(onPeakToggled)

    val mapView = remember {
        MapLibre.getInstance(context)
        // TextureView instead of the default GLSurfaceView: a SurfaceView renders into its own
        // window layer outside normal view z-ordering, so switching away from this tab could
        // leave a stale map frame briefly compositing on top of the next tab's content.
        // TextureView is an ordinary View and composites in-order, avoiding that overlay glitch.
        MapView(context, MapLibreMapOptions.createFromAttributes(context, null).textureMode(true)).apply {
            // Captured directly (rather than reading the mapLibreMap Compose state var) so the
            // listeners below have a map reference the instant getMapAsync fires, independent of
            // when that state var's assignment is actually observed.
            var currentMap: MapLibreMap? = null
            val retryHandler = android.os.Handler(android.os.Looper.getMainLooper())

            // Rebuilds the peaks source and refreshes visiblePeakIds from whatever's currently
            // rendered. Vector tiles keep streaming in progressively for a couple of seconds
            // after a pan/zoom settles — addOnCameraIdleListener only fires once, when the
            // *camera* itself stops moving, and neither that nor addOnDidBecomeIdleListener
            // (map fully idle, including tiles) alone reliably lands after every tile a peak's
            // marker depends on has actually loaded and rendered. So this is called from
            // multiple triggers below: immediately on camera-idle/map-idle, and again on a short
            // bounded retry schedule, to robustly catch whichever tile arrives last.
            fun refreshPeaks(map: MapLibreMap) {
                val style = map.style ?: return
                val source = style.getSourceAs<GeoJsonSource>(PeaksSourceId) ?: return
                source.setGeoJson(buildPeaksGeoJson(map))
                // visiblePeakIds reflects whatever was already rendered before this call, not
                // this update (setGeoJson applies asynchronously) — it's refreshed by the same
                // triggers on their next firing, which is what the retry schedule below is for.
                visiblePeakIds = querySinglePeakIds(map)
            }

            // Re-runs refreshPeaks a few more times over ~2s after the map settles, to catch
            // peaks whose tile arrives after the first post-settle query already ran. A single
            // reused Runnable per delay slot, cancelled and re-posted on every call, so an
            // in-flight retry from a previous settle doesn't also fire during/after a new one.
            val retryDelaysMs = listOf(300L, 800L, 1500L, 2500L)
            val retryRunnables = retryDelaysMs.map {
                Runnable { currentMap?.let { map -> refreshPeaks(map) } }
            }
            fun scheduleRetries() {
                retryRunnables.forEach { retryHandler.removeCallbacks(it) }
                retryDelaysMs.zip(retryRunnables).forEach { (delay, runnable) ->
                    retryHandler.postDelayed(runnable, delay)
                }
            }

            addOnDidBecomeIdleListener {
                val map = currentMap ?: return@addOnDidBecomeIdleListener
                refreshPeaks(map)
                scheduleRetries()
            }
            getMapAsync { map ->
                currentMap = map
                map.cameraPosition = CameraPosition.Builder()
                    .target(DefaultMapCenter)
                    .zoom(DefaultZoom)
                    .build()
                map.addOnMapClickListener { latLng ->
                    val screenPoint = map.projection.toScreenLocation(latLng)
                    when (val result = queryTappedPeakFeature(map, screenPoint)) {
                        is PeakTapResult.Cluster -> {
                            // Zoom toward the cluster's centroid regardless of selection mode —
                            // a cluster represents an unknown mix of peaks, so it can't be
                            // toggled as a single hike selection; zooming in lets individual
                            // peaks resolve out of it instead.
                            map.easeCamera(
                                CameraUpdateFactory.newLatLngZoom(
                                    result.centroid,
                                    map.cameraPosition.zoom + ClusterZoomStep
                                )
                            )
                            true
                        }
                        is PeakTapResult.Single -> {
                            val peak = result.peak
                            if (currentSelectionMode) {
                                currentOnPeakToggled(
                                    HikeSelectedPeak(
                                        id = visitedPeakId(peak.name, peak.position.latitude, peak.position.longitude),
                                        name = peak.name,
                                        latitude = peak.position.latitude,
                                        longitude = peak.position.longitude,
                                        elevationMeters = peak.elevationMeters
                                    )
                                )
                            } else {
                                selectedPeak = peak
                                val p = map.projection.toScreenLocation(peak.position)
                                selectedPeakScreenPos = Offset(p.x, p.y)
                            }
                            true
                        }
                        null -> {
                            if (!currentSelectionMode) {
                                selectedPeak = null
                                selectedPeakScreenPos = null
                            }
                            false
                        }
                    }
                }
                map.addOnCameraMoveListener {
                    val peak = selectedPeak ?: return@addOnCameraMoveListener
                    val p = map.projection.toScreenLocation(peak.position)
                    selectedPeakScreenPos = Offset(p.x, p.y)
                }
                map.addOnCameraIdleListener {
                    refreshPeaks(map)
                    scheduleRetries()
                }
                map.setStyle(maptilerStyleUrl(BuildConfig.MAPTILER_API_KEY)) { style ->
                    style.addImage(TransparentIconId, createTransparentIcon())
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
                    style.addImage(
                        PeakClusterIconId,
                        createClusterIcon(
                            fillColor = Color.rgb(66, 66, 66),
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
                        // Relax the style's default rank/minzoom filtering so every named
                        // peak/volcano point in loaded tile data is queryable at any zoom — this
                        // layer's own icon/text painting is then suppressed below, since it's
                        // used purely as a data source for the derived layers to query.
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
                        // A transparent icon rather than opacity 0: MapLibre excludes opacity-0
                        // symbols from queryRenderedFeatures entirely, which would leave nothing
                        // for the derived layers below to be built from. This still needs to go
                        // through normal symbol placement/hit-testing, just invisibly. The
                        // style's own text-field is cleared outright (rather than hidden via
                        // opacity) since there's no text data we need to keep queryable here.
                        layer.setProperties(
                            PropertyFactory.iconImage(TransparentIconId),
                            PropertyFactory.iconAllowOverlap(true),
                            PropertyFactory.iconIgnorePlacement(true),
                            PropertyFactory.textField("")
                        )
                    }

                    style.addSource(
                        GeoJsonSource(
                            PeaksSourceId,
                            GeoJsonOptions()
                                .withCluster(true)
                                .withClusterRadius(ClusterRadiusPx)
                                .withClusterMaxZoom(ClusterMaxZoom)
                        )
                    )
                    style.addLayer(
                        SymbolLayer(SinglePeaksLayerId, PeaksSourceId).withFilter(
                            Expression.not(Expression.has(ClusterProperty))
                        ).withProperties(
                            PropertyFactory.iconImage(PeakCrownIconId),
                            PropertyFactory.iconSize(0.6f),
                            // Overlap is already resolved by clustering upstream, so every
                            // unclustered peak here is meant to render on its own.
                            PropertyFactory.iconAllowOverlap(true),
                            PropertyFactory.iconIgnorePlacement(true),
                            PropertyFactory.textField(Expression.get(PeakLabelProperty)),
                            PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
                            PropertyFactory.textOffset(arrayOf(0f, 0.6f)),
                            PropertyFactory.textSize(13f),
                            PropertyFactory.textColor(Color.rgb(66, 66, 66)),
                            PropertyFactory.textHaloWidth(1.2f),
                            PropertyFactory.textAllowOverlap(true),
                            PropertyFactory.textIgnorePlacement(true),
                            PropertyFactory.textOptional(true)
                        )
                    )
                    style.addLayer(
                        SymbolLayer(PeakClustersLayerId, PeaksSourceId).withFilter(
                            Expression.has(ClusterProperty)
                        ).withProperties(
                            PropertyFactory.iconImage(PeakClusterIconId),
                            // Larger than a single-peak crown (iconSize 0.6) so a cluster reads
                            // clearly as its own, more prominent marker on the map.
                            PropertyFactory.iconSize(1.0f),
                            PropertyFactory.iconAllowOverlap(true),
                            PropertyFactory.iconIgnorePlacement(true),
                            // "N+" rather than an exact count: MapTiler's vector tiles omit
                            // lower-rank peaks at lower zoom, so more peaks can appear on
                            // zoom-in than point_count currently reports — it's a lower bound.
                            PropertyFactory.textField(
                                Expression.concat(
                                    Expression.toString(Expression.get(ClusterCountProperty)),
                                    Expression.literal("+")
                                )
                            ),
                            PropertyFactory.textSize(15f),
                            PropertyFactory.textColor(Color.WHITE),
                            PropertyFactory.textAllowOverlap(true),
                            PropertyFactory.textIgnorePlacement(true)
                        )
                    )

                    // Re-raise the derived peak layers and the visited-peaks overlay above the
                    // built-in layers, in draw order: (invisible built-in layers) < single peaks
                    // < clusters < visited overlay on top.
                    listOf(SinglePeaksLayerId, PeakClustersLayerId, VisitedPeaksLayerId).forEach { layerId ->
                        style.getLayer(layerId)?.let { layer ->
                            style.removeLayer(layer)
                            style.addLayer(layer)
                        }
                    }

                    // addOnCameraIdleListener/addOnDidBecomeIdleListener (registered above) don't
                    // fire for this initial load, so seed the clustering source once up front and
                    // kick off the retry schedule to catch tiles that finish loading afterwards.
                    refreshPeaks(map)
                    scheduleRetries()

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

    LaunchedEffect(mapLibreMap, visitedPeaks, selectedPeaks, visiblePeakIds) {
        val style = mapLibreMap?.style ?: return@LaunchedEffect
        val source = style.getSourceAs<GeoJsonSource>(VisitedPeaksSourceId) ?: return@LaunchedEffect
        // Selected-but-not-yet-persisted peaks (picked during an in-progress hike) get the same
        // orange tint as already-visited ones, so they're merged into the same overlay source,
        // deduplicated by id since a peak can be both already visited and re-tapped mid-hike.
        // Peaks currently absorbed into a cluster are excluded: clusters ignore visited state
        // entirely, and drawing this overlay's marker separately for a clustered peak would
        // float disconnected from the neutral cluster circle at its centroid.
        val highlightedPositions = (
            visitedPeaks.associate { it.peakId to (it.latitude to it.longitude) } +
                selectedPeaks.associate { it.id to (it.latitude to it.longitude) }
            ).filterKeys { it in visiblePeakIds }
        val features = highlightedPositions.values.map { (latitude, longitude) ->
            Feature.fromGeometry(GeoJsonPoint.fromLngLat(longitude, latitude))
        }
        source.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    LaunchedEffect(mapLibreMap, peaksVisible) {
        val style = mapLibreMap?.style ?: return@LaunchedEffect
        val visibility = if (peaksVisible) Property.VISIBLE else Property.NONE
        listOf(SinglePeaksLayerId, PeakClustersLayerId, VisitedPeaksLayerId).forEach { layerId ->
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

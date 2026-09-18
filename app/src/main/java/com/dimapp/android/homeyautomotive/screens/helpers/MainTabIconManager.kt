package com.dimapp.android.homeyautomotive.screens.helpers

import android.graphics.Color
import android.graphics.drawable.Drawable
import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.model.CarIcon
import com.dimapp.android.homeyautomotive.R
import com.dimapp.android.homeyautomotive.core.DependencyManager
import com.dimapp.android.homeyautomotive.repository.models.HomeyDevice
import com.dimapp.android.homeyautomotive.utils.IconFetcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Manager responsible for fetching, caching, and providing [CarIcon] objects for devices.
 * 
 * Handles asynchronous downloads from Homey and implements a debounced invalidation 
 * callback to prevent overloading the Car App Host IPC channel.
 *
 * @param carContext The Car App context.
 * @param scope Coroutine scope for icon downloading and debouncing.
 * @param onInvalidateNeeded Callback invoked when a UI refresh is required (debounced).
 */
class MainTabIconManager(
    private val carContext: CarContext,
    private val scope: CoroutineScope,
    private val onInvalidateNeeded: () -> Unit
) {
    companion object {
        /** Amber/Yellow status badge color (255, 160, 0) indicating an active device or overview alert. */
        val BADGE_ALERT_YELLOW = Color.rgb(255, 160, 0)
    }

    private val TAG = "MainTabIconManager"

    /** Local cache for styled CarIcons keyed by "${device.id}_${device.isActive}". */
    private val iconCache = mutableMapOf<String, CarIcon>()

    /** Raw downloaded drawables from Homey keyed by deviceId, allowing instantaneous badge updates. */
    private val rawDrawableCache = mutableMapOf<String, Drawable>()

    /** Set of device IDs currently being downloaded to avoid redundant requests. */
    private val fetchingIcons = mutableSetOf<String>()

    /** Job for the debounced invalidate logic. */
    private var invalidateDebounceJob: Job? = null

    /** Placeholders for fallback icons (active and inactive). */
    private var lightOnPlaceholder: CarIcon? = null
    private var lightOffPlaceholder: CarIcon? = null
    private var lockOpenPlaceholder: CarIcon? = null
    private var lockClosedPlaceholder: CarIcon? = null

    /** Cached icons for the Home Overview section (secure and alert). */
    private var homeSecureIcon: CarIcon? = null
    private var homeAlertIcon: CarIcon? = null

    init {
        // Pre-warm placeholders to ensure they are ready for the first render
        _getPlaceholder(isLight = true, isActive = false)
        _getPlaceholder(isLight = true, isActive = true)
        _getPlaceholder(isLight = false, isActive = false)
        _getPlaceholder(isLight = false, isActive = true)
        Log.d(TAG, "[MainTabIconManager:init] IconManager initialized with pre-warmed placeholders.")
    }

    /**
     * Returns the [CarIcon] for the given device.
     * 
     * If the styled icon is in cache, returns it.
     * If the raw drawable is cached, creates and caches the badged/unbadged icon immediately.
     * If not, returns a placeholder and starts an asynchronous download.
     * 
     * @public
     * @param device The device to get the icon for.
     * @param showBadge Whether to display the yellow status dot when active (true for Devices tab, false for Home tab).
     * @return The cached icon or a placeholder with the appropriate active badge.
     * @example
     * val icon = iconManager.getIcon(device, showBadge = true)
     */
    fun getIcon(device: HomeyDevice, showBadge: Boolean = true): CarIcon {
        val effectiveActive = device.isActive && showBadge
        val cacheKey = "${device.id}_$effectiveActive"
        val cached = iconCache[cacheKey]
        if (cached != null) return cached

        val rawDrawable = rawDrawableCache[device.id]
        if (rawDrawable != null) {
            val badgeColor = if (effectiveActive) BADGE_ALERT_YELLOW else null
            val styled = IconFetcher.getStyledIconFromDrawable(rawDrawable, badgeColor)
            iconCache[cacheKey] = styled
            return styled
        }

        // Start async download if needed
        _fetchIconAsync(device)
        return _getPlaceholder(device.isLight, effectiveActive)
    }

    /**
     * Returns the styled [CarIcon] for the Home Overview section, matching Homey's monochrome design.
     * Uses the dedicated solid house icon ([R.drawable.ic_overview_home]).
     * If [hasAlert] is true, displays a status badge dot in the top-right corner.
     * If [hasAlert] is false, displays a clean icon without badge.
     *
     * Public method.
     *
     * @public
     * @param hasAlert True if there are open barriers or lights left on.
     * @return Cached [CarIcon] with or without the alert badge.
     * @example
     * val icon = iconManager.getHomeOverviewIcon(hasAlert = false)
     */
    fun getHomeOverviewIcon(hasAlert: Boolean): CarIcon {
        return if (hasAlert) {
            if (homeAlertIcon == null) {
                homeAlertIcon = IconFetcher.getStyledIconFromResource(
                    context = carContext,
                    resId = R.drawable.ic_overview_home,
                    badgeColor = BADGE_ALERT_YELLOW
                )
            }
            homeAlertIcon!!
        } else {
            if (homeSecureIcon == null) {
                homeSecureIcon = IconFetcher.getStyledIconFromResource(
                    context = carContext,
                    resId = R.drawable.ic_overview_home,
                    badgeColor = null
                )
            }
            homeSecureIcon!!
        }
    }

    /**
     * Clears all cached icons and pending fetch flags.
     *
     * Public method.
     *
     * @public
     * @example iconManager.clearCache()
     */
    fun clearCache() {
        iconCache.clear()
        rawDrawableCache.clear()
        fetchingIcons.clear()
        homeSecureIcon = null
        homeAlertIcon = null
        lightOnPlaceholder = null
        lightOffPlaceholder = null
        lockOpenPlaceholder = null
        lockClosedPlaceholder = null
        invalidateDebounceJob?.cancel()
        invalidateDebounceJob = null
    }

    /**
     * Cancels any pending debounced invalidate jobs.
     *
     * Public method.
     *
     * @public
     * @example iconManager.cancelPendingJobs()
     */
    fun cancelPendingJobs() {
        invalidateDebounceJob?.cancel()
        invalidateDebounceJob = null
    }

    // ── Private Methods ───────────────────────────────────────────────────────

    /**
     * Downloads a raw icon drawable for a device, caches it, and generates the current CarIcon.
     *
     * Private method.
     *
     * @private
     * @param device The device whose icon is to be fetched.
     */
    private fun _fetchIconAsync(device: HomeyDevice) {
        val url = device.iconUrl ?: return
        if (fetchingIcons.contains(device.id)) return

        fetchingIcons.add(device.id)
        scope.launch {
            try {
                Log.d(TAG, "[MainTabIconManager:_fetchIconAsync] Downloading icon for ${device.name}: $url")
                val storage = DependencyManager.getTokenStorage(carContext)
                val rawDrawable = IconFetcher.fetchDrawable(carContext, url, storage)
                if (rawDrawable != null) {
                    rawDrawableCache[device.id] = rawDrawable
                    val badgeColor = if (device.isActive) BADGE_ALERT_YELLOW else null
                    val styledIcon = IconFetcher.getStyledIconFromDrawable(rawDrawable, badgeColor)
                    iconCache["${device.id}_${device.isActive}"] = styledIcon
                    _scheduleDebounceInvalidate()
                }
            } catch (e: Exception) {
                Log.e(TAG, "[MainTabIconManager:_fetchIconAsync] Icon download error for ${device.name}: ${e.message}", e)
            } finally {
                fetchingIcons.remove(device.id)
            }
        }
    }

    /**
     * Schedules a debounced invalidation call (400ms delay).
     * Prevents IPC flooding when multiple icons download simultaneously.
     *
     * Private method.
     *
     * @private
     */
    private fun _scheduleDebounceInvalidate() {
        invalidateDebounceJob?.cancel()
        invalidateDebounceJob = scope.launch {
            delay(400L)
            Log.d(TAG, "[MainTabIconManager:_scheduleDebounceInvalidate] Dispatching debounced invalidate due to icon downloads.")
            onInvalidateNeeded()
        }
    }

    /**
     * Returns a styled placeholder icon for lights or locks based on active state.
     *
     * Private method.
     *
     * @private
     * @param isLight True for lighting devices, false for locks/barriers.
     * @param isActive True if the device is turned on or open.
     * @return Cached [CarIcon] placeholder.
     */
    private fun _getPlaceholder(isLight: Boolean, isActive: Boolean): CarIcon {
        val badgeColor = if (isActive) BADGE_ALERT_YELLOW else null
        return if (isLight) {
            if (isActive) {
                if (lightOnPlaceholder == null) {
                    lightOnPlaceholder = IconFetcher.getStyledIconFromResource(carContext, R.drawable.ic_placeholder_light, badgeColor)
                }
                lightOnPlaceholder!!
            } else {
                if (lightOffPlaceholder == null) {
                    lightOffPlaceholder = IconFetcher.getStyledIconFromResource(carContext, R.drawable.ic_placeholder_light, null)
                }
                lightOffPlaceholder!!
            }
        } else {
            if (isActive) {
                if (lockOpenPlaceholder == null) {
                    lockOpenPlaceholder = IconFetcher.getStyledIconFromResource(carContext, R.drawable.ic_placeholder_lock, badgeColor)
                }
                lockOpenPlaceholder!!
            } else {
                if (lockClosedPlaceholder == null) {
                    lockClosedPlaceholder = IconFetcher.getStyledIconFromResource(carContext, R.drawable.ic_placeholder_lock, null)
                }
                lockClosedPlaceholder!!
            }
        }
    }
}

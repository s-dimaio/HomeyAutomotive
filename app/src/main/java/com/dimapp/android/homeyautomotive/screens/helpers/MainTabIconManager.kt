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
        /** Official Homey alarm blue badge color (#0082FA) indicating an active device or overview alert. */
        val BADGE_ALERT_BLUE = Color.rgb(0, 130, 250)
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
     * @param showBadge Whether to display the status badge (defaults to true).
     * @return The cached icon or a placeholder with the appropriate active badge.
     * @example
     * val icon = iconManager.getIcon(device, showBadge = true)
     */
    fun getIcon(device: HomeyDevice, showBadge: Boolean = true): CarIcon {
        val badgeType = if (showBadge) _getBadgeType(device) else IconFetcher.BadgeType.NONE
        val cacheKey = "${device.id}_${device.isActive}_$showBadge"
        val cached = iconCache[cacheKey]
        if (cached != null) return cached

        val rawDrawable = rawDrawableCache[device.id]
        if (rawDrawable != null) {
            val styled = IconFetcher.getStyledIconFromDrawable(carContext, rawDrawable, badgeType)
            iconCache[cacheKey] = styled
            return styled
        }

        // Start async download if needed
        _fetchIconAsync(device)
        return _getPlaceholder(device.isLight, device.isActive)
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
                    badgeType = IconFetcher.BadgeType.DOT_ALERT,
                    dotColor = BADGE_ALERT_BLUE
                )
            }
            homeAlertIcon!!
        } else {
            if (homeSecureIcon == null) {
                homeSecureIcon = IconFetcher.getStyledIconFromResource(
                    context = carContext,
                    resId = R.drawable.ic_overview_home,
                    badgeType = IconFetcher.BadgeType.NONE
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
     * Resolves the appropriate badge type for a given Homey device based on its category and state.
     *
     * Private method.
     *
     * @private
     * @param device The device to evaluate.
     * @return [IconFetcher.BadgeType] corresponding to the device category and state.
     * @example val badgeType = _getBadgeType(device)
     */
    private fun _getBadgeType(device: HomeyDevice): IconFetcher.BadgeType {
        return if (device.isLight) {
            if (device.isActive) IconFetcher.BadgeType.LIGHT_ON else IconFetcher.BadgeType.LIGHT_OFF
        } else {
            // For doors/locks: isActive is true when open/unlocked, false when closed/locked
            if (device.isActive) IconFetcher.BadgeType.LOCK_UNLOCKED else IconFetcher.BadgeType.LOCK_LOCKED
        }
    }

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
                    val badgeType = _getBadgeType(device)
                    val styledIcon = IconFetcher.getStyledIconFromDrawable(carContext, rawDrawable, badgeType)
                    iconCache["${device.id}_${device.isActive}_true"] = styledIcon
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
     * Returns a styled placeholder icon for lights or locks based on active state with appropriate action badge.
     *
     * Private method.
     *
     * @private
     * @param isLight True for lighting devices, false for locks/barriers.
     * @param isActive True if the device is turned on or open.
     * @return Cached [CarIcon] placeholder.
     */
    private fun _getPlaceholder(isLight: Boolean, isActive: Boolean): CarIcon {
        return if (isLight) {
            if (isActive) {
                if (lightOnPlaceholder == null) {
                    lightOnPlaceholder = IconFetcher.getStyledIconFromResource(
                        context = carContext,
                        resId = R.drawable.ic_placeholder_light,
                        badgeType = IconFetcher.BadgeType.LIGHT_ON
                    )
                }
                lightOnPlaceholder!!
            } else {
                if (lightOffPlaceholder == null) {
                    lightOffPlaceholder = IconFetcher.getStyledIconFromResource(
                        context = carContext,
                        resId = R.drawable.ic_placeholder_light,
                        badgeType = IconFetcher.BadgeType.LIGHT_OFF
                    )
                }
                lightOffPlaceholder!!
            }
        } else {
            if (isActive) {
                // Open / Unlocked
                if (lockOpenPlaceholder == null) {
                    lockOpenPlaceholder = IconFetcher.getStyledIconFromResource(
                        context = carContext,
                        resId = R.drawable.ic_placeholder_lock,
                        badgeType = IconFetcher.BadgeType.LOCK_UNLOCKED
                    )
                }
                lockOpenPlaceholder!!
            } else {
                // Closed / Locked
                if (lockClosedPlaceholder == null) {
                    lockClosedPlaceholder = IconFetcher.getStyledIconFromResource(
                        context = carContext,
                        resId = R.drawable.ic_placeholder_lock,
                        badgeType = IconFetcher.BadgeType.LOCK_LOCKED
                    )
                }
                lockClosedPlaceholder!!
            }
        }
    }
}

package com.dimapp.android.homeyautomotive.screens.helpers

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
    private val TAG = "MainTabIconManager"

    /** Local cache for custom icons (e.g. SVG converted to bitmap). */
    private val iconCache = mutableMapOf<String, CarIcon>()

    /** Set of device IDs currently being downloaded to avoid redundant requests. */
    private val fetchingIcons = mutableSetOf<String>()

    /** Job for the debounced invalidate logic. */
    private var invalidateDebounceJob: Job? = null

    /** Lazy placeholders for fallback icons. */
    private var lightPlaceholder: CarIcon? = null
    private var lockPlaceholder: CarIcon? = null

    init {
        // Pre-warm placeholders to ensure they are ready for the first render
        _getPlaceholder(isLight = true)
        _getPlaceholder(isLight = false)
        Log.d(TAG, "IconManager initialized with pre-warmed placeholders.")
    }

    /**
     * Returns the [CarIcon] for the given device.
     * 
     * If the icon is in cache, returns it. 
     * If not, returns a placeholder and starts an asynchronous download.
     * 
     * @param device The device to get the icon for.
     * @return The cached icon or a placeholder.
     */
    fun getIcon(device: HomeyDevice): CarIcon {
        val cached = iconCache[device.id]
        if (cached != null) return cached

        // Start async download if needed
        _fetchIconAsync(device)
        return _getPlaceholder(device.isLight)
    }

    /**
     * Clears all cached icons and pending fetch flags.
     */
    fun clearCache() {
        iconCache.clear()
        fetchingIcons.clear()
        invalidateDebounceJob?.cancel()
        invalidateDebounceJob = null
    }

    /**
     * Cancels any pending debounced invalidate jobs.
     */
    fun cancelPendingJobs() {
        invalidateDebounceJob?.cancel()
        invalidateDebounceJob = null
    }

    // ── Private Methods ───────────────────────────────────────────────────────

    /**
     * Downloads an icon for a device and updates the cache.
     */
    private fun _fetchIconAsync(device: HomeyDevice) {
        val url = device.iconUrl ?: return
        if (fetchingIcons.contains(device.id)) return

        fetchingIcons.add(device.id)
        scope.launch {
            try {
                Log.d(TAG, "Downloading icon for ${device.name}: $url")
                val storage = DependencyManager.getTokenStorage(carContext)
                val icon = IconFetcher.fetchCarIcon(carContext, url, storage)
                if (icon != null) {
                    iconCache[device.id] = icon
                    _scheduleDebounceInvalidate()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Icon download error for ${device.name}: ${e.message}")
            } finally {
                fetchingIcons.remove(device.id)
            }
        }
    }

    /**
     * Schedules a debounced invalidation call (400ms delay).
     * Prevents IPC flooding when multiple icons download simultaneously.
     */
    private fun _scheduleDebounceInvalidate() {
        invalidateDebounceJob?.cancel()
        invalidateDebounceJob = scope.launch {
            delay(400L)
            Log.d(TAG, "Dispatching debounced invalidate due to icon downloads.")
            onInvalidateNeeded()
        }
    }

    /**
     * Returns a styled placeholder icon for lights or locks.
     */
    private fun _getPlaceholder(isLight: Boolean): CarIcon {
        return if (isLight) {
            if (lightPlaceholder == null) {
                lightPlaceholder = IconFetcher.getStyledIconFromResource(carContext, R.drawable.ic_placeholder_light)
            }
            lightPlaceholder!!
        } else {
            if (lockPlaceholder == null) {
                lockPlaceholder = IconFetcher.getStyledIconFromResource(carContext, R.drawable.ic_placeholder_lock)
            }
            lockPlaceholder!!
        }
    }
}

package com.dimapp.android.homeyautomotive.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.car.app.model.CarIcon
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import coil.ImageLoader
import coil.decode.SvgDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.annotation.ExperimentalCoilApi
import com.dimapp.android.homeyautomotive.storage.TokenStorage
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Utility to fetch remote icons from Homey securely and convert them into AAOS [CarIcon].
 *
 * Public object.
 * A shared [TokenStorage] instance must be supplied by callers (via the [DependencyManager])
 * to avoid creating redundant storage instances. The token is read fresh from storage on every
 * HTTP request, so token rotation is handled transparently.
 */
object IconFetcher {
    private var imageLoader: ImageLoader? = null

    /**
     * Lazy-initialises and returns the Coil [ImageLoader] with SVG capabilities,
     * persistent disk caching, and injected credentials.
     *
     * The [storage] parameter is provided by the caller (from [DependencyManager]) and stored
     * by reference in the interceptor closure, so the token is always read fresh.
     *
     * Private method.
     *
     * @private
     * @param context App context.
     * @param storage Shared [TokenStorage] instance used to inject the Bearer token.
     * @return Configured [ImageLoader] instance.
     * @example val loader = _getImageLoader(context, storage)
     */
    private fun _getImageLoader(context: Context, storage: TokenStorage): ImageLoader {
        return imageLoader ?: synchronized(this) {
            imageLoader ?: run {
                val okHttpClient = OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .addInterceptor { chain ->
                        val request = chain.request()
                        val requestBuilder = request.newBuilder()
                        val token = storage.getHubSessionToken()

                        // Add token only for Athom Connect URLs (local/remote API)
                        if (!token.isNullOrBlank() && request.url.host.endsWith("connect.athom.com")) {
                            requestBuilder.addHeader("Authorization", "Bearer $token")
                        }
                        chain.proceed(requestBuilder.build())
                    }.build()

                val loader = ImageLoader.Builder(context.applicationContext)
                    .components {
                        add(SvgDecoder.Factory()) // Enables parsing of SVG files (very common in Homey)
                    }
                    .memoryCache {
                        MemoryCache.Builder(context.applicationContext)
                            .maxSizePercent(0.25)
                            .build()
                    }
                    .diskCache {
                        DiskCache.Builder()
                            .directory(context.applicationContext.cacheDir.resolve("image_cache"))
                            .maxSizeBytes(10 * 1024 * 1024) // 10 MB limit (reduced for vector optimization)
                            .build()
                    }
                    .okHttpClient(okHttpClient)
                    .build()

                imageLoader = loader
                loader
            }
        }
    }

    /**
     * Clears both disk and memory caches. Useful for manual user refresh.
     *
     * Public method.
     *
     * @public
     * @param context App context.
     * @example IconFetcher.clearCache(context, storage)
     */
    @OptIn(ExperimentalCoilApi::class)
    suspend fun clearCache(context: Context, storage: TokenStorage) {
        withContext(Dispatchers.IO) {
            val loader = _getImageLoader(context, storage)
            loader.diskCache?.clear()
            loader.memoryCache?.clear()
        }
    }

    /**
     * Downloads an icon from Homey or CDN, scales it, and wraps it into a [CarIcon].
     *
     * Public method.
     *
     * @public
     * @param context App context.
     * @param urlOrPath The icon URL (absolute) or relative path (from `iconObj.url`).
     * @param storage Shared [TokenStorage] instance (from [DependencyManager]) used to
     *   inject the Bearer token and resolve the active Homey hub ID for URL reconstruction.
     * @return [CarIcon] if successful, `null` if empty or download fails.
     * @example
     * ```
     * val icon = IconFetcher.fetchCarIcon(carContext, device.iconUrl, DependencyManager.getTokenStorage(carContext))
     * ```
     */
    suspend fun fetchCarIcon(context: Context, urlOrPath: String?, storage: TokenStorage): CarIcon? {
        if (urlOrPath.isNullOrBlank()) return null

        val homeyId = storage.getSelectedHomeyId() ?: return null

        // Reconstruct absolute URL if needed
        val fullUrl = if (urlOrPath.startsWith("http")) {
            urlOrPath
        } else {
            "https://$homeyId.connect.athom.com$urlOrPath"
        }

        val request = ImageRequest.Builder(context)
            .data(fullUrl)
            .size(256, 256) // Increased resolution for IMAGE_TYPE_LARGE, keeping RAM usage at ~260 KB
            .allowHardware(false) // Must be false to extract software Bitmap for IPC transfer
            .build()

        val loader = _getImageLoader(context, storage)
        val result = loader.execute(request)

        if (result is SuccessResult) {
            val bitmap = _drawIconOnCard(result.drawable)
            val iconCompat = IconCompat.createWithBitmap(bitmap)
            return CarIcon.Builder(iconCompat).build()
        }

        return null
    }

    /**
     * Creates a styled CarIcon from a local resource ID, applying the same white card look.
     *
     * Public method.
     *
     * @public
     * @param context App context.
     * @param resId The drawable resource ID.
     * @return A styled [CarIcon].
     * @example val icon = IconFetcher.getStyledIconFromResource(context, R.drawable.my_icon)
     */
    fun getStyledIconFromResource(context: Context, resId: Int): CarIcon {
        val drawable = ContextCompat.getDrawable(context, resId)
            ?: throw IllegalArgumentException("Resource ID $resId not found")
        
        // Tints the system vector icon black so it remains visible on a white background
        androidx.core.graphics.drawable.DrawableCompat.setTint(
            androidx.core.graphics.drawable.DrawableCompat.wrap(drawable).mutate(),
            android.graphics.Color.BLACK
        )
        
        val bitmap = _drawIconOnCard(drawable)
        val iconCompat = IconCompat.createWithBitmap(bitmap)
        return CarIcon.Builder(iconCompat).build()
    }

    /**
     * Internal helper to draw any drawable centered on a white rounded-corner card.
     * Consistently used for both fetched icons and placeholders.
     *
     * Private method.
     *
     * @private
     * @param drawable The source drawable to render inside the card.
     * @return A 256x256 software [Bitmap] with the icon centred on a white rounded card.
     */
    private fun _drawIconOnCard(drawable: Drawable): Bitmap {
        // Draw any Drawable (Bitmap/Vector/SVG) onto a fixed-size software Bitmap
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        
        val width = canvas.width.toFloat()
        val height = canvas.height.toFloat()

        // 1. Define typical Card layout
        // No shadows or margins to maximize button space
        val paddingOuter = 2f
        val cardRect = android.graphics.RectF(paddingOuter, paddingOuter, width - paddingOuter, height - paddingOuter)
        val cornerRadius = 40f
        
        // 2. Prepare the paint (Flat White Apple/Metro design)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
        }
        
        // 3. Draw the clean rounded card
        canvas.drawRoundRect(cardRect, cornerRadius, cornerRadius, paint)
        
        // 4. Calculate proportional internal padding
        // Reduced from previous attempts because we expanded the borders
        val innerPadding = 28
        val iconLeft = cardRect.left.toInt() + innerPadding
        val iconTop = cardRect.top.toInt() + innerPadding
        val iconRight = cardRect.right.toInt() - innerPadding
        val iconBottom = cardRect.bottom.toInt() - innerPadding
        
        // 5. Draw the source vector inside the calculated bounds
        drawable.setBounds(iconLeft, iconTop, iconRight, iconBottom)
        drawable.draw(canvas)
        
        return bitmap
    }
}

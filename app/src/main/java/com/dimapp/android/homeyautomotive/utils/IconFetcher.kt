package com.dimapp.android.homeyautomotive.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
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
import com.dimapp.android.homeyautomotive.R
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
    /**
     * Represents the status badge displayed in the top-right corner of a device card.
     */
    enum class BadgeType {
        NONE,
        DOT_ALERT,     // Solid yellow dot for Home Overview
        LIGHT_ON,      // Power icon, cream background (#FFF3D1), amber foreground (#C59F38)
        LIGHT_OFF,     // Power icon, soft gray background (#F1F1F8), dark gray foreground (#636366)
        LOCK_LOCKED,   // Locked padlock, soft blue background (#E5F2FE), blue foreground (#0082FA)
        LOCK_UNLOCKED  // Unlocked padlock, soft gray background (#F1F1F8), dark gray foreground (#636366)
    }

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
     * Downloads an icon from Homey or CDN and decodes it into a [Drawable] using Coil.
     *
     * Public method.
     *
     * @public
     * @param context App context.
     * @param urlOrPath The icon URL (absolute) or relative path (from `iconObj.url`).
     * @param storage Shared [TokenStorage] instance.
     * @return Decoded [Drawable] if successful, `null` otherwise.
     * @example
     * val drawable = IconFetcher.fetchDrawable(carContext, device.iconUrl, storage)
     */
    suspend fun fetchDrawable(context: Context, urlOrPath: String?, storage: TokenStorage): Drawable? {
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

        return if (result is SuccessResult) {
            result.drawable
        } else {
            null
        }
    }

    /**
     * Converts any [Drawable] into a styled [CarIcon] centered on a white rounded card,
     * with an optional badge indicator in the top-right corner.
     *
     * Public method.
     *
     * @public
     * @param context App context.
     * @param drawable The source drawable to render inside the card.
     * @param badgeType The type of badge to render in the top-right corner. Defaults to [BadgeType.NONE].
     * @param dotColor Optional custom color when [badgeType] is [BadgeType.DOT_ALERT].
     * @return A styled [CarIcon].
     * @example
     * val icon = IconFetcher.getStyledIconFromDrawable(carContext, drawable, IconFetcher.BadgeType.LIGHT_ON)
     */
    fun getStyledIconFromDrawable(
        context: Context,
        drawable: Drawable,
        badgeType: BadgeType = BadgeType.NONE,
        dotColor: Int? = null
    ): CarIcon {
        val bitmap = _drawIconOnCard(context, drawable, badgeType, dotColor)
        val iconCompat = IconCompat.createWithBitmap(bitmap)
        return CarIcon.Builder(iconCompat).build()
    }

    /**
     * Downloads an icon from Homey or CDN, scales it, and wraps it into a [CarIcon] on a white card.
     *
     * Public method.
     *
     * @public
     * @param context App context.
     * @param urlOrPath The icon URL (absolute) or relative path (from `iconObj.url`).
     * @param storage Shared [TokenStorage] instance (from [DependencyManager]) used to
     *   inject the Bearer token and resolve the active Homey hub ID for URL reconstruction.
     * @param badgeType The type of badge to render in the top-right corner. Defaults to [BadgeType.NONE].
     * @param dotColor Optional custom color when [badgeType] is [BadgeType.DOT_ALERT].
     * @return [CarIcon] if successful, `null` if empty or download fails.
     * @example
     * ```
     * val icon = IconFetcher.fetchCarIcon(carContext, device.iconUrl, storage, IconFetcher.BadgeType.LIGHT_ON)
     * ```
     */
    suspend fun fetchCarIcon(
        context: Context,
        urlOrPath: String?,
        storage: TokenStorage,
        badgeType: BadgeType = BadgeType.NONE,
        dotColor: Int? = null
    ): CarIcon? {
        val drawable = fetchDrawable(context, urlOrPath, storage) ?: return null
        return getStyledIconFromDrawable(context, drawable, badgeType, dotColor)
    }

    /**
     * Creates a styled CarIcon from a local resource ID, applying the same white card look.
     *
     * Public method.
     *
     * @public
     * @param context App context.
     * @param resId The drawable resource ID.
     * @param badgeType The type of badge to render in the top-right corner. Defaults to [BadgeType.NONE].
     * @param dotColor Optional custom color when [badgeType] is [BadgeType.DOT_ALERT].
     * @return A styled [CarIcon].
     * @example
     * val icon = IconFetcher.getStyledIconFromResource(context, R.drawable.my_icon, IconFetcher.BadgeType.DOT_ALERT)
     */
    fun getStyledIconFromResource(
        context: Context,
        resId: Int,
        badgeType: BadgeType = BadgeType.NONE,
        dotColor: Int? = null
    ): CarIcon {
        val drawable = ContextCompat.getDrawable(context, resId)
            ?: throw IllegalArgumentException("Resource ID $resId not found")
        
        // Tints the system vector icon black so it remains visible on a white background
        androidx.core.graphics.drawable.DrawableCompat.setTint(
            androidx.core.graphics.drawable.DrawableCompat.wrap(drawable).mutate(),
            Color.BLACK
        )
        
        val bitmap = _drawIconOnCard(context, drawable, badgeType, dotColor)
        val iconCompat = IconCompat.createWithBitmap(bitmap)
        return CarIcon.Builder(iconCompat).build()
    }

    /**
     * Internal helper to draw any drawable positioned on the left portion of a white rounded-corner card,
     * preserving intrinsic aspect ratio, with an optional status badge in the dedicated top-right area.
     * Consistently used for both fetched icons, placeholders, and overview badges.
     *
     * Private method.
     *
     * @private
     * @param context App context.
     * @param drawable The source drawable to render inside the card.
     * @param badgeType The type of badge to render in the top-right corner.
     * @param dotColor Optional custom color for [BadgeType.DOT_ALERT].
     * @return A 256x256 software [Bitmap] with the icon on a white rounded card.
     */
    private fun _drawIconOnCard(
        context: Context,
        drawable: Drawable,
        badgeType: BadgeType = BadgeType.NONE,
        dotColor: Int? = null
    ): Bitmap {
        // Draw any Drawable (Bitmap/Vector/SVG) onto a fixed-size software Bitmap
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        
        val width = canvas.width.toFloat()
        val height = canvas.height.toFloat()

        // 1. Define typical Card layout
        // No shadows or margins to maximize button space
        val paddingOuter = 2f
        val cardRect = RectF(paddingOuter, paddingOuter, width - paddingOuter, height - paddingOuter)
        val cornerRadius = 40f
        
        // 2. Prepare the paint (Flat White Apple/Metro design)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
        }
        
        // 3. Draw the clean rounded card
        canvas.drawRoundRect(cardRect, cornerRadius, cornerRadius, paint)
        
        // 4. Calculate proportional internal bounds:
        // Position device illustration in the left ~60% of the tile (Homey mobile/web style),
        // leaving the right ~40% reserved for status indicators (yellow dot / badge) without overlap.
        val iconLeft = (cardRect.left + 28f).toInt()
        val iconTop = (cardRect.top + 28f).toInt()
        val iconRight = (cardRect.left + 166f).toInt()
        val iconBottom = (cardRect.bottom - 28f).toInt()

        val boxWidth = iconRight - iconLeft
        val boxHeight = iconBottom - iconTop
        val intrinsicW = drawable.intrinsicWidth
        val intrinsicH = drawable.intrinsicHeight

        // Preserve intrinsic aspect ratio inside the left bounding box
        val drawLeft: Int
        val drawTop: Int
        val drawRight: Int
        val drawBottom: Int

        if (intrinsicW > 0 && intrinsicH > 0) {
            val scale = minOf(boxWidth.toFloat() / intrinsicW.toFloat(), boxHeight.toFloat() / intrinsicH.toFloat())
            val scaledW = (intrinsicW * scale).toInt()
            val scaledH = (intrinsicH * scale).toInt()
            val offsetX = iconLeft + (boxWidth - scaledW) / 2
            val offsetY = iconTop + (boxHeight - scaledH) / 2
            drawLeft = offsetX
            drawTop = offsetY
            drawRight = offsetX + scaledW
            drawBottom = offsetY + scaledH
        } else {
            drawLeft = iconLeft
            drawTop = iconTop
            drawRight = iconRight
            drawBottom = iconBottom
        }
        
        // 5. Draw the source vector inside the calculated bounds
        drawable.setBounds(drawLeft, drawTop, drawRight, drawBottom)
        drawable.draw(canvas)

        // 6. Draw status badge in top right if requested
        if (badgeType != BadgeType.NONE) {
            if (badgeType == BadgeType.DOT_ALERT) {
                // Homey alarm indicator badge: vibrant blue core (#0082FA) with a soft light-blue halo (#E5F2FE)
                val haloRadius = 28f
                val coreRadius = 14f
                val badgeCenterX = cardRect.right - 46f
                val badgeCenterY = cardRect.top + 46f

                // 1. Protective white ring for clean separation from device icon
                val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    style = Paint.Style.FILL
                }
                canvas.drawCircle(badgeCenterX, badgeCenterY, haloRadius + 3.5f, borderPaint)

                // 2. Soft light-blue outer halo (#E5F2FE)
                val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.rgb(229, 242, 254)
                    style = Paint.Style.FILL
                }
                canvas.drawCircle(badgeCenterX, badgeCenterY, haloRadius, haloPaint)

                // 3. Vibrant blue inner core (defaults to official Homey blue #0082FA)
                val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = dotColor ?: Color.rgb(0, 130, 250)
                    style = Paint.Style.FILL
                }
                canvas.drawCircle(badgeCenterX, badgeCenterY, coreRadius, corePaint)
            } else {
                // Action badge (Power / Lock) with enlarged touch-friendly radius & icon
                val badgeRadius = 32f
                val badgeCenterX = cardRect.right - 46f
                val badgeCenterY = cardRect.top + 46f

                // 1. White border ring for clean separation from the black icon or card background
                val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    style = Paint.Style.FILL
                }
                canvas.drawCircle(badgeCenterX, badgeCenterY, badgeRadius + 3.5f, borderPaint)

                // 2. Circular background color
                val bgColor = when (badgeType) {
                    BadgeType.LIGHT_ON -> Color.rgb(255, 243, 209)      // #FFF3D1
                    BadgeType.LOCK_LOCKED -> Color.rgb(229, 242, 254)   // #E5F2FE
                    BadgeType.LIGHT_OFF,
                    BadgeType.LOCK_UNLOCKED -> Color.rgb(241, 241, 248) // #F1F1F8
                    else -> Color.TRANSPARENT
                }
                val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = bgColor
                    style = Paint.Style.FILL
                }
                canvas.drawCircle(badgeCenterX, badgeCenterY, badgeRadius, bgPaint)

                // 3. Foreground icon drawable & tint
                val iconResId = when (badgeType) {
                    BadgeType.LIGHT_ON, BadgeType.LIGHT_OFF -> R.drawable.ic_badge_power
                    BadgeType.LOCK_LOCKED -> R.drawable.ic_badge_locked
                    BadgeType.LOCK_UNLOCKED -> R.drawable.ic_badge_unlocked
                    else -> null
                }
                val fgColor = when (badgeType) {
                    BadgeType.LIGHT_ON -> Color.rgb(197, 159, 56)      // #C59F38
                    BadgeType.LOCK_LOCKED -> Color.rgb(0, 130, 250)    // #0082FA
                    BadgeType.LIGHT_OFF,
                    BadgeType.LOCK_UNLOCKED -> Color.rgb(99, 99, 102)  // #636366
                    else -> null
                }

                if (iconResId != null && fgColor != null) {
                    val badgeIcon = ContextCompat.getDrawable(context, iconResId)
                    if (badgeIcon != null) {
                        val iconSize = 46
                        val halfSize = iconSize / 2
                        badgeIcon.setBounds(
                            (badgeCenterX - halfSize).toInt(),
                            (badgeCenterY - halfSize).toInt(),
                            (badgeCenterX + halfSize).toInt(),
                            (badgeCenterY + halfSize).toInt()
                        )
                        androidx.core.graphics.drawable.DrawableCompat.setTint(
                            androidx.core.graphics.drawable.DrawableCompat.wrap(badgeIcon).mutate(),
                            fgColor
                        )
                        badgeIcon.draw(canvas)
                    }
                }
            }
        }
        
        return bitmap
    }
}

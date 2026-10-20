package org.schabi.newpipe.views.glass

import android.content.Context
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Outline
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import androidx.annotation.ColorInt
import androidx.annotation.RequiresApi
import eightbitlab.com.blurview.BlurTarget
import eightbitlab.com.blurview.BlurView

/**
 * Liquid Glass Engine providing real-time Gaussian blur with color vibrancy boost
 * (WhatsApp iOS / iOS Liquid Glass aesthetic).
 *
 * - On Android 12+ (API 31+): Chained RenderEffect (createBlurEffect 42f + saturation boost 1.45x)
 * - On API < 31: BlurView fallback (16f blur radius + #A60C0D14 overlay tint)
 */
object LiquidGlassHelper {

    const val DEFAULT_BLUR_RADIUS_API31 = 42f
    const val DEFAULT_BLUR_RADIUS_LEGACY = 16f
    const val DEFAULT_SATURATION = 1.45f
    const val DEFAULT_OVERLAY_TINT = 0xA60C0D14.toInt() // #A60C0D14

    /**
     * Applies the Liquid Glass blur and color vibrancy boost effect to a view.
     *
     * @param view The view to apply the liquid glass effect to (or BlurView container)
     * @param blurTarget The root BlurTarget being blurred behind this view (required for BlurView on API < 31)
     * @param blurRadius The blur radius to apply
     * @param saturation Saturation boost multiplier (1.45f = +45% color saturation)
     * @param overlayTint Overlay color tint used on fallback rendering
     */
    @JvmStatic
    @JvmOverloads
    fun applyLiquidGlass(
        view: View,
        blurTarget: BlurTarget? = null,
        blurRadius: Float = DEFAULT_BLUR_RADIUS_API31,
        saturation: Float = DEFAULT_SATURATION,
        @ColorInt overlayTint: Int = DEFAULT_OVERLAY_TINT
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            applyApi31ChainedEffect(view, blurRadius, saturation)
        } else if (view is BlurView && blurTarget != null) {
            setupBlurView(view, blurTarget, DEFAULT_BLUR_RADIUS_LEGACY, overlayTint)
        } else {
            // Low-level fallback: translucent tint with fallback background
            view.setBackgroundColor(overlayTint)
        }
    }

    /**
     * Applies chained RenderEffect on Android 12+ (API 31+):
     * Gaussian Blur (MIRROR) chained with ColorMatrixColorFilter (setSaturation(1.45f)).
     */
    @RequiresApi(Build.VERSION_CODES.S)
    @JvmStatic
    fun applyApi31ChainedEffect(
        view: View,
        blurRadius: Float = DEFAULT_BLUR_RADIUS_API31,
        saturation: Float = DEFAULT_SATURATION
    ) {
        try {
            val blurEffect = RenderEffect.createBlurEffect(
                blurRadius,
                blurRadius,
                Shader.TileMode.MIRROR
            )
            val matrix = ColorMatrix().apply {
                setSaturation(saturation)
            }
            val filter = ColorMatrixColorFilter(matrix)
            val filterEffect = RenderEffect.createColorFilterEffect(filter)

            // Inner effect: blur, Outer effect: saturation boost
            val chainedEffect = RenderEffect.createChainEffect(filterEffect, blurEffect)
            view.setRenderEffect(chainedEffect)
        } catch (ignored: Throwable) {
            // Graceful fallback if hardware rendering pipeline rejects chained shader
        }
    }

    /**
     * Sets up Dimezis / EightBitLab BlurView on API < 31.
     */
    @JvmStatic
    @JvmOverloads
    fun setupBlurView(
        blurView: BlurView,
        blurTarget: BlurTarget,
        blurRadius: Float = DEFAULT_BLUR_RADIUS_LEGACY,
        @ColorInt overlayTint: Int = DEFAULT_OVERLAY_TINT
    ) {
        try {
            blurView.setupWith(blurTarget)
                .setBlurRadius(blurRadius)
                .setOverlayColor(overlayTint)
        } catch (ignored: Throwable) {
            blurView.setBackgroundColor(overlayTint)
        }
    }

    /**
     * Clears any render effect applied to the view.
     */
    @JvmStatic
    fun clearLiquidGlass(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                view.setRenderEffect(null)
            } catch (ignored: Throwable) {
            }
        }
    }

    /**
     * Dynamically updates the top corner radii of a view for continuous corner morphing.
     */
    @JvmStatic
    fun updateTopCornerRadius(view: View, cornerRadiusPx: Float) {
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                if (cornerRadiusPx <= 0.5f) {
                    outline.setRect(0, 0, v.width, v.height)
                } else {
                    // Set rounded rect extending past bottom to keep bottom corners square (0dp)
                    outline.setRoundRect(
                        0,
                        0,
                        v.width,
                        v.height + cornerRadiusPx.toInt(),
                        cornerRadiusPx
                    )
                }
            }
        }
        view.clipToOutline = true
        view.invalidateOutline()
    }

    /**
     * Sets a uniform corner radius outline on the given view.
     */
    @JvmStatic
    fun setUniformCornerRadius(view: View, cornerRadiusPx: Float) {
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                outline.setRoundRect(0, 0, v.width, v.height, cornerRadiusPx)
            }
        }
        view.clipToOutline = true
        view.invalidateOutline()
    }
}

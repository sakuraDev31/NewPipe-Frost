package org.schabi.newpipe.views.glass

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.NonNull
import androidx.fragment.app.FragmentActivity
import com.google.android.material.bottomsheet.BottomSheetBehavior

/**
 * Orchestrates the connected physics and fluid transitions between the grounded bottom dock,
 * the floating/docked mini-player, and the full player sheet.
 */
class PlayerSheetController(
    private val activity: FragmentActivity,
    val playerSheet: View,
    val groundedDock: View
) {

    val behavior: BottomSheetBehavior<View> = BottomSheetBehavior.from(playerSheet)
    private val density: Float = activity.resources.displayMetrics.density

    // View references from VideoDetailFragment (bound dynamically)
    var miniOverlayView: View? = null
    var videoSurfaceContainer: View? = null
    var miniControlsLayout: View? = null
    var fullContentLayout: View? = null
    var progressBar: View? = null

    private var customSlideCallback: ((Float) -> Unit)? = null
    private var customStateCallback: ((Int) -> Unit)? = null

    init {
        setupBehavior()
    }

    private fun setupBehavior() {
        behavior.isHideable = true
        updatePeekHeight()

        behavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(@NonNull bottomSheet: View, newState: Int) {
                handleStateChanged(newState)
                customStateCallback?.invoke(newState)
            }

            override fun onSlide(@NonNull bottomSheet: View, slideOffset: Float) {
                handleSlide(slideOffset)
                customSlideCallback?.invoke(slideOffset)
            }
        })
    }

    /**
     * Calculates and updates peekHeight so the mini-player sits flush on top of the grounded dock.
     * peekHeight = 60dp (mini-player bar) + groundedDock.height
     */
    fun updatePeekHeight() {
        val miniPlayerHeightPx = (60f * density).toInt()
        val dockHeightPx = if (groundedDock.height > 0) groundedDock.height else (64f * density).toInt()
        val totalPeekHeight = miniPlayerHeightPx + dockHeightPx
        behavior.peekHeight = totalPeekHeight
    }

    /**
     * Binds view references from the active VideoDetailFragment to drive fluid interpolation.
     */
    fun bindPlayerViews(
        miniOverlay: View?,
        videoSurface: View?,
        miniControls: View?,
        fullContent: View?,
        progress: View?
    ) {
        this.miniOverlayView = miniOverlay
        this.videoSurfaceContainer = videoSurface
        this.miniControlsLayout = miniControls
        this.fullContentLayout = fullContent
        this.progressBar = progress

        // Apply initial collapsed/expanded presentation based on current sheet state
        val state = behavior.state
        handleStateChanged(state)
        val offset = if (state == BottomSheetBehavior.STATE_EXPANDED) 1f else 0f
        handleSlide(offset)
    }

    /**
     * Continuous drag interpolation on slide:
     * p in [0, 1]
     */
    fun handleSlide(slideOffset: Float) {
        val p = slideOffset.coerceIn(0f, 1f)

        // 1. Bottom Dock Translation: pushes dock off-screen downwards as player expands
        val dockHeight = if (groundedDock.height > 0) groundedDock.height.toFloat() else 64f * density
        groundedDock.translationY = p * dockHeight

        // 2. Corner Morph: interpolate top corner radius from 16dp at p=0 to 0dp at p=1
        val cornerRadiusPx = 16f * (1f - p) * density
        LiquidGlassHelper.updateTopCornerRadius(playerSheet, cornerRadiusPx)

        // 3. Mini-Player Controls Cross-Fade:
        // Fade out mini controls over p in [0, 0.25]
        val miniAlpha = (1f - (p / 0.25f)).coerceIn(0f, 1f)
        miniControlsLayout?.apply {
            alpha = miniAlpha
            visibility = if (p >= 0.25f) View.GONE else View.VISIBLE
        }

        // 4. Full Content Sheet Fade-In:
        // Fade in full metadata & comments over p in [0.2, 1.0]
        val fullAlpha = ((p - 0.2f) / 0.8f).coerceIn(0f, 1f)
        fullContentLayout?.apply {
            alpha = fullAlpha
            visibility = if (p <= 0.2f) View.GONE else View.VISIBLE
        }

        // 5. Video Surface Scaling:
        // Scale video surface from 104dp x 58dp (left-aligned) at p=0 to full width x 220dp at p=1
        updateVideoSurfaceInterpolation(p)
    }

    private fun updateVideoSurfaceInterpolation(p: Float) {
        val surface = videoSurfaceContainer ?: return
        val parentWidth = if (playerSheet.width > 0) playerSheet.width else activity.resources.displayMetrics.widthPixels
        val miniWidthPx = (104f * density).toInt()
        val miniHeightPx = (58f * density).toInt()

        val fullWidthPx = parentWidth
        val fullHeightPx = (220f * density).toInt()

        val currentW = (miniWidthPx + p * (fullWidthPx - miniWidthPx)).toInt()
        val currentH = (miniHeightPx + p * (fullHeightPx - miniHeightPx)).toInt()

        val currentMarginStart = ((1f - p) * 8f * density).toInt()
        val currentMarginTop = ((1f - p) * 1f * density).toInt()

        val lp = surface.layoutParams
        if (lp is ViewGroup.MarginLayoutParams) {
            lp.width = currentW
            lp.height = currentH
            lp.marginStart = currentMarginStart
            lp.topMargin = currentMarginTop
            surface.layoutParams = lp
        }

        // Thumbnail corner radius: morphs from 12dp rounded thumbnail at p=0 to 0dp full video at p=1
        val thumbRadiusPx = 12f * (1f - p) * density
        LiquidGlassHelper.setUniformCornerRadius(surface, thumbRadiusPx)
    }

    private fun handleStateChanged(newState: Int) {
        when (newState) {
            BottomSheetBehavior.STATE_COLLAPSED -> {
                groundedDock.translationY = 0f
                LiquidGlassHelper.updateTopCornerRadius(playerSheet, 16f * density)
                miniControlsLayout?.apply {
                    alpha = 1f
                    visibility = View.VISIBLE
                }
                fullContentLayout?.apply {
                    alpha = 0f
                    visibility = View.GONE
                }
                miniOverlayView?.visibility = View.VISIBLE
                updateVideoSurfaceInterpolation(0f)
            }
            BottomSheetBehavior.STATE_EXPANDED -> {
                val dockHeight = if (groundedDock.height > 0) groundedDock.height.toFloat() else 64f * density
                groundedDock.translationY = dockHeight
                LiquidGlassHelper.updateTopCornerRadius(playerSheet, 0f)
                miniControlsLayout?.apply {
                    alpha = 0f
                    visibility = View.GONE
                }
                fullContentLayout?.apply {
                    alpha = 1f
                    visibility = View.VISIBLE
                }
                updateVideoSurfaceInterpolation(1f)
            }
            BottomSheetBehavior.STATE_HIDDEN -> {
                groundedDock.translationY = 0f
            }
            else -> {
                // Dragging or settling handled continuously in handleSlide
            }
        }
    }

    fun setOnSlideListener(listener: (Float) -> Unit) {
        this.customSlideCallback = listener
    }

    fun setOnStateListener(listener: (Int) -> Unit) {
        this.customStateCallback = listener
    }

    fun expand() {
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
    }

    fun collapse() {
        behavior.state = BottomSheetBehavior.STATE_COLLAPSED
    }

    fun hide() {
        behavior.state = BottomSheetBehavior.STATE_HIDDEN
    }

    val isCollapsed: Boolean
        get() = behavior.state == BottomSheetBehavior.STATE_COLLAPSED

    val isExpanded: Boolean
        get() = behavior.state == BottomSheetBehavior.STATE_EXPANDED

    val isHidden: Boolean
        get() = behavior.state == BottomSheetBehavior.STATE_HIDDEN
}

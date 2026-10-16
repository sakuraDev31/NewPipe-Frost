package org.schabi.newpipe.views.glass

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.res.Configuration
import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.annotation.NonNull
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.fragment.app.FragmentActivity
import com.google.android.material.bottomsheet.BottomSheetBehavior
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Advanced 2D Draggable, Dockable & Expandable Physics Controller for the Liquid Glass Video Player.
 * - Docked strip sits strictly 10dp above @id/grounded_glass_dock (never overlaps behind bottom dock)
 * - Disables full-screen ExoPlayer controls when minimized; restores them past 0.6f expansion
 * - Free 2D dragging anywhere on screen with arbitrary height and dock snapping via SpringAnimation
 * - Fluid connected morphing to full-screen (0.0f -> 1.0f)
 */
open class LiquidGlassPlayerSheetController(
    private val activity: FragmentActivity,
    val playerSheet: View,
    val groundedDock: View,
    val landscapeDuoPill: View? = null
) {

    val behavior: BottomSheetBehavior<View> = BottomSheetBehavior.from(playerSheet)
    private val density: Float = activity.resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(activity).scaledTouchSlop

    // View references from VideoDetailFragment (bound dynamically)
    var miniOverlayView: View? = null
    var videoSurfaceContainer: View? = null
    var miniControlsLayout: View? = null
    var fullContentLayout: View? = null
    var progressBar: View? = null

    // ExoPlayer view reference (optional, for controller toggling)
    var playerControlContainer: View? = null

    private var customSlideCallback: ((Float) -> Unit)? = null
    private var customStateCallback: ((Int) -> Unit)? = null

    // 2D Dragging & Snapping Physics State
    private var velocityTracker: VelocityTracker? = null
    private var downX = 0f
    private var downY = 0f
    private var initialTranslationX = 0f
    private var initialTranslationY = 0f
    private var isDraggingFloatingPiP = false
    private var isFloatingDetached = false

    private val miniPlayerHeightPx: Int get() = (62f * density).toInt()
    private val miniPlayerMarginBottomPx: Int get() = (10f * density).toInt()
    private val floatingPipWidthPx: Int get() = (260f * density).toInt()
    private val floatingPipHeightPx: Int get() = (68f * density).toInt()

    init {
        setupBehavior()
    }

    private fun setupBehavior() {
        behavior.isHideable = true
        playerSheet.elevation = 24f * density
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
     * Calculates peekHeight so the mini-player sits strictly 10dp above the grounded dock.
     * RestY = ScreenHeight - GroundedDockHeight - MiniPlayerHeight - 10dp
     */
    fun updatePeekHeight() {
        val isDockVisible = groundedDock.visibility == View.VISIBLE
        val dockHeightPx = if (isDockVisible && groundedDock.height > 0) {
            groundedDock.height
        } else if (isDockVisible) {
            (64f * density).toInt()
        } else {
            0
        }
        val totalPeekHeight = miniPlayerHeightPx + dockHeightPx + miniPlayerMarginBottomPx
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

        setupMiniPlayerTouchGestures(miniOverlay)

        val state = behavior.state
        handleStateChanged(state)
        val offset = if (state == BottomSheetBehavior.STATE_EXPANDED) 1f else 0f
        handleSlide(offset)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupMiniPlayerTouchGestures(miniOverlay: View?) {
        val target = miniOverlay ?: return
        target.setOnTouchListener { view, event ->
            if (behavior.state != BottomSheetBehavior.STATE_COLLAPSED) {
                return@setOnTouchListener false
            }

            if (velocityTracker == null) {
                velocityTracker = VelocityTracker.obtain()
            }
            velocityTracker?.addMovement(event)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    target.parent?.requestDisallowInterceptTouchEvent(true)
                    downX = event.rawX
                    downY = event.rawY
                    initialTranslationX = playerSheet.translationX
                    initialTranslationY = playerSheet.translationY
                    isDraggingFloatingPiP = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    val dist = hypot(dx, dy)

                    if (!isDraggingFloatingPiP && dist > touchSlop) {
                        isDraggingFloatingPiP = true
                        isFloatingDetached = true
                        target.parent?.requestDisallowInterceptTouchEvent(true)
                    }

                    if (isDraggingFloatingPiP) {
                        val displayMetrics = activity.resources.displayMetrics
                        val screenHeight = displayMetrics.heightPixels
                        val dockHeight = if (groundedDock.height > 0) groundedDock.height else (64f * density).toInt()
                        val maxUp = -(screenHeight - dockHeight - (100f * density).toInt()).toFloat()

                        playerSheet.translationX = initialTranslationX + dx
                        playerSheet.translationY = (initialTranslationY + dy).coerceIn(maxUp, 0f)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val vt = velocityTracker
                    vt?.computeCurrentVelocity(1000)
                    val velY = vt?.yVelocity ?: 0f
                    val totalDx = event.rawX - downX
                    val totalDy = event.rawY - downY
                    val totalDist = hypot(totalDx, totalDy)

                    velocityTracker?.recycle()
                    velocityTracker = null
                    target.parent?.requestDisallowInterceptTouchEvent(false)

                    if (!isDraggingFloatingPiP && totalDist < touchSlop) {
                        // 1. Single Tap: Expand player
                        expand()
                    } else if (velY < -1500f && isDraggingFloatingPiP && totalDy < -40f * density) {
                        // 2. Quick Swipe Up: Expand player
                        expand()
                    } else if (isDraggingFloatingPiP) {
                        // 3. Release: Magnetic Dock vs. Arbitrary Height Snapping
                        snapToArbitraryPosition()
                    }
                    true
                }
                else -> false
            }
        }
    }

    /**
     * Morph the mini-player container between the full-width docked strip and the 260dp floating PiP card.
     */
    private fun morphToFloatingPiP(floating: Boolean) {
        val parentWidth = if (playerSheet.width > 0) playerSheet.width else activity.resources.displayMetrics.widthPixels
        val startW = playerSheet.layoutParams.width
        val endW = if (floating) floatingPipWidthPx else ViewGroup.LayoutParams.MATCH_PARENT
        val startRadius = if (floating) 18f * density else 22f * density
        val endRadius = if (floating) 22f * density else 18f * density

        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 180L
            addUpdateListener { va ->
                val fraction = va.animatedFraction
                val lp = playerSheet.layoutParams
                if (endW != ViewGroup.LayoutParams.MATCH_PARENT) {
                    lp.width = (startW + fraction * (endW - startW)).toInt()
                } else {
                    if (fraction >= 0.99f) {
                        lp.width = ViewGroup.LayoutParams.MATCH_PARENT
                    } else {
                        lp.width = (startW + fraction * (parentWidth - startW)).toInt()
                    }
                }
                playerSheet.layoutParams = lp
                val currentRadius = startRadius + fraction * (endRadius - startRadius)
                LiquidGlassHelper.updateTopCornerRadius(playerSheet, currentRadius)
            }
        }
        animator.start()
    }

    /**
     * Snaps playerSheet allowing placement at any arbitrary height, or snaps to dock when dragged near bottom.
     */
    private fun snapToArbitraryPosition() {
        val displayMetrics = activity.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        val dockHeight = if (groundedDock.height > 0) groundedDock.height else (64f * density).toInt()

        val currentTransY = playerSheet.translationY
        val currentTransX = playerSheet.translationX

        // If dropped near the bottom dock, snap cleanly back to docked strip
        if (currentTransY >= -20f * density) {
            snapWithSpring(0f, 0f) {
                isFloatingDetached = false
            }
            return
        }

        // Snap safely preserving the arbitrary height selected by user
        val maxUp = -(screenHeight - dockHeight - (100f * density).toInt()).toFloat()
        val targetY = currentTransY.coerceIn(maxUp, 0f)

        // Keep horizontal translation within screen margins
        val maxHorizontalOffset = (screenWidth * 0.40f)
        val targetX = currentTransX.coerceIn(-maxHorizontalOffset, maxHorizontalOffset)

        snapWithSpring(targetX, targetY)
    }

    private fun snapWithSpring(targetX: Float, targetY: Float, onEnd: (() -> Unit)? = null) {
        val springX = SpringAnimation(playerSheet, DynamicAnimation.TRANSLATION_X).apply {
            spring = SpringForce(targetX).apply {
                stiffness = SpringForce.STIFFNESS_LOW
                dampingRatio = SpringForce.DAMPING_RATIO_LOW_BOUNCY
            }
        }
        val springY = SpringAnimation(playerSheet, DynamicAnimation.TRANSLATION_Y).apply {
            spring = SpringForce(targetY).apply {
                stiffness = SpringForce.STIFFNESS_LOW
                dampingRatio = SpringForce.DAMPING_RATIO_LOW_BOUNCY
            }
            if (onEnd != null) {
                addEndListener { _, _, _, _ -> onEnd() }
            }
        }
        springX.start()
        springY.start()
    }

    /**
     * Continuous drag interpolation on slide:
     * p in [0, 1]
     */
    fun handleSlide(slideOffset: Float) {
        val p = slideOffset.coerceIn(0f, 1f)

        // Reset any floating PiP translation when expanding
        if (p > 0.05f && (playerSheet.translationX != 0f || playerSheet.translationY != 0f)) {
            playerSheet.translationX = (1f - p) * playerSheet.translationX
            playerSheet.translationY = (1f - p) * playerSheet.translationY
        }

        // 1. Bottom Dock Translation: pushes dock off-screen downwards as player expands
        val dockHeight = if (groundedDock.height > 0) groundedDock.height.toFloat() else 64f * density
        groundedDock.translationY = p * dockHeight

        // 1b. Landscape Duo Rail Translation: pushes rail off-screen to the right as player expands
        landscapeDuoPill?.let { duo ->
            val duoWidth = if (duo.width > 0) duo.width.toFloat() else 80f * density
            duo.translationX = p * (duoWidth + 30f * density)
            duo.alpha = (1f - p * 2f).coerceIn(0f, 1f)
            if (p >= 0.4f) {
                duo.visibility = View.GONE
            }
        }

        // 2. Corner Morph: interpolate top corner radius from 18dp at p=0 to 0dp at p=1
        val cornerRadiusPx = 18f * (1f - p) * density
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
        // Scale video surface from 104dp x 54dp (left-aligned) at p=0 to full width x 220dp at p=1
        updateVideoSurfaceInterpolation(p)

        // 6. Disable Full Player Controls When Minimized:
        // Restore controls only past p > 0.6f
        playerControlContainer?.apply {
            alpha = ((p - 0.6f) / 0.4f).coerceIn(0f, 1f)
            visibility = if (p > 0.6f) View.VISIBLE else View.GONE
        }
    }

    private fun updateVideoSurfaceInterpolation(p: Float) {
        val surface = videoSurfaceContainer ?: return
        val parentWidth = if (playerSheet.width > 0) playerSheet.width else activity.resources.displayMetrics.widthPixels
        val miniWidthPx = (104f * density).toInt()
        val miniHeightPx = (54f * density).toInt()

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

        val thumbRadiusPx = 12f * (1f - p) * density
        LiquidGlassHelper.setUniformCornerRadius(surface, thumbRadiusPx)
    }

    private fun handleStateChanged(newState: Int) {
        when (newState) {
            BottomSheetBehavior.STATE_COLLAPSED -> {
                groundedDock.translationY = 0f
                if (activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                    landscapeDuoPill?.visibility = View.GONE
                }
                LiquidGlassHelper.updateTopCornerRadius(playerSheet, 18f * density)
                miniControlsLayout?.apply {
                    alpha = 1f
                    visibility = View.VISIBLE
                }
                fullContentLayout?.apply {
                    alpha = 0f
                    visibility = View.GONE
                }
                miniOverlayView?.visibility = View.VISIBLE
                playerControlContainer?.visibility = View.GONE
                updateVideoSurfaceInterpolation(0f)
            }
            BottomSheetBehavior.STATE_EXPANDED -> {
                val dockHeight = if (groundedDock.height > 0) groundedDock.height.toFloat() else 64f * density
                groundedDock.translationY = dockHeight
                landscapeDuoPill?.visibility = View.GONE
                playerSheet.translationX = 0f
                playerSheet.translationY = 0f
                LiquidGlassHelper.updateTopCornerRadius(playerSheet, 0f)
                miniControlsLayout?.apply {
                    alpha = 0f
                    visibility = View.GONE
                }
                fullContentLayout?.apply {
                    alpha = 1f
                    visibility = View.VISIBLE
                }
                playerControlContainer?.apply {
                    alpha = 1f
                    visibility = View.VISIBLE
                }
                updateVideoSurfaceInterpolation(1f)
            }
            BottomSheetBehavior.STATE_HIDDEN -> {
                groundedDock.translationY = 0f
                playerSheet.translationX = 0f
                playerSheet.translationY = 0f
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

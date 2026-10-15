package org.schabi.newpipe.views.glass

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import org.schabi.newpipe.R

/**
 * High-performance real-time Liquid Glass container inspired by WhatsApp iOS and iOS Liquid Glass.
 * Samples the underlying backdrop container (such as @id/fragment_holder) into a 1/6th downscaled
 * offscreen bitmap before each frame, applies high-radius blur with 1.45x color vibrancy saturation,
 * and renders a 1dp specular refraction border.
 */
class RealTimeLiquidGlassView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr), ViewTreeObserver.OnPreDrawListener {

    companion object {
        const val DOWNSAMPLE_FACTOR = 6
        const val DEFAULT_BLUR_RADIUS = 28f
        const val DEFAULT_SATURATION = 1.45f
        const val DEFAULT_GLASS_TINT = 0x6610121A.toInt() // 40% alpha dark glass tint
    }

    private val density = context.resources.displayMetrics.density

    var glassCornerRadius: Float = 18f * density
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var glassTint: Int = DEFAULT_GLASS_TINT
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var glassBlurRadius: Float = DEFAULT_BLUR_RADIUS
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var glassStrokeWidth: Float = 1f * density
        set(value) {
            if (field != value) {
                field = value
                strokePaint.strokeWidth = value
                invalidate()
            }
        }

    var isBlurEnabled: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var backdropView: View? = null
        set(value) {
            field = value
            invalidate()
        }

    private val clipPath = Path()
    private val viewRectF = RectF()
    private val strokeRectF = RectF()

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }

    private var downscaledBitmap: Bitmap? = null
    private var blurredBitmap: Bitmap? = null
    private var pixelBuffer: IntArray? = null
    private var isRenderingBackdrop = false

    private val srcRect = Rect()
    private val dstRect = Rect()

    private val screenLocationGlass = IntArray(2)
    private val screenLocationBackdrop = IntArray(2)

    private var lastShaderHeight = -1

    init {
        setWillNotDraw(false)

        val saturationMatrix = ColorMatrix().apply { setSaturation(DEFAULT_SATURATION) }
        bitmapPaint.colorFilter = ColorMatrixColorFilter(saturationMatrix)

        if (attrs != null) {
            val a = context.obtainStyledAttributes(attrs, R.styleable.RealTimeLiquidGlassView, defStyleAttr, 0)
            glassCornerRadius = a.getDimension(R.styleable.RealTimeLiquidGlassView_glassCornerRadius, glassCornerRadius)
            glassTint = a.getColor(R.styleable.RealTimeLiquidGlassView_glassTint, glassTint)
            glassBlurRadius = a.getDimension(R.styleable.RealTimeLiquidGlassView_glassBlurRadius, glassBlurRadius)
            glassStrokeWidth = a.getDimension(R.styleable.RealTimeLiquidGlassView_glassStrokeWidth, glassStrokeWidth)
            a.recycle()
        }
        strokePaint.strokeWidth = glassStrokeWidth
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnPreDrawListener(this)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnPreDrawListener(this)
        recycleBitmaps()
        super.onDetachedFromWindow()
    }

    private fun recycleBitmaps() {
        downscaledBitmap?.recycle()
        downscaledBitmap = null
        blurredBitmap?.recycle()
        blurredBitmap = null
        pixelBuffer = null
    }

    private fun resolveBackdropView(): View? {
        if (backdropView != null) return backdropView
        val root = rootView ?: return null
        val holder = root.findViewById<View>(R.id.fragment_holder)
        if (holder != null) return holder
        val coord = root.findViewById<View>(R.id.coordinator)
        if (coord != null) return coord
        return root.findViewById<View>(android.R.id.content)
    }

    override fun onPreDraw(): Boolean {
        if (!isBlurEnabled || visibility != View.VISIBLE || alpha <= 0.02f || width <= 0 || height <= 0) {
            return true
        }

        if (isRenderingBackdrop) {
            return true
        }

        val target = resolveBackdropView() ?: return true

        val downW = maxOf(1, width / DOWNSAMPLE_FACTOR)
        val downH = maxOf(1, height / DOWNSAMPLE_FACTOR)

        if (downscaledBitmap == null || downscaledBitmap?.width != downW || downscaledBitmap?.height != downH) {
            downscaledBitmap?.recycle()
            blurredBitmap?.recycle()
            downscaledBitmap = Bitmap.createBitmap(downW, downH, Bitmap.Config.ARGB_8888)
            blurredBitmap = Bitmap.createBitmap(downW, downH, Bitmap.Config.ARGB_8888)
            pixelBuffer = IntArray(downW * downH)
        }

        val downBmp = downscaledBitmap ?: return true
        val blurBmp = blurredBitmap ?: return true

        getLocationOnScreen(screenLocationGlass)
        target.getLocationOnScreen(screenLocationBackdrop)

        val relX = screenLocationGlass[0] - screenLocationBackdrop[0]
        val relY = screenLocationGlass[1] - screenLocationBackdrop[1]

        isRenderingBackdrop = true
        try {
            val canvas = Canvas(downBmp)
            canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
            canvas.scale(1f / DOWNSAMPLE_FACTOR, 1f / DOWNSAMPLE_FACTOR)
            canvas.translate(-relX.toFloat(), -relY.toFloat())
            target.draw(canvas)
        } catch (e: Exception) {
            // Silently swallow backdrop draw canvas errors if detached or animating
        } finally {
            isRenderingBackdrop = false
        }

        // Apply fast in-place StackBlur on downscaled bitmap
        val effectiveRadius = (glassBlurRadius / DOWNSAMPLE_FACTOR).coerceIn(1f, 25f).toInt()
        applyFastBlur(downBmp, blurBmp, effectiveRadius)

        invalidate()
        return true
    }

    override fun dispatchDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) {
            super.dispatchDraw(canvas)
            return
        }

        viewRectF.set(0f, 0f, w, h)
        clipPath.reset()
        clipPath.addRoundRect(viewRectF, glassCornerRadius, glassCornerRadius, Path.Direction.CW)

        val saveCount = canvas.save()
        canvas.clipPath(clipPath)

        // 1. Draw blurred & vibrancy boosted backdrop
        val bmp = blurredBitmap
        if (isBlurEnabled && bmp != null && !bmp.isRecycled) {
            srcRect.set(0, 0, bmp.width, bmp.height)
            dstRect.set(0, 0, width, height)
            canvas.drawBitmap(bmp, srcRect, dstRect, bitmapPaint)
        }

        // 2. Translucent glass tint (#6610121A)
        tintPaint.color = glassTint
        canvas.drawRect(viewRectF, tintPaint)

        // 3. Specular refraction border (top/left highlight)
        if (glassStrokeWidth > 0f) {
            if (strokePaint.shader == null || lastShaderHeight != height) {
                lastShaderHeight = height
                val specularShader = LinearGradient(
                    0f, 0f, 0f, h,
                    intArrayOf(
                        0x73FFFFFF.toInt(), // Top specular refraction (45% white)
                        0x14FFFFFF,         // Mid fade (8% white)
                        0x40FFFFFF          // Bottom rim highlight (25% white)
                    ),
                    floatArrayOf(0f, 0.45f, 1f),
                    Shader.TileMode.CLAMP
                )
                strokePaint.shader = specularShader
            }
            val halfStroke = glassStrokeWidth / 2f
            strokeRectF.set(halfStroke, halfStroke, w - halfStroke, h - halfStroke)
            canvas.drawRoundRect(strokeRectF, glassCornerRadius, glassCornerRadius, strokePaint)
        }

        // 4. Render children on top of the liquid glass surface
        super.dispatchDraw(canvas)

        canvas.restoreToCount(saveCount)
    }

    /**
     * Fast in-place StackBlur implementation for downscaled bitmaps.
     */
    private fun applyFastBlur(src: Bitmap, dst: Bitmap, radius: Int) {
        if (radius < 1) return
        val w = src.width
        val h = src.height
        val pix = pixelBuffer ?: return
        src.getPixels(pix, 0, w, 0, 0, w, h)

        val wm = w - 1
        val hm = h - 1
        val wh = w * h
        val div = radius + radius + 1

        val r = IntArray(wh)
        val g = IntArray(wh)
        val b = IntArray(wh)
        var rsum: Int
        var gsum: Int
        var bsum: Int
        var x: Int
        var y: Int
        var i: Int
        var p: Int
        var yp: Int
        var yi: Int
        var yw: Int
        val vmin = IntArray(maxOf(w, h))

        var divsum = (div + 1) shr 1
        divsum *= divsum
        val dv = IntArray(256 * divsum)
        for (idx in 0 until 256 * divsum) {
            dv[idx] = idx / divsum
        }

        yw = 0
        yi = 0

        val stack = Array(div) { IntArray(3) }
        var stackpointer: Int
        var stackstart: Int
        var rbs: Int
        val routsum = IntArray(3)
        val rinsum = IntArray(3)

        for (curY in 0 until h) {
            rinsum.fill(0)
            routsum.fill(0)
            rsum = 0
            gsum = 0
            bsum = 0
            for (curI in -radius..radius) {
                p = pix[yi + minOf(wm, maxOf(curI, 0))]
                val sir = stack[curI + radius]
                sir[0] = (p and 0xff0000) shr 16
                sir[1] = (p and 0x00ff00) shr 8
                sir[2] = (p and 0x0000ff)
                val rbsVal = radius + 1 - Math.abs(curI)
                rsum += sir[0] * rbsVal
                gsum += sir[1] * rbsVal
                bsum += sir[2] * rbsVal
                if (curI > 0) {
                    rinsum[0] += sir[0]
                    rinsum[1] += sir[1]
                    rinsum[2] += sir[2]
                } else {
                    routsum[0] += sir[0]
                    routsum[1] += sir[1]
                    routsum[2] += sir[2]
                }
            }
            stackpointer = radius

            for (curX in 0 until w) {
                r[yi] = dv[rsum]
                g[yi] = dv[gsum]
                b[yi] = dv[bsum]

                rsum -= routsum[0]
                gsum -= routsum[1]
                bsum -= routsum[2]

                stackstart = stackpointer - radius + div
                val sir = stack[stackstart % div]

                routsum[0] -= sir[0]
                routsum[1] -= sir[1]
                routsum[2] -= sir[2]

                if (curY == 0) {
                    vmin[curX] = minOf(curX + radius + 1, wm)
                }
                p = pix[yw + vmin[curX]]

                sir[0] = (p and 0xff0000) shr 16
                sir[1] = (p and 0x00ff00) shr 8
                sir[2] = (p and 0x0000ff)

                rinsum[0] += sir[0]
                rinsum[1] += sir[1]
                rinsum[2] += sir[2]

                rsum += rinsum[0]
                gsum += rinsum[1]
                bsum += rinsum[2]

                stackpointer = (stackpointer + 1) % div
                val nextSir = stack[stackpointer % div]

                routsum[0] += nextSir[0]
                routsum[1] += nextSir[1]
                routsum[2] += nextSir[2]

                rinsum[0] -= nextSir[0]
                rinsum[1] -= nextSir[1]
                rinsum[2] -= nextSir[2]

                yi++
            }
            yw += w
        }

        for (curX in 0 until w) {
            rinsum.fill(0)
            routsum.fill(0)
            rsum = 0
            gsum = 0
            bsum = 0
            yp = -radius * w
            for (curI in -radius..radius) {
                yi = maxOf(0, yp) + curX
                val sir = stack[curI + radius]
                sir[0] = r[yi]
                sir[1] = g[yi]
                sir[2] = b[yi]
                val rbsVal = radius + 1 - Math.abs(curI)
                rsum += r[yi] * rbsVal
                gsum += g[yi] * rbsVal
                bsum += b[yi] * rbsVal
                if (curI > 0) {
                    rinsum[0] += sir[0]
                    rinsum[1] += sir[1]
                    rinsum[2] += sir[2]
                } else {
                    routsum[0] += sir[0]
                    routsum[1] += sir[1]
                    routsum[2] += sir[2]
                }
                if (curI < hm) {
                    yp += w
                }
            }
            yi = curX
            stackpointer = radius
            for (curY in 0 until h) {
                pix[yi] = (0xff000000.toInt()) or (dv[rsum] shl 16) or (dv[gsum] shl 8) or dv[bsum]

                rsum -= routsum[0]
                gsum -= routsum[1]
                bsum -= routsum[2]

                stackstart = stackpointer - radius + div
                val sir = stack[stackstart % div]

                routsum[0] -= sir[0]
                routsum[1] -= sir[1]
                routsum[2] -= sir[2]

                if (curX == 0) {
                    vmin[curY] = minOf(curY + radius + 1, hm) * w
                }
                p = curX + vmin[curY]

                sir[0] = r[p]
                sir[1] = g[p]
                sir[2] = b[p]

                rinsum[0] += sir[0]
                rinsum[1] += sir[1]
                rinsum[2] += sir[2]

                rsum += rinsum[0]
                gsum += rinsum[1]
                bsum += rinsum[2]

                stackpointer = (stackpointer + 1) % div
                val nextSir = stack[stackpointer]

                routsum[0] += nextSir[0]
                routsum[1] += nextSir[1]
                routsum[2] += nextSir[2]

                rinsum[0] -= nextSir[0]
                rinsum[1] -= nextSir[1]
                rinsum[2] -= nextSir[2]

                yi += w
            }
        }

        dst.setPixels(pix, 0, w, 0, 0, w, h)
    }
}

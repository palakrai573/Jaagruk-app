package org.jaagruk.safety.circle

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * RegionSelectionView
 *
 * Full-screen custom View that:
 * - Renders the captured screenshot as background
 * - Dims the non-selected area
 * - Lets the user drag a rectangle selection
 * - Calls [onRegionSelected] when finger lifts with region >= 20x20px
 * - Calls [onCancel] on tap without drag
 *
 * MVP: rectangle selection only.
 * Architecture is ready for freehand by extending ACTION_MOVE to store a Path.
 */
class RegionSelectionView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var onRegionSelected : ((Rect) -> Unit)? = null
    var onCancel         : (() -> Unit)?     = null

    private var screenshot: Bitmap? = null

    private var startX   = 0f;  private var startY   = 0f
    private var currentX = 0f;  private var currentY = 0f
    private var isDragging = false

    private val dimPaint = Paint().apply { color = Color.argb(160, 0, 0, 0) }

    private val borderPaint = Paint().apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 3f; isAntiAlias = true
    }

    private val cornerPaint = Paint().apply {
        color = Color.parseColor("#3B82F6")
        style = Paint.Style.STROKE; strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND; isAntiAlias = true
    }

    private val clearPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    private val titlePaint = Paint().apply {
        color = Color.WHITE; textSize = 42f; isAntiAlias = true
        textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
    }

    private val subPaint = Paint().apply {
        color = Color.argb(200, 255, 255, 255)
        textSize = 32f; isAntiAlias = true; textAlign = Paint.Align.CENTER
    }

    fun setScreenshot(bmp: Bitmap) { screenshot = bmp; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val bmp = screenshot ?: run { canvas.drawColor(Color.BLACK); return }

        // Draw screenshot scaled to fill view
        canvas.drawBitmap(bmp, null,
            RectF(0f, 0f, width.toFloat(), height.toFloat()), null)

        if (!isDragging) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
            canvas.drawText("Drag to select", width / 2f, height / 2f - 24f, titlePaint)
            canvas.drawText("Tap anywhere to cancel", width / 2f, height / 2f + 40f, subPaint)
            return
        }

        val sel = selRect()

        // Dim overlay with transparent hole over selection
        val sc = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
        canvas.drawRect(sel, clearPaint)
        canvas.restoreToCount(sc)

        canvas.drawRect(sel, borderPaint)

        // Corner accents
        val cs = 40f
        with(sel) {
            canvas.drawLine(left, top, left + cs, top, cornerPaint)
            canvas.drawLine(left, top, left, top + cs, cornerPaint)
            canvas.drawLine(right - cs, top, right, top, cornerPaint)
            canvas.drawLine(right, top, right, top + cs, cornerPaint)
            canvas.drawLine(left, bottom - cs, left, bottom, cornerPaint)
            canvas.drawLine(left, bottom, left + cs, bottom, cornerPaint)
            canvas.drawLine(right - cs, bottom, right, bottom, cornerPaint)
            canvas.drawLine(right, bottom - cs, right, bottom, cornerPaint)
        }

        // Size hint
        if (sel.top > 60f)
            canvas.drawText("${sel.width().toInt()} × ${sel.height().toInt()}",
                sel.centerX(), sel.top - 20f, subPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x; startY = event.y
                currentX = event.x; currentY = event.y
                isDragging = false; invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                currentX = event.x; currentY = event.y
                if (Math.abs(currentX - startX) > 10f || Math.abs(currentY - startY) > 10f)
                    isDragging = true
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                currentX = event.x; currentY = event.y
                if (!isDragging) { onCancel?.invoke(); return true }
                val sel = selRect()
                if (sel.width() < 20f || sel.height() < 20f) {
                    isDragging = false; invalidate(); return true
                }
                val bmp = screenshot ?: return true
                val sx = bmp.width.toFloat() / width
                val sy = bmp.height.toFloat() / height
                onRegionSelected?.invoke(Rect(
                    (sel.left * sx).toInt(), (sel.top * sy).toInt(),
                    (sel.right * sx).toInt(), (sel.bottom * sy).toInt()
                ))
            }
        }
        return true
    }

    private fun selRect() = RectF(
        minOf(startX, currentX), minOf(startY, currentY),
        maxOf(startX, currentX), maxOf(startY, currentY)
    )
}

package com.example.ble_device_mesh.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.example.ble_device_mesh.data.StreetlightProfile
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 路灯模式曲线编辑器
 * 时间-亮度二维曲线图，支持拖动控制点
 *
 * X轴: 0-24小时 (0-1440分钟)
 * Y轴: 0-100% 亮度
 */
class StreetlightCurveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    init {
        // 启用硬件加速
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    // 控制点列表
    var controlPoints = mutableListOf<StreetlightProfile.ControlPoint>()
        set(value) {
            field = value.toMutableList()
            invalidate()
        }

    // 控制点变化回调
    var onPointRemoved: ((index: Int) -> Unit)? = null
    var onPointEdit: ((index: Int, point: StreetlightProfile.ControlPoint) -> Unit)? = null  // 点击编辑控制点

    // 绘图相关
    private val gridPaint = Paint().apply {
        color = Color.parseColor("#E0E0E0")
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }

    private val axisPaint = Paint().apply {
        color = Color.parseColor("#9E9E9E")
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    private val curvePaint = Paint().apply {
        color = Color.parseColor("#FF9800")
        strokeWidth = 4f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val fillPaint = Paint().apply {
        color = Color.parseColor("#FFE0B2")
        style = Paint.Style.FILL
        alpha = 128
    }

    private val pointPaint = Paint().apply {
        color = Color.parseColor("#FF9800")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val pointStrokePaint = Paint().apply {
        color = Color.WHITE
        strokeWidth = 3f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val selectedPaint = Paint().apply {
        color = Color.parseColor("#FF9800")
        style = Paint.Style.FILL
        isAntiAlias = true
        alpha = 80
    }

    private val textPaint = Paint().apply {
        color = Color.parseColor("#666666")
        textSize = 24f
        isAntiAlias = true
    }

    private val labelPaint = Paint().apply {
        color = Color.parseColor("#333333")
        textSize = 28f
        isAntiAlias = true
    }

    // 拖动工具提示
    private val tooltipBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2D2D2D")
        style = Paint.Style.FILL
    }

    private val tooltipTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 26f
    }

    // 时间范围偏移（用于夜间聚焦模式，起始偏移至 12:00）
    private var nightModeEnabled = false
    private var rangeStartMinutes = 0

    // 触摸相关
    private var draggedPointIndex = -1
    private var selectedPointIndex = -1  // 当前选中的点（放大高亮显示）
    private var isDragging = false       // 是否正在拖动
    private var lastDraggedPoint: StreetlightProfile.ControlPoint? = null
    private val pointRadius = 24f  // 正常控制点半径
    private val touchRadius = 48f  // 触摸检测半径

    // 选中放大动画
    private var selectedRadius = pointRadius
    private var selectAnimator: ValueAnimator? = null

    // 吸附粒度（拖动时平滑，松开后吸附）
    private val snapMinutes = 1    // 时间吸附到 1 分钟（更平滑）
    private val snapBrightness = 1 // 亮度吸附到 1%（更平滑）

    // 边距
    private val paddingLeft = 80f
    private val paddingRight = 40f
    private val paddingTop = 60f
    private val paddingBottom = 80f

    // 绘图区域
    private val chartWidth: Float
        get() = width - paddingLeft - paddingRight

    private val chartHeight: Float
        get() = height - paddingTop - paddingBottom

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        drawGrid(canvas)
        drawCurve(canvas)
        drawControlPoints(canvas)
        drawLabels(canvas)

        // 拖动控制点时显示坐标工具提示
        if (isDragging && draggedPointIndex >= 0) {
            drawTooltip(canvas, controlPoints[draggedPointIndex])
        }
    }

    /**
     * 绘制网格
     */
    private fun drawGrid(canvas: Canvas) {
        // 垂直网格线（每小时）
        for (hour in 0..24) {
            val x = paddingLeft + (hour / 24f) * chartWidth
            canvas.drawLine(x, paddingTop, x, height - paddingBottom, gridPaint)
        }

        // 水平网格线（每 20%）
        for (percent in 0..100 step 20) {
            val y = paddingTop + (1 - percent / 100f) * chartHeight
            canvas.drawLine(paddingLeft, y, width - paddingRight, y, gridPaint)
        }

        // 坐标轴
        canvas.drawLine(paddingLeft, paddingTop, paddingLeft, height - paddingBottom, axisPaint)
        canvas.drawLine(
            paddingLeft,
            height - paddingBottom,
            width - paddingRight,
            height - paddingBottom,
            axisPaint
        )
    }

    /**
     * 绘制曲线（按显示顺序，支持夜间聚焦模式）
     */
    private fun drawCurve(canvas: Canvas) {
        if (controlPoints.size < 2) return

        // 按显示位置排序（夜间模式下从 12:00 开始）
        val displaySorted = controlPoints.sortedBy { minutesToX(it.toMinutes()) }

        // 绘制填充区域（从左边缘到右边缘）
        val fillPath = Path()
        fillPath.moveTo(paddingLeft, height - paddingBottom)
        for (point in displaySorted) {
            fillPath.lineTo(minutesToX(point.toMinutes()), brightnessToY(point.brightness))
        }
        fillPath.lineTo(width - paddingRight, height - paddingBottom)
        fillPath.close()
        canvas.drawPath(fillPath, fillPaint)

        // 绘制折线
        val path = Path()
        path.moveTo(minutesToX(displaySorted.first().toMinutes()), brightnessToY(displaySorted.first().brightness))
        for (i in 1 until displaySorted.size) {
            path.lineTo(minutesToX(displaySorted[i].toMinutes()), brightnessToY(displaySorted[i].brightness))
        }
        canvas.drawPath(path, curvePaint)
    }

    /**
     * 绘制控制点
     */
    private fun drawControlPoints(canvas: Canvas) {
        for ((index, point) in controlPoints.withIndex()) {
            val x = minutesToX(point.toMinutes())
            val y = brightnessToY(point.brightness)

            val isSelected = (index == selectedPointIndex)

            if (isSelected) {
                // 选中状态：外圈光晕
                canvas.drawCircle(x, y, selectedRadius + 8, selectedPaint)
                // 外圈白色边框
                canvas.drawCircle(x, y, selectedRadius, pointStrokePaint)
                // 内圈橙色填充
                canvas.drawCircle(x, y, selectedRadius, pointPaint)
            } else {
                // 普通状态
                canvas.drawCircle(x, y, pointRadius, pointStrokePaint)
                canvas.drawCircle(x, y, pointRadius, pointPaint)
            }
        }
    }

    /**
     * 绘制标签（支持夜间聚焦模式的动态时间刻度）
     */
    private fun drawLabels(canvas: Canvas) {
        // X轴标签（时间，根据 rangeStartMinutes 偏移）
        for (hour in 0..24 step 2) {
            val x = paddingLeft + (hour / 24f) * chartWidth
            val labelHour = ((rangeStartMinutes / 60) + hour) % 24
            canvas.drawText(
                "$labelHour",
                x - 10,
                height - paddingBottom + 40,
                textPaint
            )
        }
        val xTitle = if (nightModeEnabled) "时间(时) · 夜间" else "时间(时)"
        canvas.drawText(xTitle, width / 2f - 40, height - 20f, labelPaint)

        // Y轴标签（亮度）
        for (percent in 0..100 step 20) {
            val y = paddingTop + (1 - percent / 100f) * chartHeight
            canvas.drawText(
                "$percent%",
                10f,
                y + 8f,
                textPaint
            )
        }
        canvas.save()
        canvas.rotate(-90f, 30f, height / 2f)
        canvas.drawText("亮度", 30f, height / 2f, labelPaint)
        canvas.restore()
    }

    /**
     * 绘制拖动时的坐标工具提示
     */
    private fun drawTooltip(canvas: Canvas, point: StreetlightProfile.ControlPoint) {
        val x = minutesToX(point.toMinutes())
        val y = brightnessToY(point.brightness)
        val text = "${point.getTimeString()} ${point.brightness}%"

        val textWidth = tooltipTextPaint.measureText(text)
        val tooltipWidth = textWidth + 24f
        val tooltipHeight = 34f
        val arrowHeight = 8f

        // 显示在控制点上方
        var rectLeft = x - tooltipWidth / 2
        val rectTop = y - pointRadius - tooltipHeight - arrowHeight - 10f
        val rectBottom = rectTop + tooltipHeight

        // 保持在绘图区域内
        val maxLeft = width - paddingRight - tooltipWidth
        if (rectLeft < paddingLeft) rectLeft = paddingLeft
        if (rectLeft > maxLeft) rectLeft = maxLeft

        // 圆角背景
        val rect = RectF(rectLeft, rectTop, rectLeft + tooltipWidth, rectBottom)
        canvas.drawRoundRect(rect, 8f, 8f, tooltipBgPaint)

        // 文字居中
        val textX = rectLeft + (tooltipWidth - textWidth) / 2
        val textY = rectBottom - 9f
        canvas.drawText(text, textX, textY, tooltipTextPaint)

        // 向下箭头（指向控制点）
        val path = Path()
        val arrowTip = x.coerceIn(rectLeft + arrowHeight, rectLeft + tooltipWidth - arrowHeight)
        path.moveTo(arrowTip - arrowHeight, rectBottom)
        path.lineTo(arrowTip, rectBottom + arrowHeight)
        path.lineTo(arrowTip + arrowHeight, rectBottom)
        path.close()
        canvas.drawPath(path, tooltipBgPaint)
    }

    /**
     * 播放选中放大动画
     */
    private fun animateSelect() {
        selectAnimator?.cancel()
        selectAnimator = ValueAnimator.ofFloat(pointRadius, pointRadius * 1.5f).apply {
            duration = 150
            addUpdateListener { anim ->
                selectedRadius = anim.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /**
     * 播放取消选中缩小动画
     */
    private fun animateDeselect() {
        selectAnimator?.cancel()
        selectAnimator = ValueAnimator.ofFloat(selectedRadius, pointRadius).apply {
            duration = 150
            addUpdateListener { anim ->
                selectedRadius = anim.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    // 记录 DOWN 事件坐标，用于判断是否是点击（而非拖动）
    private var downX = 0f
    private var downY = 0f
    private val touchSlop = 8f  // 超过此距离视为拖动（像素）

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y

                val hitIndex = findPointNear(event.x, event.y)
                if (hitIndex >= 0) {
                    // 点击到控制点：选中并准备拖动
                    draggedPointIndex = hitIndex
                    selectedPointIndex = hitIndex
                    isDragging = false
                    lastDraggedPoint = null
                    animateSelect()
                    // 立即禁止父布局拦截，确保拖动流畅
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
                // 点击空白区域，不处理，让父布局 ScrollView 正常滚动
                draggedPointIndex = -1
                return false
            }

            MotionEvent.ACTION_MOVE -> {
                if (draggedPointIndex < 0) {
                    return false
                }

                // 判断是否开始拖动
                if (!isDragging) {
                    val dx = abs(event.x - downX)
                    val dy = abs(event.y - downY)
                    if (dx < touchSlop && dy < touchSlop) {
                        // 还在点击范围内，继续等待
                        return true
                    }
                    // 开始拖动了
                    isDragging = true
                }

                // 确保父布局不拦截
                parent?.requestDisallowInterceptTouchEvent(true)

                val newPoint = createPointFromTouch(event.x, event.y)

                // 约束：不与相邻控制点时间重叠
                if (canMovePoint(draggedPointIndex, newPoint)) {
                    // 节流：如果新点与上次拖动点相同，跳过重绘
                    if (newPoint == lastDraggedPoint) {
                        return true
                    }

                    controlPoints[draggedPointIndex] = newPoint
                    lastDraggedPoint = newPoint
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                val handled = draggedPointIndex >= 0

                // 如果没有拖动（只是点击），触发编辑回调
                if (draggedPointIndex >= 0 && !isDragging) {
                    val point = controlPoints[draggedPointIndex]
                    onPointEdit?.invoke(draggedPointIndex, point)
                }

                draggedPointIndex = -1
                isDragging = false

                // 恢复父布局事件拦截
                parent?.requestDisallowInterceptTouchEvent(false)

                return handled
            }

            MotionEvent.ACTION_CANCEL -> {
                draggedPointIndex = -1
                isDragging = false

                // 恢复父布局事件拦截
                parent?.requestDisallowInterceptTouchEvent(false)

                return false
            }
        }
        return super.onTouchEvent(event)
    }

    /**
     * 查找触摸位置附近的控制点
     */
    private fun findPointNear(x: Float, y: Float): Int {
        for ((index, point) in controlPoints.withIndex()) {
            val px = minutesToX(point.toMinutes())
            val py = brightnessToY(point.brightness)
            val distance = kotlin.math.sqrt((x - px) * (x - px) + (y - py) * (y - py))
            if (distance <= touchRadius) {
                return index
            }
        }
        return -1
    }

    /**
     * 从触摸坐标创建控制点
     */
    private fun createPointFromTouch(x: Float, y: Float): StreetlightProfile.ControlPoint {
        val minutes = xToMinutes(x)
        val brightness = yToBrightness(y)
        return StreetlightProfile.ControlPoint(
            hour = minutes / 60,
            minute = minutes % 60,
            brightness = brightness
        )
    }

    /**
     * 检查是否可以添加新控制点
     */
    private fun canAddPoint(newPoint: StreetlightProfile.ControlPoint): Boolean {
        return controlPoints.none {
            abs(it.toMinutes() - newPoint.toMinutes()) < snapMinutes * 2
        }
    }

    /**
     * 检查是否可以移动控制点到新位置
     */
    private fun canMovePoint(index: Int, newPoint: StreetlightProfile.ControlPoint): Boolean {
        val newMinutes = newPoint.toMinutes()

        // 检查与相邻点的时间间隔
        val sorted = controlPoints.sortedBy { it.toMinutes() }
        val currentIndexInSorted = sorted.indexOf(controlPoints[index])

        // 前一个点
        if (currentIndexInSorted > 0) {
            val prevMinutes = sorted[currentIndexInSorted - 1].toMinutes()
            if (newMinutes <= prevMinutes + snapMinutes) return false
        }

        // 后一个点
        if (currentIndexInSorted < sorted.size - 1) {
            val nextMinutes = sorted[currentIndexInSorted + 1].toMinutes()
            if (newMinutes >= nextMinutes - snapMinutes) return false
        }

        return true
    }

    /**
     * 坐标转换：分钟数 -> X坐标
     * 支持时间范围偏移（夜间聚焦模式）
     */
    private fun minutesToX(minutes: Int): Float {
        var displayMinutes = minutes
        // 夜间模式下，控制点在 rangeStartMinutes 之前的需要加 1440 以正确显示在右侧
        if (displayMinutes < rangeStartMinutes) displayMinutes += 1440
        val ratio = (displayMinutes - rangeStartMinutes).toFloat() / 1440f
        return paddingLeft + ratio * chartWidth
    }

    /**
     * 坐标转换：亮度 -> Y坐标
     */
    private fun brightnessToY(brightness: Int): Float {
        return paddingTop + (1 - brightness / 100f) * chartHeight
    }

    /**
     * 坐标转换：X坐标 -> 分钟数（带吸附）
     * 支持时间范围偏移（夜间聚焦模式）
     */
    private fun xToMinutes(x: Float): Int {
        val ratio = (x - paddingLeft).coerceIn(0f, chartWidth) / chartWidth
        val minutes = (rangeStartMinutes + ratio * 1440f).roundToInt()
        // 映射回 0-1439 范围
        return ((minutes % 1440) / snapMinutes) * snapMinutes
    }

    /**
     * 坐标转换：Y坐标 -> 亮度（带吸附）
     */
    private fun yToBrightness(y: Float): Int {
        val ratio = 1 - (y - paddingTop).coerceIn(0f, chartHeight) / chartHeight
        val brightness = (ratio * 100).roundToInt()
        // 吸附到 5% 粒度
        return (brightness / snapBrightness) * snapBrightness
    }

    /**
     * 添加控制点（供外部按钮调用）
     * 在两个相邻控制点中间插入新点，或追加到末尾
     * @return 是否添加成功
     */
    fun addControlPoint(): Boolean {
        if (controlPoints.size >= 8) return false

        val sorted = controlPoints.sortedBy { it.toMinutes() }

        // 找到最大间隔，在中间插入新点
        var maxGapIndex = 0
        var maxGap = 0
        for (i in 0 until sorted.size - 1) {
            val gap = sorted[i + 1].toMinutes() - sorted[i].toMinutes()
            if (gap > maxGap) {
                maxGap = gap
                maxGapIndex = i
            }
        }

        val midMinutes = (sorted[maxGapIndex].toMinutes() + sorted[maxGapIndex + 1].toMinutes()) / 2
        val midBrightness = (sorted[maxGapIndex].brightness + sorted[maxGapIndex + 1].brightness) / 2
        // 吸附
        val snappedMinutes = (midMinutes / snapMinutes) * snapMinutes
        val snappedBrightness = (midBrightness / snapBrightness) * snapBrightness

        val newPoint = StreetlightProfile.ControlPoint(
            hour = snappedMinutes / 60,
            minute = snappedMinutes % 60,
            brightness = snappedBrightness
        )

        if (!canAddPoint(newPoint)) return false

        controlPoints.add(newPoint)
        controlPoints.sortBy { it.toMinutes() }

        // 选中新添加的点
        selectedPointIndex = controlPoints.indexOfFirst {
            it.hour == newPoint.hour && it.minute == newPoint.minute
        }
        animateSelect()

        invalidate()
        return true
    }

    /**
     * 删除指定索引的控制点
     */
    fun removePoint(index: Int) {
        if (index in controlPoints.indices && controlPoints.size > 2) {
            controlPoints.removeAt(index)
            if (selectedPointIndex == index) {
                selectedPointIndex = -1
                animateDeselect()
            } else if (selectedPointIndex > index) {
                selectedPointIndex--
            }
            onPointRemoved?.invoke(index)
            invalidate()
        }
    }

    /**
     * 获取当前控制点列表（已排序）
     */
    fun getSortedPoints(): List<StreetlightProfile.ControlPoint> {
        return controlPoints.sortedBy { it.toMinutes() }
    }

    /**
     * 取消选中状态
     */
    fun clearSelection() {
        if (selectedPointIndex >= 0) {
            animateDeselect()
            selectedPointIndex = -1
        }
        lastDraggedPoint = null
    }

    /**
     * 设置夜间聚焦模式
     * 开启后 X 轴起始点设为 12:00，夜间时段（18:00~06:00）居中展开便于编辑
     */
    fun setNightMode(enabled: Boolean) {
        if (nightModeEnabled != enabled) {
            nightModeEnabled = enabled
            rangeStartMinutes = if (enabled) 720 else 0
            invalidate()
        }
    }

    /**
     * 当前是否为夜间聚焦模式
     */
    fun isNightMode(): Boolean = nightModeEnabled

    /**
     * 更新指定索引的控制点
     * @param index 控制点索引
     * @param newPoint 新的控制点值
     * @return 是否更新成功
     */
    fun updatePoint(index: Int, newPoint: StreetlightProfile.ControlPoint): Boolean {
        if (index !in controlPoints.indices) return false

        // 检查新位置是否合法（不与相邻点重叠）
        val sorted = controlPoints.sortedBy { it.toMinutes() }
        val currentIndexInSorted = sorted.indexOf(controlPoints[index])

        val newMinutes = newPoint.toMinutes()

        // 前一个点
        if (currentIndexInSorted > 0) {
            val prevMinutes = sorted[currentIndexInSorted - 1].toMinutes()
            if (newMinutes <= prevMinutes + snapMinutes) return false
        }

        // 后一个点
        if (currentIndexInSorted < sorted.size - 1) {
            val nextMinutes = sorted[currentIndexInSorted + 1].toMinutes()
            if (newMinutes >= nextMinutes - snapMinutes) return false
        }

        controlPoints[index] = newPoint
        controlPoints.sortBy { it.toMinutes() }

        // 更新选中状态到新位置
        selectedPointIndex = controlPoints.indexOfFirst {
            it.hour == newPoint.hour && it.minute == newPoint.minute && it.brightness == newPoint.brightness
        }

        invalidate()
        return true
    }
}

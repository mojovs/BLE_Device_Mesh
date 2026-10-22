package com.example.ble_device_mesh

import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.ble_device_mesh.data.SchedulerTask
import com.example.ble_device_mesh.data.StreetlightPreset
import com.example.ble_device_mesh.data.StreetlightPresetRepository
import com.example.ble_device_mesh.data.StreetlightProfile
import com.example.ble_device_mesh.ui.StreetlightCurveView

/**
 * 路灯模式配置页面
 * 通过时间-亮度曲线图设置灯光随时间的变化规律
 */
class StreetlightModeActivity : ComponentActivity() {

    private val viewModel: MeshViewModel by viewModels()
    private lateinit var presetRepository: StreetlightPresetRepository
    private var deviceAddress: Int = 0
    private var deviceName: String = ""
    private var profile = StreetlightProfile.createDefault(0)

    // 设备回读的调度任务（槽位 0-7 属于路灯曲线）
    private val deviceTasks = java.util.TreeMap<Int, SchedulerTask>()
    // 下发期间忽略回读结果，避免开关被中间状态来回改写
    private var suppressDeviceStateUntil = 0L
    private val applyDeviceTasksRunnable = Runnable { applyDeviceTasksToUi() }

    private lateinit var curveView: StreetlightCurveView
    private lateinit var switchEnabled: Switch
    private lateinit var recyclerPoints: RecyclerView
    private lateinit var tvStatus: TextView
    private lateinit var adapter: ControlPointAdapter
    private lateinit var btnAddPoint: Button
    private lateinit var btnToggleNightMode: Button

    companion object {
        const val EXTRA_DEVICE_ADDRESS = "extra_device_address"
        const val EXTRA_DEVICE_NAME = "extra_device_name"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_streetlight_mode)

        deviceAddress = intent.getIntExtra(EXTRA_DEVICE_ADDRESS, 0)
        deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME) ?: "设备"
        presetRepository = StreetlightPresetRepository(this)
        profile = StreetlightProfile.createDefault(deviceAddress)

        initViews()
        setupListeners()
        loadFromDevice()
    }

    private fun initViews() {
        // 标题
        findViewById<TextView>(R.id.tvTitle).text = "$deviceName 定时曲线"

        // 返回按钮
        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }

        // 曲线编辑器
        curveView = findViewById(R.id.curveView)
        curveView.controlPoints = profile.controlPoints.toMutableList()

        // 启用开关
        switchEnabled = findViewById(R.id.switchEnabled)
        switchEnabled.isChecked = profile.enabled

        // 状态文本
        tvStatus = findViewById(R.id.tvStatus)
        updateStatus()

        // 控制点列表
        recyclerPoints = findViewById(R.id.recyclerPoints)
        recyclerPoints.layoutManager = LinearLayoutManager(this)
        adapter = ControlPointAdapter(
            points = curveView.getSortedPoints().toMutableList(),
            onDelete = { index -> deletePoint(index) },
            onEdit = { index -> editControlPoint(index) }
        )
        recyclerPoints.adapter = adapter

        // 添加控制点按钮
        btnAddPoint = findViewById(R.id.btnAddPoint)
        btnAddPoint.setOnClickListener { addControlPoint() }

        // 夜间聚焦按钮
        btnToggleNightMode = findViewById(R.id.btnToggleNightMode)
        updateNightModeButton()

        // 加载/保存曲线预设
        findViewById<Button>(R.id.btnSavePreset).setOnClickListener { saveCurrentAsPreset() }
        findViewById<Button>(R.id.btnLoadPreset).setOnClickListener { showLoadPresetDialog() }

        // 按钮
        findViewById<Button>(R.id.btnSendToDevice).setOnClickListener { sendToDevice() }
        findViewById<Button>(R.id.btnResetDefault).setOnClickListener { resetToDefault() }

        setupDeviceStateObservers()
    }

    /**
     * 观察设备回读的调度状态：
     * 1) SchedulerStatus 给出 16 位槽位 bitmap；
     * 2) 随后 App 会逐个读取已设置槽位的详情，通过 schedulerTaskUpdates 送达。
     * 槽位 0-7 即路灯曲线的控制点，据此恢复开关与曲线，而不是只看本地缓存。
     */
    private fun setupDeviceStateObservers() {
        viewModel.schedulerUpdates.observe(this) { (src, bitmap) ->
            if (src != deviceAddress) return@observe
            if (isDeviceStateSuppressed()) return@observe

            val slotCount = (0..7).count { (bitmap and (1 shl it)) != 0 }
            Log.d("Streetlight", "设备回读 bitmap=0x${bitmap.toString(16)}, 路灯槽位(0-7)数量=$slotCount")

            if (slotCount == 0) {
                // 设备上没有任何路灯槽位 → 曲线未启用（保留本地点位供下次下发）
                deviceTasks.clear()
                tvStatus.removeCallbacks(applyDeviceTasksRunnable)
                applyCurveEnabled(false)
            } else {
                // 只要槽位 0-7 有任务，曲线就是启用状态。
                // 不等详情解析完成，避免详情读取失败时开关一直显示关闭。
                applyCurveEnabled(true)
            }
        }

        viewModel.schedulerTaskUpdates.observe(this) { task ->
            if (task.deviceAddress != deviceAddress) return@observe
            if (task.index !in 0..7) return@observe
            if (isDeviceStateSuppressed()) return@observe

            deviceTasks[task.index] = task
            // 任务是一条一条读回来的，等这一批读完再统一刷新界面
            tvStatus.removeCallbacks(applyDeviceTasksRunnable)
            tvStatus.postDelayed(applyDeviceTasksRunnable, 700)
        }
    }

    private fun isDeviceStateSuppressed(): Boolean =
        SystemClock.elapsedRealtime() < suppressDeviceStateUntil

    /**
     * 用设备回读的任务重建曲线。
     * 需要至少 2 个有效槽位才认为是"路灯曲线"，避免把单个普通定时任务误判为曲线。
     */
    private fun applyDeviceTasksToUi() {
        if (isFinishing || isDestroyed) return
        if (isDeviceStateSuppressed()) return

        val parsed = StreetlightProfile.fromSchedulerTasks(
            deviceTasks.values.toList(), deviceAddress
        ) ?: return

        // 解析时已把设备里的 OC6701 补偿值反算成 UI 百分比，
        // 统一按 06:00 为起点排序，兼容旧版本按 00:00 下发到槽位的顺序。
        val deviceRaw = parsed.controlPoints
        val devicePoints = deviceRaw.sortedBy { it.sortKey() }
        if (devicePoints.size < 2) return

        // 如果设备上的任务就是本地曲线下发的结果，保留本地 UI 亮度值，
        // 避免多对一映射反算带来的 ±1% 显示偏差。
        val localPoints = profile.controlPoints.sortedBy { it.sortKey() }
        val deviceMatchesLocal = localPoints.size == deviceRaw.size &&
            localPoints.all { local ->
                val device = deviceRaw.firstOrNull {
                    it.hour == local.hour && it.minute == local.minute
                } ?: return@all false
                MeshViewModel.mapBrightnessForOC6701(local.brightness) ==
                    MeshViewModel.mapBrightnessForOC6701(device.brightness)
            }

        val points = if (deviceMatchesLocal) localPoints else devicePoints

        profile = StreetlightProfile(
            deviceAddress = deviceAddress,
            enabled = parsed.enabled,
            controlPoints = points
        )

        curveView.controlPoints = points.toMutableList()
        adapter.updatePoints(points)
        if (switchEnabled.isChecked != parsed.enabled) {
            switchEnabled.isChecked = parsed.enabled
        }
        updateStatus()
        persistProfile()

        Log.d("Streetlight", "从设备恢复曲线: enabled=${parsed.enabled}, points=${profile.getDescription()}")
    }

    /** 只同步开关状态（不覆盖点位） */
    private fun applyCurveEnabled(enabled: Boolean) {
        if (switchEnabled.isChecked == enabled) return
        switchEnabled.isChecked = enabled
        persistProfile()
        Log.d("Streetlight", "设备回读: 曲线 ${if (enabled) "已启用" else "未启用"}")
    }

    private fun persistProfile() {
        val prefs = getSharedPreferences("StreetlightPrefs", MODE_PRIVATE)
        prefs.edit().apply {
            putBoolean("enabled_$deviceAddress", profile.enabled)
            putInt("point_count_$deviceAddress", profile.controlPoints.size)
            profile.controlPoints.forEachIndexed { index, point ->
                putInt("point_${deviceAddress}_${index}_hour", point.hour)
                putInt("point_${deviceAddress}_${index}_minute", point.minute)
                putInt("point_${deviceAddress}_${index}_brightness", point.brightness)
            }
            apply()
        }
    }

    private fun setupListeners() {
        // 点击曲线上的控制点编辑
        curveView.onPointEdit = { index, point ->
            editControlPoint(index)
        }

        // 删除控制点回调
        curveView.onPointRemoved = { index ->
            refreshListFromCurve()
            updateStatus()
        }

        // 夜间聚焦模式切换
        btnToggleNightMode.setOnClickListener {
            curveView.setNightMode(!curveView.isNightMode())
            updateNightModeButton()
            curveView.clearSelection()
        }

        // 启用开关
        switchEnabled.setOnCheckedChangeListener { _, isChecked ->
            profile = profile.copy(enabled = isChecked)
            persistProfile()
            updateStatus()
        }
    }

    /**
     * 从曲线视图同步刷新控制点列表。
     * 页面不再有独立的“保存应用”按钮，曲线一变就写回本地缓存，
     * 避免编辑后没点下发就退出导致改动丢失。
     */
    private fun refreshListFromCurve() {
        val sorted = curveView.getSortedPoints()
        adapter.updatePoints(sorted)
        profile = profile.copy(controlPoints = sorted, enabled = switchEnabled.isChecked)
        persistProfile()
    }

    private fun updateStatus() {
        val sorted = curveView.getSortedPoints()
        if (sorted.size < 2) {
            tvStatus.text = "请至少设置 2 个控制点"
            tvStatus.setTextColor(getColor(android.R.color.holo_red_dark))
            return
        }

        val desc = sorted.joinToString(" → ") {
            "${it.getTimeString()} ${it.brightness}%"
        }
        tvStatus.text = desc
        tvStatus.setTextColor(getColor(android.R.color.darker_gray))
    }

    private fun updateNightModeButton() {
        val nightMode = curveView.isNightMode()
        btnToggleNightMode.text = if (nightMode) "全天" else "聚焦夜间"
        btnToggleNightMode.backgroundTintList = ColorStateList.valueOf(
            if (nightMode) Color.parseColor("#757575") else Color.parseColor("#7B1FA2")
        )
    }

    private fun addControlPoint() {
        if (!curveView.addControlPoint()) {
            if (curveView.controlPoints.size >= 8) {
                Toast.makeText(this, "控制点不能超过 8 个", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "无法添加控制点，间距不足", Toast.LENGTH_SHORT).show()
            }
        } else {
            // 添加后立即刷新列表
            refreshListFromCurve()
            updateStatus()
            Toast.makeText(this, "已添加控制点，拖动或点击编辑", Toast.LENGTH_SHORT).show()
        }
    }

    private fun editControlPoint(index: Int) {
        val point = curveView.controlPoints.getOrNull(index) ?: return

        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_control_point, null)
        val timePicker = dialogView.findViewById<TimePicker>(R.id.timePicker)
        val seekBarBrightness = dialogView.findViewById<SeekBar>(R.id.seekBarBrightness)
        val tvBrightnessValue = dialogView.findViewById<TextView>(R.id.tvBrightnessValue)

        // 设置当前值
        timePicker.setIs24HourView(true)
        timePicker.hour = point.hour
        timePicker.minute = point.minute
        seekBarBrightness.progress = point.brightness
        tvBrightnessValue.text = "${point.brightness}%"

        // 亮度滑块监听
        seekBarBrightness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvBrightnessValue.text = "$progress%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        AlertDialog.Builder(this)
            .setTitle("编辑控制点")
            .setView(dialogView)
            .setPositiveButton("确定") { _, _ ->
                val newHour = timePicker.hour
                val newMinute = timePicker.minute
                val newBrightness = seekBarBrightness.progress

                val newPoint = StreetlightProfile.ControlPoint(newHour, newMinute, newBrightness)

                // 更新曲线视图
                if (curveView.updatePoint(index, newPoint)) {
                    // 刷新列表
                    refreshListFromCurve()
                    updateStatus()
                    Toast.makeText(this, "已更新控制点", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "更新失败，时间与其他控制点冲突", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deletePoint(index: Int) {
        if (curveView.controlPoints.size <= 2) {
            Toast.makeText(this, "至少需要 2 个控制点", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("删除控制点")
            .setMessage("确定删除此控制点？")
            .setPositiveButton("删除") { _, _ ->
                curveView.removePoint(index)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun loadFromDevice() {
        // 先清掉上一次可能残留的设备回读状态
        deviceTasks.clear()
        suppressDeviceStateUntil = 0L
        if (::tvStatus.isInitialized) {
            tvStatus.removeCallbacks(applyDeviceTasksRunnable)
        }

        // 从本地加载
        val prefs = getSharedPreferences("StreetlightPrefs", MODE_PRIVATE)
        val enabled = prefs.getBoolean("enabled_$deviceAddress", false)
        val pointCount = prefs.getInt("point_count_$deviceAddress", 0)

        if (pointCount >= 2) {
            val points = mutableListOf<StreetlightProfile.ControlPoint>()
            for (i in 0 until pointCount) {
                val hour = prefs.getInt("point_${deviceAddress}_${i}_hour", 0)
                val minute = prefs.getInt("point_${deviceAddress}_${i}_minute", 0)
                val brightness = prefs.getInt("point_${deviceAddress}_${i}_brightness", 0)
                points.add(StreetlightProfile.ControlPoint(hour, minute, brightness))
            }
            profile = StreetlightProfile(deviceAddress, enabled, points)
            curveView.controlPoints = points
            switchEnabled.isChecked = enabled
            adapter.updatePoints(curveView.getSortedPoints())
            updateStatus()
        }

        // 从设备读取当前 Scheduler 状态
        if (viewModel.isConnected.value == true) {
            viewModel.readScheduler(deviceAddress)
        }
    }

    private fun sendToDevice() {
        if (viewModel.isConnected.value != true) {
            Toast.makeText(this, "请先连接设备", Toast.LENGTH_SHORT).show()
            return
        }

        curveView.clearSelection()

        profile = profile.copy(
            controlPoints = curveView.getSortedPoints(),
            enabled = switchEnabled.isChecked
        )

        // 只在启用时才校验点位；关闭曲线不需要点位
        if (profile.enabled) {
            if (profile.controlPoints.size < 2) {
                Toast.makeText(this, "至少需要 2 个控制点", Toast.LENGTH_SHORT).show()
                return
            }
            if (profile.controlPoints.size > 8) {
                Toast.makeText(this, "控制点不能超过 8 个", Toast.LENGTH_SHORT).show()
                return
            }
        }

        // 下发期间及回读确认期内，忽略设备状态回读，避免开关被中间状态改写
        suppressDeviceStateUntil = SystemClock.elapsedRealtime() + 10_000
        deviceTasks.clear()
        tvStatus.removeCallbacks(applyDeviceTasksRunnable)

        // 先清除槽位 0-7
        Toast.makeText(this, "正在下发配置...", Toast.LENGTH_SHORT).show()

        for (i in 0..7) {
            val emptyTask = SchedulerTask(
                index = i,
                hour = 0,
                minute = 0,
                second = 0,
                action = SchedulerTask.Action.NO_ACTION,
                brightness = 0,
                repeat = 0,
                enabled = false,
                deviceAddress = deviceAddress
            )
            viewModel.setSchedulerTask(deviceAddress, emptyTask)
        }

        // 启用时才下发控制点；关闭则只清空槽位
        if (profile.enabled) {
            val tasks = profile.toSchedulerTasks()
            tasks.forEach { task ->
                viewModel.setSchedulerTask(deviceAddress, task)
            }
            // 三种工作模式互斥：下发定时曲线时同步选中"定时"模式，
            // 否则任务已写入设备也不会执行
            viewModel.sendWorkMode(deviceAddress, MeshViewModel.WORK_MODE_TIMER)
        }

        persistProfile()

        // 刷新控制点列表
        refreshListFromCurve()
        updateStatus()

        Toast.makeText(
            this,
            if (profile.enabled) "定时曲线已下发到设备" else "定时曲线已关闭（设备槽位已清空）",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun resetToDefault() {
        AlertDialog.Builder(this)
            .setTitle("恢复默认")
            .setMessage("确定恢复默认配置？当前设置将丢失。")
            .setPositiveButton("恢复") { _, _ ->
                profile = StreetlightProfile.createDefault(deviceAddress)
                curveView.controlPoints = profile.controlPoints.toMutableList()
                switchEnabled.isChecked = profile.enabled
                adapter.updatePoints(curveView.getSortedPoints())
                persistProfile()
                updateStatus()
                Toast.makeText(this, "已恢复默认配置", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun saveCurrentAsPreset() {
        curveView.clearSelection()
        val points = curveView.getSortedPoints()
        if (points.size < 2) {
            Toast.makeText(this, "请至少设置 2 个控制点", Toast.LENGTH_SHORT).show()
            return
        }
        if (points.size > 8) {
            Toast.makeText(this, "控制点不能超过 8 个", Toast.LENGTH_SHORT).show()
            return
        }

        val input = EditText(this).apply {
            hint = "预设名称"
            setText("路灯曲线")
            selectAll()
        }

        AlertDialog.Builder(this)
            .setTitle("保存为预设")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, "请输入预设名称", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                presetRepository.savePreset(
                    StreetlightPreset(
                        name = name,
                        controlPoints = points,
                        createdAt = System.currentTimeMillis()
                    )
                )
                Toast.makeText(this, "预设已保存", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showLoadPresetDialog() {
        val presets = presetRepository.getPresets()
        if (presets.isEmpty()) {
            Toast.makeText(this, "暂无预设", Toast.LENGTH_SHORT).show()
            return
        }

        // 每条预设用不同的淡色底栏区分，避免多条预设挤在一起看不清
        val rowColors = intArrayOf(
            0xFFFFF4E5.toInt(), // 淡橙
            0xFFE8F5E9.toInt(), // 淡绿
            0xFFE3F2FD.toInt(), // 淡蓝
            0xFFF3E5F5.toInt(), // 淡紫
            0xFFFFF8E1.toInt()  // 淡黄
        )
        val cornerRadius = 10 * resources.displayMetrics.density
        val density = resources.displayMetrics.density

        val adapter = object : BaseAdapter() {
            override fun getCount(): Int = presets.size
            override fun getItem(position: Int): Any = presets[position]
            override fun getItemId(position: Int): Long = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView
                    ?: layoutInflater.inflate(R.layout.item_streetlight_preset, parent, false)
                val preset = presets[position]

                view.findViewById<View>(R.id.presetRow).apply {
                    background = GradientDrawable().apply {
                        this.cornerRadius = cornerRadius
                        setColor(rowColors[position % rowColors.size])
                    }
                    setPadding(
                        (14 * density).toInt(),
                        (10 * density).toInt(),
                        (14 * density).toInt(),
                        (10 * density).toInt()
                    )
                }
                view.findViewById<TextView>(R.id.tvPresetName).text = preset.name
                view.findViewById<TextView>(R.id.tvPresetDesc).text = preset.getDescription()
                return view
            }
        }

        AlertDialog.Builder(this)
            .setTitle("加载预设")
            .setAdapter(adapter) { _, which ->
                val preset = presets[which]
                curveView.clearSelection()
                curveView.controlPoints = preset.controlPoints.toMutableList()
                refreshListFromCurve()
                updateStatus()
                Toast.makeText(this, "已加载预设：${preset.name}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 控制点列表适配器
     */
    inner class ControlPointAdapter(
        private val points: MutableList<StreetlightProfile.ControlPoint>,
        private val onDelete: (Int) -> Unit,
        private val onEdit: (Int) -> Unit
    ) : RecyclerView.Adapter<ControlPointAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvTime: TextView = view.findViewById(R.id.tvTime)
            val tvBrightness: TextView = view.findViewById(R.id.tvBrightness)
            val btnEdit: Button = view.findViewById(R.id.btnEdit)
            val btnDelete: Button = view.findViewById(R.id.btnDelete)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = layoutInflater.inflate(R.layout.item_control_point, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val point = points[position]
            holder.tvTime.text = point.getTimeString()
            holder.tvBrightness.text = "${point.brightness}%"
            holder.btnEdit.setOnClickListener { onEdit(position) }
            holder.btnDelete.setOnClickListener { onDelete(position) }
        }

        override fun getItemCount() = points.size

        fun removePoint(index: Int) {
            if (index in points.indices) {
                points.removeAt(index)
                notifyItemRemoved(index)
            }
        }

        fun updatePoints(newPoints: List<StreetlightProfile.ControlPoint>) {
            val oldSize = points.size
            points.clear()
            points.addAll(newPoints)
            if (oldSize == 0) {
                notifyItemRangeInserted(0, points.size)
            } else {
                notifyDataSetChanged()
            }
        }
    }
}

package com.example.ble_device_mesh

import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
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
            points = profile.controlPoints.toMutableList(),
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

        // 加载路灯曲线按钮
        findViewById<Button>(R.id.btnLoadStreetlight).setOnClickListener { loadStreetlightCurve() }
        findViewById<Button>(R.id.btnSavePreset).setOnClickListener { saveCurrentAsPreset() }
        findViewById<Button>(R.id.btnLoadPreset).setOnClickListener { showLoadPresetDialog() }

        // 按钮
        findViewById<Button>(R.id.btnSave).setOnClickListener { saveProfile() }
        findViewById<Button>(R.id.btnSendToDevice).setOnClickListener { sendToDevice() }
        findViewById<Button>(R.id.btnResetDefault).setOnClickListener { resetToDefault() }
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
            updateStatus()
        }
    }

    /**
     * 从曲线视图同步刷新控制点列表
     */
    private fun refreshListFromCurve() {
        val sorted = curveView.getSortedPoints()
        adapter.updatePoints(sorted)
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

    private fun saveProfile() {
        curveView.clearSelection()

        profile = profile.copy(
            controlPoints = curveView.getSortedPoints(),
            enabled = switchEnabled.isChecked
        )

        // 保存到本地 SharedPreferences
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

        // 刷新控制点列表
        refreshListFromCurve()
        updateStatus()

        Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show()
    }

    private fun loadFromDevice() {
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
            adapter.updatePoints(points)
            updateStatus()
        }

        // 从设备读取当前 Scheduler 状态
        if (viewModel.isConnected.value == true) {
            viewModel.readScheduler(deviceAddress)
        }
    }

    private fun sendToDevice() {
        if (!viewModel.isConnected.value!!) {
            Toast.makeText(this, "请先连接设备", Toast.LENGTH_SHORT).show()
            return
        }

        curveView.clearSelection()

        profile = profile.copy(
            controlPoints = curveView.getSortedPoints(),
            enabled = switchEnabled.isChecked
        )

        if (profile.controlPoints.size < 2) {
            Toast.makeText(this, "至少需要 2 个控制点", Toast.LENGTH_SHORT).show()
            return
        }

        if (profile.controlPoints.size > 8) {
            Toast.makeText(this, "控制点不能超过 8 个", Toast.LENGTH_SHORT).show()
            return
        }

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

        // 下发新的控制点
        val tasks = profile.toSchedulerTasks(MeshViewModel.Companion::mapBrightnessForOC6701)
        tasks.forEach { task ->
            viewModel.setSchedulerTask(deviceAddress, task)
        }

        // 刷新控制点列表
        refreshListFromCurve()
        updateStatus()

        Toast.makeText(this, "定时曲线已下发到设备", Toast.LENGTH_SHORT).show()
    }

    private fun resetToDefault() {
        AlertDialog.Builder(this)
            .setTitle("恢复默认")
            .setMessage("确定恢复默认配置？当前设置将丢失。")
            .setPositiveButton("恢复") { _, _ ->
                profile = StreetlightProfile.createDefault(deviceAddress)
                curveView.controlPoints = profile.controlPoints.toMutableList()
                switchEnabled.isChecked = profile.enabled
                adapter.updatePoints(profile.controlPoints)
                updateStatus()
                Toast.makeText(this, "已恢复默认配置", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 加载路灯曲线预设
     * 18:00 100% → 20:00 60% → 22:00 30% → 23:00 10% → 06:00 0%
     */
    private fun loadStreetlightCurve() {
        AlertDialog.Builder(this)
            .setTitle("加载路灯曲线")
            .setMessage("将加载预设的路灯曲线（傍晚亮灯→深夜渐暗→清晨关闭），是否继续？")
            .setPositiveButton("加载") { _, _ ->
                val streetlightPoints = listOf(
                    StreetlightProfile.ControlPoint(18, 0, 100),  // 18:00 100%
                    StreetlightProfile.ControlPoint(20, 0, 60),   // 20:00 60%
                    StreetlightProfile.ControlPoint(22, 0, 30),   // 22:00 30%
                    StreetlightProfile.ControlPoint(23, 0, 10),   // 23:00 10%
                    StreetlightProfile.ControlPoint(6, 0, 0)      // 06:00 关灯
                )

                curveView.controlPoints = streetlightPoints.toMutableList()
                refreshListFromCurve()
                updateStatus()
                Toast.makeText(this, "已加载路灯曲线，可继续编辑", Toast.LENGTH_SHORT).show()
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

        val items = presets.map { "${it.name}\n${it.getDescription()}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("加载预设")
            .setItems(items) { _, which ->
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

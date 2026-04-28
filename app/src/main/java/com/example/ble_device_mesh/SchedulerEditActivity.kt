package com.example.ble_device_mesh

import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.DatePicker
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.TimePicker
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import com.example.ble_device_mesh.data.SchedulerRepository
import com.example.ble_device_mesh.data.SchedulerTask

/**
 * 定时任务编辑页面
 * 支持设置时间、动作、亮度、重复规则、启用状态
 */
class SchedulerEditActivity : ComponentActivity() {

    private val viewModel: MeshViewModel by viewModels()
    private lateinit var schedulerRepository: SchedulerRepository
    private var deviceAddress: Int = 0
    private var taskIndex: Int = 0
    private var existingTask: SchedulerTask? = null
    private var currentRepeat: Int = 0x7F // 默认每天

    companion object {
        const val EXTRA_DEVICE_ADDRESS = "extra_device_address"
        const val EXTRA_DEVICE_NAME = "extra_device_name"
        const val EXTRA_TASK_INDEX = "extra_task_index"
        const val EXTRA_TASK = "extra_task"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scheduler_edit)

        deviceAddress = intent.getIntExtra(EXTRA_DEVICE_ADDRESS, 0)
        taskIndex = intent.getIntExtra(EXTRA_TASK_INDEX, 0)

        schedulerRepository = SchedulerRepository(this)

        // 获取现有任务（编辑模式）
        existingTask = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_TASK, SchedulerTask::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_TASK)
        }

        // 返回按钮
        findViewById<TextView>(R.id.btnBack).setOnClickListener {
            finish()
        }

        // 设置标题
        findViewById<TextView>(R.id.tvTitle).text =
            if (existingTask != null) "编辑定时任务 #${taskIndex}" else "添加定时任务 #${taskIndex}"

        // 初始化各控件
        setupTimePicker()
        setupActionControls()
        setupRepeatControls()
        setupEnabledSwitch()
        setupButtons()

        // 如果是编辑模式，填充现有数据
        existingTask?.let { task ->
            populateFromTask(task)
        }
    }

    private fun setupTimePicker() {
        val timePicker = findViewById<TimePicker>(R.id.timePicker)
        timePicker.setIs24HourView(true)

        if (existingTask == null) {
            // 新建模式：默认当前时间
            val cal = java.util.Calendar.getInstance()
            timePicker.hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
            timePicker.minute = cal.get(java.util.Calendar.MINUTE)
        }
    }

    private fun setupActionControls() {
        val radioGroup = findViewById<RadioGroup>(R.id.radioGroupAction)
        val layoutBrightness = findViewById<LinearLayout>(R.id.layoutBrightness)
        val seekBar = findViewById<SeekBar>(R.id.seekBarBrightness)
        val tvValue = findViewById<TextView>(R.id.tvBrightnessValue)

        // 动作切换时显示/隐藏亮度控制
        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            layoutBrightness.visibility =
                if (checkedId == R.id.radioOn) View.VISIBLE else View.GONE
        }

        // 亮度调节
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvValue.text = "$progress%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun setupRepeatControls() {
        val btnEveryDay = findViewById<Button>(R.id.btnRepeatEveryDay)
        val btnWeekday = findViewById<Button>(R.id.btnRepeatWeekday)
        val btnWeekend = findViewById<Button>(R.id.btnRepeatWeekend)
        val btnOnce = findViewById<Button>(R.id.btnRepeatOnce)
        val layoutWeekdays = findViewById<LinearLayout>(R.id.layoutWeekdays)
        val layoutDate = findViewById<LinearLayout>(R.id.layoutDate)

        val checkboxes = listOf(
            findViewById<CheckBox>(R.id.cbSun),  // bit0
            findViewById<CheckBox>(R.id.cbMon),  // bit1
            findViewById<CheckBox>(R.id.cbTue),  // bit2
            findViewById<CheckBox>(R.id.cbWed),  // bit3
            findViewById<CheckBox>(R.id.cbThu),  // bit4
            findViewById<CheckBox>(R.id.cbFri),  // bit5
            findViewById<CheckBox>(R.id.cbSat),  // bit6
        )

        // 快捷选择按钮
        btnEveryDay.setOnClickListener {
            currentRepeat = 0x7F
            checkboxes.forEach { it.isChecked = true }
            layoutDate.visibility = View.GONE
        }

        btnWeekday.setOnClickListener {
            currentRepeat = 0x3E
            checkboxes.forEachIndexed { i, cb ->
                cb.isChecked = i in 1..5 // 周一到周五
            }
            layoutDate.visibility = View.GONE
        }

        btnWeekend.setOnClickListener {
            currentRepeat = 0x41
            checkboxes.forEachIndexed { i, cb ->
                cb.isChecked = i == 0 || i == 6 // 周日和周六
            }
            layoutDate.visibility = View.GONE
        }

        btnOnce.setOnClickListener {
            currentRepeat = 0x00
            checkboxes.forEach { it.isChecked = false }
            layoutDate.visibility = View.VISIBLE

            // 设置默认日期为明天
            val cal = java.util.Calendar.getInstance()
            cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
            val datePicker = findViewById<DatePicker>(R.id.datePicker)
            datePicker.updateDate(
                cal.get(java.util.Calendar.YEAR),
                cal.get(java.util.Calendar.MONTH),
                cal.get(java.util.Calendar.DAY_OF_MONTH)
            )
        }

        // 星期复选框变化时更新 repeat 值
        checkboxes.forEachIndexed { i, cb ->
            cb.setOnCheckedChangeListener { _, _ ->
                currentRepeat = 0
                checkboxes.forEachIndexed { j, checkbox ->
                    if (checkbox.isChecked) {
                        currentRepeat = currentRepeat or (1 shl j)
                    }
                }
                // 更新日期选择器可见性
                layoutDate.visibility = if (currentRepeat == 0x00) View.VISIBLE else View.GONE
            }
        }

        // 默认选中"每天"
        btnEveryDay.performClick()
    }

    private fun setupEnabledSwitch() {
        // 默认启用
    }

    private fun setupButtons() {
        findViewById<Button>(R.id.btnCancel).setOnClickListener {
            finish()
        }

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            saveTask()
        }
    }

    private fun populateFromTask(task: SchedulerTask) {
        // 时间
        val timePicker = findViewById<TimePicker>(R.id.timePicker)
        timePicker.hour = task.hour
        timePicker.minute = task.minute

        // 动作
        val radioGroup = findViewById<RadioGroup>(R.id.radioGroupAction)
        when (task.action) {
            SchedulerTask.Action.ON -> radioGroup.check(R.id.radioOn)
            SchedulerTask.Action.OFF -> radioGroup.check(R.id.radioOff)
            else -> radioGroup.check(R.id.radioOff)
        }

        // 亮度
        val seekBar = findViewById<SeekBar>(R.id.seekBarBrightness)
        val tvValue = findViewById<TextView>(R.id.tvBrightnessValue)
        seekBar.progress = task.brightness
        tvValue.text = "${task.brightness}%"

        // 重复规则
        currentRepeat = task.repeat
        val checkboxes = listOf(
            findViewById<CheckBox>(R.id.cbSun),
            findViewById<CheckBox>(R.id.cbMon),
            findViewById<CheckBox>(R.id.cbTue),
            findViewById<CheckBox>(R.id.cbWed),
            findViewById<CheckBox>(R.id.cbThu),
            findViewById<CheckBox>(R.id.cbFri),
            findViewById<CheckBox>(R.id.cbSat),
        )
        checkboxes.forEachIndexed { i, cb ->
            cb.isChecked = (task.repeat and (1 shl i)) != 0
        }

        // 日期（一次性任务）
        val layoutDate = findViewById<LinearLayout>(R.id.layoutDate)
        layoutDate.visibility = if (task.repeat == 0x00 && task.year > 0) View.VISIBLE else View.GONE
        if (task.year > 0 && task.month > 0 && task.day > 0) {
            val datePicker = findViewById<DatePicker>(R.id.datePicker)
            datePicker.updateDate(task.year, task.month - 1, task.day)
        }

        // 启用状态
        val switchEnabled = findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchEnabled)
        switchEnabled.isChecked = task.enabled
    }

    private fun saveTask() {
        val timePicker = findViewById<TimePicker>(R.id.timePicker)
        val radioGroup = findViewById<RadioGroup>(R.id.radioGroupAction)
        val seekBar = findViewById<SeekBar>(R.id.seekBarBrightness)
        val switchEnabled = findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchEnabled)

        val hour = timePicker.hour
        val minute = timePicker.minute
        val action = if (radioGroup.checkedRadioButtonId == R.id.radioOn)
            SchedulerTask.Action.ON else SchedulerTask.Action.OFF
        val brightness = seekBar.progress
        val enabled = switchEnabled.isChecked

        // 读取当前 repeat 值（从 checkboxes 计算）
        val checkboxes = listOf(
            findViewById<CheckBox>(R.id.cbSun),
            findViewById<CheckBox>(R.id.cbMon),
            findViewById<CheckBox>(R.id.cbTue),
            findViewById<CheckBox>(R.id.cbWed),
            findViewById<CheckBox>(R.id.cbThu),
            findViewById<CheckBox>(R.id.cbFri),
            findViewById<CheckBox>(R.id.cbSat),
        )
        var repeat = 0
        checkboxes.forEachIndexed { i, cb ->
            if (cb.isChecked) repeat = repeat or (1 shl i)
        }

        // 日期（一次性任务）
        var year = 0
        var month = 0
        var day = 0
        if (repeat == 0x00) {
            val datePicker = findViewById<DatePicker>(R.id.datePicker)
            year = datePicker.year
            month = datePicker.month + 1
            day = datePicker.dayOfMonth
        }

        // 验证
        if (repeat == 0x00 && (year == 0 || month == 0 || day == 0)) {
            Toast.makeText(this, "一次性任务需要选择日期", Toast.LENGTH_SHORT).show()
            return
        }

        val task = SchedulerTask(
            index = taskIndex,
            hour = hour,
            minute = minute,
            action = action,
            brightness = brightness,
            repeat = repeat,
            enabled = enabled,
            year = year,
            month = month,
            day = day,
            deviceAddress = deviceAddress
        )

        android.util.Log.d("SchedulerEdit", "创建任务: index=$taskIndex, time=$hour:$minute, action=$action(${action.value}), brightness=$brightness, repeat=0x${repeat.toString(16)}, enabled=$enabled")

        // 保存到本地
        schedulerRepository.upsertTask(deviceAddress, task)

        // 发送到设备
        if (viewModel.isConnected.value == true) {
            viewModel.setSchedulerTask(deviceAddress, task)
            Toast.makeText(this, "任务已保存并发送到设备", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "任务已保存到本地，连接设备后同步", Toast.LENGTH_SHORT).show()
        }

        setResult(RESULT_OK)
        finish()
    }
}

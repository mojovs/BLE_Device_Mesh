package com.example.ble_device_mesh

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 每 3 小时的时间同步调度器。
 *
 * 用 AlarmManager 精确闹钟在固定整点唤醒 App（Doze 下也能触发），由
 * [TimeSyncAlarmReceiver] 临时建立一次 BLE 连接完成同步，同步完立刻断开。
 *
 * 这样不需要常驻前台服务、不需要常驻通知，也不需要一直保持 BLE 连接。
 */
object TimeSyncScheduler {

    private const val TAG = "MeshTimeSync"
    private const val PREFS = "time_sync_prefs"
    private const val KEY_NEXT_TRIGGER = "next_trigger_at"
    private const val KEY_LAST_SUCCESS = "last_success_at"
    private const val KEY_LAST_RESULT_TEXT = "last_result_text"
    private const val KEY_LAST_RESULT_AT = "last_result_at"
    private const val REQUEST_CODE = 0x5453

    /** 每天每 3 小时一次的触发整点（本地时间） */
    val SYNC_HOURS = intArrayOf(2, 5, 8, 11, 14, 17, 20, 23)

    /** 距上次成功同步超过该时长后，连接 App 时顺带补一次 */
    private const val STALE_THRESHOLD_MS = 60 * 60 * 1000L

    // ---------------------------------------------------------------- 调度

    /**
     * 确保闹钟已排布。App 启动、开机、改时间、换时区时都应该调用。
     */
    fun ensureScheduled(context: Context) {
        schedule(context, afterTrigger = false)
    }

    /** 闹钟触发后重排下一次（多留 60s 余量，避免重复命中同一点） */
    fun rescheduleAfterTrigger(context: Context) {
        schedule(context, afterTrigger = true)
    }

    /** 取消闹钟（调试/关闭功能时用） */
    fun cancel(context: Context) {
        val app = context.applicationContext
        val alarmManager = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        alarmManager.cancel(pendingIntent(app))
        prefs(app).edit().remove(KEY_NEXT_TRIGGER).apply()
        Log.d(TAG, "时间同步闹钟已取消")
    }

    private fun schedule(context: Context, afterTrigger: Boolean) {
        val app = context.applicationContext
        val alarmManager = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        if (alarmManager == null) {
            Log.e(TAG, "AlarmManager 不可用，无法排布时间同步")
            return
        }

        val lead = if (afterTrigger) 60_000L else 1_000L
        val next = nextTriggerAt(System.currentTimeMillis(), lead)
        val pi = pendingIntent(app)

        try {
            if (canScheduleExact(alarmManager)) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
                Log.d(TAG, "已排布精确闹钟: ${formatTime(next)}")
            } else {
                // 用户撤销了精确闹钟权限时退化为非精确闹钟（可能偏几分钟到十几分钟）
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
                Log.w(TAG, "缺少精确闹钟权限，退化为非精确闹钟: ${formatTime(next)}")
            }
            prefs(app).edit().putLong(KEY_NEXT_TRIGGER, next).apply()
        } catch (e: Exception) {
            Log.e(TAG, "排布闹钟失败: ${e.message}", e)
        }
    }

    private fun canScheduleExact(alarmManager: AlarmManager): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, TimeSyncAlarmReceiver::class.java).apply {
            action = TimeSyncAlarmReceiver.ACTION_TIME_SYNC_ALARM
        }
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = flags or PendingIntent.FLAG_IMMUTABLE
        }
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
    }

    /**
     * 计算下一个触发时刻：今天剩余整点里第一个大于 now + minLeadMs 的点，
     * 若今天没有了则取明天第一个整点。
     */
    fun nextTriggerAt(now: Long, minLeadMs: Long = 1_000L): Long {
        val threshold = now + minLeadMs
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        for (hour in SYNC_HOURS) {
            cal.set(Calendar.HOUR_OF_DAY, hour)
            if (cal.timeInMillis > threshold) return cal.timeInMillis
        }
        cal.add(Calendar.DAY_OF_YEAR, 1)
        cal.set(Calendar.HOUR_OF_DAY, SYNC_HOURS[0])
        return cal.timeInMillis
    }

    // ---------------------------------------------------------------- 结果记录

    /** 最近一次成功同步的时间戳，0 表示从未成功 */
    fun lastSuccessAt(context: Context): Long =
        prefs(context).getLong(KEY_LAST_SUCCESS, 0L)

    fun lastResultText(context: Context): String? =
        prefs(context).getString(KEY_LAST_RESULT_TEXT, null)

    fun lastResultAt(context: Context): Long =
        prefs(context).getLong(KEY_LAST_RESULT_AT, 0L)

    /** 距上次成功同步是否已经足够久 */
    fun isStale(context: Context): Boolean =
        System.currentTimeMillis() - lastSuccessAt(context) > STALE_THRESHOLD_MS

    fun nextTriggerText(context: Context): String {
        val next = prefs(context).getLong(KEY_NEXT_TRIGGER, 0L)
        return if (next > 0) formatTime(next) else "未排布"
    }

    /** 调度摘要，供设置页展示 */
    fun summaryText(context: Context): String {
        val hours = SYNC_HOURS.joinToString(" / ") { String.format(Locale.US, "%02d:00", it) }
        val last = lastResultText(context) ?: "暂无记录"
        return "同步时刻：$hours\n下一次：${nextTriggerText(context)}\n上次结果：$last"
    }

    internal fun saveSuccess(context: Context, text: String) {
        prefs(context).edit()
            .putLong(KEY_LAST_SUCCESS, System.currentTimeMillis())
            .putLong(KEY_LAST_RESULT_AT, System.currentTimeMillis())
            .putString(KEY_LAST_RESULT_TEXT, text)
            .apply()
    }

    internal fun saveFailure(context: Context, text: String) {
        prefs(context).edit()
            .putLong(KEY_LAST_RESULT_AT, System.currentTimeMillis())
            .putString(KEY_LAST_RESULT_TEXT, text)
            .apply()
    }

    // ---------------------------------------------------------------- 工具

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun formatTime(millis: Long): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(millis))
}

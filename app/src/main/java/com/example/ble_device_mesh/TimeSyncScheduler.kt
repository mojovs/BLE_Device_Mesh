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
import kotlin.random.Random

/**
 * 时间同步调度器。
 *
 * 用 AlarmManager 精确闹钟按用户设置的间隔唤醒 App（Doze 下也能触发），由
 * [TimeSyncAlarmReceiver] 临时建立一次 BLE 连接完成同步，同步完立刻断开。
 *
 * 触发点按本地零点对齐（0/N、N*2…），再加 0~2 分钟随机抖动，避免多台手机
 * 同一秒去抢同一个 proxy 连接。
 *
 * 同步失败后 15 分钟自动重试，最多连续 2 次；成功或进入下一个正式周期后清零。
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
    private const val KEY_INTERVAL_HOURS = "interval_hours"
    private const val KEY_RETRY_COUNT = "retry_count"
    private const val REQUEST_CODE = 0x5453
    private const val RETRY_REQUEST_CODE = 0x5454

    /** 可选的自动同步间隔（小时），0 = 关闭 */
    val INTERVAL_OPTIONS = intArrayOf(0, 1, 2, 3, 6, 12, 24)

    /** 默认每 3 小时 */
    const val DEFAULT_INTERVAL_HOURS = 3

    /** 失败后的重试间隔 */
    private const val RETRY_DELAY_MS = 15 * 60 * 1000L

    /** 连续重试上限（成功后清零，进入下一个正式周期也会清零） */
    private const val MAX_RETRIES = 2

    /** 触发抖动上限，避免多台手机同一秒抢 proxy */
    private const val MAX_JITTER_MS = 120_000L

    /** 连接后补同步的阈值：间隔的一半，最短 1 小时 */
    private const val MIN_STALE_MS = 60 * 60 * 1000L

    // ---------------------------------------------------------------- 间隔设置

    /** 当前自动同步间隔（小时），0 = 关闭 */
    fun intervalHours(context: Context): Int =
        prefs(context).getInt(KEY_INTERVAL_HOURS, DEFAULT_INTERVAL_HOURS)

    fun isEnabled(context: Context): Boolean = intervalHours(context) > 0

    /** 设置间隔（小时，0 = 关闭），立即重排或取消闹钟 */
    fun setIntervalHours(context: Context, hours: Int) {
        val value = hours.coerceAtLeast(0)
        prefs(context).edit().putInt(KEY_INTERVAL_HOURS, value).apply()
        Log.d(TAG, "自动同步间隔已设为 ${if (value <= 0) "关闭" else "每 $value 小时"}")
        if (value <= 0) {
            cancel(context)
        } else {
            ensureScheduled(context)
        }
    }

    // ---------------------------------------------------------------- 调度

    /**
     * 确保闹钟已排布。App 启动、开机、改时间、换时区时都应该调用。
     */
    fun ensureScheduled(context: Context) {
        // 仅控制端不做自动对时：时间权威只有管理员手机，否则两台手机
        // 到点互相覆盖，设备时间会随两台手机的时钟差来回跳。
        if (!AppRole.canWriteTime(context)) {
            Log.d(TAG, "本机为仅控制，不参与自动对时，取消已排布的闹钟")
            cancel(context)
            return
        }
        if (!isEnabled(context)) {
            Log.d(TAG, "自动同步已关闭，不排布闹钟")
            cancel(context)
            return
        }
        schedule(context, afterTrigger = false)
    }

    /** 闹钟触发后重排下一次（多留 60s 余量，避免重复命中同一点） */
    fun rescheduleAfterTrigger(context: Context) {
        if (!isEnabled(context)) {
            cancel(context)
            return
        }
        schedule(context, afterTrigger = true)
    }

    /** 取消闹钟（关闭功能 / 切换角色时用） */
    fun cancel(context: Context) {
        val app = context.applicationContext
        val alarmManager = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        alarmManager.cancel(pendingIntent(app))
        alarmManager.cancel(retryPendingIntent(app))
        prefs(app).edit().remove(KEY_NEXT_TRIGGER).apply()
        Log.d(TAG, "时间同步闹钟已取消")
    }

    /** 同步失败后 15 分钟重试一次，最多连续重试 [MAX_RETRIES] 次 */
    fun scheduleRetry(context: Context) {
        val app = context.applicationContext
        if (!isEnabled(app) || !AppRole.canWriteTime(app)) return

        val count = prefs(app).getInt(KEY_RETRY_COUNT, 0)
        if (count >= MAX_RETRIES) {
            Log.w(TAG, "连续重试已达 $MAX_RETRIES 次，等下一个正式周期")
            return
        }

        val alarmManager = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val at = System.currentTimeMillis() + RETRY_DELAY_MS
        prefs(app).edit().putInt(KEY_RETRY_COUNT, count + 1).apply()

        try {
            if (canScheduleExact(alarmManager)) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, at, retryPendingIntent(app)
                )
            } else {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, at, retryPendingIntent(app)
                )
            }
            Log.d(TAG, "已排布失败重试: ${formatTime(at)}（第 ${count + 1} 次）")
        } catch (e: Exception) {
            Log.e(TAG, "排布重试失败: ${e.message}", e)
        }
    }

    /** 进入新的正式周期时清零重试计数 */
    internal fun resetRetryCount(context: Context) {
        prefs(context).edit().putInt(KEY_RETRY_COUNT, 0).apply()
    }

    private fun schedule(context: Context, afterTrigger: Boolean) {
        val app = context.applicationContext
        val alarmManager = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        if (alarmManager == null) {
            Log.e(TAG, "AlarmManager 不可用，无法排布时间同步")
            return
        }

        val hours = intervalHours(app)
        if (hours <= 0) {
            cancel(app)
            return
        }

        val lead = if (afterTrigger) 60_000L else 1_000L
        val next = nextTriggerAt(
            System.currentTimeMillis(),
            hours,
            lead,
            Random.nextLong(MAX_JITTER_MS)
        )
        val pi = pendingIntent(app)

        try {
            if (canScheduleExact(alarmManager)) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
                Log.d(TAG, "已排布精确闹钟（每 $hours 小时）: ${formatTime(next)}")
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

    private fun retryPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, TimeSyncAlarmReceiver::class.java).apply {
            action = TimeSyncAlarmReceiver.ACTION_TIME_SYNC_RETRY
        }
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = flags or PendingIntent.FLAG_IMMUTABLE
        }
        return PendingIntent.getBroadcast(context, RETRY_REQUEST_CODE, intent, flags)
    }

    /**
     * 计算下一个触发时刻：以本地零点为基准，按 `intervalHours` 对齐后取下一个点。
     * 例如 3 小时 → 0:00 / 3:00 / 6:00 …；24 小时 → 每天 0:00。
     */
    fun nextTriggerAt(
        now: Long,
        intervalHours: Int = DEFAULT_INTERVAL_HOURS,
        minLeadMs: Long = 1_000L,
        jitterMs: Long = 0L
    ): Long {
        if (intervalHours <= 0) return 0L
        val stepMs = intervalHours * 60L * 60L * 1000L
        val midnight = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        var next = midnight + ((now - midnight) / stepMs + 1) * stepMs
        if (next < now + minLeadMs) next += stepMs
        return next + jitterMs
    }

    // ---------------------------------------------------------------- 结果记录

    /** 最近一次成功同步的时间戳，0 表示从未成功 */
    fun lastSuccessAt(context: Context): Long =
        prefs(context).getLong(KEY_LAST_SUCCESS, 0L)

    fun lastResultText(context: Context): String? =
        prefs(context).getString(KEY_LAST_RESULT_TEXT, null)

    fun lastResultAt(context: Context): Long =
        prefs(context).getLong(KEY_LAST_RESULT_AT, 0L)

    /** 距上次成功同步是否已经足够久（阈值跟随间隔，最短 1 小时） */
    fun isStale(context: Context): Boolean {
        val intervalMs = intervalHours(context) * 60L * 60L * 1000L
        val threshold = maxOf(intervalMs / 2, MIN_STALE_MS)
        return System.currentTimeMillis() - lastSuccessAt(context) > threshold
    }

    fun nextTriggerText(context: Context): String {
        val next = prefs(context).getLong(KEY_NEXT_TRIGGER, 0L)
        return if (next > 0) formatTime(next) else "未排布"
    }

    /** 调度摘要，供设置页展示 */
    fun summaryText(context: Context): String {
        val hours = intervalHours(context)
        val mode = if (hours <= 0) "已关闭" else "每 $hours 小时"
        val last = lastResultText(context) ?: "暂无记录"
        val next = if (hours <= 0) "—" else nextTriggerText(context)
        return "自动同步：$mode\n下一次：$next\n上次结果：$last"
    }

    internal fun saveSuccess(context: Context, text: String) {
        prefs(context).edit()
            .putLong(KEY_LAST_SUCCESS, System.currentTimeMillis())
            .putLong(KEY_LAST_RESULT_AT, System.currentTimeMillis())
            .putString(KEY_LAST_RESULT_TEXT, text)
            .putInt(KEY_RETRY_COUNT, 0)
            .apply()
        // 成功后不再需要重试闹钟
        try {
            (context.applicationContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager)
                ?.cancel(retryPendingIntent(context.applicationContext))
        } catch (e: Exception) {
            Log.w(TAG, "取消重试闹钟失败: ${e.message}")
        }
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

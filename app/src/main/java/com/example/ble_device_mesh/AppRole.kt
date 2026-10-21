package com.example.ble_device_mesh

import android.content.Context

/**
 * 本机角色的轻量读取。
 *
 * 角色在导入配置时由 [MeshViewModel.applyImportedRole] 写入
 * `MeshRolePrefs / is_control_only`，运行期镜像在 [MeshViewModel.MeshState.isControlOnly]。
 *
 * 但闹钟 / 广播接收器可能在 ViewModel 初始化之前就被系统拉起（进程冷启动），
 * 那时 `MeshState.isControlOnly` 还是默认值 false，所以后台组件必须直接读
 * SharedPreferences，不能依赖 MeshState。
 */
object AppRole {

    /** true = 仅控制（没有 Device Key，不能改网络状态，也不做自动对时） */
    fun isControlOnly(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(MeshViewModel.ROLE_PREFS, Context.MODE_PRIVATE)
            .getBoolean(MeshViewModel.ROLE_KEY_CONTROL_ONLY, false)

    /**
     * 是否允许由本机给全网下发时间。
     * 只有管理员手机是时间权威；仅控制端只读，避免两台手机每 3 小时互相拉扯。
     */
    fun canWriteTime(context: Context): Boolean = !isControlOnly(context)
}

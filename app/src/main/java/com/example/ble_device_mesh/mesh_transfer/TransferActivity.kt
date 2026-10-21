package com.example.ble_device_mesh.mesh_transfer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.example.ble_device_mesh.MeshViewModel
import com.example.ble_device_mesh.R

class TransferActivity : AppCompatActivity() {

    private val viewModel: MeshViewModel by viewModels()

    private lateinit var btnTabSend: TextView
    private lateinit var btnTabReceive: TextView
    private lateinit var btnBack: Button

    private var currentFragment: androidx.fragment.app.Fragment? = null
    private var pendingPermissionAction: (() -> Unit)? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result.values.all { it }
        if (granted) {
            pendingPermissionAction?.invoke()
        } else {
            Toast.makeText(this, "需要蓝牙与 WiFi Direct 权限才能使用互传功能", Toast.LENGTH_LONG).show()
        }
        pendingPermissionAction = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_transfer)

        btnBack = findViewById(R.id.btnBack)
        btnTabSend = findViewById(R.id.btnTabSend)
        btnTabReceive = findViewById(R.id.btnTabReceive)

        btnBack.setOnClickListener { finish() }
        btnTabSend.setOnClickListener { switchToSend() }
        btnTabReceive.setOnClickListener { switchToReceive() }

        switchToSend()
    }

    fun hasTransferPermissions(): Boolean {
        val perms = requiredPermissions()
        return perms.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }

    fun requestTransferPermissions(afterGranted: () -> Unit) {
        if (hasTransferPermissions()) {
            afterGranted()
            return
        }
        pendingPermissionAction = afterGranted
        val perms = requiredPermissions()
        val needRequest = perms.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
        if (needRequest.isNotEmpty()) {
            permissionLauncher.launch(needRequest)
        } else {
            afterGranted()
        }
    }

    private fun requiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.NEARBY_WIFI_DEVICES
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }
    }

    private fun switchToSend() {
        btnTabSend.setTextColor(getColor(R.color.tab_selected))
        btnTabSend.setBackgroundResource(R.drawable.tab_selected_bg)
        btnTabReceive.setTextColor(getColor(R.color.tab_unselected))
        btnTabReceive.setBackgroundResource(android.R.color.transparent)
        showFragment(com.example.ble_device_mesh.mesh_transfer.SendFragment())
    }

    private fun switchToReceive() {
        btnTabReceive.setTextColor(getColor(R.color.tab_selected))
        btnTabReceive.setBackgroundResource(R.drawable.tab_selected_bg)
        btnTabSend.setTextColor(getColor(R.color.tab_unselected))
        btnTabSend.setBackgroundResource(android.R.color.transparent)
        showFragment(com.example.ble_device_mesh.mesh_transfer.ReceiveFragment())
    }

    private fun showFragment(fragment: androidx.fragment.app.Fragment) {
        currentFragment = fragment
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commitAllowingStateLoss()
    }

    fun getMeshViewModel(): MeshViewModel = viewModel
}
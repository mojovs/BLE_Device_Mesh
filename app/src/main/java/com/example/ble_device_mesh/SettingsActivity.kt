package com.example.ble_device_mesh

import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.BufferedReader
import java.io.InputStreamReader

class SettingsActivity : AppCompatActivity() {
    private val viewModel: MeshViewModel by viewModels()
    
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            importMeshConfig(it)
        }
    }
    
    private val fileSaverLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        uri?.let {
            exportMeshConfig(it)
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        
        val btnBack = findViewById<Button>(R.id.btnBack)
        val btnImportConfig = findViewById<Button>(R.id.btnImportConfig)
        val btnExportConfig = findViewById<Button>(R.id.btnExportConfig)
        val tvConfigStatus = findViewById<TextView>(R.id.tvConfigStatus)
        val switchAutoConnect = findViewById<Switch>(R.id.switchAutoConnect)
        val btnScanProxy = findViewById<Button>(R.id.btnScanProxy)
        val tvProxyStatus = findViewById<TextView>(R.id.tvProxyStatus)
        
        btnBack.setOnClickListener {
            finish()
        }
        
        btnImportConfig.setOnClickListener {
            filePickerLauncher.launch("application/json")
        }
        
        btnExportConfig.setOnClickListener {
            fileSaverLauncher.launch("mesh_config.json")
        }
        
        
        btnScanProxy.setOnClickListener {
            // TODO: 打开 Proxy 扫描界面
            Toast.makeText(this, "扫描 Proxy 节点", Toast.LENGTH_SHORT).show()
        }
        
        // 修改Provisioner地址
        tvConfigStatus.setOnClickListener {
            val currentAddr = viewModel.currentProvisionerAddress.value ?: 0
            val input = EditText(this)
            input.hint = "输入新地址(十六进制，如 200)"
            AlertDialog.Builder(this)
                .setTitle("修改Provisioner地址")
                .setMessage("当前地址: 0x${currentAddr.toString(16)}\n(基于设备ID自动生成)\n\n可手动修改，但建议保持自动分配")
                .setView(input)
                .setPositiveButton("确定") { _, _ ->
                    try {
                        val newAddr = input.text.toString().toInt(16)
                        viewModel.setProvisionerAddress(newAddr)
                        // 保存手动设置的地址
                        getSharedPreferences("mesh_config", MODE_PRIVATE)
                            .edit().putInt("provisioner_address", newAddr).apply()
                        Toast.makeText(this, "地址已设置为: 0x${newAddr.toString(16)}", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(this, "地址格式错误", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }

        
        // 观察网络状态
        viewModel.statusText.observe(this) { status ->
            if (status.contains("已就绪") || status.contains("创建成功") || status.contains("Mesh 网络")) {
                tvConfigStatus.text = "配置已就绪"
            }
        }
        
        viewModel.isConnected.observe(this) { connected ->
            tvProxyStatus.text = if (connected) "已连接" else "未连接"
        }
        
        viewModel.currentProvisionerAddress.observe(this) { addr ->
            tvConfigStatus.text = "Provisioner地址: 0x${addr?.toString(16)} (点击修改)"
        }
    }
    
    private fun importMeshConfig(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri) ?: throw Exception("无法打开文件流")
            val reader = BufferedReader(InputStreamReader(inputStream))
            val jsonData = reader.readText()
            reader.close()
            inputStream.close()

            viewModel.importMeshNetworkWithDevices(jsonData)
            Toast.makeText(this, "正在导入配置...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "读取文件失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun exportMeshConfig(uri: Uri) {
        val json = viewModel.exportMeshNetworkWithDevices()
        if (json == null) {
            Toast.makeText(this, "导出失败：网络未加载或为空", Toast.LENGTH_LONG).show()
            return
        }

        try {
            contentResolver.openOutputStream(uri)?.use { outputStream ->
                outputStream.write(json.toByteArray())
            }
            Toast.makeText(this, "导出成功（包含设备信息）", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "写入文件失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}

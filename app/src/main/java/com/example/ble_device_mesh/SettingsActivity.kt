package com.example.ble_device_mesh

import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import java.io.BufferedReader
import java.io.InputStreamReader

class SettingsActivity : ComponentActivity() {
    private val viewModel: MeshViewModel by viewModels()
    
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            importMeshConfig(it)
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        
        val btnBack = findViewById<Button>(R.id.btnBack)
        val btnImportConfig = findViewById<Button>(R.id.btnImportConfig)
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
        
        btnScanProxy.setOnClickListener {
            // TODO: 打开 Proxy 扫描界面
            Toast.makeText(this, "扫描 Proxy 节点", Toast.LENGTH_SHORT).show()
        }
        
        // 观察网络状态
        viewModel.statusText.observe(this) { status ->
            if (status.contains("已就绪") || status.contains("创建成功")) {
                tvConfigStatus.text = "配置已导入"
            }
        }
        
        viewModel.isConnected.observe(this) { connected ->
            tvProxyStatus.text = if (connected) "已连接" else "未连接"
        }
    }
    
    private fun importMeshConfig(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri)
            val reader = BufferedReader(InputStreamReader(inputStream))
            val jsonData = reader.readText()
            reader.close()
            
            viewModel.importMeshNetwork(jsonData)
            Toast.makeText(this, "正在导入配置...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "读取文件失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}

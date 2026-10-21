package com.example.ble_device_mesh.mesh_transfer

import java.io.BufferedInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer

/**
 * TCP 传输层：发送端 ServerSocket 发送加密数据，接收端 Socket 接收。
 * 协议：12 字节头（8 字节魔数 "MESHTRN1" + 4 字节大端总大小）+ 原始数据流。
 */
object TransferTcp {

    private const val MAGIC = "MESHTRN1"
    private const val CHUNK_SIZE = 8192

    /** 发送端启动 TCP Server，等待接收端连接后发送数据 */
    fun sendFile(
        ip: String,
        port: Int,
        data: ByteArray,
        onProgress: (Int) -> Unit,
        onDone: (Boolean, String) -> Unit
    ) {
        Thread {
            try {
                ServerSocket(port, 1, InetAddress.getByName(ip)).use { server ->
                    server.accept().use { socket ->
                        socket.soTimeout = 60_000
                        val out = socket.getOutputStream()
                        val header = ByteBuffer.allocate(12)
                        header.put(MAGIC.toByteArray(java.nio.charset.StandardCharsets.US_ASCII))
                        header.putInt(data.size)
                        out.write(header.array())
                        out.flush()
                        val buf = ByteArray(CHUNK_SIZE)
                        var offset = 0
                        while (offset < data.size) {
                            val n = minOf(CHUNK_SIZE, data.size - offset)
                            data.copyInto(buf, 0, offset, offset + n)
                            out.write(buf, 0, n)
                            offset += n
                            onProgress(offset * 100 / data.size)
                        }
                        out.flush()
                    }
                }
                onDone(true, "")
            } catch (e: Exception) {
                onDone(false, e.message ?: "发送失败")
            }
        }.start()
    }

    /** 接收端连接后接收数据 */
    fun receiveFile(
        ip: String,
        port: Int,
        onProgress: (Int) -> Unit,
        onDone: (Boolean, ByteArray?, String) -> Unit
    ) {
        Thread {
            try {
                Socket(ip, port).use { socket ->
                    socket.soTimeout = 60_000
                    val input = BufferedInputStream(socket.getInputStream())
                    val header = ByteArray(12)
                    var read = 0
                    while (read < 12) {
                        val n = input.read(header, read, 12 - read)
                        if (n <= 0) throw IOException("连接中断")
                        read += n
                    }
                    val magic = String(header, 0, 8, java.nio.charset.StandardCharsets.US_ASCII)
                    if (magic != MAGIC) throw IOException("数据格式错误")
                    val total = ByteBuffer.wrap(header, 8, 4).int
                    if (total <= 0 || total > 10 * 1024 * 1024) {
                        throw IOException("数据大小异常")
                    }
                    val data = ByteArray(total)
                    var offset = 0
                    while (offset < total) {
                        val n = input.read(data, offset, total - offset)
                        if (n <= 0) throw IOException("连接中断")
                        offset += n
                        onProgress(offset * 100 / total)
                    }
                    onDone(true, data, "")
                }
            } catch (e: Exception) {
                onDone(false, null, e.message ?: "接收失败")
            }
        }.start()
    }
}
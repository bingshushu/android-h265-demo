package com.ai.bb.myapplication

import android.util.Base64
import android.util.Log
import okhttp3.*
import okio.ByteString
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class VideoStreamPlayer(private val listener: VideoStreamListener) {
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .pingInterval(0, TimeUnit.MILLISECONDS) // 禁用ping保持连接，减少开销
        .retryOnConnectionFailure(true)
        .build()
    
    private var webSocket: WebSocket? = null
    private var isStreaming = AtomicBoolean(false)
    private var responseReceived = false
    private var frameCount = 0
    private var lastStatsTime = 0L
    
    interface VideoStreamListener {
        fun onVideoDataReceived(data: ByteArray)
        fun onConnectionOpened()
        fun onConnectionClosed()
        fun onError(error: String)
        fun onStreamStarted()
    }
    
    fun connectWebSocket(hostIp: String, port: Int) {
        val request = Request.Builder()
            .url("ws://$hostIp:$port/websocket")
            .build()
        
        val webSocketListener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d("WebSocket", "连接已建立")
                this@VideoStreamPlayer.webSocket = webSocket
                listener.onConnectionOpened()
                
                // 连接成功后发送HTTP请求获取视频流
                sendStreamRequest(hostIp, port)
            }
            
            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d("WebSocket", "收到文本消息: $text")
                // 检查是否是流开始的响应
                if (!responseReceived && (text.contains("200 OK") || text.contains("HTTP/"))) {
                    responseReceived = true
                    if (isStreaming.compareAndSet(false, true)) {
                        listener.onStreamStarted()
                    }
                }
            }
            
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                frameCount++
                val currentTime = System.currentTimeMillis()
                
                // 网络接收统计
                if (frameCount % 60 == 0) {
                    val fps = if (lastStatsTime > 0) {
                        60000.0 / (currentTime - lastStatsTime)
                    } else 0.0
                    Log.d("WebSocket", "Network RX FPS: ${String.format("%.1f", fps)}")
                    lastStatsTime = currentTime
                }
                
                // 收到视频数据后，确保触发流开始事件
                if (isStreaming.compareAndSet(false, true)) {
                    listener.onStreamStarted()
                }
                
                // 立即传递数据，不做任何缓存
                listener.onVideoDataReceived(bytes.toByteArray())
            }
            
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d("WebSocket", "连接正在关闭: $code, $reason")
                webSocket.close(1000, null)
                isStreaming.set(false)
                responseReceived = false
                listener.onConnectionClosed()
            }
            
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("WebSocket", "连接失败", t)
                isStreaming.set(false)
                responseReceived = false
                listener.onError(t.message ?: "连接失败")
            }
        }
        
        webSocket = client.newWebSocket(request, webSocketListener)
    }
    
    private fun sendStreamRequest(hostIp: String, port: Int) {
        // 构造Authorization头
        val credentials = "admin:admin"
        val encodedCredentials = Base64.encodeToString(credentials.toByteArray(), Base64.NO_WRAP)
        val authorizationHeader = "Basic $encodedCredentials"
        
        // 构造HTTP请求，参考JS代码中的getReqStreamCmdStr函数
        val contentStr = "Cseq: 1\r\nTransport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n"
        val contentLength = contentStr.length
        
        val httpRequest = "GET http://$hostIp:$port/livestream.cgi?stream=11&action=play&media=video_audio_data HTTP/1.1\r\n" +
                "Connection: Keep-Alive\r\n" +
                "Cache-Control: no-cache\r\n" +
                "Authorization: $authorizationHeader\r\n" +
                "Content-Length: $contentLength\r\n" +
                "\r\n" +
                contentStr
        
        webSocket?.send(httpRequest)
        Log.d("WebSocket", "已发送HTTP请求获取视频流")
    }
    
    fun disconnect() {
        isStreaming.set(false)
        responseReceived = false
        webSocket?.close(1000, "用户主动断开连接")
        webSocket = null
    }
    
    fun isConnected(): Boolean {
        return webSocket != null
    }
    
    fun isStreaming(): Boolean {
        return isStreaming.get()
    }
}
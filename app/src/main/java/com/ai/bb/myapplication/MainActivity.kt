package com.ai.bb.myapplication

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.view.SurfaceView
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 设置高优先级以减少延迟
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
        
        setContent {
            MaterialTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    VideoStreamScreen(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun VideoStreamScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var isConnected by remember { mutableStateOf(false) }
    var isStreaming by remember { mutableStateOf(false) }
    var connectionStatus by remember { mutableStateOf("未连接") }
    var videoStreamPlayer by remember { mutableStateOf<VideoStreamPlayer?>(null) }
    var ffmpegPlayer by remember { 
        mutableStateOf(FFmpegVideoPlayer(context))
    }
    val mainHandler = Handler(Looper.getMainLooper())
    
    Column(
        modifier = modifier
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "视频流播放器",
            style = MaterialTheme.typography.headlineMedium
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = "连接状态: $connectionStatus",
            style = MaterialTheme.typography.bodyLarge
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Button(
            onClick = {
                if (isConnected) {
                    videoStreamPlayer?.disconnect()
                    ffmpegPlayer.stopPlayback()
                    videoStreamPlayer = null
                    isConnected = false
                    isStreaming = false
                    connectionStatus = "未连接"
                } else {
                    // 创建并连接到视频流
                    val streamPlayer = VideoStreamPlayer(object : VideoStreamPlayer.VideoStreamListener {
                        override fun onVideoDataReceived(data: ByteArray) {
                            // 将接收到的视频数据传递给FFmpeg播放器
                            ffmpegPlayer.feedData(data)
                        }

                        override fun onConnectionOpened() {
                            // 在主线程上更新UI状态
                            mainHandler.post {
                                isConnected = true
                                connectionStatus = "已连接"
                            }
                        }

                        override fun onConnectionClosed() {
                            // 在主线程上更新UI状态
                            mainHandler.post {
                                isConnected = false
                                isStreaming = false
                                connectionStatus = "连接已关闭"
                            }
                        }

                        override fun onError(error: String) {
                            // 在主线程上更新UI状态和显示错误
                            mainHandler.post {
                                isConnected = false
                                isStreaming = false
                                connectionStatus = "错误: $error"
                                android.widget.Toast.makeText(
                                    context,
                                    "连接错误: $error",
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            }
                        }

                        override fun onStreamStarted() {
                            // 在主线程上更新UI状态并启动播放器
                            mainHandler.post {
                                isStreaming = true
                                connectionStatus = "正在播放视频流"
                                // 确保播放器已初始化后再启动
                                ffmpegPlayer.startStreaming()
                            }
                        }
                    })
                    
                    videoStreamPlayer = streamPlayer
                    streamPlayer.connectWebSocket("192.168.1.87", 80)
                }
            },
            enabled = true
        ) {
            Text(if (isConnected) "断开连接" else "连接到视频流")
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // 视频显示区域
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(8.dp)
        ) {
            // 始终显示SurfaceView，确保Surface早期初始化
            AndroidView(
                factory = { ctx ->
                    FrameLayout(ctx).apply {
                        val surfaceView = SurfaceView(ctx)
                        surfaceView.layoutParams = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT
                        )
                        
                        // 初始化FFmpeg播放器
                        ffmpegPlayer.initialize(surfaceView)
                        
                        addView(surfaceView)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
            
            if (!isStreaming) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.8f)
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = if (isConnected) "正在连接视频流..." else "点击上方按钮连接到视频流",
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            }
        }
    }
}
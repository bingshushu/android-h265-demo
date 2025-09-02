package com.ai.bb.myapplication

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.SurfaceView
import android.view.SurfaceHolder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.LinkedBlockingQueue
import kotlinx.coroutines.*

class FFmpegVideoPlayer(private val context: Context) {
    private var h264Decoder: H264VideoDecoder? = null
    private var surfaceView: SurfaceView? = null
    private var surface: Surface? = null
    private var isPlaying = AtomicBoolean(false)
    private var isInitialized = false
    
    // 缓存视频参数，用于Surface准备好后初始化解码器
    private var pendingMimeType: String? = null
    private var pendingWidth: Int = 0
    private var pendingHeight: Int = 0
    
    // 性能统计
    private var frameDropCount = 0
    
    // 后台处理线程
    private val decoderScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val frameQueue = LinkedBlockingQueue<ByteArray>(30) // 限制队列大小防止内存溢出
    private var processingJob: Job? = null
    
    companion object {
        private const val TAG = "FFmpegVideoPlayer"
    }
    
    fun initialize(surfaceView: SurfaceView) {
        this.surfaceView = surfaceView
        this.surfaceView?.holder?.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surface = holder.surface
                Log.d(TAG, "Surface created")
                
                // 如果有待处理的视频参数，立即初始化解码器
                pendingMimeType?.let { mimeType ->
                    if (pendingWidth > 0 && pendingHeight > 0) {
                        Log.d(TAG, "Surface ready, initializing pending decoder")
                        setupDecoderInternal(mimeType, pendingWidth, pendingHeight)
                    }
                }
            }
            
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                surface = holder.surface
                Log.d(TAG, "Surface changed: ${width}x${height}")
            }
            
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surface = null
                Log.d(TAG, "Surface destroyed")
            }
        })
    }
    
    fun startStreaming() {
        if (isPlaying.getAndSet(true)) {
            Log.w(TAG, "视频流已在播放中")
            return
        }
        
        Log.d(TAG, "开始视频流播放")
    }
    
    private fun setupDecoder(mimeType: String, width: Int, height: Int) {
        // 缓存视频参数
        pendingMimeType = mimeType
        pendingWidth = width
        pendingHeight = height
        
        // 如果Surface已准备好，立即初始化
        if (surface != null) {
            setupDecoderInternal(mimeType, width, height)
        } else {
            Log.w(TAG, "Surface not ready, decoder parameters cached")
        }
    }
    
    private fun setupDecoderInternal(mimeType: String, width: Int, height: Int) {
        surface?.let { surface ->
            // 如果已经有解码器，先停止它
            h264Decoder?.stop()
            h264Decoder = H264VideoDecoder(surface)
            h264Decoder?.initialize(mimeType, width, height)
            isInitialized = true
            Log.d(TAG, "Decoder setup completed: $mimeType ${width}x${height}")
        }
    }
    
    fun processVideoData(data: ByteArray) {
        try {
            // 快速检查头部信息（在主线程进行轻量级操作）
            if (data.size > 100) {
                val headerString = String(data, 0, minOf(data.size, 100)) // 减少字符串创建开销
                if (headerString.contains("HTTP/1.1 200 OK")) {
                    parseVideoHeader(String(data, 0, minOf(data.size, 1024)))
                    return
                }
            }
            
            // 检查视频头部信息
            if (data.size >= 4) {
                val flag = ByteBuffer.wrap(data, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
                
                when (flag) {
                    0x54565848 -> { // H265 header
                        Log.d(TAG, "Received H265 video header")
                        setupDecoder("video/hevc", 640, 352)
                        return
                    }
                    0x53565848 -> { // H264 header
                        Log.d(TAG, "Received H264 video header")
                        setupDecoder("video/avc", 640, 352)
                        return
                    }
                }
            }
            
            // 异步处理视频帧数据
            if (isInitialized && data.size > 0) {
                // 使用队列缓冲，避免主线程阻塞
                if (!frameQueue.offer(data.copyOf())) {
                    // 队列满时丢弃最旧的帧
                    frameQueue.poll()
                    frameQueue.offer(data.copyOf())
                    frameDropCount++
                }
                
                // 启动处理协程（如果尚未启动）
                if (processingJob?.isActive != true) {
                    startFrameProcessing()
                }
                
                if (!isPlaying.get()) {
                    Log.d(TAG, "收到第一批视频数据，播放器已准备")
                    isPlaying.set(true)
                }
            } else {
                if (data.size > 0) {
                    frameDropCount++
                    if (frameDropCount % 50 == 0) { // 减少日志频率
                        Log.w(TAG, "Decoder not ready, dropped $frameDropCount frames")
                    }
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing video data", e)
        }
    }
    
    private fun startFrameProcessing() {
        processingJob = decoderScope.launch {
            while (isActive && isPlaying.get()) {
                try {
                    val frameData = frameQueue.poll()
                    if (frameData != null) {
                        h264Decoder?.decodeFrame(frameData)
                    } else {
                        // 队列为空时短暂休眠，避免CPU空转
                        delay(1)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in frame processing coroutine", e)
                    delay(10)
                }
            }
        }
    }
    
    private fun parseVideoHeader(response: String) {
        try {
            when {
                response.contains("H265") -> {
                    val regex = "H265/(\\d+)/(\\d+)/(\\d+)".toRegex()
                    val match = regex.find(response)
                    match?.let {
                        val width = it.groupValues[2].toInt()
                        val height = it.groupValues[3].toInt()
                        Log.d(TAG, "Parsed H265 params: ${width}x${height}")
                        setupDecoder("video/hevc", width, height)
                    }
                }
                response.contains("H264") -> {
                    val regex = "H264/(\\d+)/(\\d+)/(\\d+)".toRegex()
                    val match = regex.find(response)
                    match?.let {
                        val width = it.groupValues[2].toInt()
                        val height = it.groupValues[3].toInt()
                        Log.d(TAG, "Parsed H264 params: ${width}x${height}")
                        setupDecoder("video/avc", width, height)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse video header", e)
        }
    }
    
    fun feedData(data: ByteArray) {
        processVideoData(data)
    }
    
    fun stopPlayback() {
        if (isPlaying.getAndSet(false)) {
            Log.d(TAG, "停止视频播放")
            
            // 停止协程处理
            processingJob?.cancel()
            processingJob = null
            
            // 清空队列
            frameQueue.clear()
            
            h264Decoder?.stop()
            h264Decoder = null
            isInitialized = false
            Log.i(TAG, "视频播放已停止")
        }
    }
    
    fun release() {
        stopPlayback()
        decoderScope.cancel()
    }
    
    fun isPlaying(): Boolean {
        return isPlaying.get()
    }
    
    fun isPrepared(): Boolean {
        return isInitialized
    }
}

class H264VideoDecoder(private val surface: Surface) {
    private var mediaCodec: MediaCodec? = null
    private var isDecoding = false
    private var width = 640
    private var height = 352
    private var frameCount = 0
    private var lastFrameTime = 0L
    private var lastSuccessfulRender = 0L
    private var consecutiveBufferFailures = 0
    private var freezeCount = 0
    
    // 复用对象减少GC压力
    private val bufferInfo = MediaCodec.BufferInfo()
    private var recreating = false
    
    companion object {
        private const val TAG = "H264VideoDecoder"
        private const val TIMEOUT_US = 5000L // 5ms超时，更快响应
        private const val MAX_CONSECUTIVE_FAILURES = 15 // 进一步增加容错
        private const val FREEZE_DETECTION_MS = 3000L // 3秒检测，更保守
        private const val MAX_QUEUE_SIZE = 5 // 限制输出缓冲区处理数量
    }
    
    fun initialize(mimeType: String, videoWidth: Int, videoHeight: Int) {
        try {
            width = videoWidth
            height = videoHeight
            
            mediaCodec = MediaCodec.createDecoderByType(mimeType)
            
            val format = MediaFormat.createVideoFormat(mimeType, width, height).apply {
                // 基础低延迟配置
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                
                // 设置合理的操作速率
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    setInteger(MediaFormat.KEY_OPERATING_RATE, 60)
                }
                
                // 移除可能导致大量调试日志的厂商参数
                // 注释掉: setInteger("vendor.qti-ext-dec-low-latency.enable", 1)
            }
            
            mediaCodec?.configure(format, surface, null, 0)
            mediaCodec?.start()
            isDecoding = true
            
            Log.d(TAG, "MediaCodec initialized: $mimeType, ${width}x${height} (Low Latency Mode)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize MediaCodec", e)
        }
    }
    
    fun decodeFrame(frameData: ByteArray) {
        if (!isDecoding || mediaCodec == null || recreating) {
            return
        }
        
        try {
            frameCount++
            val currentTime = System.currentTimeMillis()
            
            // 更保守的卡顿检测
            if (lastSuccessfulRender > 0 && (currentTime - lastSuccessfulRender) > FREEZE_DETECTION_MS) {
                Log.w(TAG, "Detected potential freeze after ${currentTime - lastSuccessfulRender}ms")
                recreateMediaCodec()
                return
            }
            
            // 减少帧率统计频率
            if (frameCount % 60 == 0) {
                val fps = if (lastFrameTime > 0) {
                    60000.0 / (currentTime - lastFrameTime)
                } else 0.0
                Log.d(TAG, "Decoding FPS: ${String.format("%.1f", fps)}")
                lastFrameTime = currentTime
            }
            
            // 先清空输出缓冲区，但限制处理数量
            val buffersCleared = drainOutputBuffer()
            if (buffersCleared > 0) {
                lastSuccessfulRender = currentTime
                consecutiveBufferFailures = 0
            }
            
            // 检查输入缓冲区
            val inputBufferIndex = mediaCodec!!.dequeueInputBuffer(TIMEOUT_US)
            if (inputBufferIndex >= 0) {
                try {
                    val inputBuffer = mediaCodec!!.getInputBuffer(inputBufferIndex)
                    inputBuffer?.let { buffer ->
                        buffer.clear()
                        if (buffer.remaining() >= frameData.size) {
                            buffer.put(frameData)
                            
                            // 使用系统时间作为时间戳，更稳定
                            val presentationTimeUs = System.nanoTime() / 1000
                            mediaCodec!!.queueInputBuffer(
                                inputBufferIndex, 
                                0, 
                                frameData.size, 
                                presentationTimeUs, 
                                0
                            )
                            consecutiveBufferFailures = 0
                        } else {
                            Log.w(TAG, "Input buffer too small: ${buffer.remaining()} < ${frameData.size}")
                            mediaCodec!!.queueInputBuffer(inputBufferIndex, 0, 0, 0, 0)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error queuing input buffer", e)
                    consecutiveBufferFailures++
                }
            } else {
                consecutiveBufferFailures++
                if (consecutiveBufferFailures > MAX_CONSECUTIVE_FAILURES) {
                    Log.w(TAG, "Too many consecutive failures ($consecutiveBufferFailures), recreating codec")
                    recreateMediaCodec()
                    return
                }
                // 输入缓冲区满时，尝试清空更多输出缓冲区
                drainOutputBuffer()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in decodeFrame", e)
            if (!recreating) {
                recreateMediaCodec()
            }
        }
    }
    
    private fun drainOutputBuffer(): Int {
        if (mediaCodec == null || recreating) return 0
        
        try {
            var outputBufferIndex = mediaCodec!!.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            var drainedFrames = 0
            var totalBuffersFound = 0
            
            // 处理输出缓冲区，但严格限制数量
            while (outputBufferIndex >= 0 && totalBuffersFound < MAX_QUEUE_SIZE) {
                totalBuffersFound++
                
                try {
                    when (outputBufferIndex) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            Log.d(TAG, "Output format changed: ${mediaCodec!!.outputFormat}")
                        }
                        MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            break
                        }
                        else -> {
                            // 只渲染最新的1帧，严格控制延迟
                            val shouldRender = drainedFrames == 0
                            mediaCodec!!.releaseOutputBuffer(outputBufferIndex, shouldRender)
                            if (shouldRender) {
                                drainedFrames++
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error releasing output buffer $outputBufferIndex", e)
                }
                
                outputBufferIndex = mediaCodec!!.dequeueOutputBuffer(bufferInfo, 0L) // 非阻塞
            }
            
            if (totalBuffersFound > 3) {
                Log.w(TAG, "Buffer backlog detected: $totalBuffersFound frames, rendered: $drainedFrames")
            }
            
            return drainedFrames
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in drainOutputBuffer", e)
            return 0
        }
    }
    
    private fun recreateMediaCodec() {
        if (recreating) return
        
        try {
            recreating = true
            freezeCount++
            Log.i(TAG, "Recreating MediaCodec (attempt: $freezeCount)")
            
            // 保存当前配置
            val currentMimeType = if (width == 640 && height == 352) "video/avc" else "video/hevc"
            
            // 安全停止当前MediaCodec
            try {
                mediaCodec?.let { codec ->
                    if (isDecoding) {
                        codec.stop()
                    }
                    codec.release()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping old MediaCodec", e)
            }
            
            // 短暂延迟确保资源完全释放
            Thread.sleep(100) // 增加延迟时间确保MediaCodec完全释放
            
            // 创建新的MediaCodec
            mediaCodec = MediaCodec.createDecoderByType(currentMimeType)
            
            val format = MediaFormat.createVideoFormat(currentMimeType, width, height).apply {
                // 标准低延迟配置
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                
                // 合理的操作速率
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    setInteger(MediaFormat.KEY_OPERATING_RATE, 60)
                }
                
                // 移除厂商特定参数以避免调试日志泛滥
                // 这些参数可能导致CCodecConfig大量日志输出
            }
            
            mediaCodec?.configure(format, surface, null, 0)
            mediaCodec?.start()
            
            // 重置所有状态
            frameCount = 0
            val currentTime = System.currentTimeMillis()
            lastFrameTime = currentTime
            lastSuccessfulRender = currentTime
            consecutiveBufferFailures = 0
            
            Log.i(TAG, "MediaCodec recreated successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to recreate MediaCodec", e)
            isDecoding = false
        } finally {
            recreating = false
        }
    }
    
    fun stop() {
        isDecoding = false
        try {
            mediaCodec?.stop()
            mediaCodec?.release()
            mediaCodec = null
            Log.d(TAG, "MediaCodec stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping MediaCodec", e)
        }
    }
}
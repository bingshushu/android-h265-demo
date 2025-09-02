# 视频流播放器应用

这是一个基于Android的视频流播放器应用，可以连接到网络摄像头并通过WebSocket接收和播放视频流。

## 功能特性

- 通过WebSocket连接到网络摄像头
- 发送HTTP请求获取视频流
- 使用ExoPlayer解码和播放视频
- 支持HTTP基本认证
- 实时视频播放

## 技术实现

### 主要组件

1. **VideoStreamPlayer** - 处理WebSocket连接和视频流接收
2. **FFmpegVideoPlayer** - 使用ExoPlayer解码和播放视频流
3. **MainActivity** - UI界面和组件协调

### 使用的库

- [OkHttp](https://square.github.io/okhttp/) - WebSocket连接
- [ExoPlayer](https://exoplayer.dev/) - 视频解码和播放

### 依赖问题解决

在开发过程中，我们遇到了Mobile-FFmpeg库的依赖问题，因为该库在Maven Central上不可用。为了解决这个问题，我们采用了Google的ExoPlayer库作为替代方案，它是一个功能强大且维护良好的媒体播放库。

## 使用方法

1. 确保网络摄像头已连接并运行在IP地址 `192.168.1.87`
2. 启动应用
3. 点击"连接到视频流"按钮
4. 应用将自动连接到摄像头并开始播放视频

## 代码结构

```
src/main/java/com/ai/bb/myapplication/
├── MainActivity.kt          # 主活动和UI界面
├── VideoStreamPlayer.kt     # WebSocket连接和视频流接收
├── FFmpegVideoPlayer.kt     # ExoPlayer视频解码和播放
```

## 工作原理

1. 应用通过WebSocket连接到摄像头的WebSocket端点
2. 连接成功后，发送HTTP请求获取视频流：
   ```
   GET http://192.168.1.87:80/livestream.cgi?stream=11&action=play&media=video_audio_data HTTP/1.1
   Connection: Keep-Alive
   Cache-Control: no-cache
   Authorization: Basic YWRtaW46YWRtaW4=
   Content-Length: 57

   Cseq: 1
   Transport: RTP/AVP/TCP;unicast;interleaved=0-1
   ```
3. 摄像头通过WebSocket发送视频数据
4. 应用使用ExoPlayer解码视频数据并在SurfaceView上播放

## 配置说明

默认配置：
- 摄像头IP地址: `192.168.1.87`
- 端口: `80`
- 用户名: `admin`
- 密码: `admin`
- 视频流ID: `11`

如需修改这些配置，可以在 `VideoStreamPlayer.kt` 和 `MainActivity.kt` 中进行相应调整。

## 注意事项

1. 确保设备可以访问摄像头的IP地址
2. 确保摄像头支持WebSocket连接和HTTP流媒体请求
3. 视频格式必须是ExoPlayer支持的格式
4. 应用需要网络权限才能连接到摄像头

## 技术改进和问题解决

在开发过程中，我们解决了多个技术问题：

1. **线程安全问题**：
   - ExoPlayer只能在主线程上访问，我们通过Handler确保所有操作都在主线程执行

2. **资源管理问题**：
   - 使用AtomicBoolean确保播放状态的线程安全
   - 正确处理管道流的打开和关闭
   - 在播放器停止时正确清理所有资源

3. **异常处理**：
   - 添加了详细的日志记录便于调试
   - 正确处理IOException和其他异常情况
   - 避免在正常关闭过程中记录不必要的错误日志

## 可能的改进

1. 添加对不同视频格式的支持
2. 实现更好的错误处理和重连机制
3. 添加音视频同步功能
4. 优化UI界面，添加播放控制按钮
5. 支持自定义摄像头地址和认证信息
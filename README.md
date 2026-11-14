# LanMirror 1.8.0 — Android Studio工程 / 内部声音 / 智能分屏

标准Android Studio单模块Java工程，保留1.7的逐路延时/FPS、1至4路自适应分屏和长按放大；新增手机内部声音与逐窗口选声。包名`com.lanmirror.app`，versionCode12，minAndroid8/API26，compile/target Android17/API37。

## 直接用Android Studio打开

1. 解压源码包，Open打开含 [settings.gradle](<settings.gradle>) 的项目根目录，不是只打开app子目录。
2. 推荐Android Studio **Rabbit 2 / 2026.2.2**或兼容AGP9.5的更新版本；Gradle JDK选 **17**。
3. SDK Manager安装Android17/API37和Build Tools37.0.0，Sync后选择app运行/构建。
4. 固定AGP9.5.1＋Gradle9.6.0，已包含官方Wrapper及分发SHA256；第一次同步需联网下载构建工具。不需要另外安装Gradle，也不需要运行PowerShell才能编译应用。

```text
LanMirror-AS-1.8.0/
├─ settings.gradle
├─ build.gradle
├─ gradle.properties
├─ gradlew / gradlew.bat
├─ gradle/wrapper/
│  ├─ gradle-wrapper.jar
│  └─ gradle-wrapper.properties
├─ app/
│  ├─ build.gradle
│  └─ src/main/
│     ├─ AndroidManifest.xml
│     └─ java/com/lanmirror/app/
├─ tests/com/lanmirror/app/
├─ test.ps1
├─ build-apk.ps1
├─ AS_BUILD.md
└─ VALIDATION.md
```

命令行构建：

```powershell
.\gradlew.bat --no-daemon :app:assembleDebug
.\gradlew.bat --no-daemon :app:assembleRelease
```

详细工具链、官方依据、签名区别和本机配置见 [AS_BUILD](<AS_BUILD.md>)。项目无第三方运行时依赖；AGP内建Kotlin已对这个纯Java模块关闭。SDK位置由你的AS生成，不在源码中硬编码本机路径。

**签名注意：** 单独交付的APK沿用以前的开发签名，可以覆盖安装旧版。AS的Debug包使用你本机Debug key，与下载包通常不同，不能直接覆盖不同签名的同包名应用。可用独立测试设备，或先卸载（会丢应用数据），或自行配置一致且安全保存的签名。Release默认不配置私钥。源码不含任何keystore/jks。

## 声音怎么使用

1. **要使用声音，两端都更新1.8.0。** 接收端进入“自动发现 / 最多四路”并保持前台。
2. 发送端选择多路UDP或多路TCP，勾选“传输内部声音”。允许系统的录音权限与屏幕捕获授权，再由接收端逐个批准。
3. 每个窗口右上有声音按钮。`声音 ✓`表示当前选择该路；点其它窗口的`选声音`切换到那路，点已选按钮使全部静音。
4. **每当新一路加入，默认选中最新加入的一路。** 手动选择会保持到下一次新路加入；当前选中路断开后，改选最近加入且仍活动的一路。主动静音后，其他路的普通状态更新或断开不会重新打开声音。
5. 放大/恢复画面**不改变声音选择**。放大期间若新路加入，声音仍按“最新加入”规则切到新路；恢复分屏可操作对应按钮。
6. 点标题/指标，或长按声音按钮，可看该路声音传输状态、播放/焦点状态和视频报告。黄色`声音 !`表示选中路的接收播放错误，不是正常播出的保证。

声音按钮表示路由选择，不能保证音源一定输出可捕获的声音。未选中的路仍采集/传输，接收端不积攒它们的待播PCM；没有将多路声音混在一起，也没有偷偷启用麦克风。

### 系统捕获限制

- **发送端内部捕获要求Android10/API29及以上**；Android8/9只能投视频。接收播放仍支持minAPI26。
- 使用AudioPlaybackCapture，仅匹配系统允许的MEDIA/GAME/UNKNOWN音频，不是所有系统提示音、语音通讯或通话都能捕获。
- 音源应用必须允许捕获，受保护/DRM内容、工作资料、系统策略可能导致无声。收到全零PCM也可能只是内容本来静音，不能据此断言被拒绝。
- Android把内部捕获权限归在RECORD_AUDIO，所以会出现录音授权；本模式**不调用MIC音源**，不申请麦克风前台服务类型，拒绝时不会改录麦克风。
- 拒绝录音权限会询问“仅投画面 / 取消”，不静默降级。
- 内录可能包含其它允许捕获应用正在播放的声音。开启声音时在Android14+请求整个默认屏幕授权，避免把全设备内录伪装成仅一个应用的音频。
- 音频通道失败、捕获失败、焦点被占用时报告声音状态，不主动关闭视频。硬件音频实例发生致命错误后，需重新进入接收端恢复；不会为了重试音频自动断开四路视频。

## 声音传输与资源边界

- 固定PCM16、48kHz、双声道，20ms块，最大3840字节；每路有效PCM约**1.536Mbps**，四路约6.144Mbps，另有网络开销。没有AAC/Opus编码开销，也不额外占四个音频MediaCodec。
- 审批v2在原视频TCP上返回动态声音TCP端口和128bit随机会话令牌。声音通道还检查来源IP，只在获准后开放，一次有效连接后关闭监听；不在发现广播中公布令牌。
- 视频仍在原有SmoothWire/UDP/TCP通道，不把PCM塞入视频写入锁。声音独立有界队列最多6帧；超时/过旧数据丢弃，音频写入阻塞只终止音频通道。
- 接收端共享一个AudioTrack，仅播放所选会话，队列居留120ms、捕获年龄250ms软件门限；请求约60ms输出缓冲，原生输入/输出最小缓冲无法满足120ms上限时会明确报告声音不支持，而不是积压几秒。
- 窗口切源用sessionId＋generation清旧队列，由音频线程pause/flush后写新源。**已经进入native或硬件的短尾音不能同步撤回**，不承诺零尾音。
- 使用视频连接的时钟校准换算音频时间；AudioRecord时间戳不可可靠映射时用读取返回时刻减块时长估算并提示。当前没有基于实际屏幕呈现反馈的精准唇同步校准。
- 系统音频焦点丢失时暂停/清空声音，不循环抢占；可再次点按钮重新选声。临时失焦恢复后只播放新数据。
- 捕获与播放各由后台owner线程操作原生对象，UI关闭不等待native。每进程各一个捕获/播放原生名额，只有原生release成功才归还；驱动挂住或释放失败可能需要系统强行停止应用进程。

## 原画面功能保留

| 路数/操作 | 布局 |
| --- | --- |
| 1路 | 铺满视频显示区域 |
| 2路 | 按源画面比例选择左右或上下 |
| 3路 | 一大两小，选择主窗在左或在上；最早活动会话为主窗 |
| 4路 | 四宫格 |
| 长按画面/标题/指标 | 放大该路，再长按恢复；返回先恢复分屏 |

评分扣除标题/指标/声音按钮区，比较等比例画面的利用面积，不裁剪拉伸、不用AI识别内容。四个SurfaceView只建立一次，布局不重挂、不设置GONE；非参与项停在遮罩下1×1像素，放大隐藏路仍接收/解码。真实OEM Surface销毁仍按原逻辑关闭相应会话，不能用几何测试保证系统永不重建Surface。

每路显示约每秒更新的阶段延时和提交FPS；点详情看累计帧数、平均/最大值。超过约2.5秒无新指标，500ms周期检查后显示“指标未更新 · --fps”。无延时样本显示`--ms`，不冒充0。

**延时是编码输出观察点→接收端Surface提交估算，不含此前采集/编码内部和此后系统合成/面板扫描。FPS为成功提交计数除实际时间，不是配置60、物理面板FPS或不同内容帧数。不能据此证明70ms端到端。**

H.264、目标1080p60，默认24Mbps，可手动32/40/60/18/12Mbps及720p60/6Mbps。保持原池化传输、12帧/8MiB视频队列、PTS、CONFIG_ACK代际门控及GOP恢复；没有因加声音或放大而自动降低画质。

## 兼容、网络与四路性能

- 关闭“内部声音”后使用原审批v1，与1.6/1.7多路视频兼容；开启声音需新版接收端，旧版不支持v2时明确提示升级或关闭声音后重连。
- 单路PIN和旧同步兼容模式仍保留，但本次声音功能针对多路接收模式。
- 自动发现仍为NSD `_lanmirror._tcp.`，支持原审批服务标识；实际监听成功后发布，离开/切后台停止接收及发布。
- 原端口：多路TCP7239审批/控制及TCP视频，UDP视频另用动态UDP；单路流畅TCP7237，旧同步TCP7236。**声音额外用动态TCP端口**，防火墙不能只放行7239。
- AP隔离、访客网络/VLAN和部分热点固件可阻止发现或互通；手填IP不绕过隔离。
- 最多4活动、8待批准、12已accept连接；申请头5秒、批准30秒限制及跨接收页面的4个视频原生解码名额保持。名字/UUID不是认证，每次重连仍需批准。
- 4×24Mbps视频＋4路PCM已有约102Mbps有效载荷，再加协议/重传/无线开销。四个应用槽不等于所有设备支持4×1080p60；请逐路增加，观察报告、丢弃、发热。
- UDP是专用分片协议，不是RTP/WebRTC/KCP/QUIC；没有完整拥塞控制、FEC或加密。音视频都只用于可信私有局域网，勿暴露公网；会话令牌不防旁路监听。

## 验证与开发入口

- [验证记录](<VALIDATION.md>)：SDK/Gradle构建、签名与21组JVM测试证据和边界。
- [MainActivity](<app/src/main/java/com/lanmirror/app/MainActivity.java>)：发送设置及权限。
- [MultiReceivePanel](<app/src/main/java/com/lanmirror/app/MultiReceivePanel.java>)：智能布局、指标及声音按钮。
- [MultiReceiver](<app/src/main/java/com/lanmirror/app/MultiReceiver.java>)：审批与共享声音路由。
- [InternalAudioCapture](<app/src/main/java/com/lanmirror/app/InternalAudioCapture.java>) / [RealtimeAudioPlayer](<app/src/main/java/com/lanmirror/app/RealtimeAudioPlayer.java>)：捕获/播放owner。
- [AudioLaneWire](<app/src/main/java/com/lanmirror/app/AudioLaneWire.java>) / [AudioLaneSender](<app/src/main/java/com/lanmirror/app/AudioLaneSender.java>) / [AudioLaneReceiver](<app/src/main/java/com/lanmirror/app/AudioLaneReceiver.java>)：独立声音网络。
- [测试脚本](<test.ps1>)：纯JVM main入口测试，不是JUnit；见AS说明，不将Gradle NO-SOURCE当测试成功。
- [独立SDK脚本](<build-apk.ps1>)：可选备用路径，生成临时Manifest，不修改Gradle源清单；不需要用它才能在AS构建。

**未进行安卓真机内部捕获、音质、音画同步、窗口触控/横竖屏、四路硬解、mDNS或热稳定验收；未证明1080p60/端到端70ms达标。** 不提供音频录制保存、麦克风、混音、远程触控、H.265或DRM/FLAG_SECURE绕过。
"# LanMirror" 

# LanMirror 1.8.0 构建与验证

日期：2026-11-13。

## 最终交付APK

- [安装包](<../LanMirror-1.8.0.apk>)：**由Gradle Release实际构建，再沿用旧开发证书签名**，不是拿独立javac的结果冒充Gradle构建。
- 包名`com.lanmirror.app`，versionCode12/name1.8.0，minAPI26，compile/target API37（Android17）。
- 大小：152143字节。
- APK SHA256：`BBBB7B49A1CDCDB4EC0676D04C6E39D2943B4C43137A67E012A1CE4EB795EFBB`。
- 签名v2/v3验证通过；证书SHA256：`96e7673cdfff6ff083f6b48edb4bb8f2a87a85db76e95de1ae959870148584ac`，与旧版一致。
- Gradle Release原包zipalign校验通过；签名后全部DEX（1个）逐一SHA256核对，与原Release完全一致。
- aapt2确认RECORD_AUDIO、mediaProjection前台服务等权限；没有麦克风前台服务权限。microphone硬件标明required=false，不因仅接收或内录就要求物理麦克风。
- Release VCS元数据为`NO_SUPPORTED_VCS_FOUND`，未嵌入仓库地址/凭据。

## Android Studio / Gradle

标准单模块application，现有业务Java源直接由Gradle构建，没有第二份需要同步维护的业务代码。

- AGP9.5.1稳定版，Gradle Wrapper9.6.0，JDK17，BuildTools37.0.0。
- 官方Wrapper JAR和分发ZIP均核对官方SHA256；Wrapper属性固定分发校验。
- 无第三方运行时依赖，`debugRuntimeClasspath`显示`No dependencies`；纯Java模块关闭AGP内建Kotlin，没有Kotlin编译任务/标准库。
- 未用`android.suppressUnsupportedCompileSdk`隐藏不支持API37的问题；所选AGP官方支持API37。
- 初始工程两次真实Debug构建成功，分别2分58秒、37秒；最终完整音频代码执行`:app:assembleDebug :app:assembleRelease`，**BUILD SUCCESSFUL，5分52秒，76项任务（43执行、8缓存、25已最新）**，包含Release lintVital。
- 最终代码另经独立SDK构建验证；独立脚本在build目录生成临时Manifest，不修改Gradle源清单。
- 只有普通Java8 bootstrap-classpath（独立脚本）和弃用API说明，不是编译失败。

工具链依据与AS导入步骤见 [AS_BUILD](<AS_BUILD.md>)。第一次同步仍需网络和本机Android SDK；源码不带SDK、Gradle缓存、本机配置或私钥。

## 21组JVM逻辑/回环测试

[test.ps1](<test.ps1>) 全部通过，**667037次断言/检查，正常进程退出0**。大部分数字来自几何遍历和随机样本，绝非同等数量的独立实机案例。

| 范围 | 数量 |
| --- | ---: |
| 原1.7的19组协议、审批、发现、队列、指标、布局和放大回归 | 656928 |
| [PcmAudioCoreTest](<tests/com/lanmirror/app/PcmAudioCoreTest.java>) | 10022 |
| [AudioProtocolTest](<tests/com/lanmirror/app/AudioProtocolTest.java>) | 87 |

### 新增音频覆盖

- PCM4字节采样帧对齐，4至3840字节合法长度遍历、构造复制所有权及数据边界。
- 队列6帧/120ms上限、捕获年龄和未来异常、清空/关闭、切源generation与旧项失效。
- 音频焦点允许/拒绝/临时失去/永久失去及显式重新选择；不是实机音频焦点测试。
- 原生lease幂等、确认释放、失败隔离和并发申请。
- 审批v2显式请求音频、令牌/端口/PIN校验、拒绝、可选音频不可用而保留视频；原v1测试继续通过。
- PCM网络截断、非法长度/序号、状态长度和UTF-8拒绝。
- 真实本机TCP审批后，原视频socket继续SmoothWire/时钟/CONFIG_ACK；声音使用独立通道。
- 错误音频令牌被拒，正确能力可随后建立；音频终止状态传达后，视频仍可继续传帧和正常结束。
- 四个独立本机PCM通道检查各自源字节，未交叉混源；不是四个安卓设备的播放测试。
- 选声按加入顺序而非申请ID大小；最新加入默认、手动切换、全部静音、旧槽复用及断开回退。

第一次总回归在所有断言已输出PASS后，宿主Windows PowerShell异常退出`0xC0000005`。Windows Application事件1000/1001确认故障程序是`powershell.exe`、模块`clr.dll`，没有JVM崩溃日志。随后隔离重跑全部21组，全部通过并正常退出0；未把该宿主异常忽略成成功。

## 音频集成与生命周期检查

- [InternalAudioCapture](<app/src/main/java/com/lanmirror/app/InternalAudioCapture.java>) 只用API29+ AudioPlaybackCapture MEDIA/GAME/UNKNOWN，无MIC回退，不stop共享MediaProjection。
- [MainActivity](<app/src/main/java/com/lanmirror/app/MainActivity.java>) 明确勾选内部声音并申请RECORD_AUDIO；拒绝后让用户选仅画面或取消。内录可含其它应用声音，Android14+开启音频时请求整个默认显示屏授权。
- [ApprovalProtocol](<app/src/main/java/com/lanmirror/app/ApprovalProtocol.java>) v1不增加字段；v2新增音频端口/随机能力。只有获批且具备视频名额后才开放音频监听。
- [AudioLaneReceiver](<app/src/main/java/com/lanmirror/app/AudioLaneReceiver.java>) 检查控制会话来源IP及128bit令牌，候选数、等待和整条消息读取有界。
- [AudioLaneSender](<app/src/main/java/com/lanmirror/app/AudioLaneSender.java>) 独立TCP、6帧队列、过旧丢弃及阻塞看门狗；不取得视频写入锁，错误仅停止音频。
- [RealtimeAudioPlayer](<app/src/main/java/com/lanmirror/app/RealtimeAudioPlayer.java>) 共享一个AudioTrack，非选中源不排队。focus及native操作都在owner，切源清旧队列且flush。fatal状态在后续选声/新窗口仍会显示，不被普通传输状态覆盖。
- [MultiReceiver](<app/src/main/java/com/lanmirror/app/MultiReceiver.java>) 串行化声音选择并附revision；[MultiReceivePanel](<app/src/main/java/com/lanmirror/app/MultiReceivePanel.java>) 以页面epoch、sessionId和revision过滤旧UI事件。声音选择与图像放大独立。
- 截断/权限/音频设备错误不主动关闭视频。原视频MediaProjection、编解码队列、native名额隔离和智能Surface布局未改成另一条管线。

## 未完成的真实设备验收

没有安卓真机内部音频捕获、允许/禁止捕获应用矩阵、音质、短尾音、蓝牙延时、音画同步、选声按钮触控、横竖屏/Surface恢复、mDNS、四路硬解、吞吐或热稳定数据。

因此不能宣称所有Android8–17设备均已实机通过、4×1080p60硬解有保证、或声音/画面达到70ms端到端。AudioRecord/AudioTrack超过120ms最小原生缓冲上限会明确报不支持；零PCM可能是正常静音或音源策略。应用无法同步撤回已经送入硬件的短尾音，也未做精准唇同步校准。

HUD仍是编码输出观察点→Surface提交估算与提交FPS，不是玻璃到玻璃延时/面板刷新测量。共享驱动、系统杀进程及OOM不能保证只影响一路。音视频未加密，仅可信局域网；不绕过DRM/FLAG_SECURE或捕获权限。

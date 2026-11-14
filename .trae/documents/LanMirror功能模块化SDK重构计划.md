# LanMirror 功能模块化 SDK 重构计划

## 背景与目标（Context）

当前约50个生产Java类集中在app模块与com.lanmirror.app包中，发送引擎依赖SenderService，视频收发直接构造音频实现，无法按功能独立发布与集成。

用户已选择：发送、接收、发现独立依赖，音频可选；保留现有app作为集成示例，提供公开API与Maven本地发布能力。本次不上传远程仓库、不创建正式签名密钥、不变更传输协议或引入加固。

## 目标模块

| 模块                | 产物 | 职责                                                                                      |
|---------------------|------|-------------------------------------------------------------------------------------------|
| lanmirror-core      | JAR  | 共享协议、TCP/UDP传输基础、帧/缓冲池/队列、时钟、指标、准入模型、音频扩展契约             |
| lanmirror-discovery | AAR  | NSD设备发现与广播、发现事件API                                                            |
| lanmirror-sender    | AAR  | 旧版及Smooth发送引擎、编码器、VirtualDisplay、发送会话API                                 |
| lanmirror-receiver  | AAR  | 单路与四路接收、审批、解码器、Surface与接收事件API                                        |
| lanmirror-audio     | AAR  | 内部音频采集、PCM独立传输、共享播放、音源选择与音频焦点                                   |
| app                 | APK  | 权限和MediaProjection授权、前台Service/通知、Activity、Panel、布局与HUD；示例组合全部模块 |

依赖方向：四个Android库只依赖core，不互相依赖；app组合所需库。core不得引用Android或app。无音频的宿主不依赖audio，不申请RECORD_AUDIO，不建立音频通道。

## API与解耦设计

-
包按com.lanmirror.core、sender、receiver、discovery、audio划分，区分api与internal；不继续用共享app包绕过访问限制，不批量将所有类改为public。
- 发送门面提供配置、启动/停止、源尺寸更新和状态事件；覆盖现有旧TCP、Smooth
  TCP/UDP、审批多路发送模式。提取独立编码器工厂与旧发送引擎，消除SmoothSender对SenderService字段、嵌套Encoder及工具方法的访问。
-
app持有MediaProjection授权及前台生命周期；SDK拥有自身创建的编码器和VirtualDisplay，释放时不擅自停止宿主借用的MediaProjection。明确停止/授权撤销/尺寸变更调用顺序。
- 接收门面保留SurfaceProvider、审批决定、断开、Surface丢失和状态/指标事件能力。复用已有Listener，不泄漏内部解码器或队列。
-
core定义最小纯Java音频扩展契约：发送音频会话工厂、接收音频端点预留与启动、选择/会话退出、状态回调及关闭。接口不出现Context、MediaProjection或PCM实现类型；audio工厂通过构造参数捕获Android依赖，由app显式注入。缺省禁用，仍按原审批协议返回无音频端口和状态。
- 保持音频端口预留→批准→时钟就绪→读取的顺序；音频错误只终止声音。保持单次会话、幂等取消、帧引用所有权、回调线程及native释放约束，并在新公开API注释中说明。

## 实施步骤与关键文件

1.
检查Git差异并保留已有gradle.properties修改；运行21组main式测试建立基线。探测AGP/Gradle所需JDK，优先现有兼容安装，仅设置构建进程环境；不修改全局JAVA_HOME或擅自升级工具链。
2.
修改settings.gradle、根build.gradle及app/build.gradle，新增库构建配置与必要Manifest，统一SDK坐标com.lanmirror:
lanmirror-<功能>，初始版本沿用1.8.0。不改示例应用ID和版本。
3.
迁移纯Java共享代码与discovery，更新包/import和测试归属。复用Wire、SmoothWire、UdpVideoTransport、FrameBufferPool、StreamFrame及现有队列，不改算法。Wire.requireCode等跨包调用以最小共享接口处理。
4.
在core引入音频扩展契约；从app/src/main/java/com/lanmirror/app/SenderService.java提取发送实现和编码器，从SmoothSender.java移除Service与音频具体类依赖。Service保留通知、授权回调和薄生命周期桥接。
5.
迁移MultiReceiver.java、ApprovedPeerSession.java、AsyncSurfaceDecoder.java、SmoothReceiver.java、ReceiverSession.java；以音频契约替换具体音频依赖。迁移InternalAudioCapture.java、RealtimeAudioPlayer.java、AudioLane*
.java与PCM类到audio并实现扩展契约。
6.
更新MainActivity.java、MultiReceivePanel.java等示例调用，确保app不引用SDK内部实现；布局/HUD保持在app。按模块迁移tests中的测试，保留main测试方式；更新test.ps1编译清单/类路径/入口，增加必要API与无音频回归测试。
7.
库配置maven-publish：core发布Java组件，Android库发布release组件及sources，生成包含依赖的POM与Gradle模块元数据。公开签名涉及core的模块使用api，其余implementation。按实际需求配置consumer
rules，不整包keep，不改变app现有混淆行为。
8.
build-apk.ps1改为调用统一Gradle构建入口，不再单独扫描app源码用D8打包。保留明确的开发签名输出用途与参数入口；开发签名不得冒充正式发行签名，签名变更需提示安装升级影响，不覆盖已有密钥。

迁移目标路径模式：lanmirror-<功能>/src/main/java/com/lanmirror/<功能>
/…；新配置、接口及测试文件仅按实施需要创建。现有README/VALIDATION等文档不作为本次自动改写范围。

## 验证与验收

- 原21组纯Java测试全部通过，新增无音频协商/音频注入失败隔离以及SDK生命周期契约相关测试。
- 构建所有库与示例APK，运行可用的lint检查；验证备用脚本确实经过Gradle。构建成功不等于真机验收。
- 执行各库publishToMavenLocal。临时独立消费者放系统临时目录，不污染项目；仅通过发布坐标验证仅发送、仅接收、仅发现、视频无音频、视频带音频组合可以解析和编译。
-
检查POM/.module及依赖图：sender/receiver不传递依赖audio，库不依赖app，无循环依赖；core无Android引用；无音频消费者Manifest不引入RECORD_AUDIO。用开启R8的消费者检查公开入口的编译与规则兼容性。
-
若有获准使用的真机，验证发现、授权、旧/新TCP、UDP、审批接受/拒绝、四路隔离、Surface销毁/重建、选声/静音及音频失败继续视频；没有设备则明确列出未验证项，不宣称低延迟或硬件行为已通过。
- 最终交付：模块源码、公开API、可运行app示例、可本地发布的JAR/AAR及验证结果。仅发布到本地，不上传远程、不提交Git。

## 范围约束

不引入FEC、ABR、Opus、协议加密、反逆向；不调码率/缓冲/重传阈值，不删除兼容模式，不承诺拆模块本身改善性能。后续SDK兼容承诺以本次整理后的公开API为基础。

# Android Studio / Gradle 构建

此工程是标准单模块Android application，使用现有Java源码和Manifest；没有另复制一份代码。打开本目录即可导入，模块为`:app`。

## 固定并已核对的工具链

| 工具 | 固定版本/要求 |
| --- | --- |
| Android Gradle Plugin | **9.5.1**，稳定发布，不使用alpha/rc |
| Gradle Wrapper | **9.6.0**，官方bin分发 |
| JDK | **17**或工具链兼容的较新版；本次验证使用JDK17 |
| Android SDK | compile/target **37**，Build Tools **37.0.0**，minSdk26 |
| Android Studio | 推荐 **Rabbit 2 / 2026.2.2**（兼容AGP9.5），或明确支持该AGP的后续版本 |
| 应用 | `com.lanmirror.app`，versionCode12 / versionName1.8.0 |

官方依据（2026-11-13查询）：

- [AGP9.5发布说明](https://developer.android.com/build/releases/agp-9-5-0-release-notes?hl=en)：最大支持API37，最低/默认Gradle9.6.0，JDK17；同页列出9.5.1。
- [Google Maven版本元数据](https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/maven-metadata.xml)：9.5.1稳定制品确实存在。
- [Android Studio兼容表](https://developer.android.com/studio/releases?hl=en#android_gradle_plugin_and_android_studio_compatibility)：Rabbit2 / 2026.2.2支持AGP7.1–9.5。
- [纯Java模块禁用内建Kotlin的官方说明](https://developer.android.com/build/migrate-to-built-in-kotlin?hl=en)：模块可用`enableKotlin = false`，避免自动Kotlin标准库依赖，不需要退回旧DSL。

未添加`android.suppressUnsupportedCompileSdk`，不能用旧Studio无法同步来判断Java业务代码出错；先匹配上述工具链。

## 导入与构建

1. Android Studio选择Open打开项目根目录。
2. 在本机SDK Manager安装Android17/API37和BuildTools37.0.0。
3. 用IDE生成本机`local.properties`，或设置当前进程Android SDK环境变量；该本地文件不应进源码包。不要把另一台机器的SDK路径照搬进可移植配置。
4. Gradle JDK选择17（或兼容版本），Sync Project，选择`:app`运行/构建。

命令行示例（把路径换成自己的JDK/SDK，不改系统环境）：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
$env:ANDROID_HOME = 'D:\Android\SDK'
.\gradlew.bat --no-daemon :app:assembleDebug
```

Linux/macOS需保留脚本执行位，或用`sh ./gradlew --no-daemon :app:assembleDebug`。首次运行需联网下载固定Gradle及Google/Maven Central构建依赖，不要求预装系统Gradle。

输出为`app/build/outputs/apk/debug/app-debug.apk`。Gradle Debug默认使用当前用户的Android debug key，**不使用已交付APK的开发签名私钥**。如果手机已安装另一个签名的同包名APK，不能直接覆盖安装不同Debug签名；需要卸载（会删除应用数据）或自行配置一致且安全保管的签名。Release刻意不配置签名，不读取原开发keystore。

`namespace`和applicationId均由模块Gradle定义；现代AGP要求源Manifest不再带`package`。min/target/version也由defaultConfig控制。独立脚本构建生成自己的临时Manifest，两种构建不互相硬改源清单。

## 官方下载与完整性

[wrapper属性](<gradle/wrapper/gradle-wrapper.properties>) 固定：

```text
distributionUrl=https://services.gradle.org/distributions/gradle-9.6.0-bin.zip
distributionSha256Sum=bbaeb2fef8710818cf0e261201dab964c572f92b942812df0c3620d62a529a01
```

实际下载的140681093字节ZIP已与[官方分发SHA256](https://downloads.gradle.org/distributions/gradle-9.6.0-bin.zip.sha256)核对一致。wrapper启动时也配置了分发SHA256校验。

[wrapper JAR](<gradle/wrapper/gradle-wrapper.jar>)、[Unix脚本](<gradlew>)和[Windows脚本](<gradlew.bat>)来自[Gradle官方v9.6.0 tag](https://github.com/gradle/gradle/tree/v9.6.0)。wrapper JAR实际SHA256与[官方wrapper checksum](https://downloads.gradle.org/distributions/gradle-9.6.0-wrapper.jar.sha256)一致：

```text
497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7
```

该tag仓库自身wrapper属性指向构建Gradle自身用的9.6.0-rc-3；本项目没有照抄该属性，而是明确固定已发布9.6.0稳定ZIP及其官方校验。wrapper二进制与脚本保留官方内容。

为避免重复下载，本次验证把已经核对的ZIP放到隔离的wrapper缓存路径；路径按官方[PathAssembler](https://raw.githubusercontent.com/gradle/gradle/v9.6.0/platforms/core-runtime/wrapper-shared/src/main/java/org/gradle/wrapper/PathAssembler.java)的URL哈希计算。真正执行的是项目自带`gradlew.bat`，不是拿手工javac冒充Gradle验证。

## 本次验证隔离

验证命令仅给当前进程设置：

```powershell
$env:GRADLE_USER_HOME = "$PWD\.gradle-user-home"
$env:ANDROID_USER_HOME = "$PWD\.android-user-home"
```

因此下载、Gradle缓存及本次自动生成的标准Debug key均留在当前工作目录下并被忽略。没有修改全局Studio/Gradle配置，没有清理用户已有缓存。本机`local.properties`仅用于这台验证机器，不可打包。

实际执行结果：

- 第一轮真实`gradlew.bat --no-daemon :app:assembleDebug`：**BUILD SUCCESSFUL**，2分58秒，33项任务执行。
- 关闭本模块内建Kotlin后，第二轮`assembleDebug`＋`dependencies --configuration debugRuntimeClasspath`：**BUILD SUCCESSFUL**，37秒；运行时依赖报告为**No dependencies**，没有Kotlin编译任务。APK内DEX也未包含Kotlin类描述符。
- aapt2确认Debug APK包名`com.lanmirror.app`、versionCode12/name1.8.0、min26/target37/compile37；签名校验通过。本次隔离Debug证书SHA256为`a55a88fc962c9ea734fc8074c1e938dcd8c70d53cf6772dfd0a91ad0d72986a8`，不是单独交付APK使用的开发证书。
- 首轮发现的`android.useAndroidX=false`弃用选项已移除；模块使用官方`enableKotlin = false`，而不是关闭现代DSL。Groovy属性改为`name = value`，不隐藏弃用或unsupported SDK警告。
- 最终赋值语法修正后另跑`help`＋`debugRuntimeClasspath`配置验证：**BUILD SUCCESSFUL**，30秒，No dependencies，未出现上述工程配置警告。
- 完整音频业务合并后，真实执行`:app:assembleDebug :app:assembleRelease`：**BUILD SUCCESSFUL**，5分52秒，76项任务（43执行、8缓存、25已最新）；包括Release的lintVital。另通过独立SDK构建。最终交付包由Gradle Release重签，DEX与该Release完全一致；这不代表真机音频/性能验收。

### 原有纯逻辑测试

现有测试是根目录`tests`中的Java `main()`入口，不是JUnit、也不在Gradle默认的`app/src/test`路径。不要把`testDebugUnitTest`的NO-SOURCE当成这些测试已通过。继续用项目已有的[测试脚本](<test.ps1>)：

```powershell
pwsh -File .\test.ps1 -JavaHome $env:JAVA_HOME
```

该脚本是纯Java测试清单的唯一维护位置，本Gradle配置没有复制一份易失同步的类名列表；当前测试脚本已包含音频新增测试。

## 应纳入源码包

- [settings.gradle](<settings.gradle>)、[根build.gradle](<build.gradle>)、[模块build.gradle](<app/build.gradle>)、[gradle.properties](<gradle.properties>)。
- [gradlew](<gradlew>)、[gradlew.bat](<gradlew.bat>)、[wrapper属性](<gradle/wrapper/gradle-wrapper.properties>)及[wrapper JAR](<gradle/wrapper/gradle-wrapper.jar>)。
- [.gitignore](<.gitignore>)、本说明，以及现有app源码、测试和README、验证记录和独立构建/测试脚本。

**不要把整个工作目录直接压缩。** 排除`.gradle`、`.gradle-user-home`、`.gradle-bootstrap`、`.android-user-home`、`.idea`、`local.properties`、所有build目录、APK/AAB、keystore/jks和临时脚本/日志。wrapper JAR是必须保留的构建基础文件，不应被“删除所有jar”规则误删。

官方Gradle分发许可和声明一并保留：[LICENSE](<gradle/wrapper/LICENSE>) / [NOTICE](<gradle/wrapper/NOTICE>)。

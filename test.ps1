param([string]$JavaHome = 'C:\Program Files\Java\jdk-17')
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$out = Join-Path $root 'build\tests'
New-Item -ItemType Directory -Force -Path $out | Out-Null
$sources = @(Get-ChildItem (Join-Path $root 'lanmirror-core\src\main\java') -Recurse -Filter '*.java' | ForEach-Object { $_.FullName })
$sources += Join-Path $root 'lanmirror-discovery\src\main\java\com\lanmirror\discovery\internal\DiscoveryRegistry.java'
$sources += Join-Path $root 'lanmirror-receiver\src\main\java\com\lanmirror\receiver\internal\SafeAudioReceiver.java'
$sources += @(Get-ChildItem (Join-Path $root 'lanmirror-audio\src\main\java') -Recurse -Filter '*.java' | Where-Object {
    $_.BaseName -notin @('InternalAudioCapture','RealtimeAudioPlayer','InternalAudio') -and
    $_.BaseName -notlike 'Android*' -and
    (Get-Content -LiteralPath $_.FullName -Raw -Encoding UTF8) -notmatch '\bandroid\.'
} | ForEach-Object { $_.FullName })
$sources += @('AdaptiveLayoutPolicy','AdaptiveFocusState','StreamHud') | ForEach-Object {
    Join-Path $root "app\src\main\java\com\lanmirror\app\$_.java"
}
$tests = @('WireTest','Ipv4Test','StreamProfileTest','ReceiverProtocolV2Test','OutboundFrameQueueTest','ReceiverCadenceRegressionTest','ComparisonModesTest','CodecBootstrapPolicyTest','SmoothSendQueueTest','SmoothDecodeQueueTest','SmoothCodecClockTest','SmoothTransportTest','ApprovalProtocolTest','AdmissionSlotsTest','ApprovalWaiterTest','StreamMetricsTest') | ForEach-Object { "com.lanmirror.core.api.$_" }
$tests += 'com.lanmirror.discovery.internal.DiscoveryRegistryTest'
$tests += 'com.lanmirror.receiver.internal.AudioExtensionIsolationTest'
$tests += @('AdaptiveLayoutPolicyTest','StreamHudFocusTest') | ForEach-Object { "com.lanmirror.app.$_" }
$tests += @('PcmAudioCoreTest','AudioProtocolTest') | ForEach-Object { "com.lanmirror.audio.internal.$_" }
$testSources = @(Get-ChildItem (Join-Path $root 'tests') -Recurse -Filter '*.java' | ForEach-Object { $_.FullName })
& (Join-Path $JavaHome 'bin\javac.exe') -encoding UTF-8 -source 8 -target 8 -d $out @sources @testSources
if ($LASTEXITCODE -ne 0) { throw "Test compilation failed: $LASTEXITCODE" }
foreach ($test in $tests) {
    & (Join-Path $JavaHome 'bin\java.exe') -classpath $out $test
    if ($LASTEXITCODE -ne 0) { throw "Test failed: $test ($LASTEXITCODE)" }
}
Write-Output 'All JVM logic/loopback tests passed. This does NOT measure Android codec performance, Wi-Fi packet-loss rate, glass-to-glass latency or physical display FPS.'

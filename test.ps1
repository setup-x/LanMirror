param([string]$JavaHome = 'C:\Program Files\Java\jdk-17')
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$out = Join-Path $root 'build\tests'
New-Item -ItemType Directory -Force -Path $out | Out-Null
$sources = @('Wire','Ipv4','StreamProfile','EncoderBacklogPolicy','DropRecovery','LatencyStats','FrameTimestampTracker','FrameCadenceStats','OutboundFrameQueue','FrameStreamWriter','EncoderDrainPolicy','CodecBootstrapPolicy','FrameBufferPool','StreamFrame','SmoothWire','UdpVideoTransport','SmoothSendQueue','SmoothDecodeQueue','SmoothFrameMetadata','SmoothCodecClock','ApprovalProtocol','DiscoveryRegistry','AdmissionSlots','ApprovalWaiter','DecodeLeaseBudget','StreamMetrics','AdaptiveLayoutPolicy','AdaptiveFocusState','StreamHud','PcmAudioFrame','PcmAudioQueue','PcmAudioFocusState','PcmNativeLease','AudioLaneWire','AudioLaneReceiver','AudioLaneSender','AudioSelectionState') | ForEach-Object {
    Join-Path $root "app\src\main\java\com\lanmirror\app\$_.java"
}
$tests = @('WireTest','Ipv4Test','StreamProfileTest','ReceiverProtocolV2Test','OutboundFrameQueueTest','ReceiverCadenceRegressionTest','ComparisonModesTest','CodecBootstrapPolicyTest','SmoothSendQueueTest','SmoothDecodeQueueTest','SmoothCodecClockTest','SmoothTransportTest','ApprovalProtocolTest','DiscoveryRegistryTest','AdmissionSlotsTest','ApprovalWaiterTest','StreamMetricsTest','AdaptiveLayoutPolicyTest','StreamHudFocusTest','PcmAudioCoreTest','AudioProtocolTest')
$testSources = $tests | ForEach-Object { Join-Path $root "tests\com\lanmirror\app\$_.java" }
& (Join-Path $JavaHome 'bin\javac.exe') -encoding UTF-8 -source 8 -target 8 -d $out @sources @testSources
if ($LASTEXITCODE -ne 0) { throw "Test compilation failed: $LASTEXITCODE" }
foreach ($test in $tests) {
    & (Join-Path $JavaHome 'bin\java.exe') -classpath $out "com.lanmirror.app.$test"
    if ($LASTEXITCODE -ne 0) { throw "Test failed: $test ($LASTEXITCODE)" }
}
Write-Output 'All JVM logic/loopback tests passed. This does NOT measure Android codec performance, Wi-Fi packet-loss rate, glass-to-glass latency or physical display FPS.'

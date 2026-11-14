param(
    [string]$Sdk = 'D:\Android\SDK',
    [string]$JavaHome = 'C:\Program Files\Java\jdk-17'
)
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$tools = Join-Path $Sdk 'build-tools\37.0.0'
foreach ($path in @((Join-Path $tools 'apksigner.bat'), (Join-Path $JavaHome 'bin\java.exe'))) {
    if (!(Test-Path -LiteralPath $path)) { throw "Missing build tool: $path. Pass -Sdk and -JavaHome for SDK 37 and JDK 17+." }
}
function Run-Checked([string]$exe, [string[]]$arguments) {
    & $exe @arguments
    if ($LASTEXITCODE -ne 0) { throw "Build command failed ($LASTEXITCODE): $exe" }
}
$oldJava = $env:JAVA_HOME
$oldSdk = $env:ANDROID_HOME
$oldSdkRoot = $env:ANDROID_SDK_ROOT
try {
    $env:JAVA_HOME = $JavaHome
    $env:ANDROID_HOME = $Sdk
    $env:ANDROID_SDK_ROOT = $Sdk
    # AGP gives local.properties precedence over environment variables; never silently ignore -Sdk.
    $localProperties = Join-Path $root 'local.properties'
    if (Test-Path -LiteralPath $localProperties) {
        $match = [regex]::Match([IO.File]::ReadAllText($localProperties), '(?m)^sdk\.dir\s*=\s*(.+)$')
        if ($match.Success) {
            $configuredSdk = $match.Groups[1].Value.Trim().Replace('\:', ':').Replace('\\', '\')
            if ([IO.Path]::GetFullPath($configuredSdk).TrimEnd('\', '/') -ine [IO.Path]::GetFullPath($Sdk).TrimEnd('\', '/')) {
                throw 'The -Sdk path differs from sdk.dir in local.properties. Align them before building; no local configuration was overwritten.'
            }
        }
    }
    # Gradle owns compilation, dependency resolution, manifest merging and DEX packaging.
    Run-Checked (Join-Path $root 'gradlew.bat') @('-p', $root, ':app:assembleRelease')
    $out = Join-Path $root 'build'
    New-Item -ItemType Directory -Force -Path $out | Out-Null
    $unsigned = Join-Path $root 'app\build\outputs\apk\release\app-release-unsigned.apk'
    $aligned = Join-Path $out 'lanmirror-aligned.apk'
    Run-Checked (Join-Path $tools 'zipalign.exe') @('-f','-p','4',$unsigned,$aligned)
    $key = Join-Path $out 'development.keystore'
    if (!(Test-Path -LiteralPath $key)) {
        Run-Checked (Join-Path $JavaHome 'bin\keytool.exe') @('-genkeypair','-keystore',$key,'-storepass','android','-keypass','android','-alias','lanmirror-dev','-dname','CN=LanMirror Development','-keyalg','RSA','-keysize','2048','-validity','10000')
    }
    $apk = Join-Path $out 'LanMirror-1.8.0.apk'
    Run-Checked (Join-Path $tools 'apksigner.bat') @('sign','--ks',$key,'--ks-key-alias','lanmirror-dev','--ks-pass','pass:android','--key-pass','pass:android','--out',$apk,$aligned)
    Run-Checked (Join-Path $tools 'apksigner.bat') @('verify','--verbose',$apk)
    Run-Checked (Join-Path $tools 'aapt2.exe') @('dump','badging',$apk)
    Get-FileHash -Algorithm SHA256 -LiteralPath $apk
    Write-Warning 'Development signing only, not a production release. This key differs from Gradle debug signing; switching keys requires uninstalling the previous app.'
    Write-Output "APK ready: $apk"
} finally {
    $env:JAVA_HOME = $oldJava
    $env:ANDROID_HOME = $oldSdk
    $env:ANDROID_SDK_ROOT = $oldSdkRoot
}

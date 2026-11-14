param(
    [string]$Sdk = 'D:\Android\SDK',
    [string]$JavaHome = 'C:\Program Files\Java\jdk-17'
)
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$platform = Join-Path $Sdk 'platforms\android-37.0\android.jar'
$tools = Join-Path $Sdk 'build-tools\37.0.0'
foreach ($path in @($platform, (Join-Path $tools 'aapt2.exe'), (Join-Path $JavaHome 'bin\javac.exe'))) {
    if (!(Test-Path -LiteralPath $path)) { throw "Missing build tool: $path. Pass -Sdk and -JavaHome to point to Android 17 SDK and JDK 17+." }
}
$env:JAVA_HOME = $JavaHome
$env:PATH = "$JavaHome\bin;$env:PATH"
$out = Join-Path $root 'build'
$classes = Join-Path $out 'classes-1.8.0'
$dex = Join-Path $out 'dex'
New-Item -ItemType Directory -Force -Path $out, $classes, $dex | Out-Null
function Run-Checked([string]$exe, [string[]]$arguments) {
    & $exe @arguments
    if ($LASTEXITCODE -ne 0) { throw "Build command failed ($LASTEXITCODE): $exe" }
}
$sources = @(Get-ChildItem -LiteralPath (Join-Path $root 'app\src\main\java') -Recurse -Filter '*.java' | ForEach-Object FullName)
Run-Checked (Join-Path $JavaHome 'bin\javac.exe') (@('-encoding','UTF-8','-source','8','-target','8','-classpath',$platform,'-d',$classes) + $sources)
Run-Checked (Join-Path $JavaHome 'bin\jar.exe') @('cf', (Join-Path $out 'classes.jar'), '-C', $classes, '.')
Run-Checked (Join-Path $tools 'd8.bat') @('--release','--min-api','26','--lib',$platform,'--output',$dex,(Join-Path $out 'classes.jar'))
$generatedManifest = Join-Path $out 'standalone-manifest.xml'
$manifestText = [System.IO.File]::ReadAllText((Join-Path $root 'app\src\main\AndroidManifest.xml'),[System.Text.Encoding]::UTF8)
$manifestText = $manifestText.Replace('<manifest ', '<manifest package="com.lanmirror.app" ')
[System.IO.File]::WriteAllText($generatedManifest,$manifestText,[System.Text.UTF8Encoding]::new($false))
$unsigned = Join-Path $out 'lanmirror-unsigned.apk'
Run-Checked (Join-Path $tools 'aapt2.exe') @('link','-I',$platform,'--manifest',$generatedManifest,'-o',$unsigned,'--min-sdk-version','26','--target-sdk-version','37','--version-code','12','--version-name','1.8.0')
Run-Checked (Join-Path $JavaHome 'bin\jar.exe') @('uf',$unsigned,'-C',$dex,'classes.dex')
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
Write-Output "APK ready: $apk"

param([switch]$Test)
$ErrorActionPreference = 'Stop'
if (-not $env:JAVA_HOME -or -not (Test-Path (Join-Path $env:JAVA_HOME 'lib\jvm.cfg'))) {
    $javaCommand = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($javaCommand) {
        $env:JAVA_HOME = Split-Path (Split-Path $javaCommand.Source -Parent) -Parent
    } else {
        $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
    }
}
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$buildDirectory = Join-Path $env:LOCALAPPDATA 'Temp\umtomi-android-build'
$tasks = @('assembleDebug')
if ($Test) { $tasks += @('testDebugUnitTest', 'lintDebug') }
Push-Location $PSScriptRoot
try {
    & .\gradlew.bat "-PumtomiBuildDir=$($buildDirectory.Replace('\','/'))" @tasks --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Android build failed: $LASTEXITCODE" }
    $destination = Join-Path $PSScriptRoot '..\artifacts'
    New-Item -ItemType Directory -Force -Path $destination | Out-Null
    $outputDirectory = Join-Path $buildDirectory 'app\outputs\apk\debug'
    $metadata = Get-Content -Raw -LiteralPath (Join-Path $outputDirectory 'output-metadata.json') | ConvertFrom-Json
    $output = $metadata.elements | Select-Object -First 1
    if (-not $output.versionName -or -not $output.outputFile) { throw 'APK output metadata is incomplete' }
    $version = $output.versionName -replace '-DEBUG$', ''
    $apk = Join-Path $destination "Umtomi-v$version.apk"
    Copy-Item -LiteralPath (Join-Path $outputDirectory $output.outputFile) -Destination $apk
    $deliveryDirectory = 'C:\Users\peopl\OneDrive\DEV\APK'
    New-Item -ItemType Directory -Force -Path $deliveryDirectory | Out-Null
    $deliveredApk = Join-Path $deliveryDirectory (Split-Path $apk -Leaf)
    Copy-Item -LiteralPath $apk -Destination $deliveredApk
    if ((Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash -ne
        (Get-FileHash -LiteralPath $deliveredApk -Algorithm SHA256).Hash) {
        throw "APK delivery verification failed: $deliveredApk"
    }
    Get-Item -LiteralPath $deliveredApk | Select-Object FullName, Length
    Get-Item -LiteralPath $apk | Select-Object FullName, Length
    Get-FileHash -LiteralPath $apk -Algorithm SHA256
} finally { Pop-Location }

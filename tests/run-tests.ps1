$ErrorActionPreference = 'Stop'
$projectPath = Split-Path -Parent $PSScriptRoot
$outputPath = Join-Path $projectPath 'build\check'
$sourcePath = Join-Path $projectPath 'app\src\main\java\io\pocketpad\remote'
$compilerPath = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\javac.exe' } else { 'javac' }
$runtimePath = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { 'java' }
$sourceFiles = 'HidReports','KeyboardMapper','WindowsLayoutData','HostLayoutPreferences','KeyCatalog','LiveTextDiff','PointerMotion','TapSequence' |
    ForEach-Object { Join-Path $sourcePath ($_ + '.java') }
$testFiles = Get-ChildItem -LiteralPath $PSScriptRoot -Filter 'Check*.java' |
    ForEach-Object { $_.FullName }
New-Item -ItemType Directory -Force -Path $outputPath | Out-Null
& $compilerPath --release 17 -encoding UTF-8 -d $outputPath @sourceFiles @testFiles
if ($LASTEXITCODE -ne 0) { throw 'JVM test compilation failed' }
Push-Location -LiteralPath $projectPath
try {
    foreach ($testName in 'CheckHid','CheckLayouts','CheckHostLayouts','CheckLiveText','CheckPointerMotion','CheckTapSequence') {
        & $runtimePath -cp $outputPath "io.pocketpad.remote.$testName"
        if ($LASTEXITCODE -ne 0) { throw "Failed: $testName" }
    }
} finally { Pop-Location }

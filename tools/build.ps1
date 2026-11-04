param(
    [Parameter(Mandatory=$true)][string]$Jdk,
    [Parameter(Mandatory=$true)][string]$Sdk,
    [string]$Gradle = 'gradle',
    [string]$SigningProperties = '',
    [switch]$Test
)
$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
$env:JAVA_HOME = (Resolve-Path -LiteralPath $Jdk).Path
$env:ANDROID_HOME = (Resolve-Path -LiteralPath $Sdk).Path
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$arguments = @('-p', (Join-Path $project 'android'), '--console=plain')
if ($Test) { $arguments += ':app:testDebugUnitTest' }
if ($SigningProperties) {
    $arguments += '-PpreviewSigningFile=' + (Resolve-Path -LiteralPath $SigningProperties).Path
    $arguments += ':app:assembleRelease'
} else { $arguments += ':app:assembleDebug' }
& $Gradle @arguments
if ($LASTEXITCODE -ne 0) { throw "Android build failed with exit code $LASTEXITCODE" }

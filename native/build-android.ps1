$ErrorActionPreference = 'Stop'
foreach ($key in @('JAVA_HOME','ANDROID_HOME','ANDROID_NDK_HOME')) {
    if (-not [Environment]::GetEnvironmentVariable($key)) { throw "Set $key first" }
}
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$version = 'v0.0.0-20251126181937-5c265dc024c4'
function Run-Go([string[]] $Arguments) {
    & go @Arguments
    if ($LASTEXITCODE -ne 0) { throw "go failed: $Arguments" }
}
Run-Go @('install',"golang.org/x/mobile/cmd/gomobile@$version")
Run-Go @('install',"golang.org/x/mobile/cmd/gobind@$version")
$goBin = (& go env GOBIN)
if (-not $goBin) { $goBin = Join-Path (& go env GOPATH) 'bin' }
$env:Path = "$goBin;$env:Path"
Push-Location (Join-Path $PSScriptRoot 'ridedaemon')
try {
    Run-Go @('test','./hud/...')
    New-Item -ItemType Directory -Force -Path '../../app/libs' | Out-Null
    & gomobile bind -target=android/arm64,android/arm -androidapi=24 -o ../../app/libs/ridedaemon.aar ./hud/api
    if ($LASTEXITCODE -ne 0) { throw 'gomobile bind failed' }
} finally { Pop-Location }

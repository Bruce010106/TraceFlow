param([switch]$Package,[string]$JdkHome)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
Push-Location (Join-Path $root 'frontend')
try {
    if (-not (Test-Path -LiteralPath 'node_modules')) { npm ci --no-audit --no-fund; if($LASTEXITCODE -ne 0){throw 'npm ci failed'} }
    npm run build
    if($LASTEXITCODE -ne 0){throw 'Frontend build failed'}
} finally { Pop-Location }
$destination=Join-Path $root 'src/main/resources/static'
[IO.Directory]::CreateDirectory($destination) | Out-Null
Get-ChildItem -LiteralPath (Join-Path $root 'frontend/dist') | ForEach-Object { Copy-Item -LiteralPath $_.FullName -Destination $destination -Recurse -Force }
Write-Output "Frontend copied to $destination"
if($Package) {
    $previousJavaHome=$env:JAVA_HOME
    $previousPath=$env:PATH
    Push-Location $root
    try {
        if(-not $JdkHome) {
            $installed=Join-Path $env:ProgramFiles 'Eclipse Adoptium'
            $jdk=Get-ChildItem -LiteralPath $installed -Directory -Filter 'jdk-17*' -ErrorAction SilentlyContinue | Select-Object -First 1
            if($jdk){$JdkHome=$jdk.FullName}
        }
        if($JdkHome){$env:JAVA_HOME=$JdkHome; $env:PATH="$JdkHome\bin;$env:PATH"}
        if(-not $env:JAVA_HOME){throw 'Set JAVA_HOME to JDK 17 or pass -JdkHome.'}
        mvn -s .mvn/central-settings.xml -B -ntp -DskipTests package
        if($LASTEXITCODE -ne 0){throw 'Backend package failed'}
    } finally {Pop-Location; $env:JAVA_HOME=$previousJavaHome; $env:PATH=$previousPath}
}

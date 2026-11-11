param([switch]$Temporary,[switch]$Mock,[int]$Port=8091,[string]$JdkHome)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
if(-not $JdkHome) {
    $installed=Join-Path $env:ProgramFiles 'Eclipse Adoptium'
    $jdk=Get-ChildItem -LiteralPath $installed -Directory -Filter 'jdk-17*' -ErrorAction SilentlyContinue | Select-Object -First 1
    if($jdk){$JdkHome=$jdk.FullName}
}
if($JdkHome){$env:JAVA_HOME=$JdkHome; $env:PATH="$JdkHome\bin;$env:PATH"}
if(-not $env:JAVA_HOME){throw 'Set JAVA_HOME to JDK 17 or pass -JdkHome.'}
$previousOptions=$env:JAVA_TOOL_OPTIONS
$socketDirectory=Join-Path $root '.local/sockets'
if($IsWindows -and $previousOptions -notmatch 'jdk.net.unixdomain.tmpdir') {
    [IO.Directory]::CreateDirectory($socketDirectory) | Out-Null
    $env:JAVA_TOOL_OPTIONS=($previousOptions+' -Djdk.net.unixdomain.tmpdir="'+$socketDirectory+'"').Trim()
}
$previousMode=$env:TRACEFLOW_AI_MODE
if($Mock){$env:TRACEFLOW_AI_MODE="mock"}
$env:TRACEFLOW_PORT=$Port.ToString()
Push-Location $root
try {
    if($Temporary) {
        Write-Output 'Temporary localhost PostgreSQL: data is discarded on exit.'
        mvn -s .mvn/central-settings.xml -B -ntp test-compile spring-boot:test-run '-Dspring-boot.run.main-class=dev.traceflow.LocalDev'
    } else { mvn -s .mvn/central-settings.xml -B -ntp spring-boot:run }
    if($LASTEXITCODE -ne 0){throw 'Backend exited with an error'}
} finally {Pop-Location; $env:JAVA_TOOL_OPTIONS=$previousOptions; $env:TRACEFLOW_AI_MODE=$previousMode}

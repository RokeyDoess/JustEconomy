param(
    [Parameter(Mandatory)][string]$PaperJar,
    [Parameter(Mandatory)][string]$VaultJar,
    [Parameter(Mandatory)][string]$JobsJar,
    [Parameter(Mandatory)][string]$DependencyCache,
    [Parameter(Mandatory)][string]$PaperRuntime,
    [string]$JavaHome = $env:JAVA_HOME,
    [ValidateRange(1024,65535)][int]$ServerPort = 25579
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$javaBin = Join-Path $JavaHome 'bin'
[xml]$project = Get-Content -LiteralPath (Join-Path $projectRoot 'pom.xml')
$candidate = Get-Item -LiteralPath (Join-Path $projectRoot ('target/JustEconomy-' + $project.project.version + '.jar'))
$runRoot = Join-Path $projectRoot ('target/server-check-' + [guid]::NewGuid().ToString('N'))
$plugins = Join-Path $runRoot 'plugins'
$classes = Join-Path $runRoot 'check-classes'
New-Item -ItemType Directory -Path $plugins,$classes | Out-Null
Copy-Item -LiteralPath (Resolve-Path -LiteralPath $PaperJar).Path -Destination (Join-Path $runRoot 'paper.jar')
foreach ($jar in @($VaultJar,$JobsJar,$candidate.FullName)) { Copy-Item -LiteralPath (Resolve-Path -LiteralPath $jar).Path -Destination $plugins }
foreach ($folder in @('cache','libraries','versions')) {
    $source = Join-Path $PaperRuntime $folder
    if (Test-Path -LiteralPath $source) { Copy-Item -LiteralPath $source -Destination $runRoot -Recurse }
}
'eula=true' | Set-Content -LiteralPath (Join-Path $runRoot 'eula.txt')
@"
server-ip=127.0.0.1
server-port=$ServerPort
online-mode=false
white-list=true
enforce-secure-profile=false
level-name=checks
level-type=minecraft:flat
generator-settings={"layers":[{"block":"minecraft:bedrock","height":1}],"biome":"minecraft:plains"}
generate-structures=false
view-distance=2
simulation-distance=2
max-players=2
pause-when-empty-seconds=-1
spawn-protection=0
enable-rcon=false
"@ | Set-Content -LiteralPath (Join-Path $runRoot 'server.properties')
$settings = Join-Path $plugins 'JustEconomy'
New-Item -ItemType Directory -Path $settings | Out-Null
$config = [IO.File]::ReadAllText((Join-Path $projectRoot 'src/main/resources/config.yml')).Replace('balancetop: true', 'balancetop: false')
[IO.File]::WriteAllText((Join-Path $settings 'config.yml'), $config)
@'
rules:
  - command: checkcharge
    cost: '2'
    standstill-seconds: 0
  - command: checkcharge market
    cost: '3'
    standstill-seconds: 1
  - command: spawn
    cost: '1.25'
    standstill-seconds: 1
  - command: tpa
    cost: '2.50'
    standstill-seconds: 1
'@ | Set-Content -LiteralPath (Join-Path $settings 'command-rules.yml')
$classpath = @($candidate.FullName, (Resolve-Path -LiteralPath $JobsJar).Path) + @(Get-ChildItem -LiteralPath $DependencyCache -Filter '*.jar' -Recurse | ForEach-Object FullName)
$arguments = @('-encoding', 'UTF-8', '-cp', ('"' + (($classpath -join [IO.Path]::PathSeparator).Replace('\','/')) + '"'), '-d', ('"' + $classes.Replace('\','/') + '"'))
foreach ($source in Get-ChildItem -LiteralPath (Join-Path $projectRoot 'src/test/server') -Filter '*.java') { $arguments += '"' + $source.FullName.Replace('\','/') + '"' }
$argumentFile = Join-Path $runRoot 'javac.args'
[IO.File]::WriteAllLines($argumentFile, $arguments)
& (Join-Path $javaBin 'javac.exe') ('@' + $argumentFile)
if ($LASTEXITCODE -ne 0) { throw 'Server checks did not compile.' }
Copy-Item -LiteralPath (Join-Path $projectRoot 'src/test/server/plugin.yml') -Destination (Join-Path $classes 'plugin.yml')
& (Join-Path $javaBin 'jar.exe') --create --file (Join-Path $plugins 'JustEconomyChecks.jar') -C $classes .
if ($LASTEXITCODE -ne 0) { throw 'Could not package the server checks.' }
Write-Output "Isolated server: $runRoot"
foreach ($phase in 1..3) {
    $stdout = Join-Path $runRoot "phase-$phase.log"
    $stderr = Join-Path $runRoot "phase-$phase-errors.log"
    $process = Start-Process -FilePath (Join-Path $javaBin 'java.exe') -ArgumentList @('-Dterminal.jline=false','-Dterminal.ansi=false',"-Djusteconomy.check.phase=$phase",'-Xms512M','-Xmx2G','-jar','paper.jar','--nogui') -WorkingDirectory $runRoot -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru -WindowStyle Hidden
    if (!$process.WaitForExit(180000)) { $process.Kill(); throw "Server check phase $phase timed out. See $stdout" }
    if ($process.ExitCode -ne 0 -or !(Select-String -LiteralPath $stdout -Pattern "SERVER CHECKS PHASE $phase PASS:" -Quiet)) {
        Get-Content -LiteralPath $stdout -Tail 55
        Get-Content -LiteralPath $stderr -Tail 20
        throw "Server check phase $phase failed. See $stdout"
    }
    Select-String -LiteralPath $stdout -Pattern 'PASS:|SERVER CHECKS PHASE' | ForEach-Object Line
}
Write-Output 'All isolated server phases passed.'

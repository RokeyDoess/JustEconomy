param(
    [Parameter(Mandatory)][string]$ServerDirectory
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$serverRoot = (Resolve-Path -LiteralPath $ServerDirectory).Path
[xml]$project = Get-Content -LiteralPath (Join-Path $projectRoot 'pom.xml')
$artifact = Join-Path $projectRoot ('target/JustEconomy-' + $project.project.version + '.jar')
if (!(Test-Path -LiteralPath $artifact)) { throw 'Build the plugin before installing it.' }
$plugins = Join-Path $serverRoot 'plugins'
if (!(Test-Path -LiteralPath $plugins)) { throw 'The server plugins directory does not exist.' }
$previous = @(Get-ChildItem -LiteralPath $plugins -Filter 'JustEconomy-*.jar' -File)
if ($previous.Count -gt 1) { throw 'Several JustEconomy JARs exist. Keep only the running version before installing.' }
$artifactHash = (Get-FileHash -LiteralPath $artifact -Algorithm SHA256).Hash
$destination = Join-Path $plugins (Split-Path $artifact -Leaf)
$staged = $false
try {
    if ($previous.Count -eq 1) {
        $stream = [IO.File]::Open($previous[0].FullName, 'Open', 'ReadWrite', 'None')
        $stream.Dispose()
    }
    Copy-Item -LiteralPath $artifact -Destination $destination -Force
} catch [IO.IOException] {
    $updateName = 'update'
    $bukkit = Join-Path $serverRoot 'bukkit.yml'
    if (Test-Path -LiteralPath $bukkit) {
        $line = Select-String -LiteralPath $bukkit -Pattern '^\s+update-folder:\s*(.+?)\s*$' | Select-Object -First 1
        if ($line) { $updateName = $line.Matches[0].Groups[1].Value.Trim().Trim('"', "'") }
    }
    if ([string]::IsNullOrWhiteSpace($updateName)) { throw 'The running JAR is locked and the update folder is disabled.' }
    $update = [IO.Path]::GetFullPath((Join-Path $plugins $updateName))
    if (!$update.StartsWith($serverRoot.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'The update folder must be inside the selected server.' }
    New-Item -ItemType Directory -Path $update -Force | Out-Null
    $filename = if ($previous.Count -eq 1) { $previous[0].Name } else { Split-Path $artifact -Leaf }
    $destination = Join-Path $update $filename
    Copy-Item -LiteralPath $artifact -Destination $destination -Force
    $staged = $true
}
if ((Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash -ne $artifactHash) { throw 'Installed JAR checksum differs from the build.' }
if (!$staged) {
    foreach ($old in $previous) { if ($old.FullName -ne $destination) { Remove-Item -LiteralPath $old.FullName } }
}
[pscustomobject]@{ File = $destination; SHA256 = $artifactHash; StagedForRestart = $staged }

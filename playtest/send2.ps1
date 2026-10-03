param(
    [Parameter(Mandatory=$true)][string[]]$Commands,
    [int]$WaitSeconds = 3
)
$out = "C:\AmbonMUD\playtest\out2.log"
$cmd = "C:\AmbonMUD\playtest\cmd2.txt"
if (-not (Test-Path $out)) { New-Item -ItemType File $out | Out-Null }
$before = (Get-Item $out).Length
foreach ($c in $Commands) { Add-Content $cmd $c }
Start-Sleep -Seconds $WaitSeconds
$fs = [System.IO.File]::Open($out, 'Open', 'Read', 'ReadWrite')
$fs.Seek($before, 'Begin') | Out-Null
$sr = New-Object System.IO.StreamReader($fs, [System.Text.Encoding]::UTF8)
$sr.ReadToEnd()
$sr.Close(); $fs.Close()

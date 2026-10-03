param(
    [Parameter(Mandatory=$true)][string[]]$Commands,
    [int]$WaitSeconds = 3
)
$out = "C:\AmbonMUD\playtest\out.log"
$cmd = "C:\AmbonMUD\playtest\cmd.txt"
$before = (Get-Item $out).Length
foreach ($c in $Commands) { Add-Content $cmd $c }
Start-Sleep -Seconds $WaitSeconds
$fs = [System.IO.File]::Open($out, 'Open', 'Read', 'ReadWrite')
$fs.Seek($before, 'Begin') | Out-Null
$sr = New-Object System.IO.StreamReader($fs, [System.Text.Encoding]::UTF8)
$sr.ReadToEnd()
$sr.Close(); $fs.Close()

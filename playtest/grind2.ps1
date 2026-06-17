$cmd = "C:\AmbonMUD\playtest\cmd.txt"
$targets = @('null-pointer', 'goblin', 'rat', 'null-pointer', 'goblin', 'rat', 'null-pointer', 'goblin')
foreach ($t in $targets) {
    Add-Content $cmd "kill $t"
    Start-Sleep -Seconds 17
}
Add-Content $cmd "score"

$cmd = "C:\AmbonMUD\playtest\cmd.txt"
$targets = @('goblin', 'null-pointer', 'rat', 'grub')
for ($round = 0; $round -lt 14; $round++) {
    $t = $targets[$round % $targets.Count]
    Add-Content $cmd "kill $t"
    Start-Sleep -Seconds 16
    Add-Content $cmd "look"
    Start-Sleep -Seconds 3
}
Add-Content $cmd "score"

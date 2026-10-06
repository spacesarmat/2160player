param([string]$what = "all", [int]$h = 3000)
$dir = "C:\Users\ANDYBUM\2160player\design\logo-v2"
$chrome = "C:\Program Files\Google\Chrome\Application\chrome.exe"
$shots = Join-Path $dir "_shots"
New-Item -ItemType Directory -Force $shots | Out-Null
python (Join-Path $dir "gen.py")
if (-not $?) { exit 1 }
function shot($file, $out, $w, $hh) {
  Start-Process -FilePath $chrome -ArgumentList "--headless=new","--disable-gpu","--hide-scrollbars","--screenshot=$out","--window-size=$w,$hh",("file:///" + ($file -replace '\\','/')) -Wait
}
if ($what -eq "all" -or $what -eq "heroes") {
  Get-ChildItem $dir -Filter "*-hero.svg" | ForEach-Object { shot $_.FullName (Join-Path $shots ($_.BaseName + ".png")) 1600 900 }
}
if ($what -eq "all" -or $what -eq "preview") {
  shot (Join-Path $dir "preview.html") (Join-Path $shots "preview.png") 1400 $h
}
Start-Sleep -Milliseconds 500
Get-ChildItem $shots | Select-Object Name, Length


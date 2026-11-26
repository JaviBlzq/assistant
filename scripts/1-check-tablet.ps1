$adb = "C:\Android\platform-tools\adb.exe"
if (-not (Test-Path $adb)) { throw "No se encuentra adb.exe en $adb" }

Write-Host "=== DISPOSITIVOS CONECTADOS ===" -ForegroundColor Cyan
& $adb devices -l

$state = (& $adb get-state 2>$null)
if ($state -ne "device") {
    Write-Host "`n[ATENCION] La tablet aun no esta autorizada (estado actual: '$state')." -ForegroundColor Yellow
    Write-Host "En la tablet: desbloquea la pantalla y pulsa 'Permitir depuracion por USB' (marca 'Permitir siempre desde este equipo')."
    exit 1
}

Write-Host "`n=== INFORMACION DE LA TABLET ===" -ForegroundColor Cyan
$props = @(
    "ro.product.manufacturer",
    "ro.product.model",
    "ro.build.version.release",
    "ro.build.version.sdk",
    "ro.product.cpu.abi"
)
foreach ($p in $props) {
    $val = ((& $adb shell getprop $p) -join "").Trim()
    Write-Host ("{0,-28}: {1}" -f $p, $val)
}

Write-Host "`n=== MEMORIA, BATERIA Y RED ===" -ForegroundColor Cyan
& $adb shell cat /proc/meminfo | Select-Object -First 3
& $adb shell dumpsys battery | Select-String "level|status|AC powered|USB powered"
& $adb shell ip -f inet addr show wlan0 | Select-String "inet "

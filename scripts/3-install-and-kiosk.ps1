param(
    [string]$ApkPath = (Join-Path $PSScriptRoot "../app/build/outputs/apk/debug/app-debug.apk")
)

$adb = "C:\Android\platform-tools\adb.exe"
$state = (& $adb get-state 2>$null)
if ($state -ne "device") { throw "Tablet no conectada o no autorizada (estado: $state)." }
if (-not (Test-Path $ApkPath)) { throw "APK no encontrada en $ApkPath. Compila primero con gradlew.bat assembleDebug." }

$sdk = [int](((& $adb shell getprop ro.build.version.sdk) -join "").Trim())

Write-Host "=== INSTALANDO ASSISTANT APK ===" -ForegroundColor Cyan
if ($sdk -ge 23) {
    $installOut = (& $adb install -r -g $ApkPath 2>&1) -join "`n"
}
if ($sdk -lt 23 -or $LASTEXITCODE -ne 0 -or $installOut -match "Unknown option|Failure") {
    $installOut = (& $adb install -r $ApkPath 2>&1) -join "`n"
}
if ($LASTEXITCODE -ne 0 -or $installOut -notmatch '(?m)^Success\s*$') {
    throw "No se pudo instalar Assistant.`n$installOut"
}
Write-Host $installOut

Write-Host "`n=== CONCEDIENDO PERMISOS ===" -ForegroundColor Cyan
if ($sdk -ge 24) {
    & $adb shell cmd notification allow_listener com.javiblzq.assistant/com.javiblzq.assistant.kiosk.NotificationBlockerService 2>$null
}
& $adb shell settings put secure enabled_notification_listeners com.javiblzq.assistant/com.javiblzq.assistant.kiosk.NotificationBlockerService 2>$null

Write-Host "`n=== CONFIGURANDO LAUNCHER Y MODO QUIOSCO (DEVICE OWNER) ===" -ForegroundColor Cyan
if ($sdk -ge 24) {
    & $adb shell cmd package set-home-activity com.javiblzq.assistant/.MainActivity 2>$null
}
$ownerOut = (& $adb shell dpm set-device-owner com.javiblzq.assistant/.kiosk.AssistantDeviceAdminReceiver 2>&1) -join "`n"
if ($ownerOut -match 'MDM_PROXY_ADMIN_INTERNAL') {
    Write-Host "[NOTA] Samsung Knox restringe Device Owner. Assistant puede funcionar como launcher HOME con pantalla completa inmersiva y WakeLock." -ForegroundColor Yellow
} elseif ($ownerOut -match 'device is already provisioned') {
    Write-Host "[NOTA] La tablet ya esta aprovisionada. Assistant se iniciara con pantalla completa inmersiva y WakeLock." -ForegroundColor Yellow
} elseif ($LASTEXITCODE -ne 0 -or $ownerOut -match 'Exception|Error|Failure') {
    Write-Warning "No se pudo configurar Device Owner.`n$ownerOut"
} elseif ($ownerOut) {
    Write-Host $ownerOut
}

Write-Host "`n=== ARRANCANDO ASSISTANT ===" -ForegroundColor Cyan
& $adb shell input keyevent KEYCODE_WAKEUP
& $adb shell am start -n com.javiblzq.assistant/.MainActivity
$ipLine = ((& $adb shell ip -f inet addr show wlan0 | Select-String "inet ") -join "").Trim()
if ($ipLine) {
    Write-Host "`nListo. IP Wi-Fi detectada: $ipLine" -ForegroundColor Green
} else {
    Write-Host "`nListo. No se detecto una IP Wi-Fi." -ForegroundColor Yellow
}

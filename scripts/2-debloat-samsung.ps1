$adb = "C:\Android\platform-tools\adb.exe"
$state = (& $adb get-state 2>$null)
if ($state -ne "device") { throw "Tablet no conectada o no autorizada (estado: $state). Ejecuta primero 1-check-tablet.ps1." }

Write-Host "=== DESINSTALANDO BLOATWARE SAMSUNG / GOOGLE PARA USUARIO 0 (SIN ROOT, REVERSIBLE CON FACTORY RESET) ===" -ForegroundColor Cyan

# Paquetes seguros de eliminar/deshabilitar para dejar la tablet rapida y libre de notificaciones
$bloatPackages = @(
    # Samsung Bloat & Store & Cloud & Bixby & Kids & AR & Social
    "com.samsung.android.bixby.agent",
    "com.samsung.android.bixby.wakeup",
    "com.samsung.android.bixby.service",
    "com.samsung.android.bixbyvision.framework",
    "com.samsung.android.app.spage",
    "com.samsung.android.kidsinstaller",
    "com.samsung.android.scloud",
    "com.samsung.android.ardrawing",
    "com.samsung.android.aremoji",
    "com.samsung.android.game.gamehome",
    "com.samsung.android.game.gametools",
    "com.samsung.android.game.gos",
    "com.samsung.android.app.tips",
    "com.samsung.android.voc",
    "com.samsung.android.mateagent",
    "com.samsung.android.app.notes",
    "com.samsung.android.email.provider",
    "com.samsung.android.calendar",
    "com.samsung.android.messaging",
    "com.samsung.android.app.watchmanagerstub",
    "com.samsung.android.oneconnect",
    "com.samsung.android.beaconmanager",
    "com.samsung.android.fmm",
    "com.samsung.android.mdx",
    "com.samsung.android.mdx.kit",
    "com.samsung.android.app.routines",
    "com.sec.android.app.samsungapps",
    "com.sec.android.app.camera",
    "com.sec.android.app.sbrowser",
    "com.sec.android.app.popupcalculator",
    "com.sec.android.app.clockpackage",
    "com.sec.android.app.voicenote",
    "com.sec.android.daemonapp",
    "com.sec.android.easyMover",
    "com.sec.android.easyMover.Agent",
    "com.samsung.storyservice",
    "com.samsung.clipboardsaveservice",
    # Bloat de terceros / Microsoft / Facebook
    "com.microsoft.skydrive",
    "com.microsoft.office.officehubrow",
    "com.microsoft.office.word",
    "com.microsoft.office.excel",
    "com.microsoft.office.powerpoint",
    "com.facebook.katana",
    "com.facebook.system",
    "com.facebook.appmanager",
    "com.facebook.services",
    "com.netflix.mediaclient",
    "com.spotify.music",
    # Apps Google innecesarias para un panel dedicado (dejamos WebView y servicios minimos)
    "com.google.android.youtube",
    "com.google.android.apps.docs",
    "com.google.android.apps.maps",
    "com.google.android.apps.photos",
    "com.google.android.apps.tachyon",
    "com.google.android.videos",
    "com.google.android.music",
    "com.google.android.gm",
    "com.google.android.calendar",
    "com.google.android.talk",
    "com.google.android.googlequicksearchbox",
    "com.google.android.feedback",
    "com.android.chrome",
    "com.android.printspooler",
    "com.android.bips",
    "com.samsung.android.app.galaxyfinder"
)

foreach ($pkg in $bloatPackages) {
    $res = (& $adb shell pm uninstall -k --user 0 $pkg 2>$null)
    if ($res -match "Success") {
        Write-Host "  [OK] Eliminado: $pkg" -ForegroundColor Green
    } else {
        $dis = (& $adb shell pm disable-user --user 0 $pkg 2>$null)
        if ($dis -match "new state") {
            Write-Host "  [OK] Deshabilitado: $pkg" -ForegroundColor DarkGreen
        }
    }
}

Write-Host "`n=== DESACTIVANDO ANIMACIONES Y NOTIFICACIONES DEL SISTEMA ===" -ForegroundColor Cyan
& $adb shell settings put global window_animation_scale 0
& $adb shell settings put global transition_animation_scale 0
& $adb shell settings put global animator_duration_scale 0
& $adb shell settings put global heads_up_notifications_enabled 0
& $adb shell settings put secure lock_screen_show_notifications 0
& $adb shell settings put secure lock_screen_allow_private_notifications 0
& $adb shell settings put global zen_mode 2
& $adb shell settings put global stay_on_while_plugged_in 7
& $adb shell settings put global wifi_sleep_policy 2

Write-Host "`nDebloat y optimizacion completados." -ForegroundColor Green

# 콘솔 SPEED(km/h)와 앱이 받은 속도를 1초마다 나란히 출력한다.
# 사용: powershell -ExecutionPolicy Bypass -File tools\compare_speed.ps1
# 종료: Ctrl+C
# 전제: 앱이 debug 빌드로 실행 중, Virtual Console 포트 포워딩(tcp:8088) 완료

$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb forward tcp:8088 tcp:8088 | Out-Null

"{0,-10} {1,12} {2,12} {3,10}" -f "시각", "콘솔 km/h", "앱 km/h", "차이"
while ($true) {
    $console = $null
    try {
        $state = Invoke-RestMethod -Uri "http://127.0.0.1:8088/vehicle/state" -TimeoutSec 2
        $console = [double]$state.controls.speed_kph
    } catch { }

    # 앱 로그: "속도 159.1 km/h (콜백 누적 N회)" — 값이 바뀔 때 1초 단위로 찍힌다
    $app = $null
    $line = & $adb logcat -d -s "부드러움" 2>$null | Select-String "속도 ([\d.]+) km/h" | Select-Object -Last 1
    if ($line) { $app = [double]$line.Matches[0].Groups[1].Value }

    $diff = if ($console -ne $null -and $app -ne $null) { "{0:N1}" -f ($app - $console) } else { "-" }
    "{0,-10} {1,12} {2,12} {3,10}" -f (Get-Date -Format "HH:mm:ss"), $console, $app, $diff
    Start-Sleep -Seconds 1
}

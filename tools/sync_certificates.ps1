# 에뮬레이터(차량)에서 발급된 '운행 승차감 증명서' PDF를 내 컴퓨터로 자동 저장한다.
#
# 사용:
#   powershell -ExecutionPolicy Bypass -File tools\sync_certificates.ps1            # 계속 감시 (Ctrl+C 종료)
#   powershell -ExecutionPolicy Bypass -File tools\sync_certificates.ps1 -Once      # 지금 있는 것만 한 번 받기
#   powershell -ExecutionPolicy Bypass -File tools\sync_certificates.ps1 -Open      # 새 PDF를 받으면 바로 열기
#   ... -Dest "D:\증명서"                                                          # 저장 폴더 지정
#
# 원리: 앱이 차량의 '다운로드' 폴더(MediaStore)에 PDF를 저장 → adb로 MediaStore를 읽어 PC로 복사.
#       debug/release 빌드 모두 동작한다.

param(
    [string]$Dest = (Join-Path ([Environment]::GetFolderPath("MyDocuments")) "부드러움지수_증명서"),
    [int]$IntervalSec = 3,
    [switch]$Once,
    [switch]$Open
)

[Console]::OutputEncoding = [Text.Encoding]::UTF8
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
New-Item -ItemType Directory -Force $Dest | Out-Null
$tmp = Join-Path $env:TEMP "smooth_cert_tmp.pdf"

Write-Host "증명서 저장 폴더: $Dest"
if (-not $Once) { Write-Host "새 증명서를 기다리는 중… (Ctrl+C 종료)" }

function Sync-Once {
    $state = (& $adb get-state 2>$null)
    if ($state -ne "device") { return 0 }
    $user = ((& $adb shell am get-current-user) -join "").Trim()
    if (-not $user) { $user = "0" }

    $rows = & $adb shell content query --user $user --uri content://media/external/downloads `
        --projection _id:_display_name 2>$null
    $saved = 0
    foreach ($row in $rows) {
        if ($row -notmatch '_id=(\d+), _display_name=(운행승차감증명서[^,]*\.pdf)') { continue }
        $id = $Matches[1]
        $name = $Matches[2].Trim()
        $target = Join-Path $Dest $name
        if (Test-Path $target) { continue }

        # PowerShell 리디렉션은 바이너리를 깨뜨리므로 cmd로 받고, 한글 이름은 나중에 붙인다
        Remove-Item $tmp -ErrorAction SilentlyContinue
        cmd /c "`"$adb`" exec-out content read --user $user --uri content://media/external/downloads/$id > `"$tmp`""
        if (-not (Test-Path $tmp)) { continue }
        $head = [IO.File]::ReadAllBytes($tmp)[0..3]
        if ([Text.Encoding]::ASCII.GetString($head) -ne "%PDF") {
            Write-Host "  ✖ $name : PDF가 아니어서 건너뜀"
            continue
        }
        Move-Item $tmp $target -Force
        $kb = [math]::Round((Get-Item $target).Length / 1KB)
        Write-Host ("  ✔ {0}  저장  {1}  ({2}KB)" -f (Get-Date -Format "HH:mm:ss"), $name, $kb)
        $saved++
        if ($Open) { Invoke-Item $target }
    }
    return $saved
}

if ($Once) {
    $n = Sync-Once
    Write-Host "새로 저장한 증명서: ${n}개"
    exit
}
while ($true) {
    try { Sync-Once | Out-Null } catch { Write-Host "  (연결 대기: $($_.Exception.Message))" }
    Start-Sleep -Seconds $IntervalSec
}

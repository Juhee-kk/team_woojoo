# 부드러움 지수 (Smoothness Index)

> **택시 승객이 내리는 순간, 그 운행이 얼마나 부드러웠는지 점수와 증명서로 돌려주는 차량용 앱**
>
> 가속·감속·핸들·방향지시등 데이터를 실시간으로 수집해 운행 1회마다 **부드러움 지수**를 계산하고,
> 하차 직후 **운행 승차감 증명서(PDF)** 를 발급합니다. DTG가 기사를 감시한다면, 이 앱은 기사를 지킵니다.

현대자동차그룹 차량용 앱 개발 공모전 출품작 · Pleos Connect (AAOS) 대상

---

## 주요 기능

| 기능 | 설명 |
|---|---|
| 자동 운행 구분 | 승객석 **좌석 점유**로 승차·하차를 감지해 운행 1회를 자동으로 나눔 (3초 유지 시 확정, 30초 미만 운행 제외) |
| 부드러움 지수 | 가속 25% · 감속 25% · 핸들 50% 가중 평균 − 깜빡이 감점 (최대 −15점) |
| 결과 화면 | `부드러움 지수 59점 · 개선 필요! (가속 82 / 감속 73 / 핸들 71)` + 안전/주의/위험 운전 배지 + 운전 요령 한 줄 |
| 증명서 발급 | 항목별 점수·감점 내역을 수치로 보여주고 **PDF로 발급** (차량 다운로드 폴더 저장, 앱 안 뷰어로 열기) |
| 재생 모드 | 녹화된 주행 시나리오(soft / rough)를 10Hz로 재생 — 실차 없이 시연 가능 |

### 점수 산정 요약

| 단계 | 기준 |
|---|---|
| 이벤트 판정 | 앞뒤·좌우 가속도 **0.3m/s² 이상이 1초 이상** 지속된 구간 |
| 이벤트 점수 | 세기 40% + 갑작스러움(저크) 60% · 세기가 위험선 이상이면 0점 |
| 요소 기준값 | 가속 1.5~3.1m/s² · 감속 1.0~2.1m/s² · 좌우 1.0~2.1m/s² (100점~0점) · 저크 1~3m/s³ |
| 위험선 출처 | 교통안전공단 eTAS 급가속 초당 11km/h, 급감속 초당 7.5km/h |
| 깜빡이 | 차선 변경(S자 3~6초)·회전(90° 이상) 전에 **같은 방향** 깜빡이를 켰는지 · 준수율 90% 이상 감점 없음, 확인된 조작 5회 미만 미적용 |
| 등급 | 90↑ 매우 부드러움 · 80↑ 부드러움(인증) · 70↑ 보통 · 70 미만 개선 필요 |

상세 근거와 설계값은 [`부드러움지수_개발명세서.md`](부드러움지수_개발명세서.md) 참고.

---

## 테스트 환경

| 항목 | 값 |
|---|---|
| 에뮬레이터 | **Pleos Connect v2.1.0** (AVD `Pleos_Connect_v2.1.0`), Android 14 (API 34) — **Cold boot 필수** |
| 화면 규격 | 디스플레이 **2560 × 1440 px, 160dpi** (가로, car UI) |
| 앱 표시 영역 | 멀티 윈도우 오른쪽 **1682 × 1210 px** (= 1682 × 1210 dp, 160dpi) |
| 실행 사용자 | 사용자 프로필 **10** (권한·설치 모두 `--user 10` 필요) |
| IDE / 빌드 | Android Studio 최신 · AGP 9.5.1 · Gradle 9.6.0 · Kotlin (AGP 내장) · JDK 11 타깃 |
| SDK | minSdk 28 · targetSdk / compileSdk 37 |
| 차량 SDK | Pleos `ai.pleos.playground:Vehicle:2.3.2` + AAOS `android.car` (`useLibrary`) |

---

## 필요한 설정

### 1. Pleos Maven 저장소 (`settings.gradle.kts`, 이미 포함)
```kotlin
dependencyResolutionManagement {
    repositories {
        google(); mavenCentral()
        maven { url = uri("https://nexus-playground.pleos.ai/repository/maven-releases/") }
    }
}
```

### 2. 권한
`AndroidManifest.xml`에 선언되어 있습니다.

| 권한 | 용도 |
|---|---|
| `pleos.car.permission.CAR_DRIVE`, `READ_CAR_STEERING`, `CAR_LIGHTS` | 핸들 각도, 브레이크, 방향지시등 |
| `pleos.car.permission.CAR_SEATS`, `CAR_DOORS`, `CAR_INFO` | 좌석 점유(운행 구분), 문, 차량 정보 |
| `android.car.permission.CAR_SPEED` | 차량 속도 — **런타임 권한** (첫 실행 시 요청 창) |

요청 창이 뜨지 않거나 재설치로 권한이 풀렸다면:
```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" shell pm grant --user 10 com.example.woojoo_1 android.car.permission.CAR_SPEED
```

### 3. Virtual Console (차량 값 조작)
에뮬레이터를 켤 때마다:
```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" forward tcp:8088 tcp:8088
# 브라우저에서 http://127.0.0.1:8088/
```
Seat Occupancy(승객석 점유), ACCEL/BRAKE 페달(속도), Steering Angle, Turn Signal을 조작할 수 있습니다.

---

## 실행 방법

### A. APK로 바로 설치
```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r --user 10 apk\woojoo_부드러움지수_release.apk
```

### B. 소스에서 빌드
1. Android Studio에서 이 폴더를 열고 Gradle Sync
2. Pleos Connect 에뮬레이터(Cold boot) 실행
3. ▶ Run (`app`) — 또는 `gradlew :app:installDebug`

### 앱 사용 순서

**실차 모드 (Virtual Console로 조작)**
1. 앱 실행 → "승객을 기다리고 있어요"
2. Virtual Console에서 **Rear right**(또는 Passenger)를 **Occupied** → 3초 뒤 "운행 기록 중"
3. ACCEL/BRAKE 페달, 핸들, 깜빡이 조작 (30초 이상)
4. 좌석을 **Empty**로 → 3초 뒤 결과 화면
5. **운행 승차감 증명서 발급** → 증명서 화면 + PDF 발급 → **PDF 열기**

> 운전석(1열 왼쪽)은 운행 판정에 쓰지 않습니다. 승객석을 조작해 주세요.

**재생 모드 (시연용)**
1. 우측 상단 **설정** → `부드러운 운전 재생` 또는 `거친 운전 재생`
2. **재생 시작 (승객 탑승)** → 127초 시나리오가 10Hz로 재생되고 끝나면 자동 하차
3. 결과: soft **98.8점(매우 부드러움)** / rough **59.3점(개선 필요)**

| 시나리오 | 파일 | 내용 |
|---|---|---|
| soft | `app/src/main/assets/replay/soft.csv` | 서서히 가속·감속, 깜빡이 먼저 켜고 차선 변경 5회·우회전 1회 |
| rough | `app/src/main/assets/replay/rough.csv` | 급출발, 속도 들쭉날쭉, 깜빡이 없이 차선 변경, 급우회전, 급정지 |

CSV 컬럼: `time_s, speed_kmh, steering_deg(좌회전 = 음수), turn_signal(OFF/LEFT/RIGHT)[, cue]` — 브레이크는 속도가 줄어드는 구간을 ON으로 간주합니다.

### 증명서 PDF 저장 위치
- 차량: `다운로드/운행승차감증명서_SM-날짜-시간-코드.pdf` (`/storage/emulated/10/Download/`)
- PC로 자동 저장하려면 아래 스크립트를 켜 두세요. 새 증명서가 생기면 `문서\부드러움지수_증명서`로 복사합니다.
  ```powershell
  powershell -ExecutionPolicy Bypass -File tools\sync_certificates.ps1        # 계속 감시
  powershell -ExecutionPolicy Bypass -File tools\sync_certificates.ps1 -Once  # 한 번만
  ```

---

## APK 파일 위치

```
apk/woojoo_부드러움지수_release.apk
```
- **release 빌드** (R8 축소·최적화 끔 — 켜면 실행 즉시 `IllegalAccessError`로 종료되는 것을 확인, `app/build.gradle.kts` 주석 참고)
- debug 전용 기능(DEBUG 패널, adb 제어)은 release에서 자동으로 꺼집니다. 재생 모드는 **설정**에서 그대로 사용할 수 있습니다.
- 서명: 저장소 루트에 `keystore.properties`가 있으면 그 키로, 없으면 Android debug 키로 서명됩니다
  (바로 설치 가능하도록). 키 파일과 `keystore.properties`는 `.gitignore`로 커밋되지 않습니다.
  ```properties
  # keystore.properties 예시 (커밋 금지)
  storeFile=../release.jks
  storePassword=****
  keyAlias=woojoo
  keyPassword=****
  ```
- 다시 만들기: `gradlew :app:assembleRelease` → `app/build/outputs/apk/release/app-release.apk`

---

## 개발용 도구 (debug 빌드 전용)

| 도구 | 사용 |
|---|---|
| 화면 DEBUG 패널 | 실시간 속도·핸들·브레이크·깜빡이, 운행 중 이벤트 수, 점유 좌석 |
| Logcat 태그 | `운행판정`(승차·하차 판정), `부드러움`(센서 수신), `속도샘플`(운행 중 속도 전부), `재생` |
| adb 제어 | 아래 참고 |
| `tools/compare_speed.ps1` | 콘솔 SPEED와 앱 수신 속도를 1초마다 비교 |

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
# 모드 변경 + 재생 시작 (mode: REAL | SOFT | ROUGH)
& $adb shell am start --user 10 -n com.example.woojoo_1/.MainActivity --es mode ROUGH --ez autoplay true
# 결과가 나온 뒤 증명서 화면 / PDF 바로 열기
& $adb shell am start --user 10 --activity-single-top -n com.example.woojoo_1/.MainActivity --ez certificate true
& $adb shell am start --user 10 --activity-single-top -n com.example.woojoo_1/.MainActivity --ez pdf true
```

### 유닛 테스트
```powershell
.\gradlew.bat :app:testDebugUnitTest
```
명세서 6.7장 계산 예시(감속 86점·가속 86점·종합 89.6점), 깜빡이 감점표, 재생 시나리오 점수(soft 90점대 / rough 50~60점대), 조언 문장을 검증합니다.

---

## 실측으로 확인한 SDK 동작 (중요)

| 항목 | 내용 | 코드 |
|---|---|---|
| 속도 단위 | `PERF_VEHICLE_SPEED`는 **m/s** → × 3.6 으로 km/h 변환 | `VehicleSensors.kt` |
| 좌석 점유 | Pleos SDK 2.3.2의 Boolean이 **반대** (원시값 1=VACANT를 `true`로 전달) → 값을 뒤집어 사용 | `VehicleSensors.kt` |
| 핸들 각도 | **좌회전 = 음수** | `TurnSignalEvaluator.kt` |
| 브레이크 | 페달 변위 없이 **ON/OFF Boolean**만 제공 | — |
| 축거 | CarInfo API에 없음 → 승용차 평균 2.9m 사용 | `ScoreConfig.kt` |
| 에뮬레이터 | 사용자 프로필 10에서 실행, adb 터치 입력은 반영되지 않음 | — |

---

## 프로젝트 구조

```
app/src/main/java/com/example/woojoo_1/
├─ MainActivity.kt            결과·상태 화면, 설정(모드 선택), 권한, 뒤로가기(moveTaskToBack)
├─ CertificateActivity.kt     증명서 화면 + PDF 발급
├─ PdfViewerActivity.kt       앱 안 PDF 뷰어 (차량에 PDF 앱이 없음)
├─ MainViewModel.kt
├─ data/
│  ├─ VehicleSensors.kt       Pleos Vehicle SDK + CarPropertyManager 연동
│  ├─ ReplayVehicleSource.kt  CSV 시나리오 10Hz 재생
│  ├─ TripMonitor.kt          좌석 점유 기반 운행 구분, 분석 트리거
│  ├─ TripRecorder.kt         센서 샘플 기록 (중복 제거, 20ms 스로틀)
│  └─ DriveMode.kt            실차 / soft / rough
├─ domain/                    (Android 비의존 · 유닛 테스트 대상)
│  ├─ SignalProcessor.kt      10Hz 리샘플, 이동평균, 가속도·저크
│  ├─ ScoreEngine.kt          이벤트 판정·채점
│  ├─ TurnSignalEvaluator.kt  차선 변경·회전 판별, 깜빡이 준수율
│  ├─ SummaryWriter.kt / AdviceWriter.kt  감점 요약 / 운전 요령 문장
│  └─ ScoreConfig.kt          모든 기준값
└─ ui/
   ├─ CertificatePdf.kt       A4 PDF 증명서 생성
   └─ Format.kt
tools/   sync_certificates.ps1 (PDF → PC), compare_speed.ps1
apk/     release APK
```

## 플랫폼 정책 대응
- 최상단 화면 Back → `moveTaskToBack(true)` (CCP)
- 로터리 미지원 선언 `com.hmg.rotary.ROTARY_SUPPORTED=false`
- `CarUxRestrictionsManager` 주행 규제 시 증명서 발급 비활성화 (결과 확인·발급은 정차 중)
- 주간/야간 테마, 텍스트 대비 4.5:1 이상, 주요 글자 18sp 이상

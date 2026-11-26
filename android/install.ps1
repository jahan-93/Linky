<#
  Linky 폰 앱을 노트북에서 adb(무선 디버깅)로 설치합니다.
  APK 파일을 폰에서 직접 열면 Play 프로텍트가 막기 때문입니다 (docs/errors.md E-005).

  사용법 (android 폴더에서):
    .\install.ps1                          처음이 아니면 (이 노트북과 이미 페어링됨)
    .\install.ps1 -Pair                    처음이면: 페어링 코드로 먼저 페어링
    .\install.ps1 -Address 192.168.0.5:37123   주소를 직접 지정
#>
param(
    [switch]$Pair,
    [string]$Address
)

$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [Text.Encoding]::UTF8
$OutputEncoding = [Text.Encoding]::UTF8

$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
$apk = Join-Path $PSScriptRoot 'app\build\outputs\apk\debug\app-debug.apk'

# adb를 실행하고 출력(표준 오류 포함)과 성공 여부를 돌려준다
function Invoke-Adb {
    $lines = & $adb @args 2>&1 | ForEach-Object { "$_" }
    [pscustomobject]@{ Ok = ($LASTEXITCODE -eq 0); Text = ($lines -join "`n") }
}

# adb mdns services에서 서비스 종류에 맞는 IP:포트 목록을 찾는다
function Find-AdbService([string]$Type) {
    for ($i = 0; $i -lt 3; $i++) {
        $r = Invoke-Adb mdns services
        $found = @($r.Text -split "`n" |
            Where-Object { $_ -like "*$Type*" } |
            ForEach-Object { if ($_ -match '(\d{1,3}(?:\.\d{1,3}){3}:\d+)') { $Matches[1] } } |
            Select-Object -Unique)
        if ($found.Count -gt 0) { return $found }
        Start-Sleep -Seconds 1
    }
    return @()
}

# 찾은 주소가 하나면 그것을, 여러 개면 고르게 하고, 없으면 직접 입력받는다
function Select-Address([string[]]$List, [string]$Prompt) {
    if ($List.Count -eq 1) {
        Write-Host "찾은 주소: $($List[0])"
        return $List[0]
    }
    if ($List.Count -gt 1) {
        Write-Host '여러 기기를 찾았습니다:'
        for ($i = 0; $i -lt $List.Count; $i++) { Write-Host "  [$($i + 1)] $($List[$i])" }
        $k = 0
        if ([int]::TryParse((Read-Host '번호를 고르세요'), [ref]$k) -and $k -ge 1 -and $k -le $List.Count) {
            return $List[$k - 1]
        }
    }
    Write-Host '같은 Wi-Fi에서 폰을 자동으로 찾지 못했습니다.' -ForegroundColor Yellow
    return (Read-Host $Prompt).Trim()
}

function Write-After {
    Write-Host ''
    Write-Host '이제 폰에서 다시 원래대로 돌려 두세요:' -ForegroundColor Cyan
    Write-Host '  1. 설정 > 개발자 옵션 > 무선 디버깅 끄기'
    Write-Host '  2. 설정 > 보안 및 개인정보 보호 > 보안 위험 자동 차단 다시 켜기'
}

Write-Host ''
Write-Host '=== Linky 폰 앱 설치 ===' -ForegroundColor Cyan
Write-Host '설치하기 전에 폰에서:'
Write-Host '  1. 설정 > 보안 및 개인정보 보호 > 보안 위험 자동 차단을 잠깐 끕니다.'
Write-Host '  2. 설정 > 개발자 옵션 > 무선 디버깅을 켭니다. (노트북과 같은 Wi-Fi)'
Write-Host ''

if (-not (Test-Path $adb)) {
    Write-Host "adb를 찾지 못했습니다: $adb" -ForegroundColor Red
    Write-Host 'Android Studio > SDK Manager > SDK Tools에서 Android SDK Platform-Tools를 설치하세요.'
    exit 1
}
if (-not (Test-Path $apk)) {
    Write-Host "APK가 없습니다: $apk" -ForegroundColor Red
    Write-Host '먼저 android 폴더에서 .\gradlew.bat assembleDebug 로 빌드하세요.'
    exit 1
}

Read-Host '준비되면 Enter를 누르세요' | Out-Null
Invoke-Adb start-server | Out-Null

if ($Pair) {
    Write-Host ''
    Write-Host '폰의 무선 디버깅 화면에서 [페어링 코드로 기기 페어링]을 누르세요.'
    $pairAddress = Select-Address @(Find-AdbService '_adb-tls-pairing._tcp') '페어링 창에 보이는 IP 주소와 포트 (예: 192.168.0.5:37099)'
    # 빈 값을 넘기면 adb가 보이지 않는 입력을 기다리며 멈춘 것처럼 보이므로 먼저 확인한다
    if ($pairAddress -notmatch '^\d{1,3}(\.\d{1,3}){3}:\d+$') {
        Write-Host "IP 주소와 포트를 192.168.0.5:37099 처럼 입력하세요: '$pairAddress'" -ForegroundColor Red
        Write-After
        exit 1
    }
    $code = (Read-Host '페어링 창에 보이는 Wi-Fi 페어링 코드 (6자리)').Trim()
    if ($code -notmatch '^\d{6}$') {
        Write-Host '페어링 코드는 6자리 숫자입니다. 다시 실행하세요.' -ForegroundColor Red
        Write-After
        exit 1
    }
    $r = Invoke-Adb pair $pairAddress $code
    Write-Host $r.Text
    if ($r.Text -notmatch 'Successfully paired') {
        Write-Host '페어링하지 못했습니다. 코드와 주소를 확인하고 다시 실행하세요.' -ForegroundColor Red
        Write-After
        exit 1
    }
}

if (-not $Address) {
    Write-Host ''
    Write-Host '폰을 찾는 중...'
    $Address = Select-Address @(Find-AdbService '_adb-tls-connect._tcp') '무선 디버깅 화면의 IP 주소 및 포트 (예: 192.168.0.5:37123)'
}
if (-not $Address) {
    Write-Host '주소가 없어 끝냅니다.' -ForegroundColor Red
    Write-After
    exit 1
}

$r = Invoke-Adb connect $Address
Write-Host $r.Text
if ($r.Text -notmatch 'connected to') {
    Write-Host '폰에 연결하지 못했습니다.' -ForegroundColor Red
    Write-Host '이 노트북에서 처음 설치하는 경우 .\install.ps1 -Pair 로 페어링부터 하세요.'
    Write-After
    exit 1
}

Write-Host ''
Write-Host '설치하는 중... (폰에 확인 창이 뜨면 허용하세요)'
$r = Invoke-Adb -s $Address install -r $apk
Write-Host $r.Text
$ok = $r.Ok -and ($r.Text -match 'Success')
Invoke-Adb disconnect $Address | Out-Null

if ($ok) {
    Write-Host ''
    Write-Host '설치 완료!' -ForegroundColor Green
    Write-Host '처음 설치했다면 Linky 앱을 열고 [접근성 설정 열기]에서 "Linky (조작)"을 켜세요.'
} else {
    Write-Host ''
    Write-Host '설치하지 못했습니다. 위 메시지를 확인하세요.' -ForegroundColor Red
    if ($r.Text -match 'INSTALL_FAILED_UPDATE_INCOMPATIBLE') {
        Write-Host '다른 PC에서 빌드한 앱이 이미 깔려 있습니다. 폰에서 Linky를 삭제한 뒤 다시 실행하세요.'
    }
}
Write-After
if (-not $ok) { exit 1 }

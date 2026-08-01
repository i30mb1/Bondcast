# Обработчик протокола bondcast-update:// (регистрирует installer/setup.iss в
# HKCU\Software\Classes\bondcast-update) — панель сидит в Docker-контейнере и
# не может сама скачать/запустить .exe на хосте; вместо этого кнопка
# "Обновить" в панели (после явного второго клика-подтверждения, см.
# app.js) открывает bondcast-update://install, и Windows передаёт запуск сюда
# через зарегистрированный протокол — тот же приём, что и launch-obs.ps1.
#
# Ссылку на файл сюда намеренно НЕ передают через параметр протокола —
# скрипт сам спрашивает GitHub API за свежим релизом, а не доверяет тому,
# что прошло через браузер/реестр.
#
# Прогресс НЕ рисуется отдельным окошком — шлётся на локальный API панели
# (POST /api/update/progress, публичный — у этого скрипта нет способа
# авторизоваться перед панелью, см. server.js), панель сама показывает его в
# том же баннере, где была кнопка "Обновить" (см. app.js). Нативный
# MessageBox — только запасной канал на случай, если панель вообще
# недоступна (иначе пользователь не увидел бы вообще никакой реакции).
$ProgressPreference = 'SilentlyContinue'
Add-Type -AssemblyName System.Windows.Forms

$ProgressUrl = 'http://localhost:8081/api/update/progress'

function Show-Message([string]$Text, [string]$Icon = 'Information') {
  [System.Windows.Forms.MessageBox]::Show($Text, 'Bondcast Stream', 'OK', $Icon) | Out-Null
}

# Возвращает $true, если панель приняла отчёт — вызывающий код на терминальных
# статусах (done/error) дублирует сообщение в MessageBox, если панель не ответила.
function Report-Progress([string]$Status, [int]$Percent = 0, [string]$Message = '') {
  try {
    $body = @{ status = $Status; percent = $Percent; message = $Message } | ConvertTo-Json -Compress
    Invoke-RestMethod -UseBasicParsing -Uri $ProgressUrl -Method Post -ContentType 'application/json' -Body $body | Out-Null
    return $true
  } catch {
    return $false
  }
}

function Complete-Update([string]$Status, [string]$Message, [string]$Icon = 'Information') {
  $reported = Report-Progress -Status $Status -Percent 100 -Message $Message
  if (-not $reported) {
    Show-Message $Message $Icon
  }
}

Report-Progress -Status 'starting' | Out-Null

$versionPath = Join-Path $PSScriptRoot 'VERSION'
$currentVersionRaw = if (Test-Path $versionPath) { (Get-Content $versionPath -Raw).Trim() } else { '0.0.0' }

try {
  $release = Invoke-RestMethod -UseBasicParsing -Uri 'https://api.github.com/repos/i30mb1/Bondcast/releases/latest' `
    -Headers @{ 'User-Agent' = 'BondcastStream-Updater' }
} catch {
  Complete-Update 'error' "Не удалось проверить обновления: $($_.Exception.Message)" 'Error'
  exit 1
}

$latestVersionRaw = $release.tag_name -replace '^v', ''
$asset = $release.assets | Where-Object { $_.name -like '*.exe' } | Select-Object -First 1
if (-not $asset) {
  Complete-Update 'error' 'В последнем релизе на GitHub нет .exe-файла — обратись к разработчику.' 'Error'
  exit 1
}

# Защита от повторного клика/гонки — сверяем ещё раз здесь, не только в
# панели (та могла показать баннер до этого момента, версия могла успеть
# смениться, или пользователь мог нажать "Обновить" из старой вкладки).
try {
  if ([version]$latestVersionRaw -le [version]$currentVersionRaw) {
    Complete-Update 'done' "Уже установлена последняя версия ($currentVersionRaw)."
    exit 0
  }
} catch {
  # currentVersionRaw не распарсился как версия (напр. "dev") — не блокируем
  # обновление из-за этого, просто пропускаем сверку.
}

$installerPath = Join-Path $env:TEMP 'BondcastStream-Update.exe'
try {
  $request = [System.Net.HttpWebRequest]::Create($asset.browser_download_url)
  $request.UserAgent = 'BondcastStream-Updater'
  $response = $request.GetResponse()
  $totalBytes = $response.ContentLength
  $responseStream = $response.GetResponseStream()
  $targetStream = [System.IO.File]::Create($installerPath)
  $buffer = New-Object byte[] 65536
  $totalRead = 0
  $lastPercent = -1
  do {
    $bytesRead = $responseStream.Read($buffer, 0, $buffer.Length)
    if ($bytesRead -gt 0) {
      $targetStream.Write($buffer, 0, $bytesRead)
      $totalRead += $bytesRead
      if ($totalBytes -gt 0) {
        $percent = [Math]::Min(100, [int](($totalRead / $totalBytes) * 100))
        if ($percent -ne $lastPercent) {
          Report-Progress -Status 'downloading' -Percent $percent | Out-Null
          $lastPercent = $percent
        }
      }
    }
  } while ($bytesRead -gt 0)
  $targetStream.Close()
  $responseStream.Close()
  $response.Close()
} catch {
  Complete-Update 'error' "Не удалось скачать обновление: $($_.Exception.Message)" 'Error'
  exit 1
}

# Снимает Zone.Identifier (Mark-of-the-Web) — без этого SmartScreen может
# перехватить запуск неподписанного .exe (сертификата нет, см. setup.iss)
# даже при /VERYSILENT и показать блокирующий экран вместо тихой установки.
# Программа уже установлена и ей доверяют — это то же доверие, что и при
# первом запуске .exe вручную с landing page, не новый риск.
Unblock-File -Path $installerPath -ErrorAction SilentlyContinue

Report-Progress -Status 'installing' -Percent 100 | Out-Null

$process = Start-Process -FilePath $installerPath -ArgumentList '/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART' -Wait -PassThru
Remove-Item $installerPath -ErrorAction SilentlyContinue

if ($process.ExitCode -eq 0) {
  Complete-Update 'done' "Обновление до версии $latestVersionRaw установлено. Перезапусти трансляцию (закрой и открой ярлык `"Запустить трансляцию`" заново), чтобы применить изменения."
} else {
  Complete-Update 'error' "Установка обновления завершилась с кодом $($process.ExitCode) — попробуй ещё раз или установи вручную с GitHub." 'Error'
}

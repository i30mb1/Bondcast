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
$ProgressPreference = 'SilentlyContinue'

function Show-Message([string]$Text, [string]$Icon = 'Information') {
  Add-Type -AssemblyName System.Windows.Forms
  [System.Windows.Forms.MessageBox]::Show($Text, 'Bondcast Stream', 'OK', $Icon) | Out-Null
}

$versionPath = Join-Path $PSScriptRoot 'VERSION'
$currentVersionRaw = if (Test-Path $versionPath) { (Get-Content $versionPath -Raw).Trim() } else { '0.0.0' }

try {
  $release = Invoke-RestMethod -UseBasicParsing -Uri 'https://api.github.com/repos/i30mb1/Bondcast/releases/latest' `
    -Headers @{ 'User-Agent' = 'BondcastStream-Updater' }
} catch {
  Show-Message "Не удалось проверить обновления: $($_.Exception.Message)" 'Error'
  exit 1
}

$latestVersionRaw = $release.tag_name -replace '^v', ''
$asset = $release.assets | Where-Object { $_.name -like '*.exe' } | Select-Object -First 1
if (-not $asset) {
  Show-Message 'В последнем релизе на GitHub нет .exe-файла — обратись к разработчику.' 'Error'
  exit 1
}

# Защита от повторного клика/гонки — сверяем ещё раз здесь, не только в
# панели (та могла показать баннер до этого момента, версия могла успеть
# смениться, или пользователь мог нажать "Обновить" из старой вкладки).
try {
  if ([version]$latestVersionRaw -le [version]$currentVersionRaw) {
    Show-Message "Уже установлена последняя версия ($currentVersionRaw)." 'Information'
    exit 0
  }
} catch {
  # currentVersionRaw не распарсился как версия (напр. "dev") — не блокируем
  # обновление из-за этого, просто пропускаем сверку.
}

Show-Message "Скачиваю и тихо устанавливаю версию $latestVersionRaw. Окно с прогрессом не появится — по готовности будет сообщение." 'Information'

$installerPath = Join-Path $env:TEMP 'BondcastStream-Update.exe'
try {
  Invoke-WebRequest -UseBasicParsing -Uri $asset.browser_download_url -OutFile $installerPath
} catch {
  Show-Message "Не удалось скачать обновление: $($_.Exception.Message)" 'Error'
  exit 1
}

# Снимает Zone.Identifier (Mark-of-the-Web) — без этого SmartScreen может
# перехватить запуск неподписанного .exe (сертификата нет, см. setup.iss)
# даже при /VERYSILENT и показать блокирующий экран вместо тихой установки.
# Программа уже установлена и ей доверяют — это то же доверие, что и при
# первом запуске .exe вручную с landing page, не новый риск.
Unblock-File -Path $installerPath -ErrorAction SilentlyContinue

$process = Start-Process -FilePath $installerPath -ArgumentList '/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART' -Wait -PassThru
Remove-Item $installerPath -ErrorAction SilentlyContinue

if ($process.ExitCode -eq 0) {
  Show-Message "Обновление до версии $latestVersionRaw установлено. Перезапусти трансляцию (закрой и открой ярлык `"Запустить трансляцию`" заново), чтобы применить изменения." 'Information'
} else {
  Show-Message "Установка обновления завершилась с кодом $($process.ExitCode) — попробуй ещё раз или установи вручную с GitHub." 'Error'
}

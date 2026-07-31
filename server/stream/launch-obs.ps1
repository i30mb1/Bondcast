# Обработчик протокола bondcast-obs:// (регистрирует installer/setup.iss в
# HKCU\Software\Classes\bondcast-obs) — кнопка "Запустить OBS" в панели не
# может напрямую стартовать .exe на хосте, панель сама сидит в Docker-контейнере;
# вместо этого браузер открывает bondcast-obs://launch, а Windows сама передаёт
# запуск сюда через зарегистрированный протокол.
#
# obs64.exe нигде не публикует свой путь через переменные окружения/PATH,
# поэтому ищем его: сначала стандартный путь программы, потом — на случай
# нестандартной директории установки — по записи в реестре деинсталлятора
# (у OBS Studio там всегда есть InstallLocation, а сам exe стабильно лежит
# в <InstallLocation>\bin\64bit\obs64.exe).
$ProgressPreference = 'SilentlyContinue'

$candidates = @('C:\Program Files\obs-studio\bin\64bit\obs64.exe')

$uninstallKeys = @(
  'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\*',
  'HKLM:\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\*',
  'HKCU:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\*'
)
foreach ($key in $uninstallKeys) {
  Get-ItemProperty -Path $key -ErrorAction SilentlyContinue |
    Where-Object { $_.DisplayName -like 'OBS Studio*' -and $_.InstallLocation } |
    ForEach-Object { $candidates += (Join-Path $_.InstallLocation 'bin\64bit\obs64.exe') }
}

$obsExe = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1

if (-not $obsExe) {
  Add-Type -AssemblyName System.Windows.Forms
  [System.Windows.Forms.MessageBox]::Show(
    'OBS Studio не найден на этом компьютере. Установи его с obsproject.com и запусти вручную.',
    'Bondcast Stream', 'OK', 'Warning') | Out-Null
  exit 1
}

# WorkingDirectory обязательно bin\64bit — OBS ищет плагины и данные
# относительно неё, при другой рабочей папке загрузка плагинов иногда ломается.
Start-Process -FilePath $obsExe -WorkingDirectory (Split-Path $obsExe)

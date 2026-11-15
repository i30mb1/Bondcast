# Обработчик протокола bondcast-netcheck:// (регистрирует installer/setup.iss в
# HKCU\Software\Classes\bondcast-netcheck) — кнопка «Найти, где теряется» в панели.
# Панель сидит в Docker-контейнере и счётчиков сетевой карты Windows не видит;
# этот скрипт снимает их дважды с паузой и шлёт панели (POST /api/netcheck/report,
# публичный — у скрипта нет способа авторизоваться, см. server.js). Панель в те же
# моменты снимает счётчики контейнера SRS и сравнивает: в карту пришло столько же,
# сколько дошло до SRS, — пакеты пропали ещё до компьютера; пришло больше —
# теряются уже здесь. Тот же мост, что у launch-obs.ps1 и update.ps1.
#
# Скрипт только читает счётчики и ничего не меняет, прав администратора не нужно.
# Параметр протокола намеренно не используется: всё, что прошло через браузер и
# реестр, сюда не попадает.
#
# Запустить можно и руками (копия без установщика, где протокол не зарегистрирован):
# нажать кнопку в панели и следом выполнить
#   powershell -ExecutionPolicy Bypass -File net-check.ps1
$ProgressPreference = 'SilentlyContinue'

$ReportUrl = 'http://localhost:8081/api/netcheck/report'

# Какие карты настоящие — тем же способом, что get-host-ips.ps1: только через них
# эфир может прийти снаружи. Виртуальные (vEthernet, WSL, VPN) панель в расчёт не
# берёт — по ним идёт обратный поток из контейнеров на этот же компьютер.
$physical = @{}
try {
  Get-NetAdapter -Physical -ErrorAction Stop | ForEach-Object { $physical[$_.Name] = $true }
} catch { }

# Сами счётчики берём из .NET, а не из netsh/Get-Counter: у тех названия переведены,
# и на русской Windows разбор ломается.
function Get-NetSample {
  $adapters = @()
  foreach ($nic in [System.Net.NetworkInformation.NetworkInterface]::GetAllNetworkInterfaces()) {
    if ($nic.OperationalStatus -ne [System.Net.NetworkInformation.OperationalStatus]::Up) { continue }
    if ($nic.NetworkInterfaceType -eq [System.Net.NetworkInformation.NetworkInterfaceType]::Loopback) { continue }
    $ips = @($nic.GetIPProperties().UnicastAddresses |
      Where-Object { $_.Address.AddressFamily -eq [System.Net.Sockets.AddressFamily]::InterNetwork } |
      ForEach-Object { $_.Address.ToString() })
    $stats = $nic.GetIPStatistics()
    $adapters += @{
      name          = $nic.Name
      ips           = $ips
      physical      = [bool]$physical[$nic.Name]
      bytesReceived = $stats.BytesReceived
      discarded     = $stats.IncomingPacketsDiscarded
      errors        = $stats.IncomingPacketsWithErrors
    }
  }
  # Все UDP-датаграммы, доставленные программам на этой машине. Проброшенный порт
  # Docker Desktop слушает обычная программа Windows, так что эфир сюда попадает.
  $global = [System.Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties()
  $received = 0; $discarded = 0; $errors = 0
  foreach ($family in 'v4', 'v6') {
    try {
      $udp = if ($family -eq 'v4') { $global.GetUdpIPv4Statistics() } else { $global.GetUdpIPv6Statistics() }
      $received += $udp.DatagramsReceived
      $discarded += $udp.IncomingDatagramsDiscarded
      $errors += $udp.IncomingDatagramsWithErrors
    } catch { } # IPv6 на машине может быть выключен целиком
  }
  return @{
    t        = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    udp      = @{ received = $received; discarded = $discarded; errors = $errors }
    adapters = @($adapters)
  }
}

# Тело шлём байтами UTF-8: Windows PowerShell 5.1 строку кодирует по-своему, и
# русские имена сетевых карт («Беспроводная сеть») приехали бы кракозябрами.
function Send-Report([hashtable]$Body) {
  $json = $Body | ConvertTo-Json -Depth 6 -Compress
  return Invoke-RestMethod -UseBasicParsing -Uri $ReportUrl -Method Post `
    -ContentType 'application/json; charset=utf-8' -Body ([System.Text.Encoding]::UTF8.GetBytes($json)) -TimeoutSec 10
}

try {
  $answer = Send-Report @{ status = 'measuring'; sample = (Get-NetSample) }
} catch {
  # Панель отказала (замер не запускали кнопкой) или недоступна — мерить не для кого.
  exit 1
}

$seconds = 30
if ($answer -and $answer.seconds -ge 5 -and $answer.seconds -le 120) { $seconds = [int]$answer.seconds }

try {
  Start-Sleep -Seconds $seconds
  Send-Report @{ status = 'done'; sample = (Get-NetSample) } | Out-Null
} catch {
  try { Send-Report @{ status = 'error'; message = "$($_.Exception.Message)" } | Out-Null } catch { }
  exit 1
}

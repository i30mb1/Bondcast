# Адрес роутера (шлюз по умолчанию) этой машины. Панель сама его не видит: она сидит
# в Docker-сети, и её собственный шлюз — это бридж Docker'а (172.x.x.1), а не роутер.
# Поэтому спрашиваем снаружи, как и LAN-адреса (get-host-ips.ps1), и передаём через
# GATEWAY_IPS. Нужен, чтобы дать в панели рабочую ссылку прямо в веб-морду роутера —
# угадывать нельзя, у всех по-разному: 192.168.1.1, 192.168.0.1, 192.168.31.1, 10.0.0.1.
#
# Физические адаптеры only — по той же причине, что и в get-host-ips.ps1: у VPN-туннелей,
# Docker-овского vEthernet и Hyper-V/WSL-свитчей свои шлюзы, и они не роутер.
# Сортировка по метрике — первым идёт тот шлюз, через который реально ходит трафик.
$ProgressPreference = 'SilentlyContinue'
$ifaces = Get-NetAdapter -Physical | Where-Object Status -eq 'Up' | Select-Object -ExpandProperty ifIndex
$hops = Get-NetRoute -AddressFamily IPv4 -DestinationPrefix '0.0.0.0/0' -InterfaceIndex $ifaces -ErrorAction SilentlyContinue |
  Sort-Object RouteMetric |
  ForEach-Object { $_.NextHop } |
  Where-Object { $_ -and $_ -ne '0.0.0.0' } |
  Select-Object -Unique
$hops -join ','

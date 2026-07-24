# LAN IP(-ы) этой машины — панель сидит в Docker-сети и не видит их сама,
# поэтому start.bat вызывает этот скрипт снаружи и передаёт результат панели
# через HOST_IPS. Физические адаптеры only - иначе VPN-туннели, Docker-овский
# vEthernet, Hyper-V/WSL virtual switches и т.п. тоже попадают в список и
# заваливают раздел "Подключение" в панели адресами, которыми нельзя пользоваться.
$ProgressPreference = 'SilentlyContinue'
$ifaces = Get-NetAdapter -Physical | Where-Object Status -eq 'Up' | Select-Object -ExpandProperty ifIndex
(Get-NetIPAddress -AddressFamily IPv4 -InterfaceIndex $ifaces -ErrorAction SilentlyContinue | Where-Object { $_.PrefixOrigin -ne 'WellKnown' }).IPAddress -join ','

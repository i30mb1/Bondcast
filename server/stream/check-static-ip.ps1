# Провайдер нигде не публикует явный флаг "IP статичный/динамический" - это
# внутренняя политика ISP, наружу не видна. Практический сигнал, который
# реально работает: PTR-запись (обратный DNS) публичного адреса - многие
# провайдеры сами кодируют в hostname static/dynamic/pool/dhcp для своих же
# нужд (проверено вживую: "mm-X-X-X-X.static.mgts.by"). Не 100% гарантия -
# часть ISP вообще не ставит PTR или использует нейтральное имя без маркера,
# тогда результат "unknown", а не "dynamic" - это не доказательство обратного.
param(
  [Parameter(Mandatory = $true)]
  [string]$Ip
)

try {
  $ptr = (Resolve-DnsName -Name $Ip -Type PTR -ErrorAction Stop -DnsOnly | Select-Object -First 1).NameHost
} catch {
  Write-Output "unknown|"
  exit 0
}

if ([string]::IsNullOrWhiteSpace($ptr)) {
  Write-Output "unknown|"
  exit 0
}

$name = $ptr.ToLowerInvariant()
if ($name -match 'static') {
  Write-Output "static|$ptr"
} elseif ($name -match 'dynamic|dhcp|pool|dsl|cable|adsl|customer|residential|home') {
  Write-Output "dynamic|$ptr"
} else {
  Write-Output "unknown|$ptr"
}

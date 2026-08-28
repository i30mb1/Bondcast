# Предполётная проверка портов: кто уже занял то, что нужно нашим контейнерам.
#
# Зачем отдельный шаг, если панель и так умеет объяснять занятый порт (diagnoseClosed
# в panel/public/app.js): панель к тому моменту должна УЖЕ работать, а самый неприятный
# случай — занят её собственный 8081. Тогда "docker compose up" падает английской
# простынёй в консоли, start.bat всё равно открывает браузер на localhost:8081 — и
# человек видит чужое приложение вместо панели. Ловим это ДО compose.
#
# Скрипт ничего не убивает по своей воле: чужая программа на нашем порту может быть
# нужна человеку. Единственное исключение — -FixDocker, он сносит НАШ ЖЕ осиротевший
# контейнер (см. шапку docker-compose.yml про два compose-проекта на одну машину).
#
# Правило безопасности: любая внутренняя ошибка этого скрипта = выход 0. Проверка
# вспомогательная, она не имеет права мешать запуску, если сама сломалась.
param(
  # "порт:протокол[:warn]" через запятую. warn = порт нужен не всем и не на старте
  # (8765 — субтитры, они поднимаются лениво), про такой только предупреждаем.
  [Parameter(Mandatory = $true)][string]$Ports,
  # Снести наш осиротевший контейнер, если порт держит он, и перепроверить.
  [switch]$FixDocker,
  # Шаблон страницы и куда положить готовую. Без них страница не создаётся,
  # скрипт просто печатает отчёт в консоль и отдаёт код возврата.
  [string]$Template = '',
  [string]$Out = ''
)

$ProgressPreference = 'SilentlyContinue'
$ErrorActionPreference = 'Continue'

# Compose-проекты, чьи контейнеры считаем своими. "bondcast-stream" закреплён в
# docker-compose.yml (name:), два других — как проект назывался раньше, по имени
# папки: "stream" при запуске из репо, "bondcaststream" у установленной копии.
# Осиротевшие контейнеры оттуда и есть самый частый случай "docker держит наш порт".
$OUR_PROJECTS = @('bondcast-stream', 'stream', 'bondcaststream')

# Процессы, за которыми стоит Docker Desktop: опубликованный порт на хосте держит
# не сам контейнер, а вот эта прослойка. Имя процесса зависит от бэкенда (WSL2 или
# Hyper-V) и версии Docker Desktop, поэтому список широкий.
$DOCKER_PROCESSES = '^(com\.docker\.|docker|vpnkit|wslrelay|wslhost)'

# Диапазоны портов, которые Windows зарезервировала под себя (Hyper-V/WSL/WinNAT).
# Классика ровно про наш 5000: bind падает с "An attempt was made to access a socket
# in a way forbidden by its access permissions", хотя порт никем не занят.
# Заголовки таблицы локализованы, а строки с числами — нет, поэтому парсим цифры.
function Get-ExcludedRanges([string]$proto) {
  $ranges = @()
  try {
    $lines = netsh int ipv4 show excludedportrange protocol=$proto 2>$null
    foreach ($line in $lines) {
      if ($line -match '^\s*(\d+)\s+(\d+)') {
        $ranges += , @([int]$Matches[1], [int]$Matches[2])
      }
    }
  } catch { }
  return $ranges
}

# Кто слушает порт прямо сейчас. $null — свободен.
function Get-PortHolder([int]$port, [string]$proto) {
  try {
    $endpoint = if ($proto -eq 'udp') {
      Get-NetUDPEndpoint -LocalPort $port -ErrorAction SilentlyContinue | Select-Object -First 1
    } else {
      Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    }
    if (-not $endpoint) { return $null }

    $procId = $endpoint.OwningProcess
    $name = ''
    $path = ''
    try {
      $proc = Get-Process -Id $procId -ErrorAction Stop
      $name = $proc.ProcessName
      # .Path бросает Access denied для системных и чужих процессов — путь приятен,
      # но не обязателен: имени и PID хватает, чтобы человек нашёл программу.
      try { $path = $proc.Path } catch { $path = '' }
    } catch {
      # PID 0/4 и защищённые процессы Get-Process не отдаёт вовсе.
      $name = if ($procId -eq 4 -or $procId -eq 0) { 'System' } else { '' }
    }

    $kind = if ($name -match $DOCKER_PROCESSES) { 'docker' }
            elseif ($procId -eq 4 -or $procId -eq 0 -or $name -eq 'System') { 'system' }
            else { 'other' }

    return [pscustomobject]@{ kind = $kind; pid = $procId; process = $name; path = $path }
  } catch {
    # Нет модуля NetTCPIP или запрет на запрос — молчим и считаем порт свободным:
    # пусть compose попробует сам, вместо того чтобы не пустить его из-за нашей ошибки.
    return $null
  }
}

# Снести наш осиротевший контейнер, который держит этот порт. Фильтр по метке
# compose-проекта обязателен: под "publish=<порт>" может попасть и чужой контейнер
# пользователя, а его трогать нельзя.
function Remove-OurContainersOnPort([int]$port) {
  $removed = $false
  foreach ($project in $OUR_PROJECTS) {
    try {
      $ids = docker ps -aq --filter "publish=$port" --filter "label=com.docker.compose.project=$project" 2>$null
    } catch { continue }
    foreach ($id in @($ids)) {
      if (-not $id) { continue }
      Write-Output "  removing leftover container $id (project $project)"
      docker rm -f $id 2>&1 | Out-Null
      $removed = $true
    }
  }
  return $removed
}

# --- Собственно проверка -----------------------------------------------------

$excludedCache = @{}
$busy = @()

foreach ($spec in $Ports.Split(',')) {
  $parts = $spec.Trim().Split(':')
  if ($parts.Count -lt 2) { continue }
  $port = [int]$parts[0]
  $proto = $parts[1].ToLowerInvariant()
  $severity = if ($parts.Count -ge 3 -and $parts[2] -eq 'warn') { 'warn' } else { 'block' }

  $holder = Get-PortHolder $port $proto

  if ($holder -and $holder.kind -eq 'docker' -and $FixDocker) {
    Write-Output "Port $port/$proto is held by Docker - looking for our own leftover containers..."
    if (Remove-OurContainersOnPort $port) {
      Start-Sleep -Milliseconds 700
      $holder = Get-PortHolder $port $proto
    }
  }

  if (-not $holder) {
    # Свободен по сокетам — но мог попасть в резерв Windows. Тогда никто не слушает,
    # а занять его всё равно не выйдет: это разные вещи, и лечатся они по-разному.
    if (-not $excludedCache.ContainsKey($proto)) { $excludedCache[$proto] = Get-ExcludedRanges $proto }
    $reserved = $false
    foreach ($range in $excludedCache[$proto]) {
      if ($port -ge $range[0] -and $port -le $range[1]) { $reserved = $true; break }
    }
    if (-not $reserved) { continue }
    $busy += [pscustomobject]@{
      port = $port; proto = $proto; severity = $severity
      kind = 'reserved'; pid = 0; process = ''; path = ''
    }
    Write-Output "Port $port/$proto is reserved by Windows (Hyper-V/WSL port exclusion range)."
    continue
  }

  $busy += [pscustomobject]@{
    port = $port; proto = $proto; severity = $severity
    kind = $holder.kind; pid = $holder.pid; process = $holder.process; path = $holder.path
  }
  $who = if ($holder.process) { "$($holder.process) (PID $($holder.pid))" } else { "PID $($holder.pid)" }
  Write-Output "Port $port/$proto is busy: $who"
}

$blocking = @($busy | Where-Object { $_.severity -eq 'block' })

# Страницу рисуем и тогда, когда заняты только "warn"-порты: start.bat в этом случае
# продолжает запуск и не открывает её, но если человек откроет файл руками — там будет
# всё, что мы знаем.
if ($Template -and $Out -and $busy.Count -gt 0) {
  try {
    $json = ConvertTo-Json -InputObject $busy -Depth 4 -Compress
    # ConvertTo-Json умеет развернуть массив из одного элемента в голый объект, а на
    # той стороне ждут массив всегда. Смотрим на результат, а не на длину списка:
    # поведение зависит от версии PowerShell, а вот скобка на месте или нет — факт.
    if (-not $json.StartsWith('[')) { $json = "[$json]" }
    $html = [System.IO.File]::ReadAllText($Template, [System.Text.Encoding]::UTF8)
    $html = $html.Replace('/*PORTS-DATA*/null', $json)
    [System.IO.File]::WriteAllText($Out, $html, (New-Object System.Text.UTF8Encoding($false)))
  } catch {
    Write-Output "Could not write the report page: $($_.Exception.Message)"
  }
}

if ($blocking.Count -gt 0) { exit 1 }
exit 0

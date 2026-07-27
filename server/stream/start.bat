@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0"

:: Title stays plain ASCII for the same codepage reason as the echo text below -
:: it's still useful even to a non-technical user as a "something is happening,
:: this window is fine" signal, updated again at each stage further down.
title Bondcast Stream - starting up...

set "PROJECT_ROOT=%~dp0"
if "%PROJECT_ROOT:~-1%"=="\" set "PROJECT_ROOT=%PROJECT_ROOT:~0,-1%"

:: If you want a fixed/manual address instead of auto-detection below, put it
:: here - it'll be the only address shown in the panel's "Connections" section.
:: Leave empty ("") to auto-detect. Example: "203.0.113.10"
set "HOST_IP_OVERRIDE="

:: The phone talks to this machine over the internet, so the address that
:: actually matters is the public one, not a NIC's LAN address - ask a public
:: IP-echo service instead of poking around this machine's network adapters.
:: --max-time bounds it in case there's no internet route right now.
:: Falls back to LAN IP detection (get-host-ips.ps1, physical adapters only -
:: VPN tunnels / Docker's own vEthernet / Hyper-V-WSL virtual switches are
:: skipped so they don't flood the panel's Connections section) if the public
:: lookup fails, so something still shows up when this machine is offline.
if not "%HOST_IP_OVERRIDE%"=="" (
  set "HOST_IPS=%HOST_IP_OVERRIDE%"
) else (
  set "HOST_IPS="
  for /f "delims=" %%a in ('curl -s --max-time 5 https://api.ipify.org') do set "HOST_IPS=%%a"
  :: Отмечаем, что это именно публичный IP, а не LAN-фолбэк ниже - проверка
  :: статичности имеет смысл только для него.
  set "PUBLIC_IP_OK=0"
  if not "!HOST_IPS!"=="" set "PUBLIC_IP_OK=1"
  if "!HOST_IPS!"=="" (
    for /f "delims=" %%a in ('powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0get-host-ips.ps1"') do set "HOST_IPS=%%a"
  )

  if not "!HOST_IPS!"=="" (
    :: If a VPN is active right now, the address curl just detected above is the
    :: VPN's exit IP, not this router's real public IP - no port forwarding will
    :: ever match it, so the phone won't reach this machine over the internet
    :: however it's configured. Ask ip-api.com's free "proxy" flag whether this
    :: specific IP is a known VPN/proxy/Tor exit address - far more reliable than
    :: guessing from local VPN-client adapter names, which missed real VPN clients
    :: it didn't recognize.
    for /f "delims=" %%a in ('curl -s --max-time 5 "http://ip-api.com/line/!HOST_IPS!?fields=proxy"') do set "VPN_DETECTED=%%a"
    if /i "!VPN_DETECTED!"=="true" (
      echo.
      echo VPN detected on this PC - !HOST_IPS! is flagged as a known VPN/proxy
      echo exit address, not your router's real public IP. Port forwarding on
      echo your router won't match it, so the phone won't be able to reach this
      echo machine over the internet.
      echo Disable the VPN, then run the desktop shortcut again to pick up your real public IP.
      echo.
    ) else if "!PUBLIC_IP_OK!"=="1" (
      :: Провайдер не сообщает "статичный/динамический" никаким API - смотрим
      :: PTR-запись публичного IP, многие ISP сами кодируют это в hostname
      :: (см. check-static-ip.ps1). Нет надёжного маркера - не значит "точно
      :: динамический", поэтому статус unknown ничего не печатает.
      set "IP_TYPE="
      for /f "delims=" %%a in ('powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0check-static-ip.ps1" -Ip "!HOST_IPS!"') do set "IP_TYPE=%%a"
      for /f "tokens=1,2 delims=|" %%a in ("!IP_TYPE!") do (
        set "IP_TYPE_KIND=%%a"
        set "IP_TYPE_PTR=%%b"
      )
      if /i "!IP_TYPE_KIND!"=="dynamic" (
        echo.
        echo Your public IP !HOST_IPS! looks dynamic ^(reverse DNS: !IP_TYPE_PTR!^).
        echo It can change without warning, and if it does, the phone won't be
        echo able to reach this machine until the address is updated in the app.
        echo Ask your ISP for a static IP, or set up DDNS, to avoid this.
        echo.
      )
      if /i "!IP_TYPE_KIND!"=="static" (
        echo Public IP !HOST_IPS! looks static ^(reverse DNS: !IP_TYPE_PTR!^).
      )
    )
  )
)

where docker >nul 2>nul
if errorlevel 1 (
  echo.
  echo Docker was not found on PATH. Opening install instructions in your browser...
  start "" "docker-missing.html"
  pause
  exit /b 1
)

:: Console echo text below is kept plain ASCII/English on purpose - Cyrillic text
:: in a UTF-8-saved .bat renders as mojibake on consoles whose codepage isn't
:: already UTF-8 (866 by default on this machine's cmd.exe), and forcing
:: "chcp 65001" is not a safe fix here: wsl.exe has long-standing bugs under
:: codepage 65001 (breaks with cryptic "cannot find file/drive" errors) - the
:: Docker/WSL calls below matter more than pretty console text. The HTML pages
:: (docker-missing.html etc.) are unaffected - browsers render UTF-8 correctly.
echo Checking Docker (up to 15s - it may still be starting up)...
:: A plain "docker info" has no timeout and can hang silently for a long time
:: while Docker Desktop is still starting up or stuck - that's what makes
:: start.bat look "frozen" with zero console output. Run it in a background
:: job with a hard timeout instead. DOCKER_CHECK: 0 = ok, 2 = timeout, else = not running.
call :checkDocker
if "!DOCKER_CHECK!"=="0" goto dockerReady

:: Docker CLI is present but the daemon isn't responding - whether the quick
:: check above timed out (DOCKER_CHECK=2) or failed outright, both look
:: identical from here: a Docker Desktop that's still cold-starting (just
:: installed, just rebooted, not set to launch at login, slow WSL2 backend
:: spin-up) produces exactly the same 15s timeout as one that's genuinely
:: stuck - the quick check can't tell those apart, and a cold start is by far
:: the more common case (e.g. right after the installer sets it up). Try
:: (re)launching Docker Desktop - harmless if it's already running or
:: starting - and give it a much longer window before actually giving up.
set "DOCKER_DESKTOP_EXE=%ProgramFiles%\Docker\Docker\Docker Desktop.exe"
if exist "%DOCKER_DESKTOP_EXE%" (
  echo.
  echo Docker Desktop isn't ready yet - starting/waiting for it, this can take a minute or two...
  start "" "%DOCKER_DESKTOP_EXE%"
  for /l %%i in (1,1,24) do (
    timeout /t 5 /nobreak >nul
    docker info >nul 2>&1 && goto dockerReady
  )
)

echo.
echo Docker Desktop still isn't responding. Opening instructions in your browser...
start "" "docker-not-running.html"
pause
exit /b 1

:dockerReady
:: Force-remove any leftover containers from a previous run before recreating them.
:: Needed because the panel can also start/stop/recreate srs and srtla-rec directly
:: via the Docker API (see server.js), bypassing compose - a stale or half-removed
:: container under these names would otherwise make "docker compose up" fail or
:: silently reuse an outdated one instead of picking up the fresh build. "-f" removes
:: it whether it is running or already stopped; missing names are silently ignored.
docker rm -f srs srtla-rec stream-panel asr-worker >nul 2>nul

title Bondcast Stream - building (first run takes a few minutes)...
echo Setting up the streaming server - srs, srtla-rec, panel...
echo First run downloads and builds everything from scratch - can take several minutes, this is normal.
echo Please don't close this window.
:: asr-worker (captions) is intentionally excluded here - it's a multi-GB GPU/CUDA
:: image, built lazily from the panel's dashboard ("Build" button under Captions)
:: only when someone actually wants subtitles, not on every plain startup.
docker compose build --progress=plain srs srtla-rec panel
if errorlevel 1 (
  echo.
  echo Build failed. See output above.
  pause
  exit /b 1
)
docker compose up -d
if errorlevel 1 (
  echo.
  echo Something went wrong. See the output above.
  pause
  exit /b 1
)

title Bondcast Stream - almost ready...
echo Waiting for the panel to come up...
for /l %%i in (1,1,15) do (
  curl -s -o nul -w "%%{http_code}" http://localhost:8081/api/status 2>nul | findstr "200" >nul && goto ready
  timeout /t 1 /nobreak >nul
)

:ready
title Bondcast Stream - running (opening browser)
echo Done! Opening the panel in your browser...
start "" "http://localhost:8081"
exit /b 0

:checkDocker
powershell -NoProfile -Command ^
  "$job = Start-Job { docker info 2>&1 | Out-Null; $LASTEXITCODE }; if (-not (Wait-Job $job -Timeout 15)) { Stop-Job $job | Out-Null; exit 2 }; exit (Receive-Job $job)"
set "DOCKER_CHECK=%errorlevel%"
exit /b

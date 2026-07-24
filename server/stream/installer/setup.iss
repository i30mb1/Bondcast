; Bondcast Stream — установщик поверх Docker Desktop.
; Компилируется Inno Setup 6 (ISCC.exe) из этой папки. Собирает файлы из уровня
; выше (..\) — тот же состав, что и ручной запуск через start.bat, плюс сам
; ставит Docker Desktop, если его ещё нет, и делает ярлык на рабочем столе.
;
; Не подписан code-signing сертификатом — Windows SmartScreen покажет
; предупреждение при первом запуске .exe, это ожидаемо (объяснено на лендинге
; до кнопки скачивания, см. ../index.html).

#define MyAppName "Bondcast Stream"
#define MyAppVersion "1.0.0"
#define MyAppPublisher "Bondcast"
#define MyAppURL "https://github.com/i30mb1/bondcast-stream"

[Setup]
AppId={{7B1D9E5E-7C2A-4B7B-9B1E-BC1B0A2B9C10}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
AppPublisherURL={#MyAppURL}
AppSupportURL={#MyAppURL}
; {localappdata} - ставим без прав администратора, все файлы только для
; текущего пользователя. Docker Desktop сам разберётся со своими правами.
DefaultDirName={localappdata}\BondcastStream
DefaultGroupName={#MyAppName}
DisableProgramGroupPage=yes
DisableDirPage=yes
DisableReadyPage=yes
DisableWelcomePage=no
OutputDir=dist
OutputBaseFilename=BondcastStream-Setup
SetupIconFile=icon.ico
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
ArchitecturesAllowed=x64compatible
PrivilegesRequired=lowest

[Languages]
Name: "russian"; MessagesFile: "compiler:Languages\Russian.isl"

[Messages]
russian.WelcomeLabel2=Установим сервер для трансляции. Нужно 5-10 минут и, возможно, одна перезагрузка.%n%nПрограмма поставит всё, что нужно: видеосервер, приём сигнала с телефона и панель управления ими в браузере.

[Files]
Source: "..\docker-compose.yml"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\start.bat"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\get-host-ips.ps1"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\docker-missing.html"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\docker-not-running.html"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\README.md"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\srs\*"; DestDir: "{app}\srs"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "..\srtla-rec\*"; DestDir: "{app}\srtla-rec"; Flags: ignoreversion recursesubdirs createallsubdirs
; node_modules намеренно не копируется - ставится внутри Docker-образа панели
; через npm install (см. panel/Dockerfile), таскать его сюда незачем.
Source: "..\panel\*"; DestDir: "{app}\panel"; Flags: ignoreversion recursesubdirs createallsubdirs; Excludes: "\node_modules\*"
; asr-obs (субтитры) - должен остаться sibling'ом srs/srtla-rec/panel: docker-compose.yml
; ссылается на него как на "./asr-obs" (build-контекст аср-worker), а панель монтирует
; его же как build-context на "/build-context/asr-obs" - оба пути ломаются, если это
; не прямой подкаталог {app}. __pycache__/reference.npy/host_name.txt/host_samples -
; локальный сгенерированный мусор (см. server/stream/asr-obs/.gitignore), таскать
; незачем; reference.npy отдельно опасен - раньше на нём уже ловили баг, когда Docker
; создавал пустую директорию на месте несуществующего bind-source (см. app/speaker.py).
Source: "..\asr-obs\*"; DestDir: "{app}\asr-obs"; Flags: ignoreversion recursesubdirs createallsubdirs; Excludes: "\__pycache__\*,\host_samples\*,\reference.npy,\reference.npy\*,\host_name.txt"
Source: "icon.ico"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{autodesktop}\Запустить трансляцию"; Filename: "{app}\start.bat"; WorkingDir: "{app}"; IconFilename: "{app}\icon.ico"; Comment: "Bondcast Stream — запустить сервер трансляций"
Name: "{group}\Запустить трансляцию"; Filename: "{app}\start.bat"; WorkingDir: "{app}"; IconFilename: "{app}\icon.ico"

[Run]
; Чекбокс на странице Finished, отмечен по умолчанию (postinstall) - запускает
; тот же ярлык, что остаётся на рабочем столе.
Filename: "{app}\start.bat"; Description: "Запустить трансляцию сейчас"; WorkingDir: "{app}"; Flags: postinstall shellexec skipifsilent runasoriginaluser

[Code]
var
  DockerMissing: Boolean;
  DockerInfoPage: TOutputMsgWizardPage;

// ---------------------------------------------------------------------------
// Проверка Docker. CLI в PATH достаточно - если демон ещё не поднят (Desktop
// только что поставили или он не запущен), это уже умеет объяснить сам
// start.bat (см. docker-not-running.html) - установщику дублировать эту
// логику незачем, у него другая задача: убедиться, что Docker Desktop вообще
// стоит на компьютере.
// ---------------------------------------------------------------------------
function IsDockerCliPresent(): Boolean;
var
  ResultCode: Integer;
begin
  Result := Exec(ExpandConstant('{cmd}'), '/c docker --version >nul 2>&1', '',
    SW_HIDE, ewWaitUntilTerminated, ResultCode) and (ResultCode = 0);
end;

// ---------------------------------------------------------------------------
// Скачивает официальный Docker Desktop Installer.exe с CDN Docker и запускает
// его тихо (--quiet --accept-license). Docker Desktop иногда требует
// перезагрузку (включение WSL2 на чистой Windows Home) - в этом случае просто
// молча продолжаем: ярлык на рабочем столе уже создан Inno Setup'ом к этому
// моменту (CurStepChanged вызывается после [Files]/[Icons]), пользователь
// перезагрузится и нажмёт на него ещё раз, как написано на DockerInfoPage ниже.
// ---------------------------------------------------------------------------
procedure DownloadAndInstallDocker();
var
  ResultCode, Elapsed, Size: Integer;
  InstallerPath, MarkerPath, DockerDesktopExe: String;
begin
  InstallerPath := ExpandConstant('{tmp}\DockerDesktopInstaller.exe');
  MarkerPath := ExpandConstant('{tmp}\docker_download_done.marker');
  DeleteFile(MarkerPath);

  // Docker Desktop Installer.exe весит under 600 МБ - на обычном домашнем
  // интернете скачивание реально занимает несколько минут. curl запущен
  // скрыто (SW_HIDE) и через ewNoWait (не блокируя скрипт), чтобы можно было
  // раз в секунду обновлять статус реальным количеством скачанных МБ - без
  // этого один долгий blocking-вызов оставлял прогресс-бар неподвижным на
  // несколько минут, и это выглядело как зависание (было замечено при тесте).
  // "&& echo done>" - маркер-файл появляется только при успешном завершении
  // curl, ewNoWait сам по себе кода возврата не даёт.
  WizardForm.StatusLabel.Caption := 'Скачиваю Docker Desktop (~500-600 МБ, обычно несколько минут)...';
  WizardForm.Update;
  if not Exec(ExpandConstant('{cmd}'),
       '/c curl -L --max-time 1200 -o "' + InstallerPath + '" ' +
       'https://desktop.docker.com/win/main/amd64/Docker%20Desktop%20Installer.exe ' +
       '&& echo done> "' + MarkerPath + '"',
       '', SW_HIDE, ewNoWait, ResultCode) then
  begin
    MsgBox('Не удалось запустить скачивание Docker Desktop. Установи его вручную: ' +
      'https://www.docker.com/products/docker-desktop/', mbError, MB_OK);
    Exit;
  end;

  Elapsed := 0;
  while (not FileExists(MarkerPath)) and (Elapsed < 1200) do
  begin
    Sleep(1000);
    Elapsed := Elapsed + 1;
    if FileSize(InstallerPath, Size) then
      WizardForm.StatusLabel.Caption := Format(
        'Скачиваю Docker Desktop... %d МБ', [Size div (1024 * 1024)]);
    WizardForm.Update;
  end;

  if not FileExists(MarkerPath) then
  begin
    MsgBox('Не удалось скачать Docker Desktop (нет интернета или слишком медленное соединение). ' +
      'Установи его вручную позже: https://www.docker.com/products/docker-desktop/ — затем запусти ' +
      '"Запустить трансляцию" с рабочего стола ещё раз.', mbError, MB_OK);
    Exit;
  end;
  DeleteFile(MarkerPath);

  WizardForm.StatusLabel.Caption := 'Устанавливаю Docker Desktop (несколько минут, не закрывай окно)...';
  WizardForm.Update;
  if not Exec(InstallerPath, 'install --quiet --accept-license', '',
       SW_SHOWNORMAL, ewWaitUntilTerminated, ResultCode) then
  begin
    MsgBox('Не удалось запустить установку Docker Desktop. Установи его вручную: ' +
      'https://www.docker.com/products/docker-desktop/', mbError, MB_OK);
    Exit;
  end;

  // Установка Docker Desktop не запускает саму программу - без неё команда
  // "docker" не заработает, даже если всё установилось (движок поднимает
  // именно запущенный Docker Desktop, а не сам факт установки). Запускаем
  // явно; ewNoWait - это трей-приложение, которое работает постоянно, а не
  // завершается само. Если Windows успела попросить перезагрузку раньше (для
  // включения WSL2) - .exe может ещё не оказаться на месте, это ожидаемо:
  // ярлык "Запустить трансляцию" уже создан (см. CurStepChanged выше), после
  // перезагрузки Docker Desktop стартует сам или пользователь откроет его вручную.
  DockerDesktopExe := ExpandConstant('{autopf}\Docker\Docker\Docker Desktop.exe');
  if FileExists(DockerDesktopExe) then
  begin
    WizardForm.StatusLabel.Caption := 'Запускаю Docker Desktop (в первый раз - до пары минут)...';
    WizardForm.Update;
    Exec(DockerDesktopExe, '', '', SW_SHOWMINNOACTIVE, ewNoWait, ResultCode);
    // Даём немного времени на старт, прежде чем передать эстафету start.bat -
    // его собственная проверка (:checkDocker) и так подождёт и объяснит
    // пользователю, если движок не успеет подняться за её лимит.
    Sleep(5000);
  end;

  MsgBox('Docker Desktop установлен и запускается. Если компьютер попросит ' +
    'перезагрузиться - перезагрузись, потом нажми на ярлык "Запустить трансляцию" ' +
    'на рабочем столе ещё раз.', mbInformation, MB_OK);
end;

procedure InitializeWizard();
begin
  DockerInfoPage := CreateOutputMsgPage(wpWelcome,
    'Нужна ещё одна программа', 'Docker Desktop (бесплатно)',
    'Bondcast Stream работает поверх Docker Desktop, а его пока не видно на этом компьютере.' + #13#10#13#10 +
    'Установщик поставит его сам на следующем шаге - от тебя ничего не потребуется.' + #13#10#13#10 +
    'Иногда после установки Windows просит один раз перезагрузить компьютер (чтобы включить WSL2). ' +
    'Если это произойдёт - после перезагрузки просто нажми на ярлык "Запустить трансляцию" ' +
    'на рабочем столе ещё раз, всё остальное продолжится само.');
end;

procedure CurPageChanged(CurPageID: Integer);
begin
  // Считаем ровно один раз, пока показана страница Welcome - это нужно
  // сделать ДО того, как пользователь нажмёт "Далее", потому что именно тогда
  // Inno Setup спросит ShouldSkipPage(DockerInfoPage.ID), а он должен уже
  // знать ответ (DockerMissing), не наоборот.
  if CurPageID = wpWelcome then
    DockerMissing := not IsDockerCliPresent();
end;

function ShouldSkipPage(PageID: Integer): Boolean;
begin
  Result := False;
  if PageID = DockerInfoPage.ID then
    Result := not DockerMissing;
end;

procedure CurStepChanged(CurStep: TSetupStep);
begin
  // ssPostInstall наступает уже ПОСЛЕ того, как Inno Setup скопировал файлы и
  // создал ярлыки ([Files]/[Icons]) - см. комментарий у DownloadAndInstallDocker.
  if (CurStep = ssPostInstall) and DockerMissing then
    DownloadAndInstallDocker();
end;

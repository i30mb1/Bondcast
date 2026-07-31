// --- Утилиты ------------------------------------------------------------
function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

function tooltip(text) {
  return `<span class="info" tabindex="0">?<span class="bubble">${escapeHtml(text)}</span></span>`;
}

function addrRow(label, value, hint) {
  return `
    <div class="addr-row">
      <span class="addr-label">${escapeHtml(label)}${hint ? tooltip(hint) : ''}</span>
      <code>${escapeHtml(value)}</code>
      <button class="copy-addr" data-value="${escapeHtml(value)}">Копировать</button>
    </div>`;
}

function bindCopyButtons(root) {
  root.querySelectorAll('.copy-addr').forEach((btn) => {
    btn.onclick = () => {
      navigator.clipboard.writeText(btn.dataset.value);
      const original = btn.textContent;
      btn.textContent = 'Скопировано';
      setTimeout(() => { btn.textContent = original; }, 1200);
    };
  });
}

// Discord-style случайное имя стрима вместо унылого "livestream" — adjective-noun-1234.
const NAME_ADJECTIVES = ['turbo', 'sneaky', 'feral', 'spicy', 'soggy', 'glorious', 'unhinged', 'majestic', 'chaotic', 'crispy', 'salty', 'fancy', 'goblin', 'based', 'cursed', 'radiant', 'grumpy', 'sleepy', 'unstable', 'legendary'];
const NAME_NOUNS = ['hamster', 'otter', 'walrus', 'goose', 'capybara', 'raccoon', 'penguin', 'narwhal', 'possum', 'ferret', 'wombat', 'axolotl', 'llama', 'platypus', 'yeti', 'gremlin', 'potato', 'pigeon', 'moth', 'shrimp'];

function randomStreamName() {
  const adj = NAME_ADJECTIVES[Math.floor(Math.random() * NAME_ADJECTIVES.length)];
  const noun = NAME_NOUNS[Math.floor(Math.random() * NAME_NOUNS.length)];
  const num = Math.floor(Math.random() * 900 + 100);
  return `${adj}-${noun}-${num}`;
}

const STREAM_NAME_KEY = 'bondcast_stream_name';

function getOrCreateStreamName() {
  return localStorage.getItem(STREAM_NAME_KEY) || regenerateStreamName();
}

function regenerateStreamName() {
  const name = randomStreamName();
  localStorage.setItem(STREAM_NAME_KEY, name);
  return name;
}

// --- Вкладки ---------------------------------------------------------------
// Раньше это были две отдельные страницы (index.html — быстрый старт,
// dashboard.html — расширенная панель); теперь одна страница с вкладками,
// выбор которых переживает перезагрузку так же, как остальные настройки
// панели (localStorage), а не сбрасывается на дефолт.
const TAB_KEY = 'bondcast_tab';
const TABS = ['quickstart', 'stream'];

const uiState = {
  tab: TABS.includes(localStorage.getItem(TAB_KEY)) ? localStorage.getItem(TAB_KEY) : 'quickstart',
};

function setTab(tab) {
  if (!TABS.includes(tab)) return;
  uiState.tab = tab;
  localStorage.setItem(TAB_KEY, tab);
  applyUiState();
}

function applyUiState() {
  document.querySelectorAll('.tab-btn').forEach((btn) => {
    btn.classList.toggle('active', btn.dataset.tab === uiState.tab);
  });
  document.querySelectorAll('.tab-panel').forEach((panel) => {
    panel.classList.toggle('active', panel.dataset.tabPanel === uiState.tab);
  });
}

document.querySelectorAll('.tab-btn').forEach((btn) => { btn.onclick = () => setTab(btn.dataset.tab); });
applyUiState();

// --- Достижимость портов снаружи (баннер + карточка) -----------------------
// Проверяем три порта: 5000/UDP (бондинг, srtla-rec), 10080/UDP (прямой SRT в
// SRS) и 4455/TCP (управление OBS по WebSocket, если телефон дёргает OBS не
// из той же локальной сети) — у стримера может быть открыт не весь набор
// (см. server/stream/README.md, раздел «Стримишь не в своей сети»), и это
// должно быть видно на панели сразу, а не выясняться потом руками через
// docker ps/роутер.
const PORTS_TO_CHECK = [
  { port: 5000, proto: 'udp', label: 'Бондинг' },
  { port: 10080, proto: 'udp', label: 'Прямой SRT' },
  { port: 4455, proto: 'tcp', label: 'Управление OBS', optional: true, note: ' — нужен, только если управляешь OBS не из локальной сети' },
  { port: 1935, proto: 'tcp', label: 'RTMP (приглашение друга)', optional: true, note: ' — нужен, только если приглашаешь кого-то стримить через OBS' },
];

// Результаты последней проверки хранятся здесь (не только рендерятся) — чек-лист
// шагов внутри каждого раскрытого сценария (см. «Три сценария начала стрима» ниже)
// читает их отсюда вместо отдельной общей карточки со списком портов.
let latestPortResults = [];

// checkPort()/renderPortChecking()/renderPortResults() раньше всегда работали со
// ВСЕМИ четырьмя портами разом и просто затирали latestPortResults целиком —
// перепроверка одного закрытого порта (клик "Проверить снова" на конкретном шаге)
// поэтому на секунду сбрасывала в спиннер вообще все шаги, включая уже открытые,
// хотя их результат не менялся. Теперь чек по умолчанию по-прежнему берёт все
// порты (PORTS_TO_CHECK), но принимает и подмножество — тогда трогает (помечает
// "проверяю" → перезаписывает результатом) только его, остальные записи в
// latestPortResults остаются как были. portStepHtml() для нетронутых портов
// выдаёт ту же самую строку, что и раньше, поэтому reconcileSteps() их и не
// перерисовывает — обновляется визуально только та карточка/шаг, где реально
// была проблема.
function renderPortChecking(ports) {
  const checkingPorts = new Set(ports.map((p) => p.port));
  latestPortResults = latestPortResults.filter((r) => !checkingPorts.has(r.meta.port));
  renderFlowList();
}

// OBS-порт не виден снаружи почти всегда по одной из двух причин: OBS вообще не
// запущен, или запущен, но в нём не включён WebSocket-сервер (без него порт 4455
// никто не слушает, снаружи он ничем не отличается от закрытого). Показываем эту
// шпаргалку прямо у строки проверки, а не только когда что-то уже не работает —
// удобнее один раз включить сразу с галкой "автозапуск", чем вспоминать потом.
const OBS_WEBSOCKET_HOWTO = `
  <details class="nested">
    <summary>Как включить WebSocket-сервер в OBS</summary>
    <div class="body">
      <ol>
        <li>Запусти OBS Studio.</li>
        <li>Меню <b>Tools → WebSocket Server Settings</b>.</li>
        <li>Поставь галку <b>Enable WebSocket server</b>. Порт по умолчанию — <code>4455</code>, менять не нужно.</li>
        <li><b>Enable Authentication</b> можно оставить выключенным — если управляешь OBS из той же
          локальной сети, что и телефон, пароль не нужен, поле пароля в настройках Bondcast оставь пустым.
          Включай его, только если пробрасываешь этот порт наружу (управляешь не из локальной сети) — иначе
          к твоему OBS сможет подключиться кто угодно из интернета.</li>
        <li>OK — настройка запоминается между запусками OBS, включать заново не нужно. Но сам OBS должен
          быть запущен, чтобы порт был виден снаружи.</li>
      </ol>
    </div>
  </details>`;

// Открывает bondcast-obs:// — протокол регистрирует установщик (installer/setup.iss,
// HKCU\Software\Classes\bondcast-obs), обработчик — launch-obs.ps1 рядом со start.bat.
// Панель сидит в Docker-контейнере и не может напрямую запустить .exe на хосте —
// только так, через собственный URL-протокол, который Windows передаёт нужному
// обработчику сама. Если OBS ставили не через установщик Bondcast (вручную/старая
// версия) — протокол не зарегистрирован, кнопка ничего не сделает, тогда запускай
// OBS вручную.
const OBS_LAUNCH_BUTTON = `
  <button type="button" class="primary" data-action="launch-obs" style="margin-top:8px">Запустить OBS</button>`;

// Один шаг чек-листа внутри раскрытого сценария (замена бывшей общей карточки
// #portStatusCard со списком всех портов сразу) — берёт результат по номеру порта
// из latestPortResults, а не считает сам.
// Состояние проверки конкретного порта — используется и для самого рендера шага
// (portStepHtml), и отдельно для того, чтобы решить, показывать ли СЛЕДУЮЩИЙ шаг
// чек-листа (см. gateSteps ниже): 'pending' — результат ещё не пришёл, 'reachable' —
// открыт, 'bad'/'error' — не открыт или проверка не удалась.
function portCheckState(port) {
  const found = latestPortResults.find((r) => r.meta.port === port);
  if (!found) return 'pending';
  if (found.data.error) return 'error';
  return found.data.reachable ? 'reachable' : 'bad';
}

function portStepHtml({ port, proto, label, optional, note }) {
  const protoLabel = proto.toUpperCase();
  const noteText = optional ? (note || '') : '';
  const obsExtras = port === 4455 ? OBS_LAUNCH_BUTTON + OBS_WEBSOCKET_HOWTO : '';
  const found = latestPortResults.find((r) => r.meta.port === port);
  if (!found) {
    return `
      <div class="flow-step">
        <span class="spinner"></span>
        <div><b>Порт ${port}/${protoLabel}</b><span class="flow-step-meta">Проверяю снаружи — стучусь через check-host.net…</span></div>
      </div>`;
  }
  const { data } = found;
  const recheckLink = `<a href="#" class="recheck-ports" data-port="${port}">Проверить снова</a>`;
  if (data.error) {
    return `
      <div class="flow-step">
        <div class="flow-step-dot bad"></div>
        <div>
          <b>${escapeHtml(label)}: проверка не удалась</b><span class="flow-step-meta">${escapeHtml(data.error)}</span>${obsExtras}
          <div class="row-meta" style="margin-top:6px">${recheckLink}</div>
        </div>
      </div>`;
  }
  if (data.reachable) {
    return `
      <div class="flow-step">
        <div class="flow-step-dot dot-live"></div>
        <div><b>Порт ${port}/${protoLabel}</b><span class="flow-step-meta">${escapeHtml(label)} — открыт снаружи (${escapeHtml(data.targetIp)})${escapeHtml(noteText)}</span>${obsExtras}</div>
      </div>`;
  }
  // Раньше подсказка-и-ссылка "Проверить снова" показывались только для
  // ОБЯЗАТЕЛЬНЫХ портов (!optional) — у опционального порта 4455 просто не было
  // способа перепроверить именно его, кроме полного цикла всех четырёх портов.
  // Развёрнутый текст (VPN/NAT) по-прежнему только для обязательных — для
  // опционального он не всегда даже актуален (note уже объясняет, когда порт
  // вообще нужен), но сама возможность перепроверить нужна в обоих случаях.
  const hint = !optional ? hintFor({ port, proto }, data) : '';
  const natHowto = !optional && data.natLikely && !data.vpnLikely && data.localIp ? natHowtoHtml(port, data.localIp) : '';
  return `
    <div class="flow-step">
      <div class="flow-step-dot ${optional ? 'warn' : 'bad'}"></div>
      <div>
        <b>Порт ${port}/${protoLabel}</b><span class="flow-step-meta">${escapeHtml(label)} — закрыт${data.targetIp ? ` (${escapeHtml(data.targetIp)})` : ''}${escapeHtml(noteText)}</span>${obsExtras}
        <div class="row-meta" style="margin-top:6px">${hint ? escapeHtml(hint) + ' ' : ''}${recheckLink}</div>
        ${natHowto}
      </div>
    </div>`;
}

// Раньше это был текст общего баннера наверху страницы (виден всегда, для любого
// порта сразу) — теперь показывается прямо внутри проблемного шага чек-листа
// нужного сценария, конкретно про тот порт, который сейчас закрыт.
function hintFor({ port, proto }, data) {
  const protoLabel = proto.toUpperCase();
  // ip-api.com красит proxy:true почти любой IP дата-центра/VPS, даже если это просто
  // чей-то сервер, а не VPN/прокси-выход — а сам Bondcast-сервер часто и есть такой VPS
  // (см. CLAUDE.md — self-hosted). "Выключи VPN" тогда бессмысленный совет, выключать
  // нечего: hostingLikely отличает этот случай от настоящего VPN-клиента на машине.
  if (data.vpnLikely && data.hostingLikely) {
    return `Порт ${port}/${protoLabel} не отвечает на ${data.targetIp} (это адрес хостинг-провайдера, не похоже на бытовой VPN) — проверь, что нужный сервис реально запущен и слушает этот порт, и что его не блокирует файрвол этой машины (Windows Defender Firewall и т.п.).`;
  }
  if (data.vpnLikely) {
    return 'Похоже, включён VPN — он часто блокирует трафик наружу. Выключи его и запусти start.bat ещё раз, мы всё перепроверим.';
  }
  if (data.natLikely) {
    return `Порт ${port}/${protoLabel} закрыт снаружи (внешний IP: ${data.targetIp}) — нужно прокинуть его на роутере.`;
  }
  return `Порт ${port}/${protoLabel} снаружи не виден (${data.targetIp}) — выключи антивирус/файрвол и попробуй снова.`;
}

// Пошаговая инструкция по пробросу — только когда закрытый порт упирается именно
// в NAT (самый частый случай для домашнего роутера), а не в VPN/антивирус, где
// шаги другие (см. hintFor выше).
function natHowtoHtml(port, localIp) {
  return `
    <details class="nested" style="margin-top:8px">
      <summary>Как открыть порт на роутере</summary>
      <div class="body">
        <ol>
          <li>Зайди в настройки роутера (обычно <code>192.168.1.1</code> или <code>192.168.0.1</code> в браузере).</li>
          <li>Найди раздел <b>Firewall → Port Forwarding</b> (может называться NAT, Virtual Server, проброс портов).</li>
          <li>Добавь правило: Local IP — <code>${escapeHtml(localIp)}</code>, порт — <code>${port}</code>, протокол — <b>UDP</b> (если нет отдельного UDP, выбери «Both»).</li>
          <li>Если правило уже есть, а порт всё равно закрыт — это частые ошибки в самом правиле:
            <ul>
              <li><b>Public (WAN) порт ≠ Local порт.</b> Легко скопировать рабочее правило для
                одного порта и забыть поменять внешний порт у копии — тогда снаружи по-прежнему
                открыт старый порт, а новый никуда не проброшен.</li>
              <li><b>Заполнено поле «Remote Host».</b> Если там стоит конкретный IP (например, по
                ошибке — публичный IP самого роутера), правило примет подключения только с него и
                отбросит реальный трафик со телефона. Это поле должно быть пустым.</li>
            </ul>
          </li>
          <li>Сохрани и нажми «Проверить снова» ниже.</li>
        </ol>
      </div>
    </details>`;
}

// Точка-диагностика на закрытой плашке сценария (см. .flow-pill-chip в index.html)
// — по портам, реально нужным именно этому сценарию. "pending", пока проверка
// ещё не пришла, ничего не рисуем — не мигать пустым кружком на каждую загрузку.
const FLOW_REQUIRED_PORTS = { bondcast: [5000], 'other-app': [10080], invite: [1935] };

function flowPortStatus(flowId) {
  const ports = FLOW_REQUIRED_PORTS[flowId] || [];
  const found = ports.map((port) => latestPortResults.find((r) => r.meta.port === port));
  if (found.some((r) => !r)) return 'pending';
  return found.every((r) => r.data.reachable) ? 'ok' : 'bad';
}

function renderPortResults(results) {
  const resultPorts = new Set(results.map((r) => r.meta.port));
  latestPortResults = [...latestPortResults.filter((r) => !resultPorts.has(r.meta.port)), ...results];
  renderFlowList();
}

async function checkPort(ports = PORTS_TO_CHECK) {
  renderPortChecking(ports);
  const results = await Promise.all(
    ports.map(async (meta) => {
      try {
        const res = await fetch(`/api/reachability?port=${meta.port}&proto=${meta.proto}`);
        return { meta, data: await res.json() };
      } catch (e) {
        return { meta, data: { error: e.message } };
      }
    }),
  );
  renderPortResults(results);
}

// Первый запуск checkPort() — ниже, после инициализации сценариев: он рендерит
// чек-лист внутри раскрытой ветки через renderFlowList(), а её состояние/DOM
// объявлены дальше в файле — вызывать здесь, до них, рано (ReferenceError на TDZ).

// Делегируем клики на document, а не на конкретный контейнер — обе кнопки живут
// внутри чек-листа сценария, который целиком перерисовывается (innerHTML) на
// каждой проверке порта и на каждое открытие/закрытие ветки, точечный обработчик
// слетал бы вместе с ней.
document.addEventListener('click', (e) => {
  const recheck = e.target.closest('.recheck-ports');
  if (recheck) {
    e.preventDefault();
    const port = Number(recheck.dataset.port);
    const meta = PORTS_TO_CHECK.find((p) => p.port === port);
    checkPort(meta ? [meta] : undefined); // meta не найден — на всякий случай перепроверяем всё, а не молчим
    return;
  }

  const watchLink = e.target.closest('[data-action="watch-preview"]');
  if (watchLink) {
    e.preventDefault();
    openPreview(watchLink.dataset.name);
    return;
  }

  const btn = e.target.closest('[data-action="launch-obs"]');
  if (!btn) return;
  window.location.href = 'bondcast-obs://launch';
  // Кнопка бесполезна, пока OBS стартует (повторный клик просто откроет второй раз
  // впустую) — прячем на время запуска и сразу перепроверяем порт, чтобы строка сама
  // обновилась на "виден", если WebSocket в OBS уже был включён раньше (не нужно
  // руками жать "Проверить снова").
  btn.disabled = true;
  btn.textContent = 'Запускаю OBS…';
  // Только 4455 — эта кнопка вообще есть только у его шага, незачем дёргать
  // остальные три порта заодно.
  setTimeout(() => checkPort([PORTS_TO_CHECK.find((p) => p.port === 4455)]), 6000);
});

// video.play() у <video id="previewVideo"> отклоняется браузером с AbortError в
// паре штатных ситуаций, обе безвредные: mpegts.js дёргает video.pause() внутри
// detachMediaElement()/destroy() (см. closePreview ниже), если предыдущий play()
// ещё не осел ("interrupted by a call to pause()") — либо сам браузер ставит
// автовоспроизведение на паузу для экономии энергии, если вкладка/видео вне
// фокуса ("paused to save power"). Оба — штатное поведение <video>/Promise API,
// не баг библиотеки; гасим именно эти два задокументированных паттерна, а не
// unhandledrejection целиком — реальные ошибки по-прежнему всплывают как обычно.
window.addEventListener('unhandledrejection', (e) => {
  if (e.reason && e.reason.name === 'AbortError' && /interrupted (by a call to pause|because .* paused to save power)/.test(e.reason.message || '')) {
    e.preventDefault();
  }
});

// URL для прямого HTTP-FLV — тот же формат, что playFlv в /api/connections на
// сервере (server.js), просто посчитанный на клиенте для произвольного имени
// стрима (playFlv там привязан к конкретному currentStreamName/inviteStreamName,
// а openPreview() ниже может открыть ЛЮБОЙ стрим из /api/streams).
function directFlvUrl(name) {
  return `${window.location.protocol}//${window.location.hostname}:8080/live/${encodeURIComponent(name)}.flv`;
}

// mpegts.js — та же библиотека, которую использует bundled-плеер SRS (форк flv.js
// с рабочей поддержкой HEVC, которой у обычного flv.js нет — источник фразы
// "надёжнее flv.js" из старого комментария этого файла), поэтому просто грузим
// её и рисуем голый <video> сами, вместо чужой debug-страницы SRS целиком
// (вкладки/URL-поле/список рекомендуемых плееров). Файл — копия с самого SRS
// (см. public/vendor/README.md), но обслуживается со своего origin, а не
// cross-origin с SRS: тот отдаёт статику без Access-Control-Allow-Origin, и
// необработанные promise-исключения из cross-origin script браузер глушит для
// unhandledrejection на этой странице — их было не увидеть и не подавить
// (см. AbortError-фильтр ниже, он реально работает только для same-origin).
let mpegtsLoadPromise = null;
function loadMpegts() {
  if (window.mpegts) return Promise.resolve(window.mpegts);
  if (!mpegtsLoadPromise) {
    mpegtsLoadPromise = new Promise((resolve, reject) => {
      const script = document.createElement('script');
      script.src = 'vendor/mpegts-1.7.3.min.js';
      script.onload = () => (window.mpegts ? resolve(window.mpegts) : reject(new Error('mpegts.js загрузился, но window.mpegts не появился')));
      script.onerror = () => reject(new Error('не удалось загрузить плеер (vendor/mpegts-1.7.3.min.js)'));
      document.head.appendChild(script);
    });
  }
  return mpegtsLoadPromise;
}

const pageEl = document.querySelector('.page');
const previewPanelEl = document.getElementById('previewPanel');
let activePreviewPlayer = null;
let currentPreviewName = null;

function formatKbps(kbps) {
  if (kbps == null) return '—';
  return kbps >= 1000 ? `${(kbps / 1000).toFixed(1)} Мбит/с` : `${kbps} кбит/с`;
}

function formatBytes(bytes) {
  if (bytes == null) return '—';
  const mb = bytes / (1024 * 1024);
  return mb >= 1024 ? `${(mb / 1024).toFixed(2)} ГБ` : `${mb.toFixed(1)} МБ`;
}

// Обновляет строку трафика/видео под превью — дёргается из refreshStreams() (тот
// же опрос раз в 5с, что уже кормит сайдбар и "Активные стримы", отдельный поллинг
// заводить незачем) данными, которые SRS и так возвращает в /api/streams.
function updatePreviewStats() {
  const statsEl = document.getElementById('previewStats');
  if (!statsEl || !currentPreviewName) return;
  const s = latestStreams.find((x) => x.name === currentPreviewName);
  if (!s) {
    statsEl.textContent = 'Стрим сейчас не публикуется.';
    return;
  }
  statsEl.innerHTML = `
    ${escapeHtml(formatCodec(s.video, s.audio))}<br>
    ↓ ${escapeHtml(formatKbps(s.kbpsRecv30s))} приём · ↑ ${escapeHtml(formatKbps(s.kbpsSend30s))} отдача<br>
    Всего с начала стрима: ↓ ${escapeHtml(formatBytes(s.recvBytes))} · ↑ ${escapeHtml(formatBytes(s.sendBytes))}`;
}

async function openPreview(name) {
  if (activePreviewPlayer) {
    activePreviewPlayer.destroy();
    activePreviewPlayer = null;
  }
  currentPreviewName = name;
  previewPanelEl.innerHTML = `
    <div class="page-preview-head">
      <b>Предпросмотр: ${escapeHtml(name)}</b>
      <button type="button" class="page-preview-close">✕</button>
    </div>
    <video id="previewVideo" width="100%" autoplay muted controls playsinline></video>
    <div class="row-meta" id="previewStatus" style="margin-top:8px">Подключаюсь…</div>
    <div class="row-meta" id="previewStats" style="margin-top:8px"></div>`;
  previewPanelEl.querySelector('.page-preview-close').onclick = closePreview;
  previewPanelEl.hidden = false;
  pageEl.classList.add('has-preview');
  updatePreviewStats();

  const statusEl = document.getElementById('previewStatus');
  const video = document.getElementById('previewVideo');
  video.addEventListener('playing', () => { statusEl.hidden = true; }, { once: true });

  try {
    const mpegts = await loadMpegts();
    if (!document.getElementById('previewVideo')) return; // окно предпросмотра уже закрыли, пока грузился mpegts.js
    if (!mpegts.getFeatureList().mseLivePlayback) {
      statusEl.textContent = 'Браузер не поддерживает воспроизведение через Media Source Extensions.';
      return;
    }
    activePreviewPlayer = mpegts.createPlayer({
      type: 'flv', url: directFlvUrl(name),
      isLive: true, enableStashBuffer: false, liveSync: true,
    });
    activePreviewPlayer.on(mpegts.Events.ERROR, (type, detail) => {
      statusEl.hidden = false;
      statusEl.textContent = `Не удалось воспроизвести: ${type}${detail ? ' — ' + detail : ''}`;
    });
    activePreviewPlayer.attachMediaElement(video);
    activePreviewPlayer.load();
    activePreviewPlayer.play();
  } catch (e) {
    statusEl.hidden = false;
    statusEl.textContent = `Не удалось запустить предпросмотр: ${e.message}`;
  }
}

function closePreview() {
  if (activePreviewPlayer) {
    activePreviewPlayer.destroy();
    activePreviewPlayer = null;
  }
  currentPreviewName = null;
  previewPanelEl.hidden = true;
  previewPanelEl.innerHTML = ''; // выгружаем видео, а не просто прячем — не гонять поток фоном впустую
  pageEl.classList.remove('has-preview');
}

// --- Подключение (адреса для OBS / мобильного приложения) -----------------
// Карточка со списком всех адресов сразу (локальный/внешний, для каждого способа)
// раньше жила здесь отдельным блоком — теперь то же самое видно точечно, внутри
// чек-листа нужного сценария (ipStepHtml/otherAppFlowBody/inviteFlowBody ниже),
// поэтому refreshConnections() только обновляет latestHosts и просит перерисовать
// сценарии; предупреждение про неизвестный IP тоже уже встроено в noHostWarningHtml.
let latestHosts = [];
let currentStreamName = getOrCreateStreamName();

async function refreshConnections() {
  try {
    const res = await fetch(`/api/connections?name=${encodeURIComponent(currentStreamName)}`);
    const data = await res.json();
    latestHosts = data.hosts || [];
    renderFlowList();
  } catch (e) {
    latestHosts = [];
    renderFlowList();
  }
}

function setStreamName(name) {
  currentStreamName = name.trim() || 'livestream';
  localStorage.setItem(STREAM_NAME_KEY, currentStreamName);
  refreshConnections();
}

// --- «Пригласить друга»: отдельное имя стрима, чтобы не путать с основным ---
// (друг стримит параллельно с телефоном/другим приложением — если бы имя было
// общим, второй источник затирал бы первый в SRS).
const INVITE_STREAM_NAME_KEY = 'bondcast_invite_stream_name';
let inviteStreamName = localStorage.getItem(INVITE_STREAM_NAME_KEY) || randomStreamName();
localStorage.setItem(INVITE_STREAM_NAME_KEY, inviteStreamName);
let inviteHosts = [];

async function refreshInviteConnections() {
  try {
    const res = await fetch(`/api/connections?name=${encodeURIComponent(inviteStreamName)}`);
    const data = await res.json();
    inviteHosts = data.hosts || [];
  } catch (e) {
    inviteHosts = [];
  }
  renderFlowList();
}

function regenerateInvite() {
  inviteStreamName = randomStreamName();
  localStorage.setItem(INVITE_STREAM_NAME_KEY, inviteStreamName);
  refreshInviteConnections();
}

// --- Три сценария начала стрима (переключатель + одна панель) ----------------
// Одна активная ветка за раз — открытие другой сворачивает предыдущую, чтобы
// страница не превращалась в простыню из всех трёх сразу.
const FLOWS = [
  { id: 'bondcast', icon: '📱', title: 'Через Bondcast', hint: 'Приложение всё настроит по QR' },
  { id: 'other-app', icon: '⇄', title: 'Стороннее приложение', hint: 'Moblin, Larix — адрес вручную' },
  { id: 'invite', icon: '👥', title: 'Пригласить друга', hint: 'Экран/вебка через его OBS' },
];

let activeFlowId = null;
const flowSelectorEl = document.getElementById('flowSelector');
const flowPanelEl = document.getElementById('flowPanel');

// Плавно "доезжаем" контейнер до новой высоты вместо мгновенного скачка. Открытие
// сценария, смена результата проверки порта или появление статус-бара резко меняют
// высоту блока — без этого всё, что ниже на странице, прыгало бы вслед за ним.
// Пропускаем анимацию, если высота не изменилась (например, опрос раз в 5с ничего
// нового не принёс) — незачем городить transition ради no-op.
//
// animateHeightGen — счётчик "поколений" на элемент: быстрый повторный клик
// (открыть/закрыть один сценарий подряд, раньше чем первая анимация успела
// доиграть) запускал вторую animateHeight поверх первой, а её onDone от ПЕРВОГО
// вызова срабатывал позже и стирал el.style.height/transition, обрывая ВТОРУЮ,
// ещё не доигравшую анимацию — высота дёргалась. Каждый вызов помечает элемент
// своим номером; onDone применяет очистку, только если элемент всё ещё "его".
const animateHeightGen = new WeakMap();

function animateHeight(el, renderFn) {
  const gen = (animateHeightGen.get(el) || 0) + 1;
  animateHeightGen.set(el, gen);

  const fromHeight = el.getBoundingClientRect().height;
  renderFn();
  const toHeight = el.scrollHeight;
  if (Math.abs(fromHeight - toHeight) < 1) return;
  el.style.height = `${fromHeight}px`;
  el.style.overflow = 'hidden';
  el.getBoundingClientRect(); // форсируем reflow — иначе браузер схлопнет transition в один кадр
  el.style.transition = 'height .22s ease';
  el.style.height = `${toHeight}px`;
  const onDone = (e) => {
    if (e.target !== el || e.propertyName !== 'height') return;
    if (animateHeightGen.get(el) !== gen) return; // элемент уже подхватил более новый вызов — не наш выход
    el.style.height = '';
    el.style.overflow = '';
    el.style.transition = '';
    el.removeEventListener('transitionend', onDone);
  };
  el.addEventListener('transitionend', onDone);
}

function setActiveFlow(id) {
  activeFlowId = activeFlowId === id ? null : id;
  renderFlowList();
}

function isStreamLive(name) {
  return latestStreams.some((s) => s.name === name);
}

// Общий "жду / уже идёт" хвост для всех трёх веток — как только SRS реально
// увидел этот поток (не раньше — само по себе появление QR/URL ничего не
// доказывает), показываем готовую ссылку для просмотра в OBS.
function liveOrWaitingHtml(name, watchOneLinerUrl) {
  if (!isStreamLive(name)) {
    return `<div class="flow-waiting"><span class="spinner"></span> Жду начала стрима «${escapeHtml(name)}»…</div>`;
  }
  return `
    <div class="flow-live">✓ Стрим идёт!</div>
    ${addrRow('Для OBS (просмотр)', watchOneLinerUrl, 'Медиаисточник → Свойства → сними галочку «Локальный файл» → вставь ссылку в поле «Вход» (URL). Не для запуска — только для просмотра.')}
    <a href="#" data-action="watch-preview" data-name="${escapeHtml(name)}" class="watch-link">Смотреть →</a>`;
}

function noHostWarningHtml() {
  return `<div class="flow-warn">IP этой машины неизвестен панели — запусти ярлык «Запустить трансляцию» на рабочем столе.</div>`;
}

function noHostWarningItems() {
  return [{ key: 'warn', html: noHostWarningHtml() }];
}

// Шаг "IP статический" — не результат проверки порта, а просто напоминание какой
// адрес будет использован ниже в этой же ветке; зелёный, пока адрес вообще известен.
function ipStepHtml(host) {
  if (!host) {
    return `<div class="flow-step"><div class="flow-step-dot bad"></div><div><b>IP не определён</b><span class="flow-step-meta">Запусти ярлык «Запустить трансляцию» на рабочем столе</span></div></div>`;
  }
  return `<div class="flow-step"><div class="flow-step-dot dot-live"></div><div><b>IP статический</b><span class="flow-step-meta">${escapeHtml(host.mobileSrtlaHost)}</span></div></div>`;
}

// Прогрессивное раскрытие: шаг с gate:'required' блокирует показ всего, что идёт
// ПОСЛЕ него, пока сам не зазеленеет (state === 'reachable') — незачем сразу
// показывать финальный QR/адрес, если ещё не понятно, дойдёт ли вообще дело до
// него. gate:'optional' (напр. порт 4455 для управления OBS) никогда не блокирует —
// он не обязателен, ждать его смысла нет. Шаги без gate (IP, финальный) всегда
// проходят, сами они ничего не блокируют.
function gateSteps(items) {
  const visible = [];
  for (const item of items) {
    visible.push(item);
    if (item.gate === 'required' && item.state !== 'reachable') break;
  }
  return visible;
}

// Точечно обновляет шаги чек-листа по стабильному ключу: перерисовывается
// (innerHTML) только тот узел, чьё содержимое реально изменилось — раньше вся
// панель уходила в innerHTML одной строкой на любое изменение (даже опрос раз в
// 5с без реальных перемен), и уже решённые шаги пересоздавались вместе с новым,
// заново проигрывая анимацию появления. Соединительная линия (.conn-wrap) между
// шагами хранится внутри html самого шага (кроме первого) — порядок шагов здесь
// только растёт (gateSteps сверху добавляет, никогда не переставляет), так что
// у узла не бывает то есть, то нет коннектора.
function reconcileSteps(container, items) {
  const seen = new Set();
  let prevEl = null;
  items.forEach((item) => {
    seen.add(item.key);
    let unit = container.querySelector(`:scope > [data-step-key="${item.key}"]`);
    const isNew = !unit;
    if (isNew) {
      unit = document.createElement('div');
      unit.dataset.stepKey = item.key;
    }
    const connector = prevEl
      ? '<div class="conn-wrap"><div class="conn-line"></div><div class="conn-packet"></div></div>'
      : '';
    const html = connector + item.html;
    if (unit.dataset.stepHtml !== html) {
      unit.innerHTML = html;
      unit.dataset.stepHtml = html;
    }
    if (isNew) unit.classList.add('step-reveal');
    if (prevEl) prevEl.after(unit); else container.prepend(unit);
    prevEl = unit;
  });
  container.querySelectorAll(':scope > [data-step-key]').forEach((el) => {
    if (!seen.has(el.dataset.stepKey)) el.remove();
  });
}

function bondcastFlowBody() {
  const host = latestHosts[0];
  if (!host) return noHostWarningItems();
  const finalStep = `
    <div class="flow-step flow-step-final">
      <div class="flow-step-final-head"><div class="flow-step-dot dot-live-red"></div><b>Отсканируй QR в приложении</b></div>
      <div class="name-row">
        <input type="text" id="streamName" value="${escapeHtml(currentStreamName)}" placeholder="имя стрима" />
        <button type="button" class="dice-btn" id="regenName" title="Сгенерировать другое имя">🎲</button>
      </div>
      <img src="${host.qrDataUrl}" alt="QR для подключения" style="display:block;margin:4px auto;border-radius:8px;width:160px;height:160px;background:#fff" />
      <div class="row-meta" style="text-align:center">Настройки → значок камеры (там же QR) → «Стримить»</div>
      ${liveOrWaitingHtml(currentStreamName, host.playSrt)}
    </div>`;
  return gateSteps([
    { key: 'ip', html: ipStepHtml(host) },
    { key: 'port-5000', html: portStepHtml(PORTS_TO_CHECK[0]), gate: 'required', state: portCheckState(5000) },
    { key: 'port-4455', html: portStepHtml(PORTS_TO_CHECK[2]), gate: 'optional', state: portCheckState(4455) },
    { key: 'final', html: finalStep },
  ]);
}

// Тумблер Moblin/Larix — чисто визуальный выбор ярлыка приложения, оба показывают
// один и тот же реальный SRT-адрес и идентификатор стрима (нет отдельного протокола
// под каждое приложение — это упростило бы неверно).
let selectedThirdPartyApp = localStorage.getItem('bondcast_thirdparty_app') === 'larix' ? 'larix' : 'moblin';

function setThirdPartyApp(app) {
  selectedThirdPartyApp = app;
  localStorage.setItem('bondcast_thirdparty_app', app);
  renderFlowList();
}

function otherAppFlowBody() {
  const host = latestHosts[0];
  if (!host) return noHostWarningItems();
  const finalStep = `
    <div class="flow-step flow-step-final">
      <div class="flow-step-final-head"><div class="flow-step-dot dot-live-red"></div><b>Выбери приложение</b></div>
      <div class="app-seg seg">
        <button type="button" class="${selectedThirdPartyApp === 'moblin' ? 'active' : ''}" data-app="moblin">Moblin</button>
        <button type="button" class="${selectedThirdPartyApp === 'larix' ? 'active' : ''}" data-app="larix">Larix</button>
      </div>
      ${addrRow('URL', host.obsSrtUrl, 'Без бондинга — сразу в SRS, не в srtla-rec.')}
      ${addrRow('Идентификатор стрима', host.obsSrtStreamId, 'В Moblin — поле "Идентификатор стрима"; в других приложениях может называться Stream ID/Stream Key.')}
      ${liveOrWaitingHtml(currentStreamName, host.playSrt)}
    </div>`;
  return gateSteps([
    { key: 'ip', html: ipStepHtml(host) },
    { key: 'port-10080', html: portStepHtml(PORTS_TO_CHECK[1]), gate: 'required', state: portCheckState(10080) },
    { key: 'final', html: finalStep },
  ]);
}

function inviteFlowBody() {
  const host = inviteHosts.find((h) => h.label.includes('внешн'));
  if (!host) {
    return [{ key: 'warn', html: '<div class="flow-warn">Не нашли внешний IP — без него друг снаружи не достучится. Проверь интернет и нажми «Проверить снова» вверху страницы.</div>' }];
  }
  const finalStep = `
    <div class="flow-step flow-step-final">
      <div class="flow-step-final-head"><div class="flow-step-dot dot-live-red"></div><b>Данные для OBS друга</b></div>
      ${addrRow('Сервер', host.obsRtmpServer, 'В OBS: поле "Сервер".')}
      <div class="name-row">
        <input type="text" id="inviteName" value="${escapeHtml(inviteStreamName)}" readonly style="font-family:'SF Mono',Consolas,monospace" />
        <button type="button" class="dice-btn" id="regenInvite" title="Сгенерировать другое имя">🎲</button>
      </div>
      <div class="row-meta">↑ Ключ трансляции (то же поле в OBS)</div>
      ${liveOrWaitingHtml(inviteStreamName, host.playSrt)}
    </div>`;
  return gateSteps([
    { key: 'ip', html: ipStepHtml(host) },
    { key: 'port-1935', html: portStepHtml(PORTS_TO_CHECK[3]), gate: 'optional', state: portCheckState(1935) },
    { key: 'final', html: finalStep },
  ]);
}

function renderFlowBody(id) {
  if (id === 'bondcast') return bondcastFlowBody();
  if (id === 'other-app') return otherAppFlowBody();
  if (id === 'invite') return inviteFlowBody();
  return [];
}

// Переключатель сам не меняет высоту (все три варианта — фиксированного размера),
// поэтому рендерится напрямую, без animateHeight — только цвет/рамка активной
// плашки, что уже плавно меняется через CSS transition на .flow-pill.
//
// Тот же кэш-по-строке, что и в renderFlowPanelContent — иначе ringPulse на
// открытой плашке дёргался бы (перезапуск infinite-анимации на новом узле) на
// каждый опрос раз в 5с, даже когда чипы портов не изменились.
let lastFlowSelectorHtml = null;

function renderFlowSelector() {
  const html = FLOWS.map((f) => {
    const status = flowPortStatus(f.id);
    const chip = status === 'pending' ? '' : `<span class="flow-pill-chip ${status}"></span>`;
    return `
    <button type="button" class="flow-pill ${activeFlowId === f.id ? 'open' : ''}" data-flow="${f.id}">
      <span class="flow-pill-badge">${f.icon}${chip}</span>
      <span class="flow-pill-title"><b>${escapeHtml(f.title)}</b><span class="flow-hint">${escapeHtml(f.hint)}</span></span>
    </button>`;
  }).join('');
  if (html === lastFlowSelectorHtml) return;
  lastFlowSelectorHtml = html;

  flowSelectorEl.innerHTML = html;
  flowSelectorEl.querySelectorAll('.flow-pill').forEach((btn) => {
    btn.onclick = () => setActiveFlow(btn.dataset.flow);
  });
}

// Единственная общая панель контента — её высоту анимирует animateHeight() в
// renderFlowList() ниже. Вызывается и на опрос раз в 5с (refreshStreams/checkPort),
// и на каждую напечатанную букву в поле имени — но теперь дальше идёт не единая
// перезапись innerHTML, а reconcileSteps() по ключам (см. выше): трогаем DOM только
// у тех шагов, что реально изменились. lastRenderedFlowId — когда сценарий
// сменился (или закрылся) целиком, точечная реконсиляция между РАЗНЫМИ сценариями
// не имеет смысла, тут по-прежнему просто пересоздаём контейнер с нуля.
let lastRenderedFlowId;

function renderFlowPanelContent() {
  if (activeFlowId !== lastRenderedFlowId) {
    lastRenderedFlowId = activeFlowId;
    flowPanelEl.innerHTML = activeFlowId ? '<div class="flow-panel-content"></div>' : '';
  }
  if (!activeFlowId) return;
  const contentEl = flowPanelEl.querySelector('.flow-panel-content');

  // Без сохранения фокуса поле #streamName могло бы пересоздаться под курсором
  // (если содержимое финального шага реально изменилось) и печатать стало бы
  // невозможно — фокус слетал бы на каждый символ.
  const active = document.activeElement;
  const focusedId = active && active.id;
  const selStart = active && 'selectionStart' in active ? active.selectionStart : null;
  const selEnd = active && 'selectionEnd' in active ? active.selectionEnd : null;

  reconcileSteps(contentEl, renderFlowBody(activeFlowId));

  contentEl.querySelectorAll('[data-app]').forEach((btn) => {
    btn.onclick = () => setThirdPartyApp(btn.dataset.app);
  });
  bindCopyButtons(contentEl);

  // Поля/кнопки могли пересоздаться (если их шаг реально изменился) — навешиваем
  // обработчики каждый раз, а не один раз при загрузке.
  const nameInput = document.getElementById('streamName');
  if (nameInput) nameInput.oninput = () => setStreamName(nameInput.value);
  const regenBtn = document.getElementById('regenName');
  if (regenBtn) regenBtn.onclick = () => setStreamName(regenerateStreamName());
  const regenInviteBtn = document.getElementById('regenInvite');
  if (regenInviteBtn) regenInviteBtn.onclick = regenerateInvite;

  if (focusedId && (!active || !active.isConnected)) {
    const toFocus = document.getElementById(focusedId);
    if (toFocus) {
      toFocus.focus();
      if (selStart !== null && toFocus.setSelectionRange) toFocus.setSelectionRange(selStart, selEnd);
    }
  }
}

function renderFlowList() {
  renderFlowSelector();
  animateHeight(flowPanelEl, renderFlowPanelContent);
}

renderFlowList();
refreshConnections();
refreshInviteConnections();
checkPort();

// --- Сайдбар "Стримы на сервере" (общий для обеих вкладок) ------------------
// Простой обзор "кто сейчас на канале" — не путать с #streamCards на вкладке
// "Функции" (там — управление субтитрами конкретного стрима, здесь — просто
// кто есть и куда стримить, если это OBS друга).
const serverStreamsEl = document.getElementById('serverStreams');

function srtPlayUrl(name) {
  const host = latestHosts[0];
  if (!host) return '';
  return `srt://${host.mobileSrtlaHost}:10080?streamid=#!::r=live/${name},m=request`;
}

function formatLiveSince(liveSinceMs) {
  if (!liveSinceMs) return '';
  const secs = Math.max(0, Math.floor((Date.now() - liveSinceMs) / 1000));
  const h = Math.floor(secs / 3600);
  const m = Math.floor((secs % 3600) / 60);
  return h > 0 ? `${h} ч ${m} мин` : `${m} мин`;
}

// Панель знает только два "своих" имени стрима (currentStreamName — общий для
// сценариев "Через Bondcast"/"Стороннее приложение", inviteStreamName — для
// "Пригласить друга") — что угодно ещё, реально пришедшее в SRS, подписываем нейтрально.
function labelForServerStream(name) {
  if (name === currentStreamName) return 'через Bondcast';
  if (name === inviteStreamName) return 'друг · через OBS';
  return 'стрим';
}

function renderServerStreams(streams) {
  const slots = streams
    .map(
      (s) => `
    <div class="server-stream-slot">
      <div class="server-stream-slot-head">
        <div class="server-stream-slot-icon">🎙</div>
        <div class="server-stream-slot-info">
          <b>${escapeHtml(s.name)}</b>
          <span class="meta">${escapeHtml(labelForServerStream(s.name))}${s.liveSinceMs ? ' · ' + escapeHtml(formatLiveSince(s.liveSinceMs)) : ''}</span>
        </div>
        <div class="live-badge"><span class="dot dot-live"></span>Live</div>
      </div>
      <div class="server-stream-slot-actions">
        <button type="button" class="copy-addr" data-value="${escapeHtml(srtPlayUrl(s.name))}">Копировать для OBS</button>
        <button type="button" class="primary server-watch-stream" data-name="${escapeHtml(s.name)}">Просмотр</button>
      </div>
    </div>`,
    )
    .join('');

  // Фиксированный "свободный слот" в конце независимо от длины списка выше —
  // не второй элемент ровно на 2, а всегда последняя карточка-приглашение.
  const emptySlot = `
    <div class="server-stream-empty">
      <div class="icon">👥</div>
      <b>Свободный слот</b>
      <div class="hint">Пригласи друга — он появится здесь, когда подключит OBS</div>
    </div>`;

  serverStreamsEl.innerHTML = slots + emptySlot;
  bindCopyButtons(serverStreamsEl);
  serverStreamsEl.querySelectorAll('.server-watch-stream').forEach((btn) => {
    btn.onclick = () => openPreview(btn.dataset.name);
  });
}

// --- "Субтитры на стриме" — тумблер сворачивает/разворачивает существующий
// блок настроек и управления; сама подписка на субтитры остаётся отдельной
// явной кнопкой внутри (см. captionsButtonHtml ниже) — тумблер только прячет
// лишнее, не меняет реальное подключённое состояние.
const SUBS_EXPANDED_KEY = 'bondcast_subs_expanded';
let subsExpanded = localStorage.getItem(SUBS_EXPANDED_KEY) !== '0';
const subsSwitchEl = document.getElementById('subsSwitch');
const subsBodyEl = document.getElementById('subsBody');

function applySubsSwitch() {
  subsSwitchEl.classList.toggle('on', subsExpanded);
  subsBodyEl.hidden = !subsExpanded;
}
subsSwitchEl.onclick = () => {
  subsExpanded = !subsExpanded;
  localStorage.setItem(SUBS_EXPANDED_KEY, subsExpanded ? '1' : '0');
  applySubsSwitch();
};
applySubsSwitch();

// --- "Умный переключатель сцен OBS" — реальный бэкенд (server.js: OBS
// websocket-клиент + монитор сигнала SRS), не просто UI-заглушка. Следит за
// тем же стримом, что и сценарий "Через Bondcast" (currentStreamName) —
// отдельного выбора стрима в этой карточке нет, как и в макете.
let sceneSwitcherState = { enabled: false, watchStreamName: null, fallbackScene: null, delaySec: 3, state: 'idle', lastError: null };
let availableObsScenes = [];
let obsScenesError = null;
const sceneSwitchEl = document.getElementById('sceneSwitch');
const sceneSwitcherBodyEl = document.getElementById('sceneSwitcherBody');

function applySceneSwitch() {
  sceneSwitchEl.classList.toggle('on', sceneSwitcherState.enabled);
  sceneSwitcherBodyEl.hidden = !sceneSwitcherState.enabled;
}

async function refreshObsScenes() {
  try {
    const res = await fetch('/api/obs/scenes');
    const data = await res.json();
    if (!res.ok) throw new Error(data.error || 'unknown error');
    availableObsScenes = data.scenes || [];
    obsScenesError = null;
  } catch (e) {
    availableObsScenes = [];
    obsScenesError = e.message;
  }
  renderSceneSwitcherBody();
}

async function refreshSceneSwitcherStatus() {
  try {
    const res = await fetch('/api/obs/scene-switcher');
    sceneSwitcherState = await res.json();
  } catch (e) {
    // тихо — не обновляем состояние до следующего опроса
  }
  applySceneSwitch();
  renderSceneSwitcherBody();
}

async function postSceneSwitcher(body) {
  try {
    const res = await fetch('/api/obs/scene-switcher', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
    const data = await res.json();
    if (!res.ok) throw new Error(data.error || 'unknown error');
    sceneSwitcherState = data;
  } catch (e) {
    alert(`Переключатель сцен: ${e.message}`);
  }
  applySceneSwitch();
  renderSceneSwitcherBody();
}

sceneSwitchEl.onclick = () => {
  if (sceneSwitcherState.enabled) {
    postSceneSwitcher({ enabled: false });
    return;
  }
  if (!currentStreamName) return;
  const fallbackScene = sceneSwitcherState.fallbackScene || availableObsScenes[0];
  if (!fallbackScene) {
    alert('Нет доступных сцен — проверь, что OBS запущен и порт 4455 открыт (см. подсказку в сценарии «Через Bondcast»).');
    return;
  }
  postSceneSwitcher({ enabled: true, watchStreamName: currentStreamName, fallbackScene, delaySec: sceneSwitcherState.delaySec });
};

function renderSceneSwitcherBody() {
  if (obsScenesError) {
    sceneSwitcherBodyEl.innerHTML = `
      <div class="row-meta">Не удалось получить список сцен из OBS: ${escapeHtml(obsScenesError)}. Проверь, что OBS запущен и в нём включён WebSocket-сервер (порт 4455) — подсказка есть в сценарии «Через Bondcast» на первой вкладке.</div>
      <button type="button" id="retryObsScenes">Проверить снова</button>`;
    const retryBtn = document.getElementById('retryObsScenes');
    if (retryBtn) retryBtn.onclick = refreshObsScenes;
    return;
  }
  const stateNote = { watching: ' · слежу за сигналом', switched: ' · сейчас показываю резервную сцену' }[sceneSwitcherState.state] || '';
  sceneSwitcherBodyEl.innerHTML = `
    <div class="row-meta">Требуется порт 4455 (WebSocket OBS) — включён в сценарии «Через Bondcast»${escapeHtml(stateNote)}</div>
    <div>
      <label class="field-label">Резервная сцена при обрыве</label>
      <select id="fallbackSceneSelect" style="width:100%;box-sizing:border-box;padding:11px 14px;background:var(--input-bg);border:1px solid var(--divider);border-radius:8px;color:var(--text);font-size:13px">
        ${availableObsScenes.map((name) => `<option value="${escapeHtml(name)}" ${name === sceneSwitcherState.fallbackScene ? 'selected' : ''}>${escapeHtml(name)}</option>`).join('')}
      </select>
    </div>
    <div class="range-field">
      <div class="range-head"><span>Переключать через</span><output id="switchDelayOut">${sceneSwitcherState.delaySec} сек</output></div>
      <input type="range" id="switchDelay" min="0" max="30" value="${sceneSwitcherState.delaySec}" />
      <div class="row-meta" style="margin-top:6px">После потери сигнала. Как только эфир вернётся — вернём рабочую сцену автоматически</div>
    </div>
    ${sceneSwitcherState.lastError ? `<div class="flow-warn">${escapeHtml(sceneSwitcherState.lastError)}</div>` : ''}
  `;
  const select = document.getElementById('fallbackSceneSelect');
  if (select) {
    select.onchange = () => postSceneSwitcher({ enabled: true, watchStreamName: currentStreamName, fallbackScene: select.value, delaySec: sceneSwitcherState.delaySec });
  }
  const delayInput = document.getElementById('switchDelay');
  const delayOut = document.getElementById('switchDelayOut');
  if (delayInput) {
    delayInput.oninput = () => { delayOut.textContent = `${delayInput.value} сек`; };
    delayInput.onchange = () => postSceneSwitcher({ enabled: true, watchStreamName: currentStreamName, fallbackScene: sceneSwitcherState.fallbackScene, delaySec: Number(delayInput.value) });
  }
}

applySceneSwitch();
refreshObsScenes();
refreshSceneSwitcherStatus();
setInterval(refreshSceneSwitcherStatus, 5000);

// --- Лог субтитров (сборка образа / запись голоса) --------------------------
const capLogCardEl = document.getElementById('capLogCard');
const capLogEl = document.getElementById('capLog');
const MAX_CAP_LOG_LINES = 500;
let capLogLines = [];
let capSource = null;

function appendCapLog(line) {
  const atBottom = capLogEl.scrollHeight - capLogEl.scrollTop - capLogEl.clientHeight < 30;
  capLogLines.push(line);
  if (capLogLines.length > MAX_CAP_LOG_LINES) capLogLines.splice(0, capLogLines.length - MAX_CAP_LOG_LINES);
  capLogEl.textContent = capLogLines.join('\n') + '\n';
  if (atBottom) capLogEl.scrollTop = capLogEl.scrollHeight;
}

function openCapLogStream(url) {
  if (capSource) capSource.close();
  capLogLines = [];
  capLogEl.textContent = '';
  capLogCardEl.hidden = false;
  capSource = new EventSource(url);
  capSource.onmessage = (e) => appendCapLog(e.data);
  return capSource;
}

// --- Активные стримы + субтитры (asr-obs) ----------------------------------
const streamCardsEl = document.getElementById('streamCards');
let captionsState = {
  connected: false, streamName: null, overlayUrl: null, imageExists: false, buildStatus: 'idle', buildError: null,
  hostName: null, hasVoiceReference: false, enrollStatus: 'idle', enrollError: null, ready: false,
  asrModel: null, speakerThreshold: null,
};

function recognitionSettingsChanged() {
  if (captionsState.asrModel !== recognitionInputs.asrModel()) return true;
  const running = Number(captionsState.speakerThreshold);
  const selected = Number(recognitionInputs.speakerThreshold.value);
  return !Number.isFinite(running) || Math.abs(running - selected) > 0.005;
}
let capBusy = false;

const ENROLL_DURATION_SEC = 15;
const HOST_NAME_KEY = 'bondcast_host_name';
const hostNameInputEl = document.getElementById('hostNameInput');
hostNameInputEl.value = localStorage.getItem(HOST_NAME_KEY) || '';
hostNameInputEl.addEventListener('input', () => {
  localStorage.setItem(HOST_NAME_KEY, hostNameInputEl.value.trim());
});

// --- Настройки распознавания (asr_model, speaker_threshold) -----------------
const RECOGNITION_DEFAULTS = { asrModel: 'v3_e2e_rnnt', speakerThreshold: 0.25 };
const qualityFastBtn = document.getElementById('qualityFastBtn');
const qualityPreciseBtn = document.getElementById('qualityPreciseBtn');
const recognitionInputs = {
  asrModel: () => (qualityPreciseBtn.classList.contains('active') ? qualityPreciseBtn.dataset.model : qualityFastBtn.dataset.model),
  speakerThreshold: document.getElementById('speakerThreshold'),
};
const speakerThresholdOutEl = document.getElementById('speakerThresholdOut');

function setAsrModel(model) {
  const isPrecise = model === qualityPreciseBtn.dataset.model;
  qualityPreciseBtn.classList.toggle('active', isPrecise);
  qualityFastBtn.classList.toggle('active', !isPrecise);
  localStorage.setItem('bondcast_asrModel', isPrecise ? qualityPreciseBtn.dataset.model : qualityFastBtn.dataset.model);
}
setAsrModel(localStorage.getItem('bondcast_asrModel') || RECOGNITION_DEFAULTS.asrModel);
qualityFastBtn.addEventListener('click', () => { setAsrModel(qualityFastBtn.dataset.model); renderStreamCards(latestStreams); });
qualityPreciseBtn.addEventListener('click', () => { setAsrModel(qualityPreciseBtn.dataset.model); renderStreamCards(latestStreams); });

{
  const stored = localStorage.getItem('bondcast_speakerThreshold');
  recognitionInputs.speakerThreshold.value = stored !== null ? stored : RECOGNITION_DEFAULTS.speakerThreshold;
}
speakerThresholdOutEl.textContent = Number(recognitionInputs.speakerThreshold.value).toFixed(2);
recognitionInputs.speakerThreshold.addEventListener('input', () => {
  localStorage.setItem('bondcast_speakerThreshold', recognitionInputs.speakerThreshold.value);
  speakerThresholdOutEl.textContent = Number(recognitionInputs.speakerThreshold.value).toFixed(2);
  renderStreamCards(latestStreams);
});

// --- Оформление оверлея ------------------------------------------------------
const OVERLAY_DEFAULTS = { size: 34, lines: 3, hostColor: '#ff3b30', guestColor: '#34c759', bgColor: '#000000', bgOpacity: 60 };
const overlayInputs = {
  size: document.getElementById('overlaySize'),
  lines: document.getElementById('overlayLines'),
  hostColor: document.getElementById('overlayHostColor'),
  guestColor: document.getElementById('overlayGuestColor'),
  bgColor: document.getElementById('overlayBgColor'),
  bgOpacity: document.getElementById('overlayBgOpacity'),
};
const overlayOutputs = {
  size: document.getElementById('overlaySizeOut'),
  lines: document.getElementById('overlayLinesOut'),
  bgOpacity: document.getElementById('overlayBgOpacityOut'),
};

function hexToRgba(hex, alpha) {
  const m = /^#?([0-9a-f]{2})([0-9a-f]{2})([0-9a-f]{2})$/i.exec(hex || '');
  if (!m) return `rgba(0,0,0,${alpha})`;
  const [r, g, b] = [m[1], m[2], m[3]].map((h) => parseInt(h, 16));
  return `rgba(${r},${g},${b},${alpha})`;
}

function updateOverlayPreview() {
  const preview = document.getElementById('overlayPreview');
  preview.style.setProperty('--prev-size', `${overlayInputs.size.value}px`);
  preview.style.setProperty('--prev-host', overlayInputs.hostColor.value);
  preview.style.setProperty('--prev-guest', overlayInputs.guestColor.value);
  preview.style.setProperty('--prev-bg', hexToRgba(overlayInputs.bgColor.value, Number(overlayInputs.bgOpacity.value) / 100));
  if (overlayOutputs.size) overlayOutputs.size.textContent = overlayInputs.size.value;
  if (overlayOutputs.lines) overlayOutputs.lines.textContent = overlayInputs.lines.value;
  if (overlayOutputs.bgOpacity) overlayOutputs.bgOpacity.textContent = `${overlayInputs.bgOpacity.value}%`;
}

Object.keys(overlayInputs).forEach((key) => {
  const el = overlayInputs[key];
  const stored = localStorage.getItem(`bondcast_overlay_${key}`);
  el.value = stored !== null ? stored : OVERLAY_DEFAULTS[key];
  el.addEventListener('input', () => {
    localStorage.setItem(`bondcast_overlay_${key}`, el.value);
    updateOverlayPreview();
    renderStreamCards(latestStreams);
  });
});
updateOverlayPreview();

function buildOverlayUrl(baseUrl) {
  try {
    const u = new URL(baseUrl);
    u.searchParams.set('size', overlayInputs.size.value || OVERLAY_DEFAULTS.size);
    u.searchParams.set('lines', overlayInputs.lines.value || OVERLAY_DEFAULTS.lines);
    u.searchParams.set('hostColor', overlayInputs.hostColor.value || OVERLAY_DEFAULTS.hostColor);
    u.searchParams.set('guestColor', overlayInputs.guestColor.value || OVERLAY_DEFAULTS.guestColor);
    u.searchParams.set('bg', hexToRgba(overlayInputs.bgColor.value, (Number(overlayInputs.bgOpacity.value) || OVERLAY_DEFAULTS.bgOpacity) / 100));
    return u.toString();
  } catch (e) {
    return baseUrl;
  }
}

function formatCodec(video, audio) {
  const parts = [];
  if (video) parts.push(`${video.codec} ${video.width}x${video.height}`);
  if (audio) parts.push(`${audio.codec} ${audio.sample_rate}Hz`);
  return parts.join(' · ') || 'кодеки ещё не определены';
}

function captionsButtonHtml(streamName) {
  if (captionsState.buildStatus === 'building') return '<button disabled>Собираю образ…</button>';
  if (!captionsState.imageExists) return '<button class="cap-build">Собрать образ для субтитров</button>';
  if (captionsState.connected && captionsState.streamName === streamName) {
    let applyBtn;
    if (!captionsState.ready) {
      applyBtn = '<button disabled title="Дождись загрузки модели">Применить настройки</button>';
    } else if (!recognitionSettingsChanged()) {
      applyBtn = '<button disabled title="Настройки не менялись с момента подключения">Применить настройки</button>';
    } else {
      applyBtn = `<button class="cap-connect" data-name="${escapeHtml(streamName)}" title="Переподключить с текущими настройками распознавания">Применить настройки</button>`;
    }
    return applyBtn + '<button class="cap-disconnect primary">Субтитры: отключить</button>';
  }
  return `<button class="cap-connect primary" data-name="${escapeHtml(streamName)}">Субтитры: подключить</button>`;
}

function captionsLoadingHtml(streamName) {
  if (!(captionsState.connected && captionsState.streamName === streamName && !captionsState.ready)) return '';
  return `<div class="row"><span class="row-label"><span class="row-meta">⏳ Загружается модель распознавания — при первом запуске (без кэша) это ~1ГБ и может занять минуту-две. Прогресс — во «Диагностика → логи субтитров».</span></span></div>`;
}

function enrollButtonHtml(streamName) {
  if (!captionsState.imageExists) {
    return '<button disabled title="Сначала собери образ — кнопка «Собрать образ для субтитров» справа">🎙 Записать голос ведущего</button>';
  }
  if (captionsState.enrollStatus === 'running') return '<button disabled>Идёт запись…</button>';
  const label = captionsState.hasVoiceReference ? 'Перезаписать голос' : 'Записать голос ведущего';
  return `<button class="cap-enroll" data-name="${escapeHtml(streamName)}" title="${ENROLL_DURATION_SEC} секунд — говорить должен только ведущий">🎙 ${label}</button>`;
}

function hostStatusHtml() {
  if (captionsState.enrollStatus === 'running') {
    const elapsed = enrollStartedAt ? (Date.now() - enrollStartedAt) / 1000 : 0;
    const capturing = elapsed < ENROLL_DURATION_SEC;
    const label = capturing
      ? `🎙 Говори без пауз — ещё ${Math.max(0, Math.ceil(ENROLL_DURATION_SEC - elapsed))}с`
      : '⏳ Считаю эмбеддинг голоса…';
    return `<div class="row"><span class="row-label">
      <span class="row-meta">${label}</span>
      <progress value="${Math.min(elapsed, ENROLL_DURATION_SEC).toFixed(1)}" max="${ENROLL_DURATION_SEC}" style="width:100%; margin-top:6px"></progress>
    </span></div>`;
  }
  if (captionsState.enrollStatus === 'error') {
    return `<div class="row"><span class="row-label"><span class="row-meta">Запись не удалась: ${escapeHtml(captionsState.enrollError || '')}</span></span></div>`;
  }
  if (captionsState.hasVoiceReference) {
    return `<div class="row"><span class="row-label"><span class="row-meta">Эталон голоса записан${captionsState.hostName ? ': ' + escapeHtml(captionsState.hostName) : ''} — реплики других помечаются как «Кто-то»</span></span></div>`;
  }
  return '';
}

function renderStreamCards(streams) {
  if (!streams.length) {
    streamCardsEl.innerHTML = '<div class="row"><span class="row-label"><span class="row-meta">пока никто не стримит</span></span></div>';
    return;
  }
  streamCardsEl.innerHTML = hostStatusHtml() + streams
    .map(
      (s) => `
    <div class="row">
      <div class="row-label">
        <b>${escapeHtml(s.name)}</b>
        <span class="row-meta">${escapeHtml(formatCodec(s.video, s.audio))}</span>
      </div>
      <div class="row-actions">
        <button class="watch-stream" data-name="${escapeHtml(s.name)}">Смотреть</button>
        ${enrollButtonHtml(s.name)}
        ${captionsButtonHtml(s.name)}
      </div>
    </div>
    ${captionsLoadingHtml(s.name)}
    ${
      captionsState.connected && captionsState.streamName === s.name
        ? addrRow('Оверлей для OBS', buildOverlayUrl(captionsState.overlayUrl), 'Добавь как Browser Source в OBS — субтитры поверх видео. Оформление ниже.')
        : ''
    }`,
    )
    .join('');

  streamCardsEl.querySelectorAll('.watch-stream').forEach((btn) => {
    btn.onclick = () => openPreview(btn.dataset.name);
  });
  streamCardsEl.querySelectorAll('.cap-build').forEach((btn) => { btn.onclick = buildCaptions; });
  streamCardsEl.querySelectorAll('.cap-connect').forEach((btn) => { btn.onclick = () => connectCaptions(btn.dataset.name); });
  streamCardsEl.querySelectorAll('.cap-disconnect').forEach((btn) => { btn.onclick = disconnectCaptions; });
  streamCardsEl.querySelectorAll('.cap-enroll').forEach((btn) => { btn.onclick = () => enrollHost(btn.dataset.name); });
  bindCopyButtons(streamCardsEl);
}

let latestStreams = [];

async function refreshStreams() {
  try {
    const res = await fetch('/api/streams');
    const data = await res.json();
    latestStreams = data.streams || [];
    renderStreamCards(latestStreams);
    renderServerStreams(latestStreams);
    updatePreviewStats();
    // Раскрытая ветка сценария (если есть) ждёт именно факта "SRS увидел поток" —
    // перерисовываем её на каждый опрос, чтобы "жду начала стрима" само сменилось
    // на "Стрим идёт!" без ручного обновления страницы.
    if (activeFlowId) renderFlowList();
  } catch (e) {
    streamCardsEl.innerHTML = `<div class="row"><span class="row-label"><span class="row-meta">не удалось получить список стримов: ${escapeHtml(e.message)}</span></span></div>`;
  }
}

async function refreshCaptionsStatus() {
  try {
    const res = await fetch('/api/captions/status');
    captionsState = await res.json();
  } catch (e) {
    // тихо — просто не обновляем состояние субтитров до следующего опроса
  }
}

async function pollStreamsAndCaptions() {
  await refreshCaptionsStatus();
  await refreshStreams();
}

async function buildCaptions() {
  if (capBusy) return;
  capBusy = true;
  try {
    const res = await fetch('/api/captions/build', { method: 'POST' });
    const data = await res.json();
    if (!res.ok) throw new Error(data.error || 'unknown error');
    openBuildLogStream();
  } catch (e) {
    alert(`Сборка образа: ${e.message}`);
  } finally {
    capBusy = false;
    pollStreamsAndCaptions();
  }
}

async function connectCaptions(name) {
  if (capBusy) return;
  capBusy = true;
  try {
    const res = await fetch('/api/captions/connect', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        name,
        asrModel: recognitionInputs.asrModel(),
        speakerThreshold: recognitionInputs.speakerThreshold.value,
      }),
    });
    const data = await res.json();
    if (!res.ok) throw new Error(data.error || 'unknown error');
  } catch (e) {
    alert(`Субтитры: ${e.message}`);
  } finally {
    capBusy = false;
    pollStreamsAndCaptions();
  }
}

async function disconnectCaptions() {
  if (capBusy) return;
  capBusy = true;
  try {
    const res = await fetch('/api/captions/disconnect', { method: 'POST' });
    const data = await res.json();
    if (!res.ok) throw new Error(data.error || 'unknown error');
  } catch (e) {
    alert(`Субтитры: ${e.message}`);
  } finally {
    capBusy = false;
    pollStreamsAndCaptions();
  }
}

function openBuildLogStream() {
  const source = openCapLogStream('/api/captions/build/logs');
  source.addEventListener('done', () => {
    source.close();
    pollStreamsAndCaptions();
  });
  source.onerror = () => {
    appendCapLog('[поток логов сборки прерван]');
    source.close();
  };
}

let enrollStartedAt = null;
let enrollTicker = null;

function stopEnrollTicker() {
  if (enrollTicker) {
    clearInterval(enrollTicker);
    enrollTicker = null;
  }
}

async function enrollHost(streamName) {
  const hostName = hostNameInputEl.value.trim();
  if (!hostName) {
    hostNameInputEl.focus();
    alert('Сначала впиши имя ведущего.');
    return;
  }
  if (capBusy) return;
  capBusy = true;
  try {
    const res = await fetch('/api/captions/enroll', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ streamName, hostName }),
    });
    const data = await res.json();
    if (!res.ok) throw new Error(data.error || 'unknown error');
    enrollStartedAt = Date.now();
    stopEnrollTicker();
    enrollTicker = setInterval(() => {
      renderStreamCards(latestStreams);
      if (captionsState.enrollStatus !== 'running') stopEnrollTicker();
    }, 250);
    openEnrollLogStream();
  } catch (e) {
    alert(`Запись голоса: ${e.message}`);
  } finally {
    capBusy = false;
    pollStreamsAndCaptions();
  }
}

function openEnrollLogStream() {
  const source = openCapLogStream('/api/containers/asr-enroll/logs');
  source.onerror = () => {
    appendCapLog('[поток логов записи прерван]');
    source.close();
  };
}

pollStreamsAndCaptions();
setInterval(pollStreamsAndCaptions, 5000);

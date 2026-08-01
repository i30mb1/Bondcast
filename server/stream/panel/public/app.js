// --- Утилиты ------------------------------------------------------------
function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

function tooltip(text) {
  return `<span class="info" tabindex="0">?<span class="bubble">${escapeHtml(text)}</span></span>`;
}

// Сдвиг фазы для повторяющихся анимаций (dot-live/dot-live-red, conn-packet) —
// несколько штук на экране разом (список активных стримов, чек-лист портов,
// пакет на соединительных линиях между шагами) без этого идут в такт и выглядят
// как одна и та же анимация под копирку. Детерминированно от seed, не
// Math.random() — reconcileSteps сравнивает html строкой и перерисовывает узел
// только когда она реально изменилась, случайное значение на каждый рендер
// заставило бы шаг мигать на каждый опрос. durationSec — длительность анимации
// конкретного элемента (dotPulse/dotPulseRed — 1.8s, packetTravel — 1.6s), сдвиг
// считается в её пределах.
function pulseDelay(seed, durationSec = 1.8) {
  let hash = 0;
  for (let i = 0; i < seed.length; i++) hash = (hash * 31 + seed.charCodeAt(i)) >>> 0;
  const offset = (hash % 100) / 100 * durationSec;
  return `animation-delay:-${offset.toFixed(2)}s`;
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
];

// Результаты последней проверки хранятся здесь (не только рендерятся) — чек-лист
// шагов внутри каждого раскрытого сценария (см. «Три сценария начала стрима» ниже)
// читает их отсюда вместо отдельной общей карточки со списком портов.
let latestPortResults = [];

// Порты, по которым сейчас идёт (пере)проверка. Раньше renderPortChecking просто
// выкидывал прошлый результат порта из latestPortResults — и на время запроса к
// check-host.net шаг проваливался в спиннер, а его gate (см. gateSteps) закрывался:
// portCheckState становился 'pending', и все шаги НИЖЕ по чек-листу пропадали, а
// потом заново «выезжали» (step-reveal). Перепроверка одного порта дёргала
// пол-панели. Теперь прошлый результат остаётся на месте, порт лишь помечается
// «проверяю» — шаг показывает маленький спиннер, но состояние (открыт/закрыт) и
// gate держатся на прошлом результате, пока не придёт новый, поэтому соседние шаги
// стоят на месте.
const recheckingPorts = new Set();

function renderPortChecking(ports) {
  ports.forEach((p) => recheckingPorts.add(p.port));
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
  const rechecking = recheckingPorts.has(port);
  if (!found) {
    return `
      <div class="flow-step">
        <span class="spinner"></span>
        <div><b>Порт ${port}/${protoLabel}</b><span class="flow-step-meta">Проверяю снаружи — стучусь через check-host.net…</span></div>
      </div>`;
  }
  const { data } = found;
  const recheckLink = rechecking
    ? '<span class="row-meta"><span class="spinner"></span> Проверяю…</span>'
    : `<a href="#" class="recheck-ports" data-port="${port}">Проверить снова</a>`;
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
    const recheckingHint = rechecking ? ' <span class="spinner"></span>' : '';
    return `
      <div class="flow-step">
        <div class="flow-step-dot dot-live" style="${pulseDelay('port-' + port)}"></div>
        <div><b>Порт ${port}/${protoLabel}</b><span class="flow-step-meta">${escapeHtml(label)} — открыт снаружи (${escapeHtml(data.targetIp)})${escapeHtml(noteText)}</span>${recheckingHint}</div>
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

function renderPortResults(results) {
  const resultPorts = new Set(results.map((r) => r.meta.port));
  resultPorts.forEach((port) => recheckingPorts.delete(port));
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
  // Список сцен (карточка "Умный переключатель сцен OBS") — отдельный запрос,
  // не завязанный на checkPort выше: он идёт от панели напрямую в OBS-websocket,
  // а не через внешний check-host.net. OBS + сам плагин WebSocket поднимаются не
  // сразу — несколько попыток с нарастающей паузой, чтобы карточка сама ожила,
  // без ручных кликов "Проверить снова".
  [3000, 6000, 10000, 15000].forEach((delay) => setTimeout(refreshObsScenes, delay));
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
  return `<div class="flow-step"><div class="flow-step-dot dot-live" style="${pulseDelay('static-ip')}"></div><div><b>IP статический</b><span class="flow-step-meta">${escapeHtml(host.mobileSrtlaHost)}</span></div></div>`;
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
      ? `<div class="conn-wrap"><div class="conn-line"></div><div class="conn-packet" style="${pulseDelay('conn-' + item.key, 1.6)}"></div></div>`
      : '';
    const html = connector + item.html;
    if (unit.dataset.stepHtml !== html) {
      unit.innerHTML = html;
      unit.dataset.stepHtml = html;
    }
    if (isNew) unit.classList.add('step-reveal');
    // Переставляем узел только если он реально не на своём месте. Лишний
    // prevEl.after()/container.prepend() даже в ту же позицию по спеке снимает узел
    // из DOM и вставляет заново, а пере-вставка перезапускает CSS-анимации внутри
    // шага (dot-live/пакет на линии) — на каждый опрос раз в 5с шаг «моргал».
    if (unit.parentNode !== container || unit.previousElementSibling !== prevEl) {
      if (prevEl) prevEl.after(unit); else container.prepend(unit);
    }
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
      <div class="flow-step-final-head"><div class="flow-step-dot dot-live-red" style="${pulseDelay('qr-scan')}"></div><b>Отсканируй QR в приложении</b></div>
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
  const isLarix = selectedThirdPartyApp === 'larix';
  const finalStep = `
    <div class="flow-step flow-step-final">
      <div class="flow-step-final-head"><div class="flow-step-dot dot-live-red" style="${pulseDelay('choose-app')}"></div><b>Выбери приложение</b></div>
      <div class="app-seg seg">
        <button type="button" class="${selectedThirdPartyApp === 'moblin' ? 'active' : ''}" data-app="moblin">Moblin</button>
        <button type="button" class="${selectedThirdPartyApp === 'larix' ? 'active' : ''}" data-app="larix">Larix</button>
      </div>
      ${addrRow(
        isLarix ? 'URL' : 'Настройки → Стримы → «Создать» → «Пользовательский» → SRT(LA) → URL',
        host.obsSrtUrl,
        isLarix ? 'Без бондинга — сразу в SRS, не в srtla-rec.' : null,
      )}
      ${addrRow(
        isLarix ? 'streamid' : 'Настройки → Стримы → «Создать» → «Пользовательский» → SRT(LA) → Идентификатор стрима',
        host.obsSrtStreamId,
        isLarix ? 'Поле "streamid" в настройках SRT-подключения — без него Larix уйдёт в режим просмотра, а не публикации.' : null,
      )}
      ${liveOrWaitingHtml(currentStreamName, host.playSrt)}
    </div>`;
  return gateSteps([
    { key: 'ip', html: ipStepHtml(host) },
    { key: 'port-10080', html: portStepHtml(PORTS_TO_CHECK[1]), gate: 'required', state: portCheckState(10080) },
    { key: 'final', html: finalStep },
  ]);
}

function inviteFlowBody() {
  const host = inviteHosts.find((h) => h.isPublic);
  if (!host) {
    return [{ key: 'warn', html: '<div class="flow-warn">Не нашли внешний IP — без него друг снаружи не достучится. Проверь интернет и нажми «Проверить снова» вверху страницы.</div>' }];
  }
  const finalStep = `
    <div class="flow-step flow-step-final">
      <div class="flow-step-final-head"><div class="flow-step-dot dot-live-red" style="${pulseDelay('obs-friend-data')}"></div><b>Данные для OBS друга</b></div>
      ${addrRow('Сервер', host.obsSrtUrl, 'В OBS: Настройки → Трансляция → Служба «Настраиваемый» → поле "Сервер".')}
      <div class="name-row">
        <input type="text" id="inviteName" value="${escapeHtml(host.obsSrtStreamId)}" readonly style="font-family:'SF Mono',Consolas,monospace" />
        <button type="button" class="dice-btn" id="regenInvite" title="Сгенерировать другое имя">🎲</button>
      </div>
      <div class="row-meta">↑ Ключ трансляции (то же поле в OBS)</div>
      ${liveOrWaitingHtml(inviteStreamName, host.playSrt)}
    </div>`;
  return gateSteps([
    { key: 'ip', html: ipStepHtml(host) },
    { key: 'port-10080', html: portStepHtml(PORTS_TO_CHECK[1]), gate: 'required', state: portCheckState(10080) },
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
// Плашки создаём один раз, дальше обновляем на месте (класс .open) — раньше на
// любое изменение перезаписывался innerHTML всего селектора разом, и все три
// узла пересоздавались: у открытой плашки заново стартовал infinite ringPulse,
// у соседних сбрасывались transition'ы — моргали все, хотя менялась одна.
// Теперь трогаем ровно ту плашку, чей статус реально изменился.
let flowPillEls = null;

function renderFlowSelector() {
  if (!flowPillEls) {
    flowSelectorEl.innerHTML = FLOWS.map((f) => `
    <button type="button" class="flow-pill" data-flow="${f.id}">
      <span class="flow-pill-badge">${f.icon}</span>
      <span class="flow-pill-title"><b>${escapeHtml(f.title)}</b><span class="flow-hint">${escapeHtml(f.hint)}</span></span>
    </button>`).join('');
    flowPillEls = {};
    flowSelectorEl.querySelectorAll('.flow-pill').forEach((btn) => {
      flowPillEls[btn.dataset.flow] = btn;
      btn.onclick = () => setActiveFlow(btn.dataset.flow);
    });
  }
  FLOWS.forEach((f) => {
    const btn = flowPillEls[f.id];
    btn.classList.toggle('open', activeFlowId === f.id);
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
        <div class="live-badge"><span class="dot dot-live" style="${pulseDelay('stream-' + s.name)}"></span>Live</div>
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
// явной кнопкой внутри (см. streamRowHtml ниже) — тумблер только прячет
// лишнее, не меняет реальное подключённое состояние.
const SUBS_EXPANDED_KEY = 'bondcast_subs_expanded';
let subsExpanded = localStorage.getItem(SUBS_EXPANDED_KEY) === '1';
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
// websocket-клиент + монитор сигнала SRS), не просто UI-заглушка. По умолчанию
// без единого клика следит за currentStreamName (стрим этого телефона/сессии —
// он существует всегда, даже если ещё не в эфире) — выбор в селекте нужен, только
// если параллельно стримит кто-то ещё и приглядывать нужно не за своим именем.
let sceneSwitcherState = { enabled: false, watchStreamName: null, fallbackScene: null, delaySec: 3, minBitrateKbps: 0, state: 'idle', lastError: null };
let availableObsScenes = [];
let obsScenesError = null;
const sceneSwitchEl = document.getElementById('sceneSwitch');
const sceneSwitcherBodyEl = document.getElementById('sceneSwitcherBody');
// Отдельно от sceneSwitcherBodyEl — та скрыта, пока переключатель выключен
// (applySceneSwitch), а ошибку включения (нет сцен/OBS недоступен) нужно
// показать именно в момент неудачной попытки включить, когда тело ещё скрыто.
const sceneSwitcherErrorEl = document.getElementById('sceneSwitcherError');

// extraHtml — готовая инструкция (напр. OBS_WEBSOCKET_HOWTO), а не ссылка "смотри
// её в другом сценарии" — стример уже здесь, на вкладке "Функции", незачем
// заставлять его переключаться на "Старт и подключение" за тем же текстом.
function showSceneSwitcherError(message, extraHtml = '') {
  // .flow-warn красит весь свой текст в акцентный красный (это ок для самой
  // ошибки) — инструкцию внутри extraHtml возвращаем к обычному цвету текста,
  // иначе шаги "как включить WebSocket" тоже стали бы красными.
  const extra = extraHtml ? `<div style="color:var(--text)">${extraHtml}</div>` : '';
  sceneSwitcherErrorEl.innerHTML = escapeHtml(message) + extra;
  sceneSwitcherErrorEl.hidden = false;
}

function clearSceneSwitcherError() {
  sceneSwitcherErrorEl.hidden = true;
}

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
    if (!res.ok) {
      const err = new Error(data.error || 'unknown error');
      err.code = data.code;
      throw err;
    }
    sceneSwitcherState = data;
    clearSceneSwitcherError();
  } catch (e) {
    // obs_unreachable — тот же диагноз, что и "Нет доступных сцен" ниже (OBS не
    // достучаться), поэтому та же инструкция, а не просто текст ошибки.
    const extra = e.code === 'obs_unreachable' ? OBS_LAUNCH_BUTTON + OBS_WEBSOCKET_HOWTO : '';
    showSceneSwitcherError(`Переключатель сцен: ${e.message}`, extra);
  }
  applySceneSwitch();
  renderSceneSwitcherBody();
}

sceneSwitchEl.onclick = () => {
  if (sceneSwitcherState.enabled) {
    postSceneSwitcher({ enabled: false });
    return;
  }
  const watchStreamName = sceneSwitcherState.watchStreamName || currentStreamName;
  const fallbackScene = sceneSwitcherState.fallbackScene || availableObsScenes[0];
  if (!fallbackScene) {
    showSceneSwitcherError(
      'Нет доступных сцен — для переключателя нужен запущенный OBS с включённым WebSocket-сервером.',
      OBS_LAUNCH_BUTTON + OBS_WEBSOCKET_HOWTO,
    );
    return;
  }
  postSceneSwitcher({ enabled: true, watchStreamName, fallbackScene, delaySec: sceneSwitcherState.delaySec, minBitrateKbps: sceneSwitcherState.minBitrateKbps });
};

function renderSceneSwitcherBody() {
  if (obsScenesError) {
    sceneSwitcherBodyEl.innerHTML = `
      <div class="row-meta">Не удалось получить список сцен из OBS: ${escapeHtml(obsScenesError)}.</div>
      ${OBS_LAUNCH_BUTTON}${OBS_WEBSOCKET_HOWTO}
      <button type="button" id="retryObsScenes" style="margin-top:8px">Проверить снова</button>`;
    const retryBtn = document.getElementById('retryObsScenes');
    if (retryBtn) retryBtn.onclick = refreshObsScenes;
    return;
  }
  // Стрим, который сейчас отслеживается, может уже не быть в latestStreams
  // (пропал сигнал — ради этого момента вся фича и существует) — не теряем его
  // из списка, иначе выпадающий список молча "забудет" текущий выбор.
  const streamNames = latestStreams.map((s) => s.name);
  if (!streamNames.includes(currentStreamName)) streamNames.unshift(currentStreamName); // свой стрим — всегда доступен, даже офлайн
  if (sceneSwitcherState.watchStreamName && !streamNames.includes(sceneSwitcherState.watchStreamName)) {
    streamNames.unshift(sceneSwitcherState.watchStreamName);
  }
  const selectStyle = 'flex:1;min-width:0;box-sizing:border-box;padding:9px 32px 9px 12px;background:var(--input-bg);border:1px solid var(--divider);border-radius:8px;color:var(--text);font-size:13px';
  const stateNote = { watching: ' · слежу за сигналом', switched: ' · сейчас показываю резервную сцену' }[sceneSwitcherState.state] || '';
  sceneSwitcherBodyEl.innerHTML = `
    <div style="display:flex;align-items:center;gap:8px">
      <label class="field-label" style="margin:0;white-space:nowrap">Стрим</label>
      <select id="watchStreamSelect" style="${selectStyle}">
        ${streamNames.map((name) => `<option value="${escapeHtml(name)}" ${name === sceneSwitcherState.watchStreamName ? 'selected' : ''}>${escapeHtml(name)}</option>`).join('')}
      </select>
    </div>
    <div style="display:flex;align-items:center;gap:8px;margin-top:8px">
      <label class="field-label" style="margin:0;white-space:nowrap">Сцена при потере сигнала</label>
      <select id="fallbackSceneSelect" style="${selectStyle}">
        ${availableObsScenes.map((name) => `<option value="${escapeHtml(name)}" ${name === sceneSwitcherState.fallbackScene ? 'selected' : ''}>${escapeHtml(name)}</option>`).join('')}
      </select>
    </div>
    <div class="range-field" style="margin-top:8px">
      <div class="range-head"><span>Переключать через${escapeHtml(stateNote)}</span><output id="switchDelayOut">${sceneSwitcherState.delaySec} сек</output></div>
      <input type="range" id="switchDelay" min="0" max="30" value="${sceneSwitcherState.delaySec}" />
      <div class="row-meta" style="margin-top:6px">После потери сигнала. Как только эфир вернётся — вернём рабочую сцену автоматически</div>
    </div>
    <div class="range-field" style="margin-top:8px">
      <div class="range-head"><span>Минимальный битрейт</span><output id="minBitrateOut">${sceneSwitcherState.minBitrateKbps ? sceneSwitcherState.minBitrateKbps + ' кбит/с' : 'выкл'}</output></div>
      <input type="range" id="minBitrate" min="0" max="20000" step="100" value="${sceneSwitcherState.minBitrateKbps || 0}" />
      <div class="row-meta" style="margin-top:6px">Если битрейт входящего сигнала падает ниже — тоже переключаем сцену, даже если публикация формально не оборвалась (плохой канал). 0 — не проверять, только полная потеря сигнала</div>
    </div>
    ${sceneSwitcherState.lastError ? `<div class="flow-warn">${escapeHtml(sceneSwitcherState.lastError)}</div>` : ''}
  `;
  const streamSelect = document.getElementById('watchStreamSelect');
  if (streamSelect) {
    streamSelect.onchange = () => postSceneSwitcher({ enabled: true, watchStreamName: streamSelect.value, fallbackScene: sceneSwitcherState.fallbackScene, delaySec: sceneSwitcherState.delaySec, minBitrateKbps: sceneSwitcherState.minBitrateKbps });
  }
  const select = document.getElementById('fallbackSceneSelect');
  if (select) {
    select.onchange = () => postSceneSwitcher({ enabled: true, watchStreamName: sceneSwitcherState.watchStreamName, fallbackScene: select.value, delaySec: sceneSwitcherState.delaySec, minBitrateKbps: sceneSwitcherState.minBitrateKbps });
  }
  const delayInput = document.getElementById('switchDelay');
  const delayOut = document.getElementById('switchDelayOut');
  if (delayInput) {
    delayInput.oninput = () => { delayOut.textContent = `${delayInput.value} сек`; };
    delayInput.onchange = () => postSceneSwitcher({ enabled: true, watchStreamName: sceneSwitcherState.watchStreamName, fallbackScene: sceneSwitcherState.fallbackScene, delaySec: Number(delayInput.value), minBitrateKbps: sceneSwitcherState.minBitrateKbps });
  }
  const minBitrateInput = document.getElementById('minBitrate');
  const minBitrateOut = document.getElementById('minBitrateOut');
  if (minBitrateInput) {
    minBitrateInput.oninput = () => { minBitrateOut.textContent = Number(minBitrateInput.value) ? `${minBitrateInput.value} кбит/с` : 'выкл'; };
    minBitrateInput.onchange = () => postSceneSwitcher({ enabled: true, watchStreamName: sceneSwitcherState.watchStreamName, fallbackScene: sceneSwitcherState.fallbackScene, delaySec: sceneSwitcherState.delaySec, minBitrateKbps: Number(minBitrateInput.value) });
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
const capBuildStageEl = document.getElementById('capBuildStage');
const capReadyStageEl = document.getElementById('capReadyStage');
const connectedSectionEl = document.getElementById('connectedSection');
const connectedStreamLabelEl = document.getElementById('connectedStreamLabel');
const obsUrlRowEl = document.getElementById('obsUrlRow');
const voicesListEl = document.getElementById('voicesList');
const addVoiceBtnEl = document.getElementById('addVoiceBtn');

let captionsState = {
  connected: false, streamName: null, overlayUrl: null, imageExists: false, buildStatus: 'idle', buildError: null,
  voices: [], enrollStatus: 'idle', enrollError: null, enrollVoiceId: null, ready: false, asrModel: null,
};

function recognitionSettingsChanged() {
  return captionsState.asrModel !== recognitionInputs.asrModel();
}
let capBusy = false;

// Общая строка ошибки для установки/подключения/отключения/записи голоса —
// вне стадийных контейнеров (см. index.html), чтобы быть видимой независимо
// от текущей стадии (в частности — ошибка установки образа на стадии 1).
const capErrorEl = document.getElementById('capError');

function showCapError(message) {
  capErrorEl.textContent = message;
  capErrorEl.hidden = false;
}

function clearCapError() {
  capErrorEl.hidden = true;
}

const ENROLL_DURATION_SEC = 15;

// --- Настройки распознавания (asr_model) — connect-time параметр, нужен до
// подключения (см. Стадия 2 в index.html). Порог схожести голоса больше не
// общий: у каждого голоса свой слайдер в мини-списке ниже.
const RECOGNITION_DEFAULTS = { asrModel: 'v3_e2e_rnnt' };
const qualityFastBtn = document.getElementById('qualityFastBtn');
const qualityPreciseBtn = document.getElementById('qualityPreciseBtn');
const recognitionInputs = {
  asrModel: () => (qualityPreciseBtn.classList.contains('active') ? qualityPreciseBtn.dataset.model : qualityFastBtn.dataset.model),
};

function setAsrModel(model) {
  const isPrecise = model === qualityPreciseBtn.dataset.model;
  qualityPreciseBtn.classList.toggle('active', isPrecise);
  qualityFastBtn.classList.toggle('active', !isPrecise);
  localStorage.setItem('bondcast_asrModel', isPrecise ? qualityPreciseBtn.dataset.model : qualityFastBtn.dataset.model);
}
setAsrModel(localStorage.getItem('bondcast_asrModel') || RECOGNITION_DEFAULTS.asrModel);

// Без ручной кнопки "Применить": если уже подключены к стриму — переключение
// Быстрое/Точное само переподключает субтитры с новой моделью; если ещё не
// подключены — модель просто запомнилась и применится при следующем "Подключить".
function applyRecognitionModelIfConnected() {
  if (captionsState.connected && captionsState.streamName && recognitionSettingsChanged()) {
    connectCaptions(captionsState.streamName);
  } else {
    renderStreamCards(latestStreams);
  }
}
qualityFastBtn.addEventListener('click', () => { setAsrModel(qualityFastBtn.dataset.model); applyRecognitionModelIfConnected(); });
qualityPreciseBtn.addEventListener('click', () => { setAsrModel(qualityPreciseBtn.dataset.model); applyRecognitionModelIfConnected(); });

// --- Оформление оверлея ------------------------------------------------------
// hostColor ушёл — именованные голоса красятся детерминированной палитрой по
// id (см. colorForId в overlay/index.html), не настраиваются здесь по одному.
// Само оформление больше не кодируется в ссылке для OBS (query-параметрами) —
// источник истины теперь на сервере (/api/captions/overlay-style, публичный
// GET), а overlay/index.html сам его переопрашивает раз в 5с. Поэтому: (а)
// ссылка для OBS теперь ПОСТОЯННАЯ, копируется в Browser Source один раз
// навсегда; (б) правки здесь просто PATCH'ат тот же эндпоинт с debounce,
// никакого localStorage — при следующей загрузке страницы читаем с сервера.
const overlayInputs = {
  size: document.getElementById('overlaySize'),
  lines: document.getElementById('overlayLines'),
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
  preview.style.setProperty('--prev-guest', overlayInputs.guestColor.value);
  preview.style.setProperty('--prev-bg', hexToRgba(overlayInputs.bgColor.value, Number(overlayInputs.bgOpacity.value) / 100));
  if (overlayOutputs.size) overlayOutputs.size.textContent = overlayInputs.size.value;
  if (overlayOutputs.lines) overlayOutputs.lines.textContent = overlayInputs.lines.value;
  if (overlayOutputs.bgOpacity) overlayOutputs.bgOpacity.textContent = `${overlayInputs.bgOpacity.value}%`;
  // Превью должно один в один повторять то, что реально покажет оверлей (см.
  // fill() в overlay/index.html) — при выключенном "Показывать, кто говорит"
  // там тоже нет ни имени, ни "Кто-то:", просто голый текст.
  const showSpeaker = showSpeakerToggleEl.checked;
  preview.querySelectorAll('.name').forEach((el) => { el.hidden = !showSpeaker; });
}

let overlayStylePatchTimer = null;
function patchOverlayStyle() {
  clearTimeout(overlayStylePatchTimer);
  overlayStylePatchTimer = setTimeout(() => {
    fetch('/api/captions/overlay-style', {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        size: overlayInputs.size.value,
        lines: overlayInputs.lines.value,
        guestColor: overlayInputs.guestColor.value,
        bgColor: overlayInputs.bgColor.value,
        bgOpacity: overlayInputs.bgOpacity.value,
      }),
    }).catch(() => {});
  }, 300);
}

Object.values(overlayInputs).forEach((el) => {
  el.addEventListener('input', () => {
    updateOverlayPreview();
    patchOverlayStyle();
  });
});

// Отдельно от overlayInputs — живёт в карточке "Голоса", не в раскрывающемся
// "Оформлении", но правит тот же ресурс (showSpeaker в overlay_style.json):
// полностью гасит подпись говорящего в оверлее (и "Имя:", и "Кто-то:"),
// просто голый текст реплики, если она не нужна вообще.
const showSpeakerToggleEl = document.getElementById('showSpeakerToggle');
showSpeakerToggleEl.addEventListener('change', () => {
  updateOverlayPreview();
  fetch('/api/captions/overlay-style', {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ showSpeaker: showSpeakerToggleEl.checked }),
  }).catch(() => {});
});

async function loadOverlayStyle() {
  try {
    const res = await fetch('/api/captions/overlay-style');
    const style = await res.json();
    overlayInputs.size.value = style.size;
    overlayInputs.lines.value = style.lines;
    overlayInputs.guestColor.value = style.guestColor;
    overlayInputs.bgColor.value = style.bgColor;
    overlayInputs.bgOpacity.value = style.bgOpacity;
    showSpeakerToggleEl.checked = style.showSpeaker !== false;
  } catch (e) {
    // тихо — останутся дефолты из атрибутов инпутов, следующий заход подтянет реальные
  }
  updateOverlayPreview();
}
loadOverlayStyle();

// --- Раскрывающаяся панель "Оформление оверлея" ------------------------------
const OVERLAY_STYLE_EXPANDED_KEY = 'bondcast_overlay_style_expanded';
let overlayStyleExpanded = localStorage.getItem(OVERLAY_STYLE_EXPANDED_KEY) === '1';
const overlayStyleToggleEl = document.getElementById('overlayStyleToggle');
const overlayStyleSwitchEl = document.getElementById('overlayStyleSwitch');
const overlaySectionEl = document.getElementById('overlaySection');
function applyOverlayStyleToggle() {
  overlayStyleSwitchEl.classList.toggle('on', overlayStyleExpanded);
  overlaySectionEl.hidden = !overlayStyleExpanded;
}
overlayStyleToggleEl.onclick = () => {
  overlayStyleExpanded = !overlayStyleExpanded;
  localStorage.setItem(OVERLAY_STYLE_EXPANDED_KEY, overlayStyleExpanded ? '1' : '0');
  applyOverlayStyleToggle();
};
applyOverlayStyleToggle();

function formatCodec(video, audio) {
  const parts = [];
  if (video) parts.push(`${video.codec} ${video.width}x${video.height}`);
  if (audio) parts.push(`${audio.codec} ${audio.sample_rate}Hz`);
  return parts.join(' · ') || 'кодеки ещё не определены';
}

// --- Стадии карточки: установка ПО -> список стримов -> подключено ---------
function applyCaptionsStage() {
  const ready = captionsState.imageExists;
  capBuildStageEl.hidden = ready;
  capReadyStageEl.hidden = !ready;
  const buildBtn = capBuildStageEl.querySelector('.cap-build');
  buildBtn.disabled = captionsState.buildStatus === 'building';
  buildBtn.textContent = captionsState.buildStatus === 'building' ? 'Устанавливаю…' : 'Установить нужное ПО';
}
capBuildStageEl.querySelector('.cap-build').onclick = buildCaptions;
addVoiceBtnEl.title = `${ENROLL_DURATION_SEC} секунд — говорить должен только этот голос`;
addVoiceBtnEl.onclick = () => {
  if (captionsState.streamName) enrollVoice(captionsState.streamName);
};

// Настройки распознавания применяются сами (см. applyRecognitionModelIfConnected)
// — отдельной кнопки "Применить" тут больше нет. "Смотреть" тоже убрали:
// в контексте подключения субтитров превью стрима не нужно, это не тот экран.
function streamRowHtml(s) {
  const isThisConnected = captionsState.connected && captionsState.streamName === s.name;
  const actionHtml = isThisConnected
    ? '<button class="cap-disconnect primary">Субтитры: отключить</button>'
    : `<button class="cap-connect primary" data-name="${escapeHtml(s.name)}">Подключить субтитры</button>`;
  const loadingHtml = isThisConnected && !captionsState.ready
    ? '<div class="row-meta" style="margin-top:8px">⏳ Загружается модель распознавания — при первом запуске (без кэша) это ~1ГБ и может занять минуту-две.</div>'
    : '';
  // .stream-row — рамка вокруг имени+кнопки, чтобы при нескольких стримах сразу
  // читалось, какая кнопка к какому стриму относится (не просто список строк).
  return `
    <div class="stream-row">
      <div class="row" style="padding:0">
        <div class="row-label">
          <b>${escapeHtml(s.name)}</b>
        </div>
        <div class="row-actions">
          ${actionHtml}
        </div>
      </div>
      ${loadingHtml}
    </div>`;
}

function renderStreamCards(streams) {
  if (!streams.length) {
    streamCardsEl.innerHTML = '<div class="row"><span class="row-label"><span class="row-meta">пока никто не стримит</span></span></div>';
  } else {
    streamCardsEl.innerHTML = streams.map(streamRowHtml).join('');
  }
  streamCardsEl.querySelectorAll('.cap-connect').forEach((btn) => { btn.onclick = () => connectCaptions(btn.dataset.name); });
  streamCardsEl.querySelectorAll('.cap-disconnect').forEach((btn) => { btn.onclick = disconnectCaptions; });

  connectedSectionEl.hidden = !captionsState.connected;
  if (captionsState.connected) {
    connectedStreamLabelEl.textContent = `Голоса и ссылка для OBS — стрим «${captionsState.streamName}»`;
    renderObsUrlRow();
    renderVoices();
  }
}

// --- Ссылка для OBS: серая ("fresh"), пока оформление не менялось с момента
// последнего копирования; акцентная ("stale") + подсказка — когда изменилось.
// Оформление больше не в URL (см. комментарий у overlayInputs выше) — ссылка
// постоянная, копируется в OBS Browser Source один раз и больше не меняется.
function renderObsUrlRow() {
  if (!(captionsState.connected && captionsState.overlayUrl)) {
    obsUrlRowEl.innerHTML = '';
    return;
  }
  obsUrlRowEl.innerHTML = addrRow('Оверлей для OBS', captionsState.overlayUrl, 'Добавь как Browser Source в OBS — субтитры поверх видео. Оформление меняется прямо здесь, ссылку обновлять не нужно.');
  bindCopyButtons(obsUrlRowEl);
}

// --- Мини-список голосов ------------------------------------------------------
function voiceEnrollProgressHtml() {
  const elapsed = enrollStartedAt ? (Date.now() - enrollStartedAt) / 1000 : 0;
  const capturing = elapsed < ENROLL_DURATION_SEC;
  const label = capturing
    ? `🎙 Говори без пауз — ещё ${Math.max(0, Math.ceil(ENROLL_DURATION_SEC - elapsed))}с`
    : '⏳ Считаю эмбеддинг голоса…';
  return `<span class="row-meta">${label}</span><progress value="${Math.min(elapsed, ENROLL_DURATION_SEC).toFixed(1)}" max="${ENROLL_DURATION_SEC}" style="width:100%;margin-top:6px"></progress>`;
}

function voiceRowHtml(voice) {
  const isEnrollingThis = captionsState.enrollStatus === 'running' && captionsState.enrollVoiceId === voice.id;
  const busy = captionsState.enrollStatus === 'running';
  const body = isEnrollingThis
    ? voiceEnrollProgressHtml()
    : `<div class="range-field">
        <div class="range-head"><span>Строгость</span><output class="voice-threshold-out">${Number(voice.threshold).toFixed(2)}</output></div>
        <input type="range" class="voice-threshold-input" min="0" max="1" step="0.01" value="${voice.threshold}" ${busy ? 'disabled' : ''} />
      </div>`;
  return `
    <div class="voice-row" data-voice-id="${escapeHtml(voice.id)}">
      <div class="voice-row-main">
        <input type="text" class="voice-name-input" value="${escapeHtml(voice.name)}" maxlength="40" ${busy ? 'disabled' : ''} />
        <button type="button" class="voice-rerecord" title="Перезаписать голос" ${busy ? 'disabled' : ''}>🔁</button>
        <button type="button" class="voice-delete" title="Удалить голос" ${busy ? 'disabled' : ''}>✕</button>
      </div>
      ${body}
      ${!voice.hasEmbedding && !isEnrollingThis ? '<div class="row-meta">Запись ещё не завершена</div>' : ''}
    </div>`;
}

function bindVoiceRowHandlers() {
  voicesListEl.querySelectorAll('.voice-row[data-voice-id]').forEach((row) => {
    const id = row.dataset.voiceId;
    const nameInput = row.querySelector('.voice-name-input');
    if (nameInput) {
      const commit = () => {
        const name = nameInput.value.trim();
        if (name) patchVoice(id, { name });
      };
      nameInput.addEventListener('blur', commit);
      nameInput.addEventListener('keydown', (e) => { if (e.key === 'Enter') nameInput.blur(); });
    }
    const thresholdInput = row.querySelector('.voice-threshold-input');
    const thresholdOut = row.querySelector('.voice-threshold-out');
    if (thresholdInput) {
      let debounceTimer = null;
      thresholdInput.addEventListener('input', () => {
        if (thresholdOut) thresholdOut.textContent = Number(thresholdInput.value).toFixed(2);
        clearTimeout(debounceTimer);
        debounceTimer = setTimeout(() => patchVoice(id, { threshold: Number(thresholdInput.value) }), 250);
      });
    }
    const rerecordBtn = row.querySelector('.voice-rerecord');
    if (rerecordBtn) {
      rerecordBtn.onclick = () => {
        if (!captionsState.streamName) return;
        enrollVoice(captionsState.streamName, id);
        rerecordBtn.blur(); // иначе фокус внутри #voicesList заблокирует рендер прогресс-бара (см. гейт в renderVoices)
      };
    }
    const deleteBtn = row.querySelector('.voice-delete');
    if (deleteBtn) {
      deleteBtn.onclick = () => {
        deleteBtn.blur();
        deleteVoice(id);
      };
    }
  });
}

// Правки одного голоса идут строго по очереди — не Promise.all, чтобы более
// старый ответ (напр. на первый keystroke) не затёр более новое значение,
// пришедшее позже по сети раньше своего места в очереди.
const voicePatchInFlight = new Map();
function patchVoice(id, body) {
  const prev = voicePatchInFlight.get(id) || Promise.resolve();
  const next = prev
    .then(() =>
      fetch(`/api/captions/voices/${encodeURIComponent(id)}`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      }),
    )
    .then(async (res) => {
      const data = await res.json();
      if (!res.ok) throw new Error(data.error || 'unknown error');
      // Правим локальный кэш сразу — не ждём следующего 5-секундного опроса,
      // иначе значение визуально "откатится" на старое до него.
      const voice = captionsState.voices.find((v) => v.id === id);
      if (voice) Object.assign(voice, data.voice);
    })
    .catch((e) => showCapError(`Голос: ${e.message}`));
  voicePatchInFlight.set(id, next);
  return next;
}

function deleteVoice(id) {
  fetch(`/api/captions/voices/${encodeURIComponent(id)}`, { method: 'DELETE' })
    .then(async (res) => {
      const data = await res.json();
      if (!res.ok) throw new Error(data.error || 'unknown error');
      captionsState.voices = captionsState.voices.filter((v) => v.id !== id);
      renderVoices();
    })
    .catch((e) => showCapError(`Голос: ${e.message}`));
}

// Точечный рендер, отдельно от renderStreamCards (которая перезаписывает
// соседний streamCardsEl целиком раз в 5с) — если этого не сделать, печать
// имени голоса или перетаскивание слайдера строгости будут обрываться каждым
// опросом (streamCardsEl.innerHTML полностью пересоздаёт DOM).
function renderVoices() {
  const enrollingNew = captionsState.enrollStatus === 'running'
    && captionsState.enrollVoiceId
    && !captionsState.voices.some((v) => v.id === captionsState.enrollVoiceId);
  addVoiceBtnEl.disabled = captionsState.enrollStatus === 'running';
  addVoiceBtnEl.textContent = enrollingNew ? '🎙 Идёт запись…' : '🎙 Определить голос';

  if (voicesListEl.contains(document.activeElement)) return; // юзер сейчас печатает/тащит слайдер — не трогаем DOM

  const errorHtml = captionsState.enrollStatus === 'error'
    ? `<div class="flow-warn">Запись голоса не удалась: ${escapeHtml(captionsState.enrollError || '')}</div>`
    : '';
  const rows = captionsState.voices.map(voiceRowHtml).join('');
  const pendingRow = enrollingNew ? `<div class="voice-row">${voiceEnrollProgressHtml()}</div>` : '';
  const body = rows + pendingRow;
  voicesListEl.innerHTML = errorHtml + (body || '<div class="row-meta">Голоса ещё не записаны — все реплики подписываются «Кто-то»</div>');
  bindVoiceRowHandlers();
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
  applyCaptionsStage();
}

async function pollStreamsAndCaptions() {
  await refreshCaptionsStatus();
  await refreshStreams();
}

async function buildCaptions() {
  if (capBusy) return;
  capBusy = true;
  clearCapError();
  try {
    const res = await fetch('/api/captions/build', { method: 'POST' });
    const data = await res.json();
    if (!res.ok) throw new Error(data.error || 'unknown error');
    openBuildLogStream();
  } catch (e) {
    showCapError(`Установка: ${e.message}`);
  } finally {
    capBusy = false;
    pollStreamsAndCaptions();
  }
}

async function connectCaptions(name) {
  if (capBusy) return;
  capBusy = true;
  clearCapError();
  try {
    const res = await fetch('/api/captions/connect', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name, asrModel: recognitionInputs.asrModel() }),
    });
    const data = await res.json();
    if (!res.ok) throw new Error(data.error || 'unknown error');
  } catch (e) {
    showCapError(`Субтитры: ${e.message}`);
  } finally {
    capBusy = false;
    pollStreamsAndCaptions();
  }
}

async function disconnectCaptions() {
  if (capBusy) return;
  capBusy = true;
  clearCapError();
  try {
    const res = await fetch('/api/captions/disconnect', { method: 'POST' });
    const data = await res.json();
    if (!res.ok) throw new Error(data.error || 'unknown error');
  } catch (e) {
    showCapError(`Субтитры: ${e.message}`);
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
    appendCapLog('[поток логов установки прерван]');
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

// Без voiceId — запись НОВОГО голоса (имя вводится после успеха, не до —
// плейсхолдер приходит с сервера уже рабочим). С voiceId — перезапись
// существующего (имя/порог не меняются).
async function enrollVoice(streamName, voiceId) {
  if (capBusy) return;
  capBusy = true;
  clearCapError();
  try {
    const res = await fetch('/api/captions/enroll', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(voiceId ? { streamName, voiceId } : { streamName }),
    });
    const data = await res.json();
    if (!res.ok) throw new Error(data.error || 'unknown error');
    enrollStartedAt = Date.now();
    captionsState.enrollStatus = 'running';
    captionsState.enrollVoiceId = data.voiceId;
    const isNewVoice = !voiceId;
    stopEnrollTicker();
    enrollTicker = setInterval(() => {
      renderVoices();
      if (captionsState.enrollStatus !== 'running') {
        stopEnrollTicker();
        if (isNewVoice && captionsState.enrollStatus === 'done') focusVoiceNameInput(data.voiceId);
      }
    }, 250);
    openEnrollLogStream();
  } catch (e) {
    showCapError(`Запись голоса: ${e.message}`);
  } finally {
    capBusy = false;
    pollStreamsAndCaptions();
  }
}

function focusVoiceNameInput(voiceId) {
  const input = voicesListEl.querySelector(`.voice-row[data-voice-id="${CSS.escape(voiceId)}"] .voice-name-input`);
  if (input) { input.focus(); input.select(); }
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

// --- Баннер обновления --------------------------------------------------------
// Тихая установка (bondcast-update:// -> update.ps1, тот же мост, что и у
// "Запустить OBS") запускается ТОЛЬКО по явному клику — и не с одного клика:
// первый превращает кнопку в вопрос-подтверждение, реально скачивает и ставит
// только второй. Без нативных alert()/confirm() (в проекте их уже сознательно
// убирали — см. git log), вся кнопка целиком в баннере.
const updateBannerEl = document.getElementById('updateBanner');
const updateBannerTextEl = document.getElementById('updateBannerText');
const updateBannerBtnEl = document.getElementById('updateBannerBtn');
let updateConfirmPending = false;

function resetUpdateButton() {
  updateConfirmPending = false;
  updateBannerBtnEl.textContent = 'Обновить';
  updateBannerBtnEl.classList.remove('primary');
}

updateBannerBtnEl.onclick = () => {
  if (!updateConfirmPending) {
    updateConfirmPending = true;
    updateBannerBtnEl.textContent = 'Точно? Скачает и тихо установит .exe';
    updateBannerBtnEl.classList.add('primary');
    return;
  }
  window.location.href = 'bondcast-update://install';
  resetUpdateButton();
};

async function refreshUpdateStatus() {
  try {
    const res = await fetch('/api/update/status');
    const data = await res.json();
    if (data.updateAvailable) {
      // note — первая строка текста релиза (см. checkForUpdate в server.js),
      // шуточное однострочное описание в духе Discord-патчноутов, не сухой номер версии.
      const noteHtml = data.note ? `<b>${escapeHtml(data.note)}</b> — ` : '';
      updateBannerTextEl.innerHTML = `${noteHtml}вышла версия ${escapeHtml(data.latestVersion)} (сейчас ${escapeHtml(data.currentVersion)})`;
      updateBannerEl.hidden = false;
    } else {
      updateBannerEl.hidden = true;
      resetUpdateButton();
    }
  } catch (e) {
    // тихо — панель могла быть недоступна секунду, попробуем на следующем опросе
  }
}
refreshUpdateStatus();
setInterval(refreshUpdateStatus, 5 * 60 * 1000);
// Дольше 5 минут не открывал вкладку — статус мог устареть (напр. только что
// поставил обновление в фоне) — перепроверяем сразу, как вернулся, а не ждём
// остаток интервала.
document.addEventListener('visibilitychange', () => {
  if (!document.hidden) refreshUpdateStatus();
});

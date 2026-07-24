const cardsEl = document.getElementById('cards');

// Discord-style случайное имя стрима вместо унылого "livestream" — adjective-noun-1234.
const NAME_ADJECTIVES = ['turbo', 'sneaky', 'feral', 'spicy', 'soggy', 'glorious', 'unhinged', 'majestic', 'chaotic', 'crispy', 'salty', 'fancy', 'goblin', 'based', 'cursed', 'radiant', 'grumpy', 'sleepy', 'unstable', 'legendary'];
const NAME_NOUNS = ['hamster', 'otter', 'walrus', 'goose', 'capybara', 'raccoon', 'penguin', 'narwhal', 'possum', 'ferret', 'wombat', 'axolotl', 'llama', 'platypus', 'yeti', 'gremlin', 'potato', 'pigeon', 'moth', 'shrimp'];

function randomStreamName() {
  const adj = NAME_ADJECTIVES[Math.floor(Math.random() * NAME_ADJECTIVES.length)];
  const noun = NAME_NOUNS[Math.floor(Math.random() * NAME_NOUNS.length)];
  const num = Math.floor(Math.random() * 900 + 100);
  return `${adj}-${noun}-${num}`;
}

// Одно и то же имя на index.html/dashboard.html, и оно переживает перезапуск start.bat —
// тот открывает страницу в новой вкладке, а у новой вкладки уже нет sessionStorage старой.
const STREAM_NAME_KEY = 'bondcast_stream_name';

function getOrCreateStreamName() {
  return localStorage.getItem(STREAM_NAME_KEY) || regenerateStreamName();
}

function regenerateStreamName() {
  const name = randomStreamName();
  localStorage.setItem(STREAM_NAME_KEY, name);
  return name;
}

function formatUptime(startedAt) {
  if (!startedAt) return '—';
  const started = new Date(startedAt).getTime();
  const secs = Math.floor((Date.now() - started) / 1000);
  const h = Math.floor(secs / 3600);
  const m = Math.floor((secs % 3600) / 60);
  const s = secs % 60;
  return `${h}ч ${m}м ${s}с`;
}

function renderCards(statuses) {
  cardsEl.innerHTML = '';
  statuses.forEach((c) => {
    const div = document.createElement('div');
    div.className = 'row';
    div.innerHTML = `
      <span class="dot ${c.running ? 'running' : 'stopped'}"></span>
      <div class="row-label">
        <b>${c.name}</b>
        <span class="row-meta">${c.found ? c.state : 'не найден'}${c.running ? ` · ${formatUptime(c.startedAt)}` : ''}</span>
      </div>
      <div class="row-actions">
        <button class="start ${!c.running ? 'primary' : ''}" ${c.running ? 'disabled' : ''}>${c.found ? 'Start' : 'Создать'}</button>
        <button class="stop" ${!c.running ? 'disabled' : ''}>Stop</button>
      </div>
    `;
    div.querySelector('.start').onclick = () => callAction(c.name, c.found ? 'start' : 'recreate');
    div.querySelector('.stop').onclick = () => callAction(c.name, 'stop');
    cardsEl.appendChild(div);
  });
}

async function callAction(name, action) {
  try {
    const res = await fetch(`/api/containers/${name}/${action}`, { method: 'POST' });
    const data = await res.json();
    if (!res.ok) throw new Error(data.error || 'unknown error');
  } catch (e) {
    alert(`${name} ${action}: ${e.message}`);
  }
  refreshStatus();
}

async function refreshStatus() {
  try {
    const res = await fetch('/api/status');
    const data = await res.json();
    renderCards(data);
  } catch (e) {
    cardsEl.innerHTML = `<div class="card">Не удалось получить статус: ${e.message}</div>`;
  }
}

refreshStatus();
setInterval(refreshStatus, 5000);

// --- Видео (HTTP-FLV) ---
let flvPlayer = null;
const videoHintEl = document.getElementById('videoHint');

function stopVideo(hint) {
  if (flvPlayer) {
    flvPlayer.destroy();
    flvPlayer = null;
  }
  videoHintEl.textContent = hint || '';
}

function loadVideo() {
  const name = document.getElementById('streamName').value.trim() || 'livestream';
  const videoEl = document.getElementById('videoEl');
  stopVideo();

  // flv.js в браузере умеет декодировать только H.264 — на HEVC (частый выбор для
  // энергоэффективной записи с телефона) он не падает с ошибкой, а на каждый новый
  // фрагмент живого потока молча долбит demux заново, роняя сотни console.error —
  // событие flvjs.Events.ERROR при этом не всплывает, поймать и остановить снаружи
  // нечем. Единственный надёжный фикс — вообще не пытаться, зная кодек заранее
  // (latestStreams — из /api/streams, куда SRS его уже отдаёт).
  const known = latestStreams.find((s) => s.name === name);
  if (known && known.video && /hevc/i.test(known.video.codec)) {
    videoHintEl.textContent = 'Поток в HEVC — браузерный плеер такое не умеет. Смотри по ссылке для VLC ниже.';
    return;
  }

  if (!window.flvjs || !window.flvjs.isSupported()) {
    videoHintEl.textContent = 'flv.js не поддерживается в этом браузере.';
    return;
  }
  const url = `${window.location.protocol}//${window.location.hostname}:8080/live/${name}.flv`;
  flvPlayer = flvjs.createPlayer({ type: 'flv', url });
  // Защита от прочих (не-кодековых) ошибок — сеть оборвалась, поток закончился и т.п.
  flvPlayer.on(flvjs.Events.ERROR, (type, detail) => {
    stopVideo(`Превью прервано: ${type}/${detail}.`);
  });
  flvPlayer.attachMediaElement(videoEl);
  flvPlayer.load();
  flvPlayer.play().catch(() => {});
}
document.getElementById('loadVideo').onclick = loadVideo;

// --- Подключение (адреса для OBS / мобильного приложения) ---
const connectionsEl = document.getElementById('connections');
const streamNameEl = document.getElementById('streamName');

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

async function refreshConnections() {
  const name = streamNameEl.value.trim() || 'livestream';
  try {
    const res = await fetch(`/api/connections?name=${encodeURIComponent(name)}`);
    const data = await res.json();
    if (!data.hosts || !data.hosts.length) {
      connectionsEl.innerHTML = '<div class="meta">IP этой машины неизвестен панели — запусти ярлык «Запустить трансляцию» (он определяет адрес и передаёт панели).</div>';
      return;
    }
    connectionsEl.innerHTML = data.hosts
      .map(
        (h) => `
      <div class="host-block">
        <h4>${escapeHtml(h.label)}</h4>

        <div class="subgroup-title">Стримить с телефона (Bondcast)</div>
        ${addrRow('Хост', h.mobileSrtlaHost, 'В приложении Bondcast: Настройки подключения → вставь сюда вручную (или отсканируй QR на главном экране панели).')}
        ${addrRow('Порт', String(h.mobileSrtlaPort), 'В приложении Bondcast: то же окно, поле "Порт".')}

        <div class="subgroup-title">Смотреть трансляцию</div>
        ${addrRow('Ссылка для OBS', h.playSrt, 'В OBS: Файл → Мультимедиа (Media Source) → сними галочку "Локальный файл" → вставь эту ссылку в поле "Вход".')}
        <details class="nested">
          <summary>Другие способы посмотреть</summary>
          ${addrRow('HTTP-FLV', h.playFlv, 'Открой ссылку в VLC. В браузере не откроется напрямую — нужна страница с flv.js.')}
          ${addrRow('HLS', h.playHls, 'Открой ссылку в VLC/Safari или любом HLS-плеере. Задержка больше, чем у SRT — обычно 5-10 секунд.')}
        </details>

        <details class="nested">
          <summary>Стримить с компа через OBS (вместо телефона)</summary>
          <div class="subgroup-title">RTMP</div>
          ${addrRow('Сервер', h.obsRtmpServer, 'В OBS: Настройки → Трансляция → Сервис "Особый" → вставь сюда, в поле "Сервер".')}
          ${addrRow('Ключ трансляции', name, 'В том же окне OBS: поле "Ключ трансляции".')}
          <div class="subgroup-title">SRT (задержка ниже)</div>
          ${addrRow('Сервер', h.obsSrtUrl, 'В OBS: Настройки → Трансляция → Сервис "Особый" → вставь сюда, в поле "Сервер".')}
          ${addrRow('Stream ID', h.obsSrtStreamId, 'В том же окне OBS: поле "Ключ трансляции". Можно оставить пустым — тогда сервер сам назовёт поток "livestream".')}
        </details>
      </div>`
      )
      .join('');
    connectionsEl.querySelectorAll('.copy-addr').forEach((btn) => {
      btn.onclick = () => {
        navigator.clipboard.writeText(btn.dataset.value);
        const original = btn.textContent;
        btn.textContent = 'Скопировано';
        setTimeout(() => { btn.textContent = original; }, 1200);
      };
    });
  } catch (e) {
    connectionsEl.innerHTML = `<div class="meta">Не удалось получить адреса: ${escapeHtml(e.message)}</div>`;
  }
}

streamNameEl.value = getOrCreateStreamName();
streamNameEl.addEventListener('input', () => {
  localStorage.setItem(STREAM_NAME_KEY, streamNameEl.value.trim());
  refreshConnections();
});
document.getElementById('regenName').addEventListener('click', () => {
  streamNameEl.value = regenerateStreamName();
  refreshConnections();
});
refreshConnections();

// --- Логи + битрейт ---
const logEl = document.getElementById('log');
const tabButtons = document.querySelectorAll('.tabs button');
let currentSource = null;
let currentTab = 'srs';

const bitrateChart = new Chart(document.getElementById('bitrateChart'), {
  type: 'line',
  data: {
    labels: [],
    datasets: [{
      label: 'ikbps avg5s',
      data: [],
      borderColor: '#5b8def',
      backgroundColor: 'rgba(91,141,239,0.15)',
      tension: 0.3,
      pointRadius: 0,
    }],
  },
  options: {
    animation: false,
    scales: {
      x: { display: false },
      y: { beginAtZero: true, grid: { color: '#262b36' }, ticks: { color: '#8b92a3' } },
    },
    plugins: { legend: { display: false } },
  },
});

function pushBitratePoint(avg5) {
  const d = bitrateChart.data;
  d.labels.push('');
  d.datasets[0].data.push(avg5);
  if (d.labels.length > 60) {
    d.labels.shift();
    d.datasets[0].data.shift();
  }
  bitrateChart.update('none');
}

// Держим последнее известное значение и тикаем графиком раз в секунду,
// чтобы он "ехал" даже когда SRS не пишет новую строку (раз в ~10с).
let lastAvg5 = 0;
setInterval(() => pushBitratePoint(lastAvg5), 1000);

// Панель может простоять открытой часами во время стрима - без потолка textContent
// растёт бесконечно (уже видели 9000+ строк за пару минут), и каждая новая строка
// переписывает весь узел целиком, так что вкладка ощутимо подвисает. Храним только
// последние MAX_LOG_LINES.
const MAX_LOG_LINES = 500;
let logLines = [];

function appendLog(line) {
  const atBottom = logEl.scrollHeight - logEl.scrollTop - logEl.clientHeight < 30;
  logLines.push(line);
  if (logLines.length > MAX_LOG_LINES) logLines.splice(0, logLines.length - MAX_LOG_LINES);
  logEl.textContent = logLines.join('\n') + '\n';
  if (atBottom) logEl.scrollTop = logEl.scrollHeight;

  const m = line.match(/ikbps=(\d+),(\d+),(\d+)/);
  if (m) {
    document.getElementById('ikAvg30').textContent = m[1];
    document.getElementById('ikAvg5').textContent = m[2];
    lastAvg5 = Number(m[2]);
  }
}

function openLogStream(name) {
  if (currentSource) currentSource.close();
  logLines = [];
  logEl.textContent = '';
  currentSource = new EventSource(`/api/containers/${name}/logs`);
  currentSource.onmessage = (e) => appendLog(e.data);
  currentSource.onerror = () => appendLog('[поток логов прерван]');
}

tabButtons.forEach((btn) => {
  btn.onclick = () => {
    tabButtons.forEach((b) => b.classList.remove('active'));
    btn.classList.add('active');
    currentTab = btn.dataset.name;
    openLogStream(currentTab);
  };
});

openLogStream(currentTab);

// --- Активные стримы + субтитры (asr-obs) ----------------------------------
// Имя стрима генерируется на телефоне заново каждую сессию — в отличие от
// текстового поля выше (для ручного OBS/QR), здесь опрашиваем сам SRS через
// панель (/api/streams), чтобы показать, что реально сейчас идёт в эфир.
const streamCardsEl = document.getElementById('streamCards');
let captionsState = {
  connected: false, streamName: null, overlayUrl: null, imageExists: false, buildStatus: 'idle', buildError: null,
  hostName: null, hasVoiceReference: false, enrollStatus: 'idle', enrollError: null,
};
let capBusy = false; // защита от повторного клика, пока предыдущее действие ещё в полёте

// Должно совпадать с ENROLL_DURATION_SEC в panel/server.js — тут используется только
// для текста кнопки, реальную длительность записи задаёт сервер.
const ENROLL_DURATION_SEC = 15;
const HOST_NAME_KEY = 'bondcast_host_name';
const hostNameInputEl = document.getElementById('hostNameInput');
hostNameInputEl.value = localStorage.getItem(HOST_NAME_KEY) || '';
hostNameInputEl.addEventListener('input', () => {
  localStorage.setItem(HOST_NAME_KEY, hostNameInputEl.value.trim());
});

// --- Настройки распознавания (asr_model, speaker_threshold) -----------------
// В отличие от оформления оверлея эти два реально влияют на то, как считает
// GPU-контейнер — но состояние тоже держим только в браузере (localStorage) и
// просто подмешиваем в тело POST /api/captions/connect при подключении, не
// заводя отдельный эндпоинт «сохранить настройки». Правило то же — меняются
// только при следующем подключении, не на лету у уже работающего контейнера.
const RECOGNITION_DEFAULTS = { asrModel: 'v3_e2e_rnnt', speakerThreshold: 0.25 };
const recognitionInputs = {
  asrModel: document.getElementById('asrModelSelect'),
  speakerThreshold: document.getElementById('speakerThreshold'),
};
const speakerThresholdOutEl = document.getElementById('speakerThresholdOut');
Object.keys(recognitionInputs).forEach((key) => {
  const el = recognitionInputs[key];
  const stored = localStorage.getItem(`bondcast_${key}`);
  el.value = stored !== null ? stored : RECOGNITION_DEFAULTS[key];
});
speakerThresholdOutEl.textContent = Number(recognitionInputs.speakerThreshold.value).toFixed(2);
recognitionInputs.asrModel.addEventListener('input', () => {
  localStorage.setItem('bondcast_asrModel', recognitionInputs.asrModel.value);
});
recognitionInputs.speakerThreshold.addEventListener('input', () => {
  localStorage.setItem('bondcast_speakerThreshold', recognitionInputs.speakerThreshold.value);
  speakerThresholdOutEl.textContent = Number(recognitionInputs.speakerThreshold.value).toFixed(2);
});

// --- Оформление оверлея ------------------------------------------------------
// Чисто клиентская настройка (overlay/index.html читает те же имена параметров
// из URL) — панели/докеру про них знать не нужно, поэтому без похода на сервер:
// значения хранятся в localStorage и подмешиваются в overlayUrl прямо в браузере.
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
    renderStreamCards(latestStreams); // перерисовать ссылку на оверлей с новыми параметрами
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
    return baseUrl; // overlayUrl ещё не пришёл с сервера (null) — просто ничего не подмешиваем
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
    // Модель/порог применяются только при пересоздании контейнера — «Применить»
    // делает это одним кликом (тот же /connect, что и подключение с нуля), не
    // заставляя сперва жать «Отключить».
    return (
      `<button class="cap-connect" data-name="${escapeHtml(streamName)}" title="Переподключить с текущими настройками распознавания">Применить настройки</button>` +
      '<button class="cap-disconnect primary">Субтитры: отключить</button>'
    );
  }
  return `<button class="cap-connect" data-name="${escapeHtml(streamName)}">Субтитры: подключить</button>`;
}

function enrollButtonHtml(streamName) {
  // Кнопка всегда видна (раньше пропадала совсем, пока не собран образ — выглядело
  // как «не работает»); без образа — просто задизейблена с понятной причиной,
  // а не молча исчезает.
  if (!captionsState.imageExists) {
    return '<button disabled title="Сначала собери образ — кнопка «Собрать образ для субтитров» справа">🎙 Записать голос ведущего</button>';
  }
  if (captionsState.enrollStatus === 'running') return '<button disabled>Идёт запись…</button>';
  const label = captionsState.hasVoiceReference ? 'Перезаписать голос' : 'Записать голос ведущего';
  return `<button class="cap-enroll" data-name="${escapeHtml(streamName)}" title="${ENROLL_DURATION_SEC} секунд — говорить должен только ведущий">🎙 ${label}</button>`;
}

function hostStatusHtml() {
  if (captionsState.enrollStatus === 'running') {
    return `<div class="row"><span class="row-label"><span class="row-meta">🎙 Запись голоса — говори ${ENROLL_DURATION_SEC}с без пауз, лог ниже</span></span></div>`;
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
    ${
      captionsState.connected && captionsState.streamName === s.name
        ? addrRow('Оверлей для OBS', buildOverlayUrl(captionsState.overlayUrl), 'Добавь как Browser Source в OBS — субтитры поверх видео. Оформление ниже.')
        : ''
    }`,
    )
    .join('');

  streamCardsEl.querySelectorAll('.watch-stream').forEach((btn) => {
    btn.onclick = () => {
      streamNameEl.value = btn.dataset.name;
      localStorage.setItem(STREAM_NAME_KEY, btn.dataset.name);
      refreshConnections();
      document.querySelector('details.advanced').open = true;
      loadVideo();
    };
  });
  streamCardsEl.querySelectorAll('.cap-build').forEach((btn) => { btn.onclick = buildCaptions; });
  streamCardsEl.querySelectorAll('.cap-connect').forEach((btn) => { btn.onclick = () => connectCaptions(btn.dataset.name); });
  streamCardsEl.querySelectorAll('.cap-disconnect').forEach((btn) => { btn.onclick = disconnectCaptions; });
  streamCardsEl.querySelectorAll('.cap-enroll').forEach((btn) => { btn.onclick = () => enrollHost(btn.dataset.name); });
  streamCardsEl.querySelectorAll('.copy-addr').forEach((btn) => {
    btn.onclick = () => {
      navigator.clipboard.writeText(btn.dataset.value);
      const original = btn.textContent;
      btn.textContent = 'Скопировано';
      setTimeout(() => { btn.textContent = original; }, 1200);
    };
  });
}

let latestStreams = []; // нужен loadVideo(), чтобы заранее знать кодек и не пытаться играть HEVC

async function refreshStreams() {
  try {
    const res = await fetch('/api/streams');
    const data = await res.json();
    latestStreams = data.streams || [];
    renderStreamCards(latestStreams);
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
        asrModel: recognitionInputs.asrModel.value,
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
  // Переиспользуем тот же <pre id="log"> и вкладки, что и логи контейнеров —
  // на время сборки образа источник просто временно переключается на неё.
  if (currentSource) currentSource.close();
  logLines = [];
  logEl.textContent = '';
  document.querySelector('details.advanced').open = true;
  currentSource = new EventSource('/api/captions/build/logs');
  currentSource.onmessage = (e) => appendLog(e.data);
  currentSource.addEventListener('done', () => {
    currentSource.close(); // сборка кончилась — дальше эндпоинту стримить нечего, не держим соединение
    pollStreamsAndCaptions();
  });
  // EventSource по умолчанию переподключается на любое закрытие соединения — тут это
  // не нужно (сборка не повторяется сама), close() останавливает автопереподключение.
  currentSource.onerror = () => {
    appendLog('[поток логов сборки прерван]');
    currentSource.close();
  };
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
    openEnrollLogStream();
  } catch (e) {
    alert(`Запись голоса: ${e.message}`);
  } finally {
    capBusy = false;
    pollStreamsAndCaptions();
  }
}

function openEnrollLogStream() {
  // Тот же приём, что и у сборки образа (openBuildLogStream) — тот же <pre id="log">,
  // временно переключённый на другой источник.
  if (currentSource) currentSource.close();
  logLines = [];
  logEl.textContent = '';
  document.querySelector('details.advanced').open = true;
  currentSource = new EventSource('/api/containers/asr-enroll/logs');
  currentSource.onmessage = (e) => appendLog(e.data);
  // Контейнер asr-enroll удаляется сразу после записи (см. server.js) — без close()
  // EventSource по умолчанию переподключался бы к уже несуществующему контейнеру
  // раз в ~3с бесконечно, спамя одну и ту же строку в лог (поймано вживую при тесте).
  currentSource.onerror = () => {
    appendLog('[поток логов записи прерван]');
    currentSource.close();
  };
}

pollStreamsAndCaptions();
setInterval(pollStreamsAndCaptions, 5000);

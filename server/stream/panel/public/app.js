// --- Утилиты ------------------------------------------------------------
function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

// Значки рисуем сами, а не эмодзи: эмодзи на разных системах выглядят по-разному,
// не красятся в цвет состояния и рядом с нашими линейными значками смотрятся чужими.
const ICONS = {
  phone: '<rect x="7" y="2.5" width="10" height="19" rx="2.5"/><path d="M11 18.6h2"/>',
  monitor: '<rect x="2.8" y="5" width="18.4" height="12" rx="2.5"/><path d="M8 20h8"/>',
  captions: '<rect x="3" y="5" width="18" height="14" rx="3"/><path d="M10.2 10.6a2.4 2.4 0 1 0 0 2.8"/><path d="M17.2 10.6a2.4 2.4 0 1 0 0 2.8"/>',
  sparkle: '<path d="M12 3.5l1.9 5 5 1.9-5 1.9-1.9 5-1.9-5-5-1.9 5-1.9z"/><path d="M18.5 15.5l.8 2 2 .8-2 .8-.8 2-.8-2-2-.8 2-.8z"/>',
  scene: '<path d="M3 7h4l10 10h4"/><path d="M17.5 4.5L21 8l-3.5 3.5"/><path d="M3 17h4"/><path d="M17.5 13.5L21 17l-3.5 3.5"/>',
  logs: '<path d="M12 4v11"/><path d="M7.5 11.5L12 16l4.5-4.5"/><path d="M4.5 19.5h15"/>',
  swap: '<path d="M4 8h15"/><path d="M15.5 4.5L19 8l-3.5 3.5"/><path d="M20 16H5"/><path d="M8.5 12.5L5 16l3.5 3.5"/>',
  users: '<circle cx="9" cy="8.5" r="3.2"/><path d="M3 19.5c.6-3.4 3-5 6-5s5.4 1.6 6 5"/><circle cx="17" cy="9.5" r="2.4"/><path d="M17.5 14.6c2 .3 3.2 1.8 3.6 4.4"/>',
  dice: '<rect x="4" y="4" width="16" height="16" rx="3.5"/><circle cx="9" cy="9" r=".6"/><circle cx="15" cy="9" r=".6"/><circle cx="12" cy="12" r=".6"/><circle cx="9" cy="15" r=".6"/><circle cx="15" cy="15" r=".6"/>',
  mic: '<rect x="9" y="3" width="6" height="11" rx="3"/><path d="M5.5 11.5a6.5 6.5 0 0 0 13 0"/><path d="M12 18v3"/>',
  redo: '<path d="M19.5 12a7.5 7.5 0 1 1-2.4-5.5"/><path d="M19.8 3.8v4.4h-4.4"/>',
  close: '<path d="M6 6l12 12"/><path d="M18 6L6 18"/>',
  check: '<path d="M5 12.5l4.5 4.5L19 7.5"/>',
};

function icon(name) {
  return `<svg class="ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${ICONS[name]}</svg>`;
}

// Значки в статичной вёрстке (index.html) расставляются по data-icon — чтобы сами
// контуры жили в одном месте.
document.querySelectorAll('[data-icon]').forEach((el) => { el.innerHTML = icon(el.dataset.icon); });

// Тумблер — только «включено / выключено», и он один на всю панель (.switch в
// index.html). Настоящая кнопка с role="switch": работает с клавиатуры и читалкой.
function switchHtml({ on, small, disabled, label, attrs = '' }) {
  return `<button type="button" class="switch${small ? ' sm' : ''}" role="switch" aria-checked="${on ? 'true' : 'false'}" aria-label="${escapeHtml(label)}"${disabled ? ' disabled' : ''} ${attrs}></button>`;
}

function setSwitch(el, on, disabled = false) {
  el.setAttribute('aria-checked', on ? 'true' : 'false');
  el.disabled = disabled;
}

function isSwitchOn(el) {
  return el.getAttribute('aria-checked') === 'true';
}

// Тумблер нажали, а включать пока нечего (не скачано, не выбрана сцена, нет OBS):
// встряхиваем и подсвечиваем ту строку, где написано, чего не хватает. Так
// понятнее, чем погашенный тумблер, который молча не нажимается.
function nudge(el) {
  if (!el) return;
  el.classList.remove('nudge');
  el.getBoundingClientRect(); // иначе повторное нажатие анимацию не перезапустит
  el.classList.add('nudge');
  el.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
}

// Перерисовываем узел, только если разметка реально поменялась: опросы идут раз в
// 3–5 секунд, и замена innerHTML под курсором съедала клик (нажал на старой
// кнопке, отпустил уже на новой) и сбрасывала наведение на подсказку «?».
const lastHtml = new WeakMap();
function setHtml(el, html) {
  if (lastHtml.get(el) === html) return false;
  lastHtml.set(el, html);
  el.innerHTML = html;
  return true;
}

// Содержимое блока поменялось: высота доезжает плавно, а новое проявляется, а не
// выскакивает одним кадром — иначе после щелчка по тумблеру непонятно, что именно
// на экране стало другим. Проявление — только когда блок реально перестроился
// (изменилась высота): цифры статуса обновляются каждые 3 секунды, им мигать незачем.
function swapHtml(el, html) {
  if (lastHtml.get(el) === html) return false;
  const before = el.offsetHeight;
  animateHeight(el, () => setHtml(el, html));
  if (before && Math.abs(el.scrollHeight - before) > 8) {
    el.classList.remove('swap-in');
    el.getBoundingClientRect(); // иначе анимация не перезапустится
    el.classList.add('swap-in');
  }
  return true;
}

// Путь до нужного экрана в приложении — одной строкой над блоком полей, а не
// повторно под каждым полем.
function pathHtml(text) {
  return `<div class="addr-path">${escapeHtml(text)}</div>`;
}

// Адрес с кнопкой «Копировать»: подпись сверху, адрес и кнопка в одну строку.
// where — короткая приписка, если у поля есть своя тонкость (напр. streamid у Larix).
// extraHtml — ещё одна кнопка в той же строке (кубик «другой ключ» у приглашения).
function addrRow(label, value, where, extraHtml = '') {
  return `
    <div class="addr">
      ${label ? `<span class="addr-label">${escapeHtml(label)}</span>` : ''}
      <div class="addr-line">
        <code>${escapeHtml(value)}</code>
        <button type="button" class="copy-addr" data-value="${escapeHtml(value)}">Копировать</button>${extraHtml}
      </div>
      ${where ? `<span class="addr-where">${escapeHtml(where)}</span>` : ''}
    </div>`;
}

function bindCopyButtons(root) {
  root.querySelectorAll('.copy-addr').forEach((btn) => {
    btn.onclick = () => {
      navigator.clipboard.writeText(btn.dataset.value);
      const original = btn.textContent;
      btn.textContent = 'Готово';
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

// --- Отправка ошибок страницы в лог панели ---------------------------------
// Сломавшийся фронт молчит: пользователь видит белый экран и не может ничего
// показать. Отправляем в тот же лог, что и всё остальное, — под именем "ui".
function reportClientError(message, stack) {
  try {
    fetch('/api/logs/client', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ message: String(message), stack: String(stack || ''), url: location.href }),
    }).catch(() => {});
  } catch (e) {
    // Панель недоступна — сообщать всё равно некуда, молчим.
  }
}

window.addEventListener('error', (e) => {
  reportClientError(e.message, e.error && e.error.stack);
});
window.addEventListener('unhandledrejection', (e) => {
  const reason = e.reason;
  reportClientError(reason && reason.message ? reason.message : String(reason), reason && reason.stack);
});

// --- Карта разделов слева ---------------------------------------------------
// Постоянный список разделов. Раньше пункты «Картинка в OBS», «Субтитры»,
// «Улучшение» и «Заглушка» висели под замком «нужен живой эфир» — хотя сервер
// живого эфира не требует нигде: заглушку, вид субтитров и разрешение выбирают
// заранее, а ссылка для OBS собирается из имени стрима. Теперь раздел открыт
// всегда, а внутри блокируется только то одно действие, которому чего-то не
// хватает (например, «Записать голос» без звука эфира), и причина написана рядом.
// Выбранный раздел переживает перезагрузку (localStorage).
const SECTION_KEY = 'bondcast_section';
// Живой лог — по-прежнему за адресом с #debug (запоминаем насовсем): сам пункт
// «Логи» доступен всегда, но рядовому стримеру хватает кнопки «Скачать логи».
const DEBUG_KEY = 'bondcast_debug';
if (location.hash === '#debug') localStorage.setItem(DEBUG_KEY, '1');
const debugEnabled = localStorage.getItem(DEBUG_KEY) === '1';

// group — заголовок группы перед пунктом, sep — разделительная линия перед ним.
// feature — у пункта есть тумблер (см. FEATURES ниже).
const SECTIONS = [
  { id: 'connect', icon: 'phone', title: 'Подключить телефон', group: 'Подключение' },
  { id: 'subs', icon: 'captions', title: 'Субтитры', sep: true, group: 'Функции', feature: true },
  { id: 'enhance', icon: 'sparkle', title: 'Улучшение картинки', feature: true },
  { id: 'scene', icon: 'scene', title: 'Заглушка при обрыве', feature: true },
  { id: 'logs', icon: 'logs', title: 'Логи', sep: true },
];

// Был ли эфир хоть раз за эту сессию — чтобы отличить «ещё не подключали» от
// «подключали, но сигнал пропал» (жёлтая точка у «Подключить телефон»).
let everLive = false;
// Объявлен здесь, а не рядом с опросом: чек-лист подключения (он выше по файлу)
// тоже смотрит, идёт ли эфир.
let latestStreams = [];
// applySceneSwitch()/renderSubs() зовут renderSectionNav() и сами вызываются ещё
// при разборе файла — а latestStreams/captionsState объявлены ниже через let и до
// конца разбора лежат в мёртвой зоне. Пока флаг не поднят, левую колонку не трогаем;
// поднимается он в самом низу app.js, там же идёт первый показ.
let navReady = false;

function hasLiveStream() {
  return latestStreams.length > 0;
}

function isLive(name) {
  return latestStreams.some((s) => s.name === name);
}

// Какой эфир берёт функция, если человек не выбирал сам: свой, если он идёт;
// иначе первый живой; иначе своё имя — оно есть всегда, функция его просто дождётся.
function defaultStream() {
  if (isLive(currentStreamName)) return currentStreamName;
  return latestStreams.length ? latestStreams[0].name : currentStreamName;
}

// Из чего выбирать: всё живое + своё имя (+ то, что функция уже держит, даже если
// оно сейчас не в эфире — иначе выбор молча «забывался» бы на обрыве).
function streamCandidates(held) {
  const names = latestStreams.map((s) => s.name);
  if (!names.includes(currentStreamName)) names.unshift(currentStreamName);
  if (held && !names.includes(held)) names.unshift(held);
  return names;
}

// Чипсы выбора эфира. Показываем, только когда выбирать есть из чего: обычно эфир
// один, и вопрос «какой?» был бы лишней строкой на экране.
function streamChipsHtml(names, active, attr) {
  if (names.length < 2) return '';
  const chips = names
    .map((name) => `<button type="button" class="chip${name === active ? ' is-active' : ''}" ${attr}="${escapeHtml(name)}"><span class="chip-dot${isLive(name) ? ' live' : ''}"></span>${escapeHtml(name)}</button>`)
    .join('');
  return `<div><label class="field-label">Какой эфир</label><div class="chip-row">${chips}</div></div>`;
}

const uiState = {
  section: SECTIONS.some((s) => s.id === localStorage.getItem(SECTION_KEY))
    ? localStorage.getItem(SECTION_KEY)
    : 'connect',
};

function setSection(id) {
  if (!SECTIONS.some((s) => s.id === id)) return;
  uiState.section = id;
  localStorage.setItem(SECTION_KEY, id);
  applySections();
  renderSectionNav();
}

function applySections() {
  if (!navReady) return;
  const diagCard = document.getElementById('diagCard');
  if (diagCard) diagCard.hidden = !debugEnabled;
  document.querySelectorAll('.section-panel').forEach((panel) => {
    panel.classList.toggle('active', panel.dataset.sectionPanel === uiState.section);
  });
}

// Состояние функции для меню — то же самое, что показывает тумблер в её карточке:
//   on      — тумблер включён;
//   setup   — включать пока нечего (не скачано): тумблера в меню нет, клик по
//             пункту ведёт в раздел, где кнопка «Скачать»;
//   why     — два-три слова под названием: чего функция ждёт прямо сейчас;
//   toggle  — что делает тумблер.
const FEATURES = {
  subs: () => ({
    on: subsOn(),
    setup: !captionsState.imageExists,
    busy: subsPending !== null,
    why: captionsState.buildStatus === 'building' ? 'скачиваю'
      : !captionsState.imageExists ? 'нужно скачать'
      : !subsOn() ? ''
      : !captionsState.ready ? 'готовлюсь'
      : !isLive(captionsState.streamName) ? 'жду эфир' : '',
    toggle: toggleSubs,
  }),
  enhance: () => ({
    on: enhanceOn(),
    setup: !enhanceState.imageExists || Boolean(enhanceState.imageOutdated),
    busy: enhancePending !== null,
    why: enhanceState.buildStatus === 'building' ? 'скачиваю'
      : !enhanceState.imageExists ? 'нужно скачать'
      : enhanceState.imageOutdated ? 'нужно обновить'
      : !enhanceOn() ? ''
      : !isLive(enhanceState.streamName) ? 'жду эфир'
      : !enhanceState.outputLive ? 'готовлюсь' : '',
    toggle: toggleEnhance,
  }),
  scene: () => ({
    on: sceneSwitcherState.enabled,
    setup: false,
    busy: false,
    why: sceneSwitcherState.state === 'switched' ? 'показываю заглушку' : '',
    toggle: toggleScene,
  }),
};

// Правый край строки: тумблер у функций (включается прямо из меню, не открывая
// раздел), точка у шагов подключения.
function navStatusHtml(section, feature) {
  if (feature) {
    if (feature.setup) return '';
    return switchHtml({ on: feature.on, small: true, disabled: feature.busy, label: section.title, attrs: `data-feature="${section.id}"` });
  }
  if (section.id === 'connect') {
    if (connectProblem()) return '<span class="nav-dot bad"></span>';
    if (hasLiveStream()) return '<span class="nav-dot"></span>';
    if (everLive) return '<span class="nav-dot warn"></span>';
  }
  return '';
}

function navWhyText(section, feature) {
  if (feature) return feature.why;
  if (section.id === 'connect') {
    const problem = connectProblem();
    if (problem) return problem;
    if (hasLiveStream()) return 'эфир идёт';
    if (everLive) return 'нет сигнала';
  }
  return '';
}

const sectionNavEl = document.getElementById('sectionNav');

function renderSectionNav() {
  if (!navReady) return;
  const html = SECTIONS.map((section) => {
    const feature = section.feature ? FEATURES[section.id]() : null;
    const why = navWhyText(section, feature);
    return [
      section.sep ? '<div class="nav-sep"></div>' : '',
      section.group ? `<div class="nav-group">${escapeHtml(section.group)}</div>` : '',
      `<div class="nav-item${uiState.section === section.id ? ' active' : ''}">`,
      `<button type="button" class="nav-btn" data-section="${section.id}">`,
      icon(section.icon),
      `<span class="nav-txt"><b>${escapeHtml(section.title)}</b>${why ? `<span class="nav-why">${escapeHtml(why)}</span>` : ''}</span>`,
      '</button>',
      navStatusHtml(section, feature),
      '</div>',
    ].join('');
  }).join('');
  if (!setHtml(sectionNavEl, html)) return;
  sectionNavEl.querySelectorAll('.nav-btn').forEach((btn) => {
    btn.onclick = () => setSection(btn.dataset.section);
  });
  sectionNavEl.querySelectorAll('[data-feature]').forEach((btn) => {
    btn.onclick = () => FEATURES[btn.dataset.feature]().toggle();
  });
}

// --- Достижимость портов снаружи (баннер + карточка) -----------------------
// Проверяем три порта: 5000/UDP (бондинг, srtla-rec), 10080/UDP (прямой SRT в
// SRS) и 4455/TCP (управление OBS по WebSocket, если телефон дёргает OBS не
// из той же локальной сети) — у стримера может быть открыт не весь набор
// (см. server/stream/README.md, раздел «Порт не виден снаружи — почему»), и это
// должно быть видно на панели сразу, а не выясняться потом руками через
// docker ps/роутер.
const PORTS_TO_CHECK = [
  { port: 5000, proto: 'udp', label: 'Приём с телефона' },
  { port: 10080, proto: 'udp', label: 'Приём видео' },
  { port: 4455, proto: 'tcp', label: 'Управление OBS', optional: true, note: 'управление OBS с улицы' },
  { port: 1935, proto: 'tcp', label: 'Приём видео от PRISM' },
  // Не блокирует публикацию (для неё хватает 1935) — но без него у RTMP-источника
  // (PRISM Live) нет рабочего способа предпросмотра в OBS: SRT-play в этой SRS-сборке
  // отдаёт кадры только для стримов, пришедших тоже по SRT, для RTMP-источника
  // остаётся только HTTP-FLV (см. obsPlayUrl) — а он идёт именно через этот порт.
  { port: 8080, proto: 'tcp', label: 'Картинка для OBS', optional: true, note: 'картинка в OBS от PRISM' },
];

// Результаты последней проверки хранятся здесь (не только рендерятся) — чек-лист
// шагов внутри каждого раскрытого сценария (см. «Три сценария начала стрима» ниже)
// читает их отсюда вместо отдельной общей карточки со списком портов.
let latestPortResults = [];

// Порты, по которым сейчас идёт (пере)проверка. Раньше renderPortChecking просто
// выкидывал прошлый результат порта из latestPortResults — и на время запроса к
// check-host.net шаг проваливался в спиннер и терял прошлый вердикт. Теперь
// прошлый результат остаётся на месте, порт лишь помечается «проверяю» — шаг
// показывает маленький спиннер, но сам ответ (открыт/закрыт) держится на прошлом
// результате, пока не придёт новый, поэтому шаг не мигает.
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
// Кнопка «Запустить OBS» внутри открывает bondcast-obs:// — протокол регистрирует
// установщик (installer/setup.iss, HKCU\Software\Classes\bondcast-obs), обработчик —
// launch-obs.ps1 рядом со start.bat. Панель сидит в Docker-контейнере и не может
// напрямую запустить .exe на хосте, только так. Если OBS ставили не через
// установщик Bondcast — протокол не зарегистрирован, и кнопка ничего не сделает.
const OBS_WEBSOCKET_HOWTO = `
  <details class="nested">
    <summary>Как включить</summary>
    <div class="body">
      <button type="button" class="primary" data-action="launch-obs">Запустить OBS</button>
      <ol>
        <li>В OBS открой <b>Tools → WebSocket Server Settings</b>.</li>
        <li>Включи <b>Enable WebSocket server</b>, порт <code>4455</code>.</li>
        <li><b>Enable Authentication</b> выключи. Включай, только если открываешь порт 4455 наружу.</li>
      </ol>
    </div>
  </details>`;

// Что в шаге нужно перезапустить — название ярлыка, как оно стоит на рабочем столе.
const RESTART = 'перезапусти ярлык «Запустить трансляцию»';

// Один шаг чек-листа. Слева либо номер — это делает человек, либо точка — это
// панель проверила сама. Раньше у обоих была синяя мигающая точка, и шаг
// читался как статус. Номер подставляет gateSteps() по порядку ({{n}}).
// title/meta/extra/right — готовая разметка.
const NUM = '<div class="num-step">{{n}}</div>';

function dot(kind) {
  return `<span class="dot ${kind}"></span>`;
}

function stepHtml(mark, title, meta = '', extra = '', right = '') {
  return `
    <div class="flow-step">
      ${mark}
      <div class="step-txt"><b>${title}</b>${meta ? `<span class="flow-step-meta">${meta}</span>` : ''}${extra}</div>
      ${right}
    </div>`;
}

// Шаг, с которым всё в порядке (или который необязателен), сжимается в одну строку:
// крупно на экране остаётся только то, где нужно что-то сделать. kind: 'ok' — галочка,
// иначе серая точка.
function compactStepHtml(kind, text, right = '', extra = '') {
  const mark = kind === 'ok' ? `<span class="step-check">${icon('check')}</span>` : dot('');
  return `
    <div class="flow-step is-compact">
      ${mark}
      <div class="step-txt">${text}${extra}</div>
      ${right}
    </div>`;
}

// Шаг с ошибкой: красный знак на линии и красная рамка — сломанное место видно
// сразу, не вчитываясь в подписи.
function errorStepHtml(title, meta = '', extra = '', right = '') {
  return `
    <div class="flow-step is-error">
      <span class="step-bang">!</span>
      <div class="step-txt"><b>${title}</b>${meta ? `<span class="flow-step-meta">${meta}</span>` : ''}${extra}</div>
      ${right}
    </div>`;
}

// Один шаг чек-листа внутри раскрытого сценария (замена бывшей общей карточки
// #portStatusCard со списком всех портов сразу) — берёт результат по номеру порта
// из latestPortResults, а не считает сам.
function portStepHtml({ port, proto, label, optional, note }) {
  const protoLabel = proto.toUpperCase();
  const obsExtras = port === 4455 ? OBS_WEBSOCKET_HOWTO : '';
  const found = latestPortResults.find((r) => r.meta.port === port);
  const title = `${escapeHtml(label)} · порт ${port}/${protoLabel}`;
  if (!found) return stepHtml('<span class="spinner"></span>', title, 'Проверяю…');
  const { data } = found;
  // Перепроверка — значок в правом углу шага: он относится к этому шагу и только
  // к нему. Пока идёт запрос, прошлый вердикт остаётся на месте (см. recheckingPorts).
  const recheckBtn = recheckingPorts.has(port)
    ? '<span class="spinner"></span>'
    : `<button type="button" class="btn-icon btn-ghost recheck-ports" data-port="${port}" title="Проверить снова">${icon('redo')}</button>`;
  if (data.error) {
    // Проверка не удалась — это НЕ «порт закрыт»: check-host.net мог не ответить или
    // упереться в свой лимит проверок. Но причины на этой машине (сервис не запущен,
    // порт не проброшен) видны и без внешней пробы — если такая нашлась, она куда
    // полезнее строчки «проверка не удалась». Сетевые версии тут наоборот не берём:
    // подкреплять их нечем, внешнего ответа-то и не было.
    const dx = diagnoseClosed({ port, proto }, data, latestPortResults);
    const localDx = dx.scope === 'local' ? dx : null;
    if (localDx) return errorStepHtml(title, escapeHtml(localDx.hint), obsExtras + (localDx.actions || '') + (localDx.howto || ''), recheckBtn);
    return stepHtml(dot('wait'), title, 'Проверка не удалась — попробуй ещё раз', obsExtras, recheckBtn);
  }
  if (data.reachable) return compactStepHtml('ok', `${escapeHtml(label)} · порт ${port} открыт`, recheckBtn);
  // У необязательного порта разбор причин не показываем: там ответ всегда один
  // («OBS не запущен или в нём выключен WebSocket»), и он уже лежит в раскрывашке.
  if (optional) return compactStepHtml('', escapeHtml(`Необязательно: ${note}`), recheckBtn, obsExtras);
  const dx = diagnoseClosed({ port, proto }, data, latestPortResults);
  // Если причина не на этой машине, а дома по Wi-Fi всё заработает и так — это
  // первая кнопка: один щелчок вместо похода в роутер.
  const lanEscape = dx.scope !== 'local' && activeFlowId !== 'invite' && availableScopes().includes('lan')
    ? '<button type="button" class="primary" style="margin-top:8px" data-netmode="lan">Стримить из дома, по Wi-Fi</button>'
    : '';
  return errorStepHtml(title, escapeHtml(dx.hint), lanEscape + obsExtras + (dx.actions || '') + (dx.howto || ''), recheckBtn);
}

// --- Почему порт закрыт -----------------------------------------------------
// Раньше веток было три: VPN, NAT и «всё остальное — выключи антивирус». Причём
// самая частая, домашняя, до пользователя не доходила: natLikely считался по
// HOST_IPS, куда start.bat кладёт ПУБЛИЧНЫЙ адрес, — значит «нужен проброс порта»
// почти никогда и не показывалось, показывалось «выключи антивирус». Плюс целый
// пласт причин вообще не рассматривался: сервис на этой машине может быть просто
// не запущен, порт может быть занят другой программой, адрес мог смениться после
// старта, а у мобильного оператора проброс невозможен в принципе.
//
// Теперь причины разложены по порядку — от ближней и точно проверяемой к дальней
// и предположительной. Первая совпавшая ветка и есть ответ: перечислять всё сразу
// значит отправить человека крутить роутер, когда у него просто лежит контейнер.
//
// Что мы знаем точно, а что — только предполагаем:
//   точно      — состояние контейнера и проброшен ли порт (спрашиваем Docker),
//                слушает ли порт сама машина (дозвон на host.docker.internal),
//                «пакет дошёл и получил отказ» против «пакет утонул» (verdict),
//                виден ли снаружи хоть один другой порт (кросс-проверка);
//   гипотеза   — VPN, мобильный оператор, серый IP: тут опора на репутацию адреса
//                у ip-api.com, поэтому такие ветки формулируются как версия.
//
// scope: 'local'   — причина на этой машине, чинится здесь и сейчас;
//        'address' — проверяется не тот адрес;
//        'network' — роутер/провайдер/фаервол по пути.

function detailsHtml(summary, bodyHtml) {
  return `<details class="nested" style="margin-top:8px"><summary>${escapeHtml(summary)}</summary><div class="body">${bodyHtml}</div></details>`;
}

function serviceFixButton(label, name, op, port) {
  return `<button type="button" class="primary" style="margin-top:8px" data-action="service-fix" data-name="${escapeHtml(name)}" data-op="${op}" data-port="${port}">${escapeHtml(label)}</button>`;
}

// Кросс-проверка по остальным портам: если снаружи виден ХОТЬ ОДИН порт — значит
// путь «интернет → эта машина» в принципе работает, и серый IP провайдера, двойной
// NAT и «фаервол режет всё подряд» отпадают сами собой. Остаётся причина уровня
// одного порта. Ключевое слово — «доказанно виден»: у UDP «открыт» означает всего
// лишь «нам не прислали ICMP-ошибку», и молчание фаервола выглядит точно так же,
// поэтому такой результат сервер помечает conclusive:false и здесь он не считается.
function confirmedOpenPort(results) {
  const found = results.find((r) => r.data.reachable && r.data.conclusive);
  return found ? found.meta.port : null;
}

// Кнопка прямо в веб-морду роутера. Адрес приходит с хоста (GATEWAY_IPS, реальный шлюз
// из таблицы маршрутов) — угадывать его нельзя, у одного 192.168.1.1, у другого
// 192.168.0.1 или 192.168.31.1. Ссылка ведёт в локальную сеть, поэтому открывает её
// браузер пользователя напрямую — панели проксировать нечего.
function routerLinkHtml(data) {
  const url = data.gatewayUrl || (data.gatewayIp ? `http://${data.gatewayIp}` : '');
  if (!url) {
    return `<p>Открой настройки роутера в браузере. Адрес написан на наклейке снизу роутера,
      чаще всего это <code>192.168.1.1</code> или <code>192.168.0.1</code>.</p>`;
  }
  // gatewayGuessed — адрес не пришёл с хоста, а выведен из LAN-адреса (последний октет → 1).
  // Обычно попадает, но обещать этого нельзя, поэтому рядом сразу сказано, где взять верный.
  const note = data.gatewayGuessed
    ? ' — адрес определён приблизительно; если страница не открылась, посмотри верный на наклейке снизу роутера'
    : '';
  return `
    <a class="btn primary" href="${escapeHtml(url)}" target="_blank" rel="noopener">Открыть настройки роутера</a>
    <div class="row-meta">${escapeHtml(url)}${escapeHtml(note)}</div>`;
}

// Без логина в роутер ссылка выше бесполезна, а пароль — первое, обо что спотыкаются:
// его либо не меняли (и он на наклейке), либо меняли и забыли. Reset упомянут честно,
// вместе с ценой: он сбрасывает и Wi-Fi, после него переподключать весь дом.
const ROUTER_LOGIN_HOWTO = `
  <details class="nested">
    <summary>Роутер просит логин и пароль</summary>
    <div class="body">
      <ul>
        <li>Чаще всего они на той же наклейке снизу роутера, где адрес и имя Wi-Fi. Типовые пары:
          <code>admin</code>/<code>admin</code>, <code>admin</code>/<code>password</code>,
          <code>admin</code> и пустой пароль.</li>
        <li>Если пароль меняли при настройке — спроси того, кто настраивал. Если роутер выдал
          провайдер, пароль знает их поддержка — и часто она может пробросить порт сама, по заявке:
          так и скажи: «нужно пробросить порт на компьютер», и назови номер из строки выше.</li>
        <li>Reset на корпусе вернёт заводской пароль, но заодно сбросит Wi-Fi и настройки
          провайдера — переподключать придётся весь дом. Крайняя мера.</li>
      </ul>
    </div>
  </details>`;

// Раздел проброса называется у всех по-разному, и это ровно то место, где человек
// застревает, уже зайдя в роутер.
const ROUTER_MENU_HINTS = `
  <details class="nested">
    <summary>Где искать проброс портов у разных роутеров</summary>
    <div class="body">
      <ul>
        <li><b>TP-Link</b> — Дополнительные настройки → NAT-переадресация → Виртуальные серверы</li>
        <li><b>Keenetic / Zyxel</b> — Сетевые правила → Переадресация портов</li>
        <li><b>Xiaomi / Redmi</b> — Расширенные настройки → Переадресация портов</li>
        <li><b>ASUS</b> — Интернет → Переадресация портов</li>
        <li><b>D-Link</b> — Межсетевой экран → Виртуальные серверы</li>
        <li><b>MikroTik</b> — IP → Firewall → NAT, правило <code>dst-nat</code></li>
        <li><b>Роутеры провайдера (Huawei, ZTE, Sagemcom)</b> — Forward Rules → Port Mapping;
          бывает, что раздел виден только под «расширенным» логином, который есть у поддержки</li>
      </ul>
      <p>Синонимы в меню: Port Forwarding, Virtual Server, NAT, Переадресация портов, Виртуальные серверы.</p>
    </div>
  </details>`;

// Инструкция по пробросу — общая для веток «до машины вообще не пускают» и «пускают,
// но не на этот порт». Local IP берём именно LAN-адрес (LAN_IPS из start.bat): раньше
// сюда подставлялся localIp, а это в домашнем случае публичный адрес — в правило
// роутера его вписывать бессмысленно.
function forwardingHowtoHtml(port, proto, data) {
  const protoLabel = proto.toUpperCase();
  const lanIp = data.lanIp;
  const localIpLine = lanIp
    ? `Local IP — <code>${escapeHtml(lanIp)}</code>`
    : 'Local IP — адрес этой машины в локальной сети (<code>ipconfig</code> в командной строке, строка IPv4)';
  return `
    ${routerLinkHtml(data)}
    ${ROUTER_LOGIN_HOWTO}
    <ol>
      <li>Открой настройки роутера кнопкой выше.</li>
      <li>Найди раздел <b>Переадресация портов</b> (Port Forwarding, NAT, Virtual Server).
        ${ROUTER_MENU_HINTS}</li>
      <li>Добавь правило: ${localIpLine}, порт — <code>${port}</code>, протокол — <b>${protoLabel}</b>
        (если отдельного ${protoLabel} нет, выбери «Both»).</li>
      <li>Если правило уже есть, а порт всё равно закрыт — это частые ошибки в самом правиле:
        <ul>
          <li><b>Протокол не тот.</b> ${protoLabel === 'UDP' ? 'SRT и бондинг ходят только по UDP — правило с одним TCP не сработает.' : 'Этому порту нужен именно TCP.'}</li>
          <li><b>Public (WAN) порт ≠ Local порт.</b> Легко скопировать рабочее правило для
            одного порта и забыть поменять внешний порт у копии — тогда снаружи по-прежнему
            открыт старый порт, а новый никуда не проброшен.</li>
          <li><b>Заполнено поле «Remote Host».</b> Если там стоит конкретный IP (например, по
            ошибке — публичный IP самого роутера), правило примет подключения только с него и
            отбросит реальный трафик со телефона. Это поле должно быть пустым.</li>
          <li><b>Правило ведёт на старый адрес.</b> Если у этой машины нет DHCP-резервации,
            её локальный адрес меняется после перезагрузки роутера, и правило начинает
            указывать в никуда${lanIp ? ` — сейчас машина занимает <code>${escapeHtml(lanIp)}</code>` : ''}.
            В настройках роутера найди «DHCP → Address Reservation» и закрепи адрес за этой машиной.</li>
        </ul>
      </li>
      <li>Сохрани и нажми «Проверить снова».</li>
    </ol>`;
}

// Проверка «а белый ли у тебя вообще адрес» — сам сервер этого не видит: снаружи
// и серый абонент за CGNAT, и белый выглядят одинаково (у обоих ipify отдаёт
// публичный адрес, просто у первого он общий на тысячи человек). Единственный
// надёжный способ — посмотреть WAN-адрес в самом роутере и сравнить.
function grayIpHowtoHtml(data) {
  return `
    ${routerLinkHtml(data)}
    <ol>
      <li>Открой настройки роутера и найди страницу <b>Статус / WAN / Информация о подключении</b>.</li>
      <li>Посмотри <b>WAN IP</b> (внешний адрес самого роутера) и сравни с адресом, по которому
        стучится панель — <code>${escapeHtml(data.targetIp || '—')}</code>.</li>
      <li><b>Совпадает</b> — адрес белый, проброс портов работать может, причина в другом
        (правило проброса, фаервол).</li>
      <li><b>Не совпадает</b>, а WAN-адрес начинается на <code>100.64</code>…<code>100.127</code>,
        <code>10.</code>, <code>172.16</code>…<code>172.31</code> или <code>192.168</code> — адрес серый:
        <ul>
          <li>Роутер стоит за <b>ещё одним роутером</b> (модем/приставка провайдера) — пробрось порт
            на обоих устройствах или переведи модем провайдера в режим моста (bridge).</li>
          <li>Либо тебя посадил за общий NAT <b>провайдер</b> (CGNAT). Пробрасывать нечего:
            закажи у провайдера «белый» (публичный) IP — обычно это платная услуга,
            либо подними приёмник на арендованном сервере (VPS) и стримь на него.</li>
        </ul>
      </li>
    </ol>`;
}

function firewallHowtoHtml(port, proto) {
  return `
    <ol>
      <li><b>Сеть помечена как «Общедоступная».</b> Параметры Windows → Сеть и Интернет → своё
        подключение → <b>Тип сетевого профиля: Частная</b>. В общедоступном профиле Windows режет
        входящие подключения жёстче, и разрешающее правило может просто не действовать.</li>
      <li><b>Docker не разрешён в брандмауэре.</b> Панель управления → Брандмауэр Защитника Windows →
        «Разрешение взаимодействия с приложением» → найди <b>Docker Desktop Backend</b>
        (<code>com.docker.backend.exe</code>) и поставь галки для частной и публичной сети.
        Если при первом запуске Docker окно с запросом закрыли крестиком — Windows запомнил это
        как запрет, и порт не виден снаружи ничем не выдавая себя.</li>
      <li><b>Сторонний антивирус со своим фаерволом</b> (Kaspersky, ESET, Avast, Comodo, Bitdefender)
        имеет собственные правила, независимые от брандмауэра Windows. Выключи его сетевой экран
        на минуту и нажми «Проверить снова» — если порт открылся, добавь разрешение
        <code>${port}/${proto.toUpperCase()}</code> в правила антивируса, а не оставляй его выключенным.</li>
      <li><b>Правило прямо на порт.</b> Если возиться с приложениями не хочется, в PowerShell
        от администратора:
        <br><code>New-NetFirewallRule -DisplayName "Bondcast ${port}" -Direction Inbound -Protocol ${proto.toUpperCase()} -LocalPort ${port} -Action Allow</code></li>
    </ol>`;
}

function diagnoseClosed({ port, proto }, data, results) {
  const protoLabel = proto.toUpperCase();
  const local = data.local || {};
  const svc = local.container;

  // --- Ближний круг: эта самая машина ---------------------------------------
  if (local.dockerError) {
    return {
      scope: 'local',
      hint: `Docker не отвечает — открой Docker Desktop и ${RESTART}`,
    };
  }

  if (svc && local.found === false) {
    return {
      scope: 'local',
      hint: `Сервис ${svc} удалён`,
      actions: serviceFixButton(`Создать и запустить ${svc}`, svc, 'recreate', port),
      howto: detailsHtml('Кнопка не сработала', '<p>Запусти ярлык «Запустить трансляцию» — он пересоздаёт все сервисы с нуля.</p>'),
    };
  }

  if (svc && local.found && !local.running) {
    const err = String(local.error || '');
    // Docker записывает сюда причину неудачного старта. Два случая стоят
    // отдельного разбора — иначе человек читает «контейнер не запустился» и идёт
    // в роутер, хотя проблема в занятом порте на самой машине.
    if (/already allocated|address already in use|bind for/i.test(err)) {
      return {
        scope: 'local',
        hint: `Порт ${port} занят другой программой`,
        howto: detailsHtml('Как найти и освободить порт', `
          <ol>
            <li>В PowerShell: <code>Get-NetUDPEndpoint -LocalPort ${port}</code> для UDP или
              <code>Get-NetTCPConnection -LocalPort ${port} -State Listen</code> для TCP —
              в ответе будет <code>OwningProcess</code> (номер процесса).</li>
            <li>Кто это: <code>Get-Process -Id &lt;номер&gt;</code>.</li>
            <li>Частые виновники: второй запущенный экземпляр этого же сервера, свой
              srtla/SRS вне Docker, OBS с включённым сервером, NDI Tools, IIS.</li>
          </ol>
          <p style="opacity:.75">Ошибка Docker: <code>${escapeHtml(err.slice(0, 200))}</code></p>`),
      };
    }
    if (/forbidden by its access permissions|access permissions|excluded port/i.test(err)) {
      return {
        scope: 'local',
        hint: `Windows зарезервировал порт ${port} под себя`,
        howto: detailsHtml('Как вернуть порт себе', `
          <ol>
            <li>Проверь, попал ли порт в резерв (PowerShell от администратора):
              <br><code>netsh int ipv4 show excludedportrange protocol=${proto}</code></li>
            <li>Если ${port} внутри одного из диапазонов — сбрось резерв:
              <br><code>net stop winnat</code> → <code>net start winnat</code>
              <br>Резерв выдаётся заново при каждой загрузке, поэтому после перезагрузки
              порт может снова уехать — тогда закрепи его за собой один раз:
              <br><code>netsh int ipv4 add excludedportrange protocol=${proto} startport=${port} numberofports=1 store=persistent</code></li>
            <li>Запусти ярлык «Запустить трансляцию» заново.</li>
          </ol>
          <p style="opacity:.75">Ошибка Docker: <code>${escapeHtml(err.slice(0, 200))}</code></p>`),
      };
    }
    if (local.state === 'restarting' || local.restartCount > 3) {
      return {
        scope: 'local',
        hint: `Сервис ${svc} падает по кругу — скачай логи`,
        howto: detailsHtml('Частые причины', '<p>Занятый порт или испорченный <code>srs.conf</code> — второе лечится ярлыком «Запустить трансляцию», он пересобирает контейнеры с нуля.</p>'),
      };
    }
    return {
      scope: 'local',
      hint: `Сервис ${svc} остановлен`,
      actions: serviceFixButton(`Запустить ${svc}`, svc, 'start', port),
      howto: err ? detailsHtml('Что говорит Docker', `<p><code>${escapeHtml(err.slice(0, 300))}</code></p>`) : '',
    };
  }

  if (svc && local.running && local.published === false) {
    return {
      scope: 'local',
      hint: `Порт ${port} не отдан наружу — ${RESTART}`,
    };
  }

  // Дозвон контейнер → хост. Для 4455 это главный вопрос вообще: OBS не запущен
  // или в нём не включён WebSocket — снаружи оба случая выглядят как закрытый порт,
  // а лечатся совсем не роутером (шпаргалка и кнопка запуска уже стоят рядом).
  if (local.hostListening === false) {
    return {
      scope: 'local',
      hint: port === 4455
        ? 'OBS не слушает порт — включи в нём WebSocket'
        : `Порт ${port} никто не слушает — ${RESTART}`,
    };
  }

  // --- Тот ли это вообще адрес ----------------------------------------------
  if (data.ipChanged) {
    return {
      scope: 'address',
      hint: `Адрес сменился на ${data.freshPublicIp} — ${RESTART}`,
      howto: detailsHtml('Повторяется каждый раз', '<p>Провайдер выдаёт динамический адрес: закажи статический IP или настрой DDNS — иначе телефон будет терять сервер после каждой смены.</p>'),
    };
  }

  // hosting НЕ отличает "свой VPS" от "включён VPN": почти любой коммерческий
  // VPN-выход тоже стоит в дата-центре и приходит с hosting:true. Замеры по ip-api:
  // Hetzner/DigitalOcean/Scaleway/Google — proxy:false + hosting:true, а реальный
  // VPN-выход (FranTech) — proxy:true + hosting:true. То есть proxy:true сам по себе
  // и есть признак VPN/прокси, а hosting лишь говорит "адрес дата-центра".
  // Одна строка с действием вместо разбора всех «а если». Человеку с включённым
  // VPN нужно ровно одно — выключить его; остальные ветки (проброс к VPN-адресу
  // бесполезен, метка может быть ложной на своём VPS) он читать не будет, а экран
  // они забивают. Не помогло — «Проверить снова» приведёт к следующему диагнозу.
  if (data.vpnLikely) {
    return {
      scope: 'address',
      hint: `Похоже, включён VPN — выключи его и ${RESTART}`,
    };
  }

  // Мобильный оператор — единственный случай, где честный ответ «сделать нельзя
  // ничего». Лучше сказать это сразу, чем отправить человека переставлять галки
  // в роутере, который тут ни на что не влияет.
  if (data.mobileLikely) {
    return {
      scope: 'network',
      hint: `Мобильный интернет${data.isp ? ` (${data.isp})` : ''} — порт не открыть, нужен проводной`,
      howto: detailsHtml('Если проводного нет', `
        <ul>
          <li>Заказать у оператора «белый / публичный IP» — обычно платная услуга в личном кабинете.</li>
          <li>Поднять приём бондинга на арендованном сервере (VPS), а домой забирать готовый поток.</li>
        </ul>`),
    };
  }

  // --- Дальний круг: путь снаружи -------------------------------------------
  // "refused" — самый информативный ответ из всех: пакет ДОШЁЛ до адреса и получил
  // явный отказ. Значит и маршрут, и проброс до какой-то машины работают, просто
  // на той стороне никто не слушает. Роутер тут чинить нечего — чинить надо адрес
  // назначения в правиле.
  if (data.verdict === 'refused') {
    return {
      scope: 'network',
      hint: 'Правило в роутере ведёт не на этот компьютер',
      howto: detailsHtml('Что проверить', `
        <ol>
          <li>Если правило проброса на роутере есть — оно ведёт не на ту машину.
            Сверь в нём Local IP с адресом этого компьютера${data.lanIp ? `: сейчас это <code>${escapeHtml(data.lanIp)}</code>` : ''}.
            Адрес мог смениться после перезагрузки — закрепи его в роутере
            (DHCP → Address Reservation).</li>
          <li>Сверь протокол: правило должно быть на <b>${protoLabel}</b>, а не на «другой» —
            ${protoLabel === 'UDP' ? 'SRT ходит только по UDP' : 'этому порту нужен TCP'}.</li>
          <li>Если правила нет вовсе — отказ прислал сам роутер. Заведи правило:</li>
        </ol>
        ${forwardingHowtoHtml(port, proto, data)}`),
    };
  }

  // Машина с публичным адресом напрямую — роутера между ней и интернетом нет,
  // значит и пробрасывать нечего, вся оборона на самой машине (и на фаерволе
  // хостинга, если это VPS).
  if (data.lanIp && !data.natLikely) {
    return {
      scope: 'network',
      hint: 'Порт режет фаервол компьютера или хостинга',
      howto: detailsHtml('Что проверить', `
        <ol>
          <li>Фаервол хостинга/облака — отдельный слой поверх машины, о нём забывают чаще
            всего: security group в AWS, «Ingress rules» в Oracle Cloud, «Firewall» в панели
            Hetzner/Selectel/DigitalOcean. Правило нужно и там тоже.</li>
          <li>Фаервол самой машины: на Windows — см. ниже, на Linux —
            <code>ufw allow ${port}/${proto}</code> или соответствующее правило iptables.</li>
        </ol>
        ${firewallHowtoHtml(port, proto)}`),
    };
  }

  const openPort = confirmedOpenPort(results);
  if (openPort) {
    return {
      scope: 'network',
      hint: `Порт ${openPort} открыт, этот — нет: проверь правило в роутере`,
      howto: detailsHtml('Что проверить', `
        ${forwardingHowtoHtml(port, proto, data)}
        <p>Если правило точно верное — остаётся фаервол с правилом на конкретный порт:</p>
        ${firewallHowtoHtml(port, proto)}`),
    };
  }

  return {
    scope: 'network',
    hint: 'Снаружи не доходит ничего — начни с роутера',
    howto:
      detailsHtml('1. Проброс порта на роутере', forwardingHowtoHtml(port, proto, data)) +
      detailsHtml('2. Белый ли у тебя адрес', grayIpHowtoHtml(data)) +
      detailsHtml('3. Фаервол Windows и антивирус', firewallHowtoHtml(port, proto)) +
      detailsHtml('4. Если ничего из этого', `
        <p>Некоторые провайдеры режут входящие подключения на домашних тарифах — звони
        провайдеру и проси снять ограничение или дать «белый IP» отдельной услугой.</p>`),
  };
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

// Кнопка из диагноза «сервис не запущен / контейнера нет» — чинит причину прямо
// отсюда, вместо «иди перезапусти ярлык». Панель уже умеет и то, и другое
// (/api/containers/:name/start и /recreate), просто до сих пор эти ручки никто
// не дёргал из UI: вкладку «Сервисы» убрали, а причина осталась.
async function runServiceFix(btn) {
  const { name, op, port } = btn.dataset;
  const original = btn.textContent;
  btn.disabled = true;
  btn.textContent = op === 'recreate' ? 'Создаю…' : 'Запускаю…';
  btn.parentNode.querySelectorAll('.service-fix-error').forEach((el) => el.remove());
  try {
    const res = await fetch(`/api/containers/${encodeURIComponent(name)}/${op}`, { method: 'POST' });
    const body = await res.json().catch(() => ({}));
    if (!res.ok) throw new Error(body.error || `сервер ответил ${res.status}`);
    // Сервис поднимается не мгновенно — перепроверяем именно этот порт (дважды, с
    // запасом), чтобы шаг позеленел сам, без ручного «Проверить снова».
    const meta = PORTS_TO_CHECK.find((p) => p.port === Number(port));
    [1500, 6000].forEach((delay) => setTimeout(() => checkPort(meta ? [meta] : undefined), delay));
  } catch (e) {
    btn.disabled = false;
    btn.textContent = original;
    btn.insertAdjacentHTML('afterend', `<div class="flow-warn service-fix-error">${escapeHtml(e.message)}</div>`);
  }
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

  const fixBtn = e.target.closest('[data-action="service-fix"]');
  if (fixBtn) {
    e.preventDefault();
    runServiceFix(fixBtn);
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
let previewLiveTimer = null;
let previewRetryTimer = null;
let previewWatchdogTimer = null;

// Снос всего, что живёт вокруг <video>: сам плеер и три таймера. Вызывается и при
// закрытии окна, и перед каждым перезапуском — иначе старые тикеры продолжали бы
// дёргать уже удалённый элемент, а мёртвый плеер держал бы сокет.
function teardownPreviewPlayer() {
  if (activePreviewPlayer) {
    // destroy() у mpegts.js кидается, если плеер уже помер сам (например, после
    // NetworkError) — для нас это штатный путь, глушим
    try { activePreviewPlayer.destroy(); } catch (e) { /* уже мёртв */ }
    activePreviewPlayer = null;
  }
  clearInterval(previewLiveTimer);
  clearInterval(previewWatchdogTimer);
  clearTimeout(previewRetryTimer);
  previewLiveTimer = null;
  previewWatchdogTimer = null;
  previewRetryTimer = null;
}

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
    statsEl.textContent = 'Эфира нет';
    return;
  }
  // Стримеру хватает «какая картинка и сколько доходит». Кодеки и счётчики байт —
  // для разбора полётов, они остаются за #debug.
  const size = s.video ? `${s.video.width}×${s.video.height} · ` : '';
  const debug = debugEnabled
    ? `<br>${escapeHtml(formatCodec(s.video, s.audio))} · отдача ${escapeHtml(formatKbps(s.kbpsSend30s))}<br>
    Принято ${escapeHtml(formatBytes(s.recvBytes))} · отдано ${escapeHtml(formatBytes(s.sendBytes))}`
    : '';
  statsEl.innerHTML = `${escapeHtml(size + formatKbps(s.kbpsRecv30s))}${debug}`;
}

async function openPreview(name) {
  teardownPreviewPlayer();
  currentPreviewName = name;
  previewPanelEl.innerHTML = `
    <div class="page-preview-head">
      <b>${escapeHtml(name)}</b>
      <button type="button" class="btn-icon btn-ghost page-preview-close" title="Закрыть">${icon('close')}</button>
    </div>
    <video id="previewVideo" width="100%" autoplay muted playsinline></video>
    <div class="page-preview-bar">
      <button type="button" id="previewMute">Включить звук</button>
      <button type="button" id="previewFull">Во весь экран</button>
      <span class="live-chip" id="previewLive">В ЭФИРЕ</span>
    </div>
    <div class="row-meta" id="previewStatus" style="margin-top:8px">Подключаюсь…</div>
    <div class="row-meta" id="previewStats" style="margin-top:8px"></div>`;
  previewPanelEl.querySelector('.page-preview-close').onclick = closePreview;
  previewPanelEl.hidden = false;
  pageEl.classList.add('has-preview');
  updatePreviewStats();

  const video = document.getElementById('previewVideo');
  const muteBtn = document.getElementById('previewMute');
  muteBtn.onclick = () => {
    video.muted = !video.muted;
    muteBtn.textContent = video.muted ? 'Включить звук' : 'Выключить звук';
  };
  document.getElementById('previewFull').onclick = () => {
    if (document.fullscreenElement) document.exitFullscreen();
    else video.requestFullscreen?.();
  };

  startPreviewPlayer(name);
}

// Подушка буфера. Первая версия догоняла край впритык (0.4 с) — плеер съедал буфер
// досуха, вставал с readyState=2 и сам уже не оживал: картинка замирала каждые
// ~15 секунд. Секунда запаса гасит и неровные метки в потоке, и то, что сервер
// отдаёт данные пачками, а догоняем только когда отстали по-настоящему.
const LIVE_MAX_LAG = 3.0;   // с — при каком отставании догоняем живой край
const LIVE_KEEP = 1.0;      // с — сколько буфера оставляем себе после прыжка
const NUDGE_AFTER_MS = 2500; // столько стоим на месте, прежде чем подтолкнуть плеер
const STALL_LIMIT_MS = 8000; // ... и столько, прежде чем пересоздать его целиком
const RETRY_MS = 2000;       // пауза между попытками переподключиться

// Поднимает плеер поверх уже нарисованного окна. Отдельно от openPreview(), потому
// что пересоздавать приходится часто: HTTP-FLV рвётся на каждой остановке
// трансляции, и mpegts.js после NetworkError сам не восстанавливается — раньше окно
// просто застывало на последнем кадре навсегда, даже когда эфир возвращался.
async function startPreviewPlayer(name) {
  const statusEl = document.getElementById('previewStatus');
  const video = document.getElementById('previewVideo');
  const liveChip = document.getElementById('previewLive');
  if (!statusEl || !video) return; // окно закрыли
  statusEl.hidden = false;

  // Перезапуск, но только пока окно открыто и показывает ТОТ ЖЕ стрим: иначе после
  // закрытия окна или переключения на соседний стрим таймер поднимал бы плеер заново.
  const scheduleRetry = (why) => {
    if (currentPreviewName !== name || !document.getElementById('previewVideo')) return;
    teardownPreviewPlayer();
    statusEl.hidden = false;
    statusEl.textContent = `${why} Подключаюсь заново…`;
    if (liveChip) liveChip.classList.remove('is-live');
    previewRetryTimer = setTimeout(() => startPreviewPlayer(name), RETRY_MS);
  };

  video.addEventListener('playing', () => { statusEl.hidden = true; }, { once: true });

  // Догоняем живой край. Само оно там не держится: любая микрозадержка (сеть,
  // декодер, свёрнутая вкладка) оставляет плеер позади, и дальше он играет прошлое.
  // Порог не нулевой: метки времени в потоке идут неровно, и погоня за самым краем
  // превратилась бы в непрерывные рывки.
  const jumpToLive = () => {
    const b = video.buffered;
    if (!b.length) return;
    const edge = b.end(b.length - 1);
    const lag = edge - video.currentTime;
    // Прыгаем и когда просто отстали, и когда currentTime вывалился из буфера
    // (после разрыва такое бывает) — во втором случае lag отрицательный.
    if (lag > LIVE_MAX_LAG || lag < 0) {
      video.currentTime = Math.max(edge - LIVE_KEEP, b.start(b.length - 1));
    }
    if (liveChip) liveChip.classList.toggle('is-live', lag >= 0 && lag <= LIVE_MAX_LAG);
  };
  previewLiveTimer = setInterval(jumpToLive, 1000);
  video.addEventListener('waiting', () => setTimeout(jumpToLive, 200));

  // Сторож на случай, когда ошибки нет, а картинка стоит: video.error пустой,
  // paused=false, а currentTime не двигается. Проверять readyState тут нельзя —
  // при заморозке он как раз и падает, и сторож ослеп бы ровно тогда, когда нужен.
  //
  // Две ступени, потому что причины разные. Чаще всего плеер просто упёрся в конец
  // буфера и не возобновился сам, хотя данные уже подъехали — тогда хватает
  // микроперемотки, она незаметна. И только если и это не помогло, пересоздаём
  // плеер целиком: это дороже, с чёрным кадром и переподключением.
  let lastCt = -1;
  let stillSince = Date.now();
  let nudged = false;
  previewWatchdogTimer = setInterval(() => {
    if (video.paused) { stillSince = Date.now(); return; }
    if (Math.abs(video.currentTime - lastCt) > 0.01) {
      lastCt = video.currentTime;
      stillSince = Date.now();
      nudged = false;
      return;
    }
    const still = Date.now() - stillSince;
    const b = video.buffered;
    const ahead = b.length ? b.end(b.length - 1) - video.currentTime : 0;
    if (still > STALL_LIMIT_MS) {
      scheduleRetry('Картинка замерла.');
    } else if (still > NUDGE_AFTER_MS && !nudged && ahead > 0.1) {
      nudged = true;
      video.currentTime = video.currentTime + Math.min(ahead, 0.2);
    }
  }, 1000);

  try {
    const mpegts = await loadMpegts();
    if (currentPreviewName !== name || !document.getElementById('previewVideo')) return;
    if (!mpegts.getFeatureList().mseLivePlayback) {
      statusEl.textContent = 'Этот браузер не покажет эфир — открой Chrome или Edge';
      return;
    }
    activePreviewPlayer = mpegts.createPlayer({
      type: 'flv', url: directFlvUrl(name),
      isLive: true,
      enableStashBuffer: false,
      // Штатная догонялка mpegts.js. Раньше здесь стояло liveSync: true — такой
      // опции в mpegts.js 1.7.3 нет вообще (её ключи начинаются на
      // liveBufferLatency*), поэтому строка ничего не делала и задержка росла
      // молча. Порог держим одинаковым с jumpToLive() выше, чтобы две догонялки
      // не дёргали друг друга.
      liveBufferLatencyChasing: true,
      liveBufferLatencyMaxLatency: LIVE_MAX_LAG,
      liveBufferLatencyMinRemain: LIVE_KEEP,
      // Предпросмотр держат открытым часами — без чистки SourceBuffer растёт до
      // упора, и Chrome начинает вычищать его сам, рывками.
      autoCleanupSourceBuffer: true,
    });
    // Любая ошибка = перезапуск. Самая частая — NetworkError/UnrecoverableEarlyEof:
    // её отдаёт КАЖДАЯ остановка трансляции, потому что HTTP-FLV просто обрывается.
    activePreviewPlayer.on(mpegts.Events.ERROR, (type, detail) => {
      scheduleRetry('Картинка оборвалась.');
    });
    activePreviewPlayer.attachMediaElement(video);
    activePreviewPlayer.load();
    activePreviewPlayer.play();
  } catch (e) {
    scheduleRetry('Не удалось показать картинку.');
  }
}

function closePreview() {
  currentPreviewName = null; // ПЕРВЫМ: по нему scheduleRetry() понимает, что перезапускать больше нечего
  teardownPreviewPlayer();
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
// Пока первый ответ не пришёл, адреса не «нет» — его ещё не спросили. Без этого
// флага страница открывалась с красным «Адрес компьютера неизвестен», которое
// через секунду само пропадало (а в «Картинке в OBS» висело до следующего опроса).
let hostsLoaded = false;
let currentStreamName = getOrCreateStreamName();

async function refreshConnections() {
  try {
    const res = await fetch(`/api/connections?name=${encodeURIComponent(currentStreamName)}`);
    const data = await res.json();
    latestHosts = data.hosts || [];
  } catch (e) {
    latestHosts = [];
  }
  hostsLoaded = true;
  renderFlowList();
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
let inviteHostsLoaded = false;

async function refreshInviteConnections() {
  try {
    const res = await fetch(`/api/connections?name=${encodeURIComponent(inviteStreamName)}`);
    const data = await res.json();
    inviteHosts = data.hosts || [];
  } catch (e) {
    inviteHosts = [];
  }
  inviteHostsLoaded = true;
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
// Заголовки держим короткими: три плашки стоят в ряд, и перенос у одной делает
// весь ряд разной высоты — читается как поломка вёрстки, а не как текст.
const FLOWS = [
  { id: 'bondcast', icon: 'phone', title: 'Через Bondcast', hint: 'Проще всего' },
  { id: 'other-app', icon: 'swap', title: 'Другое приложение', hint: 'Moblin, Larix, PRISM' },
  { id: 'invite', icon: 'users', title: 'Компьютер друга', hint: 'Друг стримит из OBS' },
];

// Ветка «Через Bondcast» открыта сразу: три закрытые плашки и пустота под ними —
// это экран, на котором непонятно, что делать дальше. Выбор запоминаем, чтобы
// человек, который стримит из Moblin, не открывал свою ветку каждый раз заново.
const FLOW_KEY = 'bondcast_flow';
let activeFlowId = FLOWS.some((f) => f.id === localStorage.getItem(FLOW_KEY))
  ? localStorage.getItem(FLOW_KEY)
  : 'bondcast';
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
  if (activeFlowId) localStorage.setItem(FLOW_KEY, activeFlowId);
  else localStorage.removeItem(FLOW_KEY);
  renderFlowList();
}

// --- Куда подключаться: домашний адрес или внешний ---------------------------
// /api/connections отдаёт оба (см. scope в server.js). Домашний работает без
// единой настройки роутера — это самый простой первый успех, и раньше панель его
// не знала вовсе: в QR всегда уходил внешний адрес, а он без проброса не отвечает.
const NET_MODE_KEY = 'bondcast_net_mode';
let netMode = localStorage.getItem(NET_MODE_KEY) === 'internet' ? 'internet' : 'lan';

function hostsForScope(scope) {
  return latestHosts.filter((h) => h.scope === scope);
}

function availableScopes() {
  return ['lan', 'internet'].filter((scope) => hostsForScope(scope).length > 0);
}

function effectiveNetMode() {
  const available = availableScopes();
  if (!available.length) return netMode;
  return available.includes(netMode) ? netMode : available[0];
}

function activeHost() {
  return hostsForScope(effectiveNetMode())[0] || latestHosts[0] || null;
}

// OBS стоит на этом же компьютере, поэтому ссылки «посмотреть» всегда берут
// домашний адрес, даже когда телефон подключается через интернет: до внешнего
// адреса ещё нужен проброс порта, до локального — нет.
function watchHost() {
  return hostsForScope('lan')[0] || latestHosts[0] || null;
}

function setNetMode(mode) {
  netMode = mode;
  localStorage.setItem(NET_MODE_KEY, mode);
  renderFlowList();
}

// Переключатель режима — первым шагом чек-листа. Если внешний адрес неизвестен
// (или, наоборот, машина смотрит в интернет напрямую и локального нет), выбирать
// нечего — шаг не рисуем.
function netModeStepHtml() {
  if (availableScopes().length < 2) return '';
  const mode = effectiveNetMode();
  return stepHtml(NUM, 'Откуда подключается телефон', mode === 'lan' ? 'Телефон и компьютер — в одной Wi-Fi' : '', `
    <div class="seg" style="margin-top:8px">
      <button type="button" data-netmode="lan" class="${mode === 'lan' ? 'active' : ''}">Дома, по Wi-Fi</button>
      <button type="button" data-netmode="internet" class="${mode === 'internet' ? 'active' : ''}">Через интернет</button>
    </div>`);
}

// Отдельный шаг про домашнюю сеть нужен, только когда выбора нет вовсе: внешний
// адрес не нашёлся, и стримить получится лишь из дома. Когда выбор есть, то же
// самое уже сказано подписью под переключателем выше.
function lanStepHtml() {
  if (availableScopes().length >= 2) return '';
  return stepHtml(dot('wait'), 'Только по домашней Wi-Fi', escapeHtml(`Внешний адрес не нашёлся — ${RESTART}`));
}

// Шаги про сеть: в домашнем режиме проверять нечего (роутер в этом разговоре не
// участвует), в интернет-режиме — чек-лист с адресом и портами.
function netSteps(host, portItems) {
  if (effectiveNetMode() === 'lan') {
    return [{ key: 'lan-note', html: lanStepHtml() }];
  }
  return [{ key: 'ip', html: ipStepHtml(host) }, ...portItems];
}

const HOST_WAIT_HTML = '<div class="status-line"><span class="spinner"></span>Ищу адрес…</div>';

function noHostWarningHtml() {
  if (!hostsLoaded) return HOST_WAIT_HTML;
  return `<div class="flow-warn">Адрес компьютера неизвестен — ${RESTART}</div>`;
}

function noHostWarningItems() {
  return [{ key: 'warn', html: noHostWarningHtml() }];
}

// Не результат проверки порта, а напоминание, какой адрес уйдёт телефону.
function ipStepHtml(host) {
  if (!host) return errorStepHtml('Адрес компьютера неизвестен', escapeHtml(RESTART));
  return compactStepHtml('ok', `Адрес компьютера · ${escapeHtml(host.mobileSrtlaHost)}`);
}

// Что сломано в подключении прямо сейчас — парой слов для меню слева: ошибку
// должно быть видно из любого раздела, а не только из раскрытого чек-листа.
function connectProblem() {
  if (!hostsLoaded || !activeFlowId) return '';
  if (!latestHosts.length) return 'адрес неизвестен';
  const viaInternet = activeFlowId === 'invite' || effectiveNetMode() === 'internet';
  if (!viaInternet) return '';
  const port = activeFlowId === 'bondcast' ? 5000 : (activeFlowId === 'other-app' && selectedThirdPartyApp === 'prism' ? 1935 : 10080);
  const found = latestPortResults.find((r) => r.meta.port === port);
  return found && !found.data.error && !found.data.reachable ? 'порт закрыт' : '';
}

// Эфир уже идёт — значит, приложение стоит, адрес вписан и сеть пропускает: шаги
// подключения сворачиваются в одну строку. Развернуть можно кнопкой — например,
// чтобы показать код второму телефону.
let setupOpen = false;

function phoneSteps(name, doneText, setupItems) {
  if (!isLive(name)) return setupItems;
  const toggle = `<button type="button" class="btn-ghost" data-toggle-setup>${setupOpen ? 'Свернуть' : 'Показать шаги'}</button>`;
  const done = { key: 'phone-done', html: compactStepHtml('ok', escapeHtml(doneText), toggle) };
  return setupOpen ? [done, ...setupItems] : [done];
}

// Продолжение той же линии: что сделать в OBS и дошла ли картинка. Раньше это был
// отдельный раздел «Картинка в OBS» — путь обрывался на коде для телефона, и про
// OBS надо было догадаться. Ссылка собирается из имени стрима, поэтому вставить её
// можно заранее, до эфира.
function obsSteps(name, waitHint = 'Начнётся, когда нажмёшь «Стримить»') {
  const stream = latestStreams.find((s) => s.name === name);
  const since = stream && stream.liveSinceMs ? ` · ${formatLiveSince(stream.liveSinceMs)}` : '';
  const link = stepHtml(NUM, 'Вставь ссылку в OBS', 'Источники → + → Медиаисточник → «Ввод»',
    `<div style="margin-top:8px">${addrRow('', obsPlayUrl(name))}</div>`);
  const live = stream
    ? stepHtml(dot('ok'), 'Эфир идёт', escapeHtml(name + since), '',
      `<button type="button" class="primary" data-watch="${escapeHtml(name)}">Смотреть</button>`)
    : stepHtml(dot('wait'), 'Жду эфир', escapeHtml(waitHint));
  return [{ key: 'obs-link', html: link }, { key: 'live', html: live }];
}

// Чек-лист показывает состояние сети, но ничего не прячет: закрытый порт не
// обрывает список — финальный QR и адреса видны всегда (дома всё равно сработает).
// Пустые шаги отфильтровываем, а шагам «это делает человек» раздаём номера по
// порядку — вместо {{n}} из NUM.
function gateSteps(items) {
  let n = 0;
  return items
    .filter((item) => item.html)
    .map((item) => (item.html.includes('{{n}}') ? { ...item, html: item.html.replace('{{n}}', String(++n)) } : item));
}

// Точечно обновляет шаги чек-листа по стабильному ключу: перерисовывается
// (innerHTML) только тот узел, чьё содержимое реально изменилось — раньше вся
// панель уходила в innerHTML одной строкой на любое изменение (даже опрос раз в
// 5с без реальных перемен), и уже решённые шаги пересоздавались вместе с новым,
// заново проигрывая анимацию появления. Соединительная линия (.conn-line) между
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
    const connector = prevEl ? '<div class="conn-line"></div>' : '';
    const html = connector + item.html;
    if (unit.dataset.stepHtml !== html) {
      unit.innerHTML = html;
      unit.dataset.stepHtml = html;
    }
    if (isNew) unit.classList.add('step-reveal');
    // Переставляем узел только если он реально не на своём месте. Лишний
    // prevEl.after()/container.prepend() даже в ту же позицию по спеке снимает узел
    // из DOM и вставляет заново, а пере-вставка перезапускает CSS-анимации внутри
    // шага (появление, спиннер проверки) — на каждый опрос раз в 5с шаг «моргал».
    if (unit.parentNode !== container || unit.previousElementSibling !== prevEl) {
      if (prevEl) prevEl.after(unit); else container.prepend(unit);
    }
    prevEl = unit;
  });
  container.querySelectorAll(':scope > [data-step-key]').forEach((el) => {
    if (!seen.has(el.dataset.stepKey)) el.remove();
  });
}

// Ссылка на страницу приложения в Google Play. Первым шагом, до проверок сети и
// до QR-кода: у человека, который открыл панель впервые, приложения на телефоне
// ещё нет, и «наведи телефон на код» ему наводить нечем.
const PLAY_URL = 'https://play.google.com/store/apps/details?id=n7.bondcast';

function installStepHtml() {
  return stepHtml(NUM, 'Установи Bondcast на телефон', '', '',
    `<a class="btn primary" href="${PLAY_URL}" target="_blank" rel="noopener">Google Play</a>`);
}

function bondcastFlowBody() {
  const host = activeHost();
  if (!host) return noHostWarningItems();
  // QR — главное на шаге, поэтому сразу под заголовком; имя стрима ниже: его
  // меняют редко, и оно уже зашито в код.
  const finalStep = `
    <div class="flow-step flow-step-final">
      <div class="flow-step-final-head">${NUM}<b>Наведи телефон на код</b></div>
      <img class="qr" src="${host.qrDataUrl}" alt="Код для подключения" />
      <div class="row-meta" style="text-align:center">Bondcast → Настройки → камера → «Стримить»</div>
      <div>
        <label class="field-label" for="streamName">Имя стрима</label>
        <div class="name-row">
          <input type="text" id="streamName" value="${escapeHtml(currentStreamName)}" placeholder="имя стрима" />
          <button type="button" class="btn-lg btn-icon" id="regenName" title="Другое имя">${icon('dice')}</button>
        </div>
      </div>
    </div>`;
  return gateSteps([
    ...phoneSteps(currentStreamName, 'Телефон подключён', [
      { key: 'install', html: installStepHtml() },
      { key: 'net-mode', html: netModeStepHtml() },
      ...netSteps(host, [
        { key: 'port-5000', html: portStepHtml(PORTS_TO_CHECK[0]) },
        { key: 'port-4455', html: portStepHtml(PORTS_TO_CHECK[2]) },
      ]),
      { key: 'final', html: finalStep },
    ]),
    ...obsSteps(currentStreamName),
  ]);
}

// Тумблер Moblin/Larix/PRISM — выбор ярлыка приложения. Moblin и Larix показывают
// один и тот же SRT-адрес и идентификатор стрима (нет отдельного протокола под
// каждое приложение — это усложнило бы без пользы). PRISM Live не умеет SRT вообще,
// только RTMP — для него отдельная пара полей (см. otherAppFlowBody).
const THIRD_PARTY_APPS = ['moblin', 'larix', 'prism'];
let selectedThirdPartyApp = THIRD_PARTY_APPS.includes(localStorage.getItem('bondcast_thirdparty_app'))
  ? localStorage.getItem('bondcast_thirdparty_app')
  : 'moblin';

function setThirdPartyApp(app) {
  selectedThirdPartyApp = app;
  localStorage.setItem('bondcast_thirdparty_app', app);
  renderFlowList();
}

function otherAppFlowBody() {
  const host = activeHost();
  if (!host) return noHostWarningItems();
  const isLarix = selectedThirdPartyApp === 'larix';
  const isPrism = selectedThirdPartyApp === 'prism';
  const appSeg = `
      <div class="seg">
        <button type="button" class="${selectedThirdPartyApp === 'moblin' ? 'active' : ''}" data-app="moblin">Moblin</button>
        <button type="button" class="${isLarix ? 'active' : ''}" data-app="larix">Larix</button>
        <button type="button" class="${isPrism ? 'active' : ''}" data-app="prism">PRISM Live</button>
      </div>`;
  // PRISM Live умеет только RTMP (не SRT) — отдельная пара полей, без бондинга,
  // напрямую в SRS (как Larix). Ключ трансляции — просто имя стрима, без префикса/
  // символов streamid-формата, который используют Moblin/Larix через SRT.
  // Подписи полей — ровно так, как они называются в самом приложении: человек
  // ищет глазами совпадение, а не пересказ.
  const path = isPrism
    ? 'PRISM Live → настройки трансляции → RTMP'
    : isLarix
      ? 'Larix → Settings → Connections → New connection'
      : 'Moblin → Настройки → Стримы → Создать → Пользовательский → SRT(LA)';
  const addrRows = isPrism
    ? pathHtml(path) + addrRow('URL трансляции', host.rtmpUrl) + addrRow('Ключ трансляции', currentStreamName)
    : pathHtml(path) +
      addrRow('URL', host.obsSrtUrl) +
      addrRow(
        isLarix ? 'streamid' : 'Идентификатор стрима',
        host.obsSrtStreamId,
        isLarix ? 'Обязательно: без него эфир не пойдёт' : null,
      );
  const finalStep = `
    <div class="flow-step flow-step-final">
      <div class="flow-step-final-head">${NUM}<b>Чем снимаешь?</b></div>
      ${appSeg}
      ${addrRows}
    </div>`;
  // PRISM пришёл по RTMP, а не по SRT — SRT-play у этой SRS-сборки не отдаёт кадры
  // для RTMP-источника (сессия открывается и виснет без данных), поэтому для
  // предпросмотра берём HTTP-FLV — он ремуксится из общего источника независимо
  // от протокола приёма (см. obsPlayUrl выше).
  //
  // PRISM без SRT — гейт на порт 1935/RTMP вместо 10080/SRT, который используют
  // Moblin/Larix. Плюс отдельный (необязательный, не блокирует финальный шаг)
  // шаг про 8080 — без него публикация пройдёт, но предпросмотр в OBS не заработает.
  const steps = isPrism
    ? [
        { key: 'port-1935', html: portStepHtml(PORTS_TO_CHECK[3]) },
        { key: 'port-8080', html: portStepHtml(PORTS_TO_CHECK[4]) },
      ]
    : [{ key: 'port-10080', html: portStepHtml(PORTS_TO_CHECK[1]) }];
  return gateSteps([
    ...phoneSteps(currentStreamName, 'Приложение подключено', [
      { key: 'net-mode', html: netModeStepHtml() },
      ...netSteps(host, steps),
      { key: 'final', html: finalStep },
    ]),
    ...obsSteps(currentStreamName, 'Начнётся, когда запустишь стрим в приложении'),
  ]);
}

function inviteFlowBody() {
  const host = inviteHosts.find((h) => h.isPublic);
  if (!host) {
    return [{ key: 'warn', html: inviteHostsLoaded ? `<div class="flow-warn">Внешний адрес не нашёлся — ${RESTART}</div>` : HOST_WAIT_HTML }];
  }
  const finalStep = `
    <div class="flow-step flow-step-final">
      <div class="flow-step-final-head">${NUM}<b>Отправь другу две строки</b></div>
      ${pathHtml('У друга в OBS: Настройки → Трансляция → «Настраиваемый»')}
      ${addrRow('Сервер', host.obsSrtUrl)}
      ${addrRow('Ключ потока', host.obsSrtStreamId, null, `<button type="button" class="btn-icon" id="regenInvite" title="Другой ключ">${icon('dice')}</button>`)}
    </div>`;
  // Режима «дома по Wi-Fi» у этой ветки нет: друг сидит не в твоей квартире,
  // домашний адрес ему бесполезен.
  return gateSteps([
    ...phoneSteps(inviteStreamName, 'Друг подключён', [
      { key: 'ip', html: ipStepHtml(host) },
      { key: 'port-10080', html: portStepHtml(PORTS_TO_CHECK[1]) },
      { key: 'final', html: finalStep },
    ]),
    ...obsSteps(inviteStreamName, 'Начнётся, когда друг запустит стрим'),
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
      <span class="flow-pill-badge">${icon(f.icon)}</span>
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
  contentEl.querySelectorAll('[data-netmode]').forEach((btn) => {
    btn.onclick = () => setNetMode(btn.dataset.netmode);
  });
  contentEl.querySelectorAll('[data-toggle-setup]').forEach((btn) => {
    btn.onclick = () => { setupOpen = !setupOpen; renderFlowList(); };
  });
  contentEl.querySelectorAll('[data-watch]').forEach((btn) => {
    btn.onclick = () => openPreview(btn.dataset.watch);
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
  renderSectionNav(); // точка и подпись у «Подключить телефон» зависят от тех же проверок
}

renderFlowList();
refreshConnections();
refreshInviteConnections();
checkPort();

// --- "Сейчас в эфире" в левой колонке ---------------------------------------
// Простой обзор "кто сейчас на канале" — не путать с #streamCards в разделе
// "Субтитры" (там — управление субтитрами конкретного стрима, здесь — просто
// кто есть и куда стримить, если это OBS друга).
const serverStreamsEl = document.getElementById('serverStreams');

// HTTP-FLV, а не SRT: SRT-play в этой SRS-сборке отдаёт кадры, только если сам
// стрим тоже пришёл по SRT (Bondcast/Moblin/Larix) — для стрима, пришедшего по
// RTMP (PRISM Live и вообще любой сторонний RTMP-источник), SRT-сессия у OBS
// открывается, но виснет без единого кадра и рвётся по таймауту (проверено:
// то же самое "зависшее превью", про которое сообщил пользователь). HTTP-FLV
// работает в обоих случаях — SRS ремуксит в него независимо от протокола приёма
// (та же технология, что у встроенного плеера панели, см. loadMpegts() выше).
function obsPlayUrl(name) {
  const host = watchHost();
  if (!host) return '';
  return `http://${host.mobileSrtlaHost}:8080/live/${name}.flv`;
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
  if (name === currentStreamName) return 'с телефона';
  if (name === inviteStreamName) return 'от друга';
  return 'стрим';
}

// Только «кто в эфире» и «посмотреть». Ссылка для OBS отсюда ушла в раздел
// «Картинка в OBS» — она была и там, и тут, двумя разными кнопками.
function renderServerStreams(streams) {
  const slots = streams
    .map(
      (s) => `
    <div class="live-slot">
      <div class="live-slot-head">
        <span class="dot ok pulse"></span>
        <div class="live-slot-info">
          <b>${escapeHtml(s.name)}</b>
          <span class="meta">${escapeHtml(labelForServerStream(s.name))}${s.liveSinceMs ? ' · ' + escapeHtml(formatLiveSince(s.liveSinceMs)) : ''}</span>
        </div>
      </div>
      <button type="button" class="server-watch-stream" data-name="${escapeHtml(s.name)}">Смотреть</button>
      ${s.name !== currentStreamName && s.name !== inviteStreamName && obsPlayUrl(s.name)
        ? `<button type="button" class="copy-addr" data-value="${escapeHtml(obsPlayUrl(s.name))}">Ссылка для OBS</button>`
        : ''}
    </div>`,
    )
    .join('');
  // «Пока никого» — только когда и правда никого: раньше эта плашка висела и под
  // живым эфиром.
  if (!setHtml(serverStreamsEl, slots || '<div class="live-empty">Пока никого</div>')) return;
  bindCopyButtons(serverStreamsEl);
  serverStreamsEl.querySelectorAll('.server-watch-stream').forEach((btn) => {
    btn.onclick = () => openPreview(btn.dataset.name);
  });
}

// --- «Заглушка при обрыве» — реальный бэкенд (server.js: OBS websocket-клиент +
// монитор сигнала SRS). Настраивается заранее: сцену, задержку и порог выбирают
// при выключенном тумблере, сервер их помнит (см. applySettings в
// scene-switcher.js). Следит по умолчанию за своим стримом — его имя есть всегда,
// даже когда он ещё не в эфире.
let sceneSwitcherState = { enabled: false, watchStreamName: null, fallbackScene: null, delaySec: 3, minBitrateKbps: 0, state: 'idle', lastError: null };
let availableObsScenes = [];
let obsScenesError = null;
const sceneSwitchEl = document.getElementById('sceneSwitch');
const sceneSwitcherBodyEl = document.getElementById('sceneSwitcherBody');
const sceneSwitcherErrorEl = document.getElementById('sceneSwitcherError');

// extraHtml — готовая инструкция (напр. OBS_WEBSOCKET_HOWTO) прямо на месте.
function showSceneSwitcherError(message, extraHtml = '') {
  // .flow-warn красит свой текст в красный — инструкцию внутри возвращаем к
  // обычному цвету, иначе шаги «как включить WebSocket» тоже стали бы красными.
  const extra = extraHtml ? `<div style="color:var(--text)">${extraHtml}</div>` : '';
  sceneSwitcherErrorEl.innerHTML = escapeHtml(message) + extra;
  sceneSwitcherErrorEl.hidden = false;
}

function clearSceneSwitcherError() {
  sceneSwitcherErrorEl.hidden = true;
}

// Выбранная сцена-заглушка, если она всё ещё есть в OBS. Молча брать первую из
// списка нельзя: первой обычно стоит рабочая сцена с камерой.
function sceneChosen() {
  const scene = sceneSwitcherState.fallbackScene;
  return scene && availableObsScenes.includes(scene) ? scene : null;
}

// Запомненное имя берём, только пока оно имеет смысл — своё или живое. Иначе после
// смены имени стрима заглушка следила бы за эфиром, которого больше не будет.
function sceneWatchName() {
  const held = sceneSwitcherState.watchStreamName;
  if (held && (sceneSwitcherState.enabled || held === currentStreamName || isLive(held))) return held;
  return defaultStream();
}

function sceneSettings(patch) {
  return {
    enabled: sceneSwitcherState.enabled,
    watchStreamName: sceneWatchName(),
    fallbackScene: sceneSwitcherState.fallbackScene,
    delaySec: sceneSwitcherState.delaySec,
    minBitrateKbps: sceneSwitcherState.minBitrateKbps,
    ...patch,
  };
}

// Чего не хватает, чтобы включить: null — всё есть.
function sceneBlocker() {
  if (obsScenesError) return 'нужен OBS';
  if (!availableObsScenes.length) return 'в OBS нет сцен';
  if (!sceneChosen()) return 'выбери сцену';
  return null;
}

function applySceneSwitch() {
  renderSectionNav();
  // Тумблер не гасим, даже когда включать рано (нет OBS, не выбрана сцена): он тот
  // же, что в меню слева, и по нажатию показывает, чего не хватает (toggleScene).
  setSwitch(sceneSwitchEl, sceneSwitcherState.enabled);
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
  applySceneSwitch();
  renderSceneSwitcherBody();
}

async function refreshSceneSwitcherStatus() {
  try {
    const res = await fetch('/api/obs/scene-switcher');
    sceneSwitcherState = await res.json();
  } catch (e) {
    // тихо — не обновляем состояние до следующего опроса
  }
  // OBS могли запустить уже после загрузки панели. Сцены перечитываем, только пока
  // раздел открыт и их нет: дёргать OBS раз в 5 секунд без повода незачем.
  if (uiState.section === 'scene' && (obsScenesError || !availableObsScenes.length)) {
    refreshObsScenes();
    return;
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
    // obs_unreachable — OBS не достучаться: показываем не только текст, а сразу
    // инструкцию, как включить в нём WebSocket.
    const extra = e.code === 'obs_unreachable' ? OBS_WEBSOCKET_HOWTO : '';
    showSceneSwitcherError(`Заглушка: ${e.message}`, extra);
  }
  applySceneSwitch();
  renderSceneSwitcherBody();
}

function toggleScene() {
  if (sceneSwitcherState.enabled) {
    postSceneSwitcher(sceneSettings({ enabled: false }));
    return;
  }
  if (sceneBlocker()) {
    setSection('scene'); // там написано, чего не хватает
    nudge(sceneSwitcherBodyEl.querySelector('[data-need]'));
    return;
  }
  postSceneSwitcher(sceneSettings({ enabled: true }));
}
sceneSwitchEl.onclick = toggleScene;

function renderSceneSwitcherBody() {
  if (obsScenesError || !availableObsScenes.length) {
    const html = `
      <div class="status-line" data-need>${dot('bad')}${obsScenesError ? 'OBS не отвечает — включи в нём WebSocket' : 'В OBS нет ни одной сцены'}</div>
      ${obsScenesError ? OBS_WEBSOCKET_HOWTO : ''}
      <button type="button" id="retryObsScenes" style="align-self:flex-start">Проверить снова</button>`;
    if (swapHtml(sceneSwitcherBodyEl, html)) document.getElementById('retryObsScenes').onclick = refreshObsScenes;
    return;
  }
  const watch = sceneWatchName();
  const scene = sceneChosen();
  const status = !sceneSwitcherState.enabled ? ''
    : sceneSwitcherState.state === 'switched' ? `<div class="status-line">${dot('wait')}Показываю заглушку</div>`
    : isLive(watch) ? `<div class="status-line">${dot('ok')}Слежу за эфиром · ${escapeHtml(watch)}</div>`
    : `<div class="status-line">${dot('wait')}Жду эфир · ${escapeHtml(watch)}</div>`;
  const sceneChips = availableObsScenes
    .map((v) => `<button type="button" class="chip${v === scene ? ' is-active' : ''}" data-scene="${escapeHtml(v)}">${escapeHtml(v)}</button>`)
    .join('');
  const html = `
    ${status}
    ${streamChipsHtml(streamCandidates(sceneSwitcherState.enabled ? watch : null), watch, 'data-watch-stream')}
    <div>
      <label class="field-label">Сцена-заглушка</label>
      <div class="chip-row">${sceneChips}</div>
      ${scene ? '' : '<div class="row-meta" data-need style="margin-top:6px">Выбери сцену — и включай</div>'}
    </div>
    <div class="range-field">
      <div class="range-head"><span>Ждать перед переключением</span><output id="switchDelayOut">${sceneSwitcherState.delaySec} сек</output></div>
      <input type="range" id="switchDelay" min="0" max="30" value="${sceneSwitcherState.delaySec}" />
    </div>
    <div class="range-field">
      <div class="range-head"><span>Обрыв, если битрейт ниже</span><output id="minBitrateOut">${sceneSwitcherState.minBitrateKbps ? sceneSwitcherState.minBitrateKbps + ' кбит/с' : 'выкл'}</output></div>
      <input type="range" id="minBitrate" min="0" max="20000" step="100" value="${sceneSwitcherState.minBitrateKbps || 0}" />
      <div class="row-meta">«Выкл» — только при полной пропаже</div>
    </div>
    ${sceneSwitcherState.lastError ? `<div class="flow-warn">${escapeHtml(sceneSwitcherState.lastError)}</div>` : ''}
  `;
  // Перерисовываем, только когда что-то поменялось: иначе опрос раз в 5 секунд
  // обрывал бы перетаскивание ползунка.
  if (!swapHtml(sceneSwitcherBodyEl, html)) return;
  sceneSwitcherBodyEl.querySelectorAll('[data-watch-stream]').forEach((btn) => {
    btn.onclick = () => postSceneSwitcher(sceneSettings({ watchStreamName: btn.dataset.watchStream }));
  });
  sceneSwitcherBodyEl.querySelectorAll('[data-scene]').forEach((btn) => {
    btn.onclick = () => postSceneSwitcher(sceneSettings({ fallbackScene: btn.dataset.scene }));
  });
  const delayInput = document.getElementById('switchDelay');
  const delayOut = document.getElementById('switchDelayOut');
  delayInput.oninput = () => { delayOut.textContent = `${delayInput.value} сек`; };
  delayInput.onchange = () => postSceneSwitcher(sceneSettings({ delaySec: Number(delayInput.value) }));
  const minBitrateInput = document.getElementById('minBitrate');
  const minBitrateOut = document.getElementById('minBitrateOut');
  minBitrateInput.oninput = () => { minBitrateOut.textContent = Number(minBitrateInput.value) ? `${minBitrateInput.value} кбит/с` : 'выкл'; };
  minBitrateInput.onchange = () => postSceneSwitcher(sceneSettings({ minBitrateKbps: Number(minBitrateInput.value) }));
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
const capBuildStageEl = document.getElementById('capBuildStage');
const capReadyStageEl = document.getElementById('capReadyStage');
const obsUrlRowEl = document.getElementById('obsUrlRow');
const voicesListEl = document.getElementById('voicesList');
const addVoiceBtnEl = document.getElementById('addVoiceBtn');
const addVoiceWhyEl = document.getElementById('addVoiceWhy');
const subsSwitchEl = document.getElementById('subsSwitch');
const subsStatusEl = document.getElementById('subsStatus');
const subsStreamPickEl = document.getElementById('subsStreamPick');

let captionsState = {
  connected: false, streamName: null, overlayUrl: null, imageExists: false, buildStatus: 'idle', buildError: null,
  voices: [], enrollStatus: 'idle', enrollError: null, enrollVoiceId: null, ready: false, asrModel: null,
};

function recognitionSettingsChanged() {
  return captionsState.asrModel !== recognitionInputs.asrModel();
}
let capBusy = false;

// --- Субтитры: вкл/выкл ------------------------------------------------------
// Тумблер в шапке карточки — настоящее вкл/выкл, тот же, что в меню слева. Раньше
// он сворачивал карточку, а включала субтитры отдельная кнопка у стрима — и
// тумблер стоял выключенным при работающих субтитрах.
// Без эфира субтитры включаются «на взвод»: воркер стартует на имени стрима и сам
// дожидается звука (FfmpegAudioSource в asr-obs переподключается по кругу), а
// модель к приходу эфира уже прогрета.
let subsPick = null;    // эфир, выбранный руками; null — берём по умолчанию
let subsPending = null; // true/false, пока идёт запрос на вкл/выкл; null — запроса нет

function subsOn() {
  return subsPending !== null ? subsPending : Boolean(captionsState.connected);
}

function subsTargetStream() {
  if (captionsState.connected && captionsState.streamName) return captionsState.streamName;
  return subsPick && streamCandidates().includes(subsPick) ? subsPick : defaultStream();
}

function toggleSubs() {
  if (subsPending !== null) return;
  if (!captionsState.imageExists) {
    setSection('subs'); // там кнопка «Скачать»
    nudge(capBuildStageEl);
    return;
  }
  if (captionsState.connected) disconnectCaptions();
  else connectCaptions(subsTargetStream());
}
subsSwitchEl.onclick = toggleSubs;

// Голос записывается со звука эфира — это единственное в разделе, чему эфир нужен
// прямо сейчас. null — писать не с чего.
function enrollStream() {
  const name = subsTargetStream();
  return isLive(name) ? name : null;
}

function renderSubs() {
  setSwitch(subsSwitchEl, subsOn(), subsPending !== null);

  let status = '';
  if (captionsState.connected) {
    const name = escapeHtml(captionsState.streamName || '');
    if (!captionsState.ready) status = '<span class="spinner"></span>Готовлюсь — в первый раз минута-две';
    else if (isLive(captionsState.streamName)) status = `${dot('ok')}Работают · ${name}`;
    else status = `${dot('wait')}Жду эфир · ${name}`;
  }
  subsStatusEl.hidden = !status;
  setHtml(subsStatusEl, status);

  const target = subsTargetStream();
  const chips = streamChipsHtml(streamCandidates(captionsState.connected ? captionsState.streamName : null), target, 'data-subs-stream');
  subsStreamPickEl.hidden = !chips;
  if (setHtml(subsStreamPickEl, chips)) {
    subsStreamPickEl.querySelectorAll('[data-subs-stream]').forEach((btn) => {
      btn.onclick = () => {
        subsPick = btn.dataset.subsStream;
        if (captionsState.connected && captionsState.streamName !== subsPick) connectCaptions(subsPick);
        else renderSubs();
      };
    });
  }

  renderObsUrlRow();
  renderVoices();
  renderSectionNav();
}

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
    renderSubs();
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
  const showSpeaker = isSwitchOn(showSpeakerToggleEl);
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

// Отдельно от overlayInputs — живёт в карточке «Голоса», но правит тот же
// ресурс (showSpeaker в overlay_style.json):
// полностью гасит подпись говорящего в оверлее (и "Имя:", и "Кто-то:"),
// просто голый текст реплики, если она не нужна вообще.
const showSpeakerToggleEl = document.getElementById('showSpeakerToggle');
showSpeakerToggleEl.onclick = () => {
  setSwitch(showSpeakerToggleEl, !isSwitchOn(showSpeakerToggleEl));
  updateOverlayPreview();
  fetch('/api/captions/overlay-style', {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ showSpeaker: isSwitchOn(showSpeakerToggleEl) }),
  }).catch(() => {});
};

async function loadOverlayStyle() {
  try {
    const res = await fetch('/api/captions/overlay-style');
    const style = await res.json();
    overlayInputs.size.value = style.size;
    overlayInputs.lines.value = style.lines;
    overlayInputs.guestColor.value = style.guestColor;
    overlayInputs.bgColor.value = style.bgColor;
    overlayInputs.bgOpacity.value = style.bgOpacity;
    setSwitch(showSpeakerToggleEl, style.showSpeaker !== false);
  } catch (e) {
    // тихо — останутся дефолты из атрибутов инпутов, следующий заход подтянет реальные
  }
  updateOverlayPreview();
}
loadOverlayStyle();

function formatCodec(video, audio) {
  const parts = [];
  if (video) parts.push(`${video.codec} ${video.width}x${video.height}`);
  if (audio) parts.push(`${audio.codec} ${audio.sample_rate}Hz`);
  return parts.join(' · ') || 'формат пока не определился';
}

// --- Стадии карточки: не скачано -> скачано (всё настраивается) --------------
function applyCaptionsStage() {
  const ready = captionsState.imageExists;
  capBuildStageEl.hidden = ready;
  capReadyStageEl.hidden = !ready;
  const buildBtn = capBuildStageEl.querySelector('.cap-build');
  buildBtn.disabled = captionsState.buildStatus === 'building';
  buildBtn.textContent = captionsState.buildStatus === 'building' ? 'Скачиваю…' : 'Скачать';
  renderSubs();
}
capBuildStageEl.querySelector('.cap-build').onclick = buildCaptions;
addVoiceBtnEl.title = `${ENROLL_DURATION_SEC} секунд, говорит один человек`;
addVoiceBtnEl.onclick = () => {
  const name = enrollStream();
  if (name) enrollVoice(name);
};

// Ссылка постоянная: оформление хранится на сервере, а не в адресе, поэтому в
// OBS её вставляют один раз — и сделать это можно до эфира.
function renderObsUrlRow() {
  const html = captionsState.overlayUrl
    ? addrRow('Ссылка для OBS', captionsState.overlayUrl, 'OBS → Источники → + → Браузер. Вставить один раз')
    : '';
  if (setHtml(obsUrlRowEl, html)) bindCopyButtons(obsUrlRowEl);
}

// --- Мини-список голосов ------------------------------------------------------
function voiceEnrollProgressHtml() {
  const elapsed = enrollStartedAt ? (Date.now() - enrollStartedAt) / 1000 : 0;
  const capturing = elapsed < ENROLL_DURATION_SEC;
  const label = capturing
    ? `Говори без пауз — ещё ${Math.max(0, Math.ceil(ENROLL_DURATION_SEC - elapsed))} с`
    : 'Запоминаю голос…';
  return `<span class="row-meta">${label}</span><progress value="${Math.min(elapsed, ENROLL_DURATION_SEC).toFixed(1)}" max="${ENROLL_DURATION_SEC}"></progress>`;
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
        <button type="button" class="btn-icon voice-rerecord" title="Записать заново" ${busy || !enrollStream() ? 'disabled' : ''}>${icon('redo')}</button>
        <button type="button" class="btn-icon voice-delete" title="Удалить голос" ${busy ? 'disabled' : ''}>${icon('close')}</button>
      </div>
      ${body}
      ${!voice.hasEmbedding && !isEnrollingThis ? '<div class="row-meta">Запись не закончена</div>' : ''}
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
        const name = enrollStream();
        if (!name) return;
        enrollVoice(name, id);
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
    .catch((e) => showCapError(`Голос не сохранился: ${e.message}`));
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

// Список перерисовываем, только когда он реально поменялся и пока в нём нет
// фокуса: иначе опрос раз в 5 секунд обрывал бы печать имени голоса и
// перетаскивание ползунка строгости.
function renderVoices() {
  const enrollingNew = captionsState.enrollStatus === 'running'
    && captionsState.enrollVoiceId
    && !captionsState.voices.some((v) => v.id === captionsState.enrollVoiceId);
  const canEnroll = Boolean(enrollStream());
  addVoiceBtnEl.disabled = captionsState.enrollStatus === 'running' || !canEnroll;
  setHtml(addVoiceBtnEl, `${icon('mic')}${enrollingNew ? 'Идёт запись…' : 'Записать голос'}`);
  // Причина — рядом с кнопкой, которую она блокирует, а не замком на весь раздел.
  addVoiceWhyEl.hidden = canEnroll;
  addVoiceWhyEl.textContent = 'Нужен эфир: голос пишем с него';

  if (voicesListEl.contains(document.activeElement)) return; // юзер сейчас печатает/тащит слайдер — не трогаем DOM

  const errorHtml = captionsState.enrollStatus === 'error'
    ? `<div class="flow-warn">Голос не записался: ${escapeHtml(captionsState.enrollError || '')}</div>`
    : '';
  const rows = captionsState.voices.map(voiceRowHtml).join('');
  const pendingRow = enrollingNew ? `<div class="voice-row">${voiceEnrollProgressHtml()}</div>` : '';
  const body = rows + pendingRow;
  if (setHtml(voicesListEl, errorHtml + (body || '<div class="row-meta">Голосов нет — все будут «Кто-то»</div>'))) bindVoiceRowHandlers();
}

async function refreshStreams() {
  try {
    const res = await fetch('/api/streams');
    const data = await res.json();
    latestStreams = data.streams || [];
    if (latestStreams.length) everLive = true;
    renderSubs();
    renderServerStreams(latestStreams);
    updatePreviewStats();
    // Метки в левой колонке и чипсы выбора эфира живут ровно от этого опроса —
    // отдельного источника правды про «идёт ли эфир» у панели нет.
    applySections();
    renderFlowList(); // шаги «телефон подключён» и «эфир идёт» живут от того же опроса
    renderSceneSwitcherBody();
    renderEnhanceBody();
    renderSectionNav();
  } catch (e) {
    // тихо — список эфиров обновится на следующем опросе
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
    showCapError(`Не скачалось: ${e.message}`);
  } finally {
    capBusy = false;
    pollStreamsAndCaptions();
  }
}

async function connectCaptions(name) {
  if (capBusy) return;
  capBusy = true;
  subsPending = true;
  clearCapError();
  renderSubs();
  try {
    const res = await fetch('/api/captions/connect', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name, asrModel: recognitionInputs.asrModel() }),
    });
    const data = await res.json();
    if (!res.ok) throw new Error(data.error || 'unknown error');
  } catch (e) {
    showCapError(`Субтитры не включились: ${e.message}`);
  } finally {
    capBusy = false;
    await pollStreamsAndCaptions();
    subsPending = null;
    renderSubs();
  }
}

async function disconnectCaptions() {
  if (capBusy) return;
  capBusy = true;
  subsPending = false;
  clearCapError();
  renderSubs();
  try {
    const res = await fetch('/api/captions/disconnect', { method: 'POST' });
    const data = await res.json();
    if (!res.ok) throw new Error(data.error || 'unknown error');
  } catch (e) {
    showCapError(`Субтитры не выключились: ${e.message}`);
  } finally {
    capBusy = false;
    await pollStreamsAndCaptions();
    subsPending = null;
    renderSubs();
  }
}

function openBuildLogStream() {
  const source = openCapLogStream('/api/captions/build/logs');
  source.addEventListener('done', () => {
    source.close();
    pollStreamsAndCaptions();
  });
  source.onerror = () => {
    appendCapLog('[связь с журналом загрузки оборвалась]');
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
    showCapError(`Голос не записался: ${e.message}`);
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
    appendCapLog('[связь с журналом записи оборвалась]');
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
// убирали — см. git log). Сам прогресс (скачивание/установка) — update.ps1
// шлёт его на локальный /api/update/progress (см. server.js), а не рисует
// отдельным системным окошком — пользователь жал кнопку здесь же, в панели,
// здесь и должен видеть, что происходит.
const updateBannerEl = document.getElementById('updateBanner');
const updateBannerOfferEl = document.getElementById('updateBannerOffer');
const updateBannerTextEl = document.getElementById('updateBannerText');
const updateBannerBtnEl = document.getElementById('updateBannerBtn');
const updateBannerProgressEl = document.getElementById('updateBannerProgress');
const updateProgressTextEl = document.getElementById('updateProgressText');
const updateProgressBarEl = document.getElementById('updateProgressBar');
let updateConfirmPending = false;
// true с момента подтверждённого клика (или если при загрузке страницы уже
// шло обновление, начатое до перезагрузки — см. checkResumeUpdateProgress) —
// пока true, refreshUpdateStatus не трогает баннер: 5-минутный опрос иначе
// мог бы затереть прогресс обратно на "Обновить" посреди скачивания.
let updateInProgress = false;
let updateProgressTimer = null;
let updateStartTimeoutTimer = null;
// Каким был сервер в начале обновления (bootId из /api/update/progress). Другой
// bootId в ответе = панель уже перезапустилась новой, пора перезагрузить страницу.
let updateBootId = null;
// Перезапуск не уложился в отведённое время — говорим, что сделать руками.
let updateRestartTimer = null;
const UPDATE_RESTART_LIMIT_MS = 5 * 60 * 1000;
const UPDATE_RESTART_FAILED = 'Не перезапустилось само — запусти ярлык «Запустить трансляцию»';

function resetUpdateButton() {
  updateConfirmPending = false;
  updateBannerBtnEl.textContent = 'Обновить';
}

function stopUpdateProgressPolling() {
  if (updateProgressTimer) {
    clearInterval(updateProgressTimer);
    updateProgressTimer = null;
  }
}

// Если статус реально сдвинулся (не "starting") — update.ps1 точно запустился,
// протокол у пользователя зарегистрирован. Таймаут ниже больше не нужен.
function clearUpdateStartTimeout() {
  if (updateStartTimeoutTimer) {
    clearTimeout(updateStartTimeoutTimer);
    updateStartTimeoutTimer = null;
  }
}

function renderUpdateProgress(data) {
  updateBannerOfferEl.hidden = true;
  updateBannerProgressEl.hidden = false;
  if (data.status === 'downloading') {
    clearUpdateStartTimeout();
    updateProgressBarEl.value = data.percent;
    updateProgressTextEl.textContent = `Скачиваю обновление… ${data.percent}%`;
  } else if (data.status === 'installing') {
    clearUpdateStartTimeout();
    // Без value — браузер сам рисует "бегущую" полосу: Inno Setup в /VERYSILENT
    // не отдаёт наружу прогресс копирования файлов, честнее не выдумывать процент.
    updateProgressBarEl.removeAttribute('value');
    updateProgressTextEl.textContent = 'Устанавливаю…';
  } else if (data.status === 'restarting') {
    // Файлы уже новые, установщик сам пересобирает панель (start.bat /update).
    // Опрос не останавливаем: по нему и поймём, что поднялась новая панель.
    clearUpdateStartTimeout();
    updateProgressBarEl.removeAttribute('value');
    if (!updateRestartTimer) {
      updateProgressTextEl.textContent = 'Перезапускаю — страница обновится сама';
      updateRestartTimer = setTimeout(() => {
        updateProgressTextEl.textContent = UPDATE_RESTART_FAILED;
        stopUpdateProgressPolling();
      }, UPDATE_RESTART_LIMIT_MS);
    }
  } else if (data.status === 'done') {
    // Сюда приходит только «обновлять нечего»: после настоящей установки статус —
    // restarting, и страница перезагружается сама.
    clearUpdateStartTimeout();
    updateProgressBarEl.value = 100;
    updateProgressTextEl.textContent = 'Уже стоит последняя версия';
    stopUpdateProgressPolling();
  } else if (data.status === 'error') {
    clearUpdateStartTimeout();
    clearTimeout(updateRestartTimer);
    updateProgressBarEl.removeAttribute('value');
    updateProgressTextEl.textContent = data.message === 'restart-failed'
      ? UPDATE_RESTART_FAILED
      : `Не обновилось: ${data.message || 'причина неизвестна'}`;
    stopUpdateProgressPolling();
  } else {
    updateProgressBarEl.removeAttribute('value');
    updateProgressTextEl.textContent = 'Запускаю обновление…';
  }
}

// Панель перезапустилась новой — перезагружаем страницу: в браузере ещё старый
// скрипт, а сервер уже другой. Ошибки опроса в промежутке — норма: контейнер
// пересоздаётся, несколько секунд панель не отвечает.
function reloadIfPanelRestarted(data) {
  if (!data.bootId) return false;
  if (updateBootId === null) {
    updateBootId = data.bootId;
    return false;
  }
  if (data.bootId === updateBootId) return false;
  window.location.reload();
  return true;
}

async function pollUpdateProgress() {
  try {
    const res = await fetch('/api/update/progress');
    const data = await res.json();
    if (reloadIfPanelRestarted(data)) return;
    if (data.status && data.status !== 'idle') renderUpdateProgress(data);
  } catch (e) {
    // тихо — попробуем на следующем тике
  }
}

// На случай, если страницу перезагрузили посреди уже идущего обновления
// (начатого до перезагрузки) — подхватываем текущий прогресс с сервера, а не
// показываем снова "Обновить" поверх того, что уже вовсю качается/ставится.
async function checkResumeUpdateProgress() {
  try {
    const res = await fetch('/api/update/progress');
    const data = await res.json();
    reloadIfPanelRestarted(data); // первый вызов только запоминает, каким был сервер
    if (data.status && data.status !== 'idle') {
      updateInProgress = true;
      updateBannerEl.hidden = false;
      renderUpdateProgress(data);
      if (data.status !== 'done' && data.status !== 'error') {
        stopUpdateProgressPolling();
        updateProgressTimer = setInterval(pollUpdateProgress, 500);
      }
    }
  } catch (e) {
    // тихо
  }
}

updateBannerBtnEl.onclick = () => {
  if (!updateConfirmPending) {
    updateConfirmPending = true;
    updateBannerBtnEl.textContent = hasLiveStream() ? 'Идёт эфир. Всё равно обновить?' : 'Точно обновить?';
    return;
  }
  updateInProgress = true;
  renderUpdateProgress({ status: 'starting' });
  stopUpdateProgressPolling();
  updateProgressTimer = setInterval(pollUpdateProgress, 500);
  // Если через 8с статус так и не сдвинулся с "starting" — почти наверняка
  // Windows не запустила update.ps1 вообще (у старых установленных копий,
  // до этой версии, протокол bondcast-update:// просто не зарегистрирован в
  // реестре — сам он появляется только начиная с той версии, что его ввела).
  // Без этой подсказки кнопка выглядела бы зависшей навсегда без объяснений.
  clearUpdateStartTimeout();
  updateStartTimeoutTimer = setTimeout(() => {
    updateProgressTextEl.innerHTML = 'Не запускается. ' +
      '<a href="https://github.com/i30mb1/Bondcast/releases/latest" target="_blank" rel="noopener">Скачай новую версию вручную</a>' +
      ' — дальше обновится само';
  }, 8000);
  // Сбрасываем прогресс на сервере ДО перехода по протоколу — иначе первый же
  // опрос мог увидеть "хвост" от прошлого обновления (status: done/error).
  fetch('/api/update/progress', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ status: 'starting', percent: 0 }),
  })
    .catch(() => {})
    .finally(() => {
      window.location.href = 'bondcast-update://install';
    });
};

async function refreshUpdateStatus() {
  if (updateInProgress) return;
  try {
    const res = await fetch('/api/update/status');
    const data = await res.json();
    if (data.updateAvailable) {
      // note — первая строка текста релиза (см. checkForUpdate в server.js),
      // шуточное однострочное описание в духе Discord-патчноутов, не сухой номер версии.
      const versionsHtml = `<span class="update-version">
        <span class="update-version-chip is-current">${escapeHtml(data.currentVersion)}</span>
        <span class="update-version-arrow">→</span>
        <span class="update-version-chip is-target">${escapeHtml(data.latestVersion)}</span>
      </span>`;
      const noteHtml = data.note ? escapeHtml(data.note) : 'Доступно обновление';
      updateBannerTextEl.innerHTML = `${versionsHtml}${noteHtml}`;
      updateBannerEl.hidden = false;
      updateBannerOfferEl.hidden = false;
      updateBannerProgressEl.hidden = true;
    } else {
      updateBannerEl.hidden = true;
      resetUpdateButton();
    }
  } catch (e) {
    // тихо — панель могла быть недоступна секунду, попробуем на следующем опросе
  }
}
checkResumeUpdateProgress().then(refreshUpdateStatus);
setInterval(refreshUpdateStatus, 5 * 60 * 1000);
// Дольше 5 минут не открывал вкладку — статус мог устареть (напр. только что
// поставил обновление в фоне) — перепроверяем сразу, как вернулся, а не ждём
// остаток интервала.
document.addEventListener('visibilitychange', () => {
  if (!document.hidden) refreshUpdateStatus();
});

// --- Раздел «Улучшение картинки» --------------------------------------------
// Тумблер в шапке — настоящее вкл/выкл (как у «Заглушки»), а не свернуть/развернуть.
// Включение: воркер на сервере берёт эфир с телефона, улучшает на видеокарте и
// отдаёт рядом как <стрим>_enh, а панель сама перекидывает медиаисточник OBS на
// него (см. enhanceTick в server.js). Выключение — OBS назад, воркер гасится.
const ENHANCE_TARGET_KEY = 'bondcast_enhance_target';
const ENHANCE_TARGET_OPTIONS = [
  ['auto', 'Авто'],
  ['same', 'Без увеличения'],
  ['1080', '1080p'],
  ['1440', '1440p'],
  ['2160', '4K'],
];
let enhanceState = { imageExists: false, buildStatus: 'idle', connected: false, obs: { state: 'idle', inputs: [] } };
let enhanceTarget = ENHANCE_TARGET_OPTIONS.some(([v]) => v === localStorage.getItem(ENHANCE_TARGET_KEY))
  ? localStorage.getItem(ENHANCE_TARGET_KEY)
  : 'auto';
// Тонкие настройки улучшения. Одной «лучшей» комбинации нет: на тонких линиях и
// фактуре приятнее «Натурально», на лице крупным планом — «Резко», на плохой связи
// нужна очистка посильнее — поэтому выбор отдан человеку. Умолчания те же, что у
// воркера без переменных (enhance/app/main.py).
const ENHANCE_OPTS_KEY = 'bondcast_enhance_opts';
const ENHANCE_STYLE_OPTIONS = [
  ['natural', 'Натурально'],
  ['sharp', 'Резко'],
];
function loadEnhanceOpts() {
  let saved = {};
  try {
    saved = JSON.parse(localStorage.getItem(ENHANCE_OPTS_KEY)) || {};
  } catch (e) {
    // битая запись — берём умолчания
  }
  const unit = (v) => (typeof v === 'number' && v >= 0 && v <= 1 ? v : 1);
  return {
    style: ENHANCE_STYLE_OPTIONS.some(([v]) => v === saved.style) ? saved.style : 'natural',
    strength: unit(saved.strength),
    clean: unit(saved.clean),
    preclean: saved.preclean === true,
    smooth: saved.smooth === true,
  };
}
let enhanceOpts = loadEnhanceOpts();
let enhanceAdvancedOpen = false;
let enhanceDemoCut = 50; // где стоит шторка примера «было/стало», в процентах
let enhanceBusy = false;
let enhancePick = null;    // эфир, выбранный руками; null — берём по умолчанию
let enhancePending = null; // true/false, пока идёт запрос на вкл/выкл; null — запроса нет
let enhanceBuildLines = [];
const enhanceSwitchEl = document.getElementById('enhanceSwitch');
const enhanceBodyEl = document.getElementById('enhanceBody');
const enhanceErrorEl = document.getElementById('enhanceError');

function showEnhanceError(message, extraHtml = '') {
  const extra = extraHtml ? `<div style="color:var(--text)">${extraHtml}</div>` : '';
  enhanceErrorEl.innerHTML = escapeHtml(message) + extra;
  enhanceErrorEl.hidden = false;
}

function clearEnhanceError() {
  enhanceErrorEl.hidden = true;
}

function enhanceOn() {
  return enhancePending !== null ? enhancePending : Boolean(enhanceState.connected);
}

function enhanceTargetStream() {
  if (enhanceState.connected && enhanceState.streamName) return enhanceState.streamName;
  return enhancePick && streamCandidates().includes(enhancePick) ? enhancePick : defaultStream();
}

// Без эфира улучшение включается «на взвод»: воркер стартует на имени стрима и
// сам дожидается картинки (цикл «жду источник снова» в enhance/app/main.py).
// Раньше тумблер без эфира щёлкал во «вкл», показывал ошибку и отщёлкивал обратно.
function toggleEnhance() {
  if (enhancePending !== null) return;
  if (!enhanceState.imageExists || enhanceState.imageOutdated) {
    setSection('enhance'); // там кнопка «Скачать» / «Обновить»
    nudge(enhanceBodyEl.querySelector('[data-need]'));
    return;
  }
  if (enhanceState.connected) disconnectEnhance();
  else connectEnhance(enhanceTargetStream());
}

function enhanceStatsHtml() {
  const st = enhanceState.stats;
  if (!enhanceState.outputLive || !st) {
    return isLive(enhanceState.streamName)
      ? '<div class="status-line"><span class="spinner"></span>Готовлюсь — пара секунд</div>'
      : `<div class="status-line">${dot('wait')}Жду эфир · ${escapeHtml(enhanceState.streamName || '')}</div>`;
  }
  const upscale = st.outWidth !== st.inWidth || st.outHeight !== st.inHeight;
  const what = upscale
    ? `${st.inWidth}×${st.inHeight} → ${st.outWidth}×${st.outHeight}`
    : `${st.inWidth}×${st.inHeight}, только очистка`;
  // Отставание растёт, только если видеокарта/процессор не успевают за эфиром —
  // тогда честно говорим и подсказываем, что поменять.
  const slow = st.lagMs > 500
    ? `<div class="flow-warn">Не успевает, отстаёт на ${(st.lagMs / 1000).toFixed(1)} с — снизь разрешение</div>`
    : '';
  return `
    <div class="status-line">${dot('ok')}${escapeHtml(what)} · ${st.fps.toFixed(0)} к/с · ${st.gpuMs.toFixed(0)} мс на кадр</div>
    ${slow}`;
}

function enhanceObsHtml() {
  const out = enhanceState.outputName;
  if (!out || !enhanceState.outputLive) return '';
  const manual = addrRow('Улучшенный эфир', obsPlayUrl(out), 'OBS → медиаисточник → «Ввод». Выключишь — верни прежнюю');
  const obs = enhanceState.obs || {};
  if (obs.state === 'switched') {
    return `<div class="status-line">${dot('ok')}OBS показывает улучшенную картинку</div>`;
  }
  if (obs.state === 'unreachable') {
    return `
      <div class="row-meta">OBS не отвечает — вставь ссылку сам</div>
      ${manual}
      ${OBS_WEBSOCKET_HOWTO}`;
  }
  return `
    <div class="row-meta">Источник в OBS не найден — вставь ссылку сам</div>
    ${manual}`;
}

// Пример «было/стало» — пока улучшение выключено: человек решает, включать ли, по
// картинке, а не по словам. Правая половина — в выбранном характере:
// переключил «Резко» — сразу видно разницу.
// Кадры в public/enhance-demo — настоящий эфир со слабой связи (360p), пропущенный
// через этот же воркер, а не нарисованная реклама.
function enhanceDemoHtml() {
  const sharp = enhanceOpts.style === 'sharp';
  return `
    <div>
      <div class="enh-demo-view" id="enhanceDemo">
        <img src="enhance-demo/${sharp ? 'sharp' : 'natural'}.jpg" alt="" draggable="false">
        <img class="before" src="enhance-demo/before.jpg" alt="" draggable="false">
        <span class="enh-demo-tag left">Было</span>
        <span class="enh-demo-tag right">Стало</span>
        <span class="enh-demo-line"></span>
        <input type="range" min="0" max="100" value="50" aria-label="Сравнить картинку до и после улучшения">
      </div>
    </div>`;
}

function bindEnhanceDemo() {
  const demo = document.getElementById('enhanceDemo');
  if (!demo) return;
  const range = demo.querySelector('input');
  // Положение шторки помним сами: раздел перерисовывается при каждом переключении
  // (характер, плавность), и шторка иначе прыгала бы обратно в середину — а человек
  // как раз поставил её на интересное место, чтобы сравнить «Натурально» и «Резко».
  range.value = enhanceDemoCut;
  const apply = () => {
    enhanceDemoCut = Number(range.value);
    demo.style.setProperty('--cut', `${enhanceDemoCut}%`);
  };
  range.oninput = apply;
  apply();
}

let enhanceBodyHtml = '';
function setEnhanceBodyHtml(html) {
  if (html === enhanceBodyHtml) return false;
  enhanceBodyHtml = html;
  swapHtml(enhanceBodyEl, html);
  return true;
}

// Опрос раз в 3 с — перерисовываем, только если что-то реально поменялось, иначе
// у кнопки «Копировать» слетало бы «Готово», а у подсказки «?» — наведение.
function renderEnhanceBody() {
  if (!enhanceState.imageExists || enhanceState.imageOutdated) {
    const building = enhanceState.buildStatus === 'building';
    // Образ есть, но от прошлой версии Bondcast: новые настройки он не понимает.
    const update = enhanceState.imageExists;
    if (!setEnhanceBodyHtml(`
      ${enhanceDemoHtml()}
      <div class="row" data-need>
        <div class="row-label">
          <b>${update ? 'Улучшение обновилось' : 'Скачай улучшение'}</b>
          <span class="row-meta">${update ? 'Около минуты. Эфир не прервётся' : 'Один раз, 3 ГБ. Нужна NVIDIA RTX'}</span>
        </div>
        <button type="button" class="enhance-build primary"${building ? ' disabled' : ''}>${building ? (update ? 'Обновляю…' : 'Скачиваю…') : (update ? 'Обновить' : 'Скачать')}</button>
      </div>
      ${enhanceBuildLines.length ? `<pre class="log-box" id="enhanceBuildLog">${escapeHtml(enhanceBuildLines.slice(-200).join('\n'))}</pre>` : ''}
      ${enhanceState.buildStatus === 'error' ? `<div class="flow-warn">Не скачалось: ${escapeHtml(enhanceState.buildError || '')}</div>` : ''}`)) return;
    enhanceBodyEl.querySelector('.enhance-build').onclick = buildEnhance;
    bindEnhanceDemo();
    const logEl = document.getElementById('enhanceBuildLog');
    if (logEl) logEl.scrollTop = logEl.scrollHeight;
    return;
  }

  const seg = ENHANCE_TARGET_OPTIONS
    .map(([value, label]) => `<button type="button" data-target="${value}" class="${value === enhanceTarget ? 'active' : ''}">${escapeHtml(label)}</button>`)
    .join('');
  const styleSeg = ENHANCE_STYLE_OPTIONS
    .map(([value, label]) => `<button type="button" data-style="${value}" class="${value === enhanceOpts.style ? 'active' : ''}">${escapeHtml(label)}</button>`)
    .join('');
  const smoothSeg = [[false, 'Как с телефона'], [true, 'Вдвое плавнее']]
    .map(([value, label]) => `<button type="button" data-smooth="${value ? 1 : 0}" class="${value === enhanceOpts.smooth ? 'active' : ''}">${label}</button>`)
    .join('');
  const pct = (v) => Math.round(v * 100);
  // Что происходит прямо сейчас — первой строкой. При выключенном тумблере тут
  // пример «было/стало»: настройки ниже меняют его на глазах.
  const connectedHtml = enhanceState.connected
    ? `
      <div class="stack-s">
        ${enhanceStatsHtml()}
        ${enhanceState.error ? `<div class="flow-warn">${escapeHtml(enhanceState.error)}</div>` : ''}
        ${enhanceObsHtml()}
      </div>`
    : enhanceDemoHtml();
  const chips = streamChipsHtml(
    streamCandidates(enhanceState.connected ? enhanceState.streamName : null),
    enhanceTargetStream(),
    'data-enhance-stream',
  );

  if (!setEnhanceBodyHtml(`
    ${connectedHtml}
    ${chips}
    <div>
      <label class="field-label">Разрешение <span class="info" tabindex="0">?<span class="bubble">«Авто» — поднимет до 1080p. 1440p и 4K — для YouTube. Twitch выше 1080p не показывает.</span></span></label>
      <div class="seg" id="enhanceTargetSeg">${seg}</div>
    </div>
    <div>
      <label class="field-label">Характер</label>
      <div class="seg" id="enhanceStyleSeg">${styleSeg}</div>
      <div class="row-meta" style="margin-top:6px">${enhanceOpts.style === 'sharp' ? 'Чётче лицо, мелочи дорисованы' : 'Как снято, без дорисовки'}</div>
    </div>
    <div>
      <label class="field-label">Плавность</label>
      <div class="seg" id="enhanceSmoothSeg">${smoothSeg}</div>
      ${enhanceOpts.smooth ? '<div class="row-meta" style="margin-top:6px">В OBS поставь 60 к/с</div>' : ''}
    </div>
    <details class="nested" id="enhanceAdvanced"${enhanceAdvancedOpen ? ' open' : ''}>
      <summary>Тонкая настройка</summary>
      <div class="body" style="gap:14px">
        <div class="range-field">
          <div class="range-head"><span>Сила эффекта</span><output id="enhanceStrengthOut">${pct(enhanceOpts.strength)}%</output></div>
          <input type="range" id="enhanceStrength" min="0" max="100" step="5" value="${pct(enhanceOpts.strength)}" />
        </div>
        <div class="range-field">
          <div class="range-head"><span>Очистка от квадратов</span><output id="enhanceCleanOut">${pct(enhanceOpts.clean)}%</output></div>
          <input type="range" id="enhanceClean" min="0" max="100" step="5" value="${pct(enhanceOpts.clean)}" />
          <div class="row-meta">Больше — чище, но мягче кожа</div>
        </div>
        <label class="switch-row">
          <span>Чистить до увеличения</span>
          ${switchHtml({ on: enhanceOpts.preclean, small: true, label: 'Чистить до увеличения', attrs: 'id="enhancePreclean"' })}
        </label>
      </div>
    </details>`)) return;
  bindEnhanceDemo();
  bindCopyButtons(enhanceBodyEl);
  enhanceBodyEl.querySelectorAll('[data-enhance-stream]').forEach((btn) => {
    btn.onclick = () => {
      enhancePick = btn.dataset.enhanceStream;
      if (enhanceState.connected && enhanceState.streamName !== enhancePick) connectEnhance(enhancePick);
      else renderEnhanceBody();
    };
  });
  enhanceBodyEl.querySelectorAll('#enhanceTargetSeg button').forEach((btn) => {
    btn.onclick = () => setEnhanceTarget(btn.dataset.target);
  });
  enhanceBodyEl.querySelectorAll('#enhanceStyleSeg button').forEach((btn) => {
    btn.onclick = () => setEnhanceOpt('style', btn.dataset.style);
  });
  enhanceBodyEl.querySelectorAll('#enhanceSmoothSeg button').forEach((btn) => {
    btn.onclick = () => setEnhanceOpt('smooth', btn.dataset.smooth === '1');
  });
  document.getElementById('enhancePreclean').onclick = () => setEnhanceOpt('preclean', !enhanceOpts.preclean);
  // Спойлер помним сами: раздел перерисовывается на каждое изменение статуса.
  const advanced = document.getElementById('enhanceAdvanced');
  advanced.ontoggle = () => {
    if (advanced.open === enhanceAdvancedOpen) return;
    enhanceAdvancedOpen = advanced.open;
    renderEnhanceBody();
  };
  // Ползунки: пока тянут — меняем только цифру (перерисовка оборвала бы
  // перетаскивание), а применяем и перезапускаем воркер, когда отпустили.
  for (const [id, outId, key] of [['enhanceStrength', 'enhanceStrengthOut', 'strength'], ['enhanceClean', 'enhanceCleanOut', 'clean']]) {
    const input = document.getElementById(id);
    input.oninput = () => { document.getElementById(outId).textContent = `${input.value}%`; };
    input.onchange = () => setEnhanceOpt(key, Number(input.value) / 100);
  }
}

function applyEnhanceState() {
  // Не скачано или устарело — тумблер всё равно нажимается: toggleEnhance()
  // подсветит кнопку «Скачать» / «Обновить» под ним.
  setSwitch(enhanceSwitchEl, enhanceOn(), enhancePending !== null);
  renderEnhanceBody();
  renderSectionNav();
}

async function refreshEnhanceStatus() {
  try {
    const res = await fetch('/api/enhance/status');
    enhanceState = await res.json();
  } catch (e) {
    return; // тихо — обновим на следующем опросе
  }
  applyEnhanceState();
}

async function enhancePost(path, body) {
  const res = await fetch(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body || {}),
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.error || `HTTP ${res.status}`);
  return data;
}

async function connectEnhance(name) {
  if (enhanceBusy) return;
  enhanceBusy = true;
  enhancePending = true;
  clearEnhanceError();
  applyEnhanceState();
  try {
    await enhancePost('/api/enhance/connect', { name, target: enhanceTarget, ...enhanceOpts });
  } catch (e) {
    showEnhanceError(`Улучшение не включилось: ${e.message}`);
  } finally {
    enhanceBusy = false;
    await refreshEnhanceStatus();
    enhancePending = null;
    applyEnhanceState();
  }
}

async function disconnectEnhance() {
  if (enhanceBusy) return;
  enhanceBusy = true;
  enhancePending = false;
  clearEnhanceError();
  applyEnhanceState();
  try {
    const data = await enhancePost('/api/enhance/disconnect');
    if (data.warning) showEnhanceError(data.warning);
  } catch (e) {
    showEnhanceError(`Улучшение не выключилось: ${e.message}`);
  } finally {
    enhanceBusy = false;
    await refreshEnhanceStatus();
    enhancePending = null;
    applyEnhanceState();
  }
}

// Новое разрешение при включённом улучшении применяется сразу — перезапуском
// воркера (пара секунд старой картинки в OBS, обрыва нет: панель сначала
// возвращает OBS на исходный поток).
function setEnhanceTarget(value) {
  enhanceTarget = value;
  localStorage.setItem(ENHANCE_TARGET_KEY, value);
  renderEnhanceBody();
  if (enhanceState.connected && enhanceState.streamName && enhanceState.target !== value) {
    connectEnhance(enhanceState.streamName);
  }
}

// Остальные настройки (характер, сила, очистка, плавность) меняются так же —
// перезапуском воркера на ходу. Имена ключей совпадают с полями /api/enhance/status.
function setEnhanceOpt(key, value) {
  enhanceOpts[key] = value;
  localStorage.setItem(ENHANCE_OPTS_KEY, JSON.stringify(enhanceOpts));
  renderEnhanceBody();
  if (enhanceState.connected && enhanceState.streamName && enhanceState[key] !== value) {
    connectEnhance(enhanceState.streamName);
  }
}

async function buildEnhance() {
  if (enhanceBusy) return;
  enhanceBusy = true;
  clearEnhanceError();
  enhanceBuildLines = [];
  try {
    await enhancePost('/api/enhance/build');
    enhanceState.buildStatus = 'building';
    renderEnhanceBody();
    const source = new EventSource('/api/enhance/build/logs');
    source.onmessage = (e) => {
      enhanceBuildLines.push(e.data);
      if (enhanceBuildLines.length > 1000) enhanceBuildLines.shift();
      renderEnhanceBody();
    };
    source.addEventListener('done', () => {
      source.close();
      refreshEnhanceStatus();
    });
    // Без close() EventSource сам переподключается бесконечно (см. субтитры).
    source.onerror = () => source.close();
  } catch (e) {
    showEnhanceError(`Не скачалось: ${e.message}`);
  } finally {
    enhanceBusy = false;
  }
}

enhanceSwitchEl.onclick = toggleEnhance;

refreshEnhanceStatus();
setInterval(refreshEnhanceStatus, 3000);

// Первый показ левой колонки — здесь, а не рядом с её кодом: renderSectionNav()
// читает latestStreams/captionsState/sceneSwitcherState, а они объявлены через let
// ниже по файлу и до этой строки лежат в мёртвой зоне.
navReady = true;
applySections();
renderSectionNav();

// --- Живой лог (адрес с #debug) ---------------------------------------------
// Тот же поток строк, что уходит в файл (см. panel/logger.js), только живьём.
// Фильтры чисто клиентские: строк в буфере максимум 500 + новые, фильтровать на
// сервере смысла нет, а переподключать SSE на каждое изменение фильтра — вредно.
if (debugEnabled) {
  const diagLog = document.getElementById('diagLog');
  const diagSearch = document.getElementById('diagSearch');
  const diagPause = document.getElementById('diagPause');
  const diagClear = document.getElementById('diagClear');

  const LEVEL_ORDER = ['DEBUG', 'INFO', 'WARN', 'ERROR'];
  let diagLines = [];
  let diagPaused = false;

  // Фильтры — чипсы, а не select: наборы короткие и целиком помещаются на экран,
  // держать их за кликом «раскрой список» незачем (тот же приём, что в «Заглушке
  // при обрыве»). Первый чипс в каждом ряду — «не фильтровать», пустое значение.
  const DIAG_SERVICES = ['', 'panel', 'srs', 'srtla-rec', 'overlay', 'asr-worker', 'enhance-worker', 'ui'];
  const DIAG_LEVELS = [['', 'любой'], ['INFO', 'info и выше'], ['WARN', 'warn и выше'], ['ERROR', 'только error']];
  let diagServiceValue = '';
  let diagLevelValue = '';

  function renderDiagChips(el, items, active, onPick) {
    el.innerHTML = items
      .map(([value, label]) => `<button type="button" class="chip ${value === active ? 'is-active' : ''}" data-value="${escapeHtml(value)}">${escapeHtml(label)}</button>`)
      .join('');
    el.querySelectorAll('.chip').forEach((btn) => { btn.onclick = () => onPick(btn.dataset.value); });
  }

  function renderDiagFilters() {
    renderDiagChips(
      document.getElementById('diagServiceChips'),
      DIAG_SERVICES.map((name) => [name, name || 'все']),
      diagServiceValue,
      (value) => { diagServiceValue = value; renderDiagFilters(); renderDiag(); },
    );
    renderDiagChips(
      document.getElementById('diagLevelChips'),
      DIAG_LEVELS,
      diagLevelValue,
      (value) => { diagLevelValue = value; renderDiagFilters(); renderDiag(); },
    );
  }

  // Разбираем строку регуляркой, а не срезами по фиксированным позициям: имена
  // сервисов разной длины ("asr-worker" длиннее колонки), и срезы бы поехали.
  const DIAG_RE = /^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})\s+(DEBUG|INFO|WARN|ERROR)\s+(\S+)\s(.*)$/;

  function diagMatches(line) {
    const needleOnly = diagSearch.value.trim().toLowerCase();
    const m = DIAG_RE.exec(line);
    // Служебные строки («связь оборвалась») не имеют сервиса и уровня — их
    // фильтруем только поиском, иначе они бы пропадали при любом выборе сервиса.
    if (!m) return !needleOnly || line.toLowerCase().includes(needleOnly);
    const [, , level, service] = m;
    if (diagServiceValue && service !== diagServiceValue) return false;
    const min = diagLevelValue;
    if (min && LEVEL_ORDER.indexOf(level) < LEVEL_ORDER.indexOf(min)) return false;
    if (needleOnly && !line.toLowerCase().includes(needleOnly)) return false;
    return true;
  }

  function renderDiag() {
    diagLog.textContent = diagLines.filter(diagMatches).join('\n');
    if (!diagPaused) diagLog.scrollTop = diagLog.scrollHeight;
  }

  renderDiagFilters();
  diagSearch.oninput = renderDiag;
  diagClear.onclick = () => { diagLines = []; renderDiag(); };
  diagPause.onclick = () => {
    diagPaused = !diagPaused;
    diagPause.textContent = diagPaused ? 'Продолжить' : 'Пауза';
  };

  const diagSource = new EventSource('/api/logs/stream');
  diagSource.onmessage = (e) => {
    diagLines.push(e.data);
    // Тот же потолок, что у кольцевого буфера на сервере — страница не должна
    // расти в памяти бесконечно за сутки открытой вкладки.
    if (diagLines.length > 2000) diagLines.shift();
    if (!diagPaused) renderDiag();
  };
  diagSource.onerror = () => {
    diagLines.push('[связь с логом оборвалась, перезагрузи страницу]');
    renderDiag();
  };
}

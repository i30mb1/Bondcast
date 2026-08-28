// --- Утилиты ------------------------------------------------------------
function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

// Сдвиг фазы для повторяющихся анимаций (dot-live/dot-live-accent, conn-packet) —
// несколько штук на экране разом (список активных стримов, чек-лист портов,
// пакет на соединительных линиях между шагами) без этого идут в такт и выглядят
// как одна и та же анимация под копирку. Детерминированно от seed, не
// Math.random() — reconcileSteps сравнивает html строкой и перерисовывает узел
// только когда она реально изменилась, случайное значение на каждый рендер
// заставило бы шаг мигать на каждый опрос. durationSec — длительность анимации
// конкретного элемента (dotPulse/dotPulseAccent — 1.8s, packetTravel — 1.6s), сдвиг
// считается в её пределах.
function pulseDelay(seed, durationSec = 1.8) {
  let hash = 0;
  for (let i = 0; i < seed.length; i++) hash = (hash * 31 + seed.charCodeAt(i)) >>> 0;
  const offset = (hash % 100) / 100 * durationSec;
  return `animation-delay:-${offset.toFixed(2)}s`;
}

// Путь до нужного экрана в приложении — одной строкой над блоком полей. Раньше
// он повторялся в подсказке под КАЖДЫМ полем, и выходило «Идентификатор стрима»
// в подписи и «поле "Идентификатор стрима"» под ним — два раза об одном.
function pathHtml(text) {
  return `<div class="addr-path">${escapeHtml(text)}</div>`;
}

// where — короткая приписка к конкретному полю, если у него есть своя тонкость
// (напр. streamid у Larix). Название поля не повторяет — оно уже в label.
function addrRow(label, value, where) {
  return `
    <div class="addr-row">
      <span class="addr-label">${escapeHtml(label)}</span>
      <code>${escapeHtml(value)}</code>
      <button class="copy-addr" data-value="${escapeHtml(value)}">Копировать</button>
    </div>
    ${where ? `<div class="addr-where">${escapeHtml(where)}</div>` : ''}`;
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
// (см. server/stream/README.md, раздел «Порт не виден снаружи — почему»), и это
// должно быть видно на панели сразу, а не выясняться потом руками через
// docker ps/роутер.
const PORTS_TO_CHECK = [
  { port: 5000, proto: 'udp', label: 'Приём с телефона' },
  { port: 10080, proto: 'udp', label: 'Приём видео' },
  { port: 4455, proto: 'tcp', label: 'Управление OBS', optional: true, note: '. Нужен, только если дёргаешь OBS не из своей сети' },
  { port: 1935, proto: 'tcp', label: 'Приём видео от PRISM' },
  // Не блокирует публикацию (для неё хватает 1935) — но без него у RTMP-источника
  // (PRISM Live) нет рабочего способа предпросмотра в OBS: SRT-play в этой SRS-сборке
  // отдаёт кадры только для стримов, пришедших тоже по SRT, для RTMP-источника
  // остаётся только HTTP-FLV (см. obsPlayUrl) — а он идёт именно через этот порт.
  { port: 8080, proto: 'tcp', label: 'Картинка для OBS', optional: true, note: '. Нужен, чтобы показать в OBS эфир, пришедший по RTMP (PRISM Live)' },
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
      <button type="button" class="primary" data-action="launch-obs" style="align-self:flex-start">Запустить OBS</button>
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

// Один шаг чек-листа внутри раскрытого сценария (замена бывшей общей карточки
// #portStatusCard со списком всех портов сразу) — берёт результат по номеру порта
// из latestPortResults, а не считает сам.
function portStepHtml({ port, proto, label, optional, note }) {
  const protoLabel = proto.toUpperCase();
  const noteText = optional ? (note || '') : '';
  const obsExtras = port === 4455 ? OBS_WEBSOCKET_HOWTO : '';
  const found = latestPortResults.find((r) => r.meta.port === port);
  const rechecking = recheckingPorts.has(port);
  if (!found) {
    return `
      <div class="flow-step">
        <span class="spinner"></span>
        <div><b>Порт ${port}/${protoLabel}</b><span class="flow-step-meta">Проверяю, видно ли его из интернета…</span></div>
      </div>`;
  }
  const { data } = found;
  // Значок в правом углу шага вместо текстовой ссылки «Проверить снова»: он
  // относится к этому шагу и только к нему, и не тянет на себя строку текста.
  const recheckBtn = rechecking
    ? '<span class="spinner step-refresh"></span>'
    : `<button type="button" class="step-refresh recheck-ports" data-port="${port}" title="Проверить снова">↻</button>`;
  if (data.error) {
    // Проверка не удалась — это НЕ «порт закрыт»: check-host.net мог не ответить или
    // упереться в свой лимит проверок. Но причины на этой машине (сервис не запущен,
    // порт не проброшен) видны и без внешней пробы — если такая нашлась, она куда
    // полезнее строчки «проверка не удалась». Сетевые версии тут наоборот не берём:
    // подкреплять их нечем, внешнего ответа-то и не было.
    const dx = diagnoseClosed({ port, proto }, data, latestPortResults);
    const localDx = dx.scope === 'local' ? dx : null;
    return `
      <div class="flow-step">
        <div class="flow-step-dot bad"></div>
        <div>
          <b>Порт ${port}/${protoLabel}: ${localDx ? 'закрыт' : 'не получилось проверить'}</b><span class="flow-step-meta">${escapeHtml(localDx ? localDx.hint : `${data.error} — это не значит, что порт закрыт, попробуй ещё раз`)}</span>${obsExtras}
          ${localDx ? (localDx.actions || '') + (localDx.howto || '') : ''}
        </div>
        ${recheckBtn}
      </div>`;
  }
  if (data.reachable) {
    return `
      <div class="flow-step">
        <div class="flow-step-dot dot-live" style="${pulseDelay('port-' + port)}"></div>
        <div><b>Порт ${port}/${protoLabel}</b><span class="flow-step-meta">${escapeHtml(label)} — из интернета дойдёт (${escapeHtml(data.targetIp)})${escapeHtml(noteText)}</span></div>
        ${recheckBtn}
      </div>`;
  }
  const dx = diagnoseClosed({ port, proto }, data, latestPortResults);
  // У необязательного порта разбор причин не показываем совсем: там ответ всегда
  // один и тот же («OBS не запущен или в нём выключен WebSocket»), и он уже лежит
  // в раскрывашке рядом — дублировать его текстом значит утроить строку.
  const showDx = !optional;
  return `
    <div class="flow-step">
      <div class="flow-step-dot ${optional ? 'warn' : 'bad'}"></div>
      <div>
        <b>Порт ${port}/${protoLabel}</b><span class="flow-step-meta">${optional ? `${escapeHtml(label)} — не отвечает${escapeHtml(noteText)}` : `${escapeHtml(label)} — из интернета не дойдёт${data.targetIp ? ` (${escapeHtml(data.targetIp)})` : ''}. Дома, по своей Wi-Fi, стрим всё равно пойдёт`}</span>${obsExtras}
        ${showDx ? `<div class="row-meta" style="margin-top:6px">${escapeHtml(dx.hint)}</div>` : ''}
        ${showDx ? (dx.actions || '') + (dx.howto || '') : ''}
      </div>
      ${recheckBtn}
    </div>`;
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
    <a class="btn-link" href="${escapeHtml(url)}" target="_blank" rel="noopener">Открыть настройки роутера</a>
    <div class="row-meta" style="margin-top:6px">${escapeHtml(url)}${escapeHtml(note)}</div>`;
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
          так и скажи, «нужно пробросить порт 5000 UDP на компьютер».</li>
        <li>Кнопка Reset на корпусе (зажать секунд на 10) вернёт заводской пароль, но заодно сбросит
          имя и пароль Wi-Fi и настройки подключения к провайдеру — переподключать придётся все
          устройства в доме. Это крайняя мера, не первый шаг.</li>
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
      hint: `Панель не смогла спросить Docker про сервис ${svc || ''} (${local.dockerError}) — пока Docker не отвечает, порт ${port} слушать некому.`,
      howto: detailsHtml('Что делать', `
        <ol>
          <li>Открой Docker Desktop и дождись, пока значок кита перестанет мигать.</li>
          <li>Запусти ярлык «Запустить трансляцию» ещё раз — он поднимет сервисы заново.</li>
        </ol>`),
    };
  }

  if (svc && local.found === false) {
    return {
      scope: 'local',
      hint: `Контейнер ${svc} удалён — порт ${port} на этой машине никто не слушает, роутер тут ни при чём.`,
      actions: serviceFixButton(`Создать и запустить ${svc}`, svc, 'recreate', port),
      howto: detailsHtml('Если кнопка не сработала', `
        <p>Кнопке нужен путь к папке сервера, который задаёт ярлык. Если панель запускали
        не ярлыком, а <code>docker compose</code> вручную — просто запусти ярлык
        «Запустить трансляцию»: он пересоздаёт все контейнеры с нуля.</p>`),
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
        hint: `Порт ${port} на этой машине уже занят другой программой — поэтому ${svc} не смог запуститься.`,
        howto: detailsHtml('Как найти и освободить порт', `
          <ol>
            <li>В PowerShell: <code>Get-NetUDPEndpoint -LocalPort ${port}</code> для UDP или
              <code>Get-NetTCPConnection -LocalPort ${port} -State Listen</code> для TCP —
              в ответе будет <code>OwningProcess</code> (номер процесса).</li>
            <li>Кто это: <code>Get-Process -Id &lt;номер&gt;</code>.</li>
            <li>Частые виновники: второй запущенный экземпляр этого же сервера, свой
              srtla/SRS вне Docker, OBS с включённым сервером, NDI Tools, IIS.</li>
            <li>Закрой программу (или поменяй порт у неё) и запусти ярлык
              «Запустить трансляцию» заново.</li>
          </ol>
          <p style="opacity:.75">Ошибка Docker: <code>${escapeHtml(err.slice(0, 200))}</code></p>`),
      };
    }
    if (/forbidden by its access permissions|access permissions|excluded port/i.test(err)) {
      return {
        scope: 'local',
        hint: `Windows зарезервировал порт ${port} под себя, и ${svc} не смог его занять — это известная особенность Hyper-V/WSL, а не проблема сети.`,
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
        hint: `Сервис ${svc} падает и перезапускается по кругу (перезапусков: ${local.restartCount || '?'}) — порт ${port} успевает закрыться раньше, чем до него достучатся.`,
        howto: detailsHtml('Что делать', `
          <p>Смотри лог сервиса на вкладке «Функции» → «Диагностика»: там будет строка,
          на которой он падает. Чаще всего это занятый порт или испорченный
          <code>srs.conf</code>. Ярлык «Запустить трансляцию» пересобирает контейнеры
          с нуля и лечит второй случай.</p>`),
      };
    }
    return {
      scope: 'local',
      hint: `Сервис ${svc} остановлен — порт ${port} на этой машине никто не слушает.`,
      actions: serviceFixButton(`Запустить ${svc}`, svc, 'start', port),
      howto: err ? detailsHtml('Что говорит Docker', `<p><code>${escapeHtml(err.slice(0, 300))}</code></p>`) : '',
    };
  }

  if (svc && local.running && local.published === false) {
    return {
      scope: 'local',
      hint: `Сервис ${svc} работает, но порт ${port} не отдан из контейнера наружу — снаружи до него не достучаться никаким пробросом.`,
      howto: detailsHtml('Что делать', `
        <p>Так бывает, если контейнер когда-то создали руками, без публикации порта.
        Запусти ярлык «Запустить трансляцию» — он удаляет старые контейнеры и создаёт
        их заново по <code>docker-compose.yml</code>, уже с портами.</p>`),
    };
  }

  // Дозвон контейнер → хост. Для 4455 это главный вопрос вообще: OBS не запущен
  // или в нём не включён WebSocket — снаружи оба случая выглядят как закрытый порт,
  // а лечатся совсем не роутером (шпаргалка и кнопка запуска уже стоят рядом).
  if (local.hostListening === false) {
    return {
      scope: 'local',
      hint: port === 4455
        ? 'OBS на этой машине не слушает порт 4455 — либо OBS не запущен, либо в нём выключен WebSocket-сервер.'
        : `Порт ${port} не отвечает даже на самой этой машине — значит дело не в роутере, а в том, что его никто не занял.`,
      howto: port === 4455 ? '' : detailsHtml('Что делать', `
        <p>Запусти ярлык «Запустить трансляцию» заново и посмотри, не ругается ли окно
        на занятый порт.</p>`),
    };
  }

  // --- Тот ли это вообще адрес ----------------------------------------------
  if (data.ipChanged) {
    return {
      scope: 'address',
      hint: `Панель проверяет адрес ${data.localIp} (он снят один раз, при запуске ярлыка), а сейчас интернет видит этот компьютер как ${data.freshPublicIp} — проверяется не тот адрес.`,
      howto: detailsHtml('Что делать', `
        <ol>
          <li>Скорее всего провайдер сменил адрес — так бывает у динамического IP после
            переподключения. Запусти ярлык «Запустить трансляцию» заново: панель подхватит
            новый адрес, и его же нужно будет заново отдать телефону (QR).</li>
          <li>Если адрес меняется постоянно, договорись с провайдером о статическом IP или
            настрой DDNS — иначе телефон будет терять сервер после каждой смены.</li>
          <li>Реже бывает наоборот: адрес верный, просто у Docker свой путь в интернет и
            изнутри контейнера видно другой выход. Проверь на самом компьютере (любой сайт
            «мой IP»): если он показывает ${escapeHtml(String(data.localIp))} — адрес верный,
            причина в чём-то другом, жми «Проверить снова» и смотри следующий диагноз.</li>
        </ol>`),
    };
  }

  // hosting НЕ отличает "свой VPS" от "включён VPN": почти любой коммерческий
  // VPN-выход тоже стоит в дата-центре и приходит с hosting:true. Замеры по ip-api:
  // Hetzner/DigitalOcean/Scaleway/Google — proxy:false + hosting:true, а реальный
  // VPN-выход (FranTech) — proxy:true + hosting:true. То есть proxy:true сам по себе
  // и есть признак VPN/прокси, а hosting лишь говорит "адрес дата-центра".
  if (data.vpnLikely) {
    return {
      scope: 'address',
      hint: `Адрес ${data.targetIp} помечен как известный VPN/прокси-выход — похоже, на этой машине включён VPN, и проверяется его адрес, а не твой настоящий.`,
      howto: detailsHtml('Что делать', `
        <ol>
          <li>Выключи VPN (клиент целиком, не только «отключить в браузере») и запусти ярлык
            «Запустить трансляцию» заново — панель определит настоящий публичный адрес.</li>
          <li>Проброс портов на роутере к VPN-адресу не относится вообще: трафик приходит на
            чужой сервер, до тебя он не дойдёт, как правило ни настраивай.</li>
          ${data.hostingLikely ? `<li>Если VPN не включён и это твой собственный сервер на VPS —
            метка «прокси» ложная. Тогда причина внутри машины: проверь, что сервис слушает
            <code>0.0.0.0</code>, а не только localhost, и что порт ${port}/${protoLabel} открыт
            в фаерволе машины и в панели хостинга (security group).</li>` : ''}
        </ol>`),
    };
  }

  // Мобильный оператор — единственный случай, где честный ответ «сделать нельзя
  // ничего». Лучше сказать это сразу, чем отправить человека переставлять галки
  // в роутере, который тут ни на что не влияет.
  if (data.mobileLikely) {
    return {
      scope: 'network',
      hint: `Адрес ${data.targetIp} принадлежит мобильному оператору${data.isp ? ` (${data.isp})` : ''} — там абоненты сидят за общим NAT оператора, и проброс порта невозможен в принципе.`,
      howto: detailsHtml('Варианты', `
        <ol>
          <li>Подключить сервер к проводному интернету (домашний провайдер) — самый простой путь.</li>
          <li>Заказать у оператора услугу «белый / публичный IP» — у большинства она есть,
            обычно платная и включается в личном кабинете.</li>
          <li>Не держать приёмник дома вообще: арендовать сервер (VPS) с белым адресом и поднять
            приём бондинга там, а домой уже забирать готовый поток.</li>
        </ol>`),
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
      hint: `Проба снаружи дошла до ${data.targetIp} и получила отказ — путь работает, но порт ${port}/${protoLabel} на том конце никто не слушает.`,
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
      hint: `У этой машины публичный адрес напрямую, роутера между ней и интернетом нет — значит порт ${port}/${protoLabel} режет фаервол самой машины или фаервол хостинга.`,
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
      hint: `Порт ${openPort} снаружи виден, а ${port}/${protoLabel} — нет. Значит до этой машины из интернета пускают, и дело в правиле именно для порта ${port}.`,
      howto: detailsHtml('Что проверить', `
        <p>Раз соседний порт работает, серый IP провайдера, двойной NAT и «фаервол режет всё
        подряд» отпадают — ищем то, что отличает именно этот порт.</p>
        ${forwardingHowtoHtml(port, proto, data)}
        <p>Если правило точно верное — остаётся фаервол с правилом на конкретный порт:</p>
        ${firewallHowtoHtml(port, proto)}`),
    };
  }

  return {
    scope: 'network',
    hint: `Пакеты снаружи молча теряются: ни один проверенный порт до этой машины не доходит (адрес ${data.targetIp}). Сервис на месте, значит режут по дороге.`,
    howto:
      detailsHtml('1. Проброс порта на роутере — начни отсюда', forwardingHowtoHtml(port, proto, data)) +
      detailsHtml('2. Проверить, белый ли у тебя адрес (серый IP / второй роутер)', grayIpHowtoHtml(data)) +
      detailsHtml('3. Фаервол Windows и антивирус', firewallHowtoHtml(port, proto)) +
      detailsHtml('4. Если ничего из этого', `
        <p>Остаётся редкое: некоторые провайдеры режут входящие подключения на домашних
        тарифах. Признак — адрес белый, правило проброса верное, фаервол снят, а снаружи
        не видно ни одного порта. Это лечится только звонком провайдеру (попросить снять
        ограничение или дать «белый IP» отдельной услугой) либо переносом приёмника на
        арендованный сервер.</p>`),
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
    statsEl.textContent = 'Сейчас ничего не идёт.';
    return;
  }
  statsEl.innerHTML = `
    ${escapeHtml(formatCodec(s.video, s.audio))}<br>
    Принимаем ${escapeHtml(formatKbps(s.kbpsRecv30s))} · отдаём ${escapeHtml(formatKbps(s.kbpsSend30s))}<br>
    За весь эфир: принято ${escapeHtml(formatBytes(s.recvBytes))}, отдано ${escapeHtml(formatBytes(s.sendBytes))}`;
}

async function openPreview(name) {
  teardownPreviewPlayer();
  currentPreviewName = name;
  previewPanelEl.innerHTML = `
    <div class="page-preview-head">
      <b>Эфир: ${escapeHtml(name)}</b>
      <button type="button" class="page-preview-close">✕</button>
    </div>
    <video id="previewVideo" width="100%" autoplay muted playsinline></video>
    <div class="page-preview-bar">
      <button type="button" id="previewMute">🔇 Включить звук</button>
      <button type="button" id="previewFull">⛶ Во весь экран</button>
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
    muteBtn.textContent = video.muted ? '🔇 Включить звук' : '🔊 Выключить звук';
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
      statusEl.textContent = 'Этот браузер не умеет показывать живое видео. Открой панель в Chrome или Edge.';
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
// Заголовки держим короткими: три плашки стоят в ряд, и перенос у одной делает
// весь ряд разной высоты — читается как поломка вёрстки, а не как текст.
const FLOWS = [
  { id: 'bondcast', icon: '📱', title: 'Через Bondcast', hint: 'Настроится само по коду' },
  { id: 'other-app', icon: '⇄', title: 'Другое приложение', hint: 'Moblin, Larix, PRISM — адрес вручную' },
  { id: 'invite', icon: '👥', title: 'Компьютер друга', hint: 'Друг стримит из своего OBS' },
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
  return `
    <div class="flow-step">
      <div class="flow-step-dot dot-live-accent" style="${pulseDelay('net-mode')}"></div>
      <div>
        <b>Откуда телефон будет подключаться</b>
        <div class="app-seg seg" style="margin-top:8px">
          <button type="button" data-netmode="lan" class="${mode === 'lan' ? 'active' : ''}">Дома, по Wi-Fi</button>
          <button type="button" data-netmode="internet" class="${mode === 'internet' ? 'active' : ''}">Через интернет</button>
        </div>
      </div>
    </div>`;
}

function lanStepHtml() {
  const tail = availableScopes().length < 2
    ? 'Внешний адрес узнать не удалось — для стрима с улицы проверь интернет и запусти ярлык заново.'
    : 'На улице, из мобильного интернета, этот адрес не сработает — тогда переключи на «Через интернет».';
  return `
    <div class="flow-step">
      <div class="flow-step-dot dot-live" style="${pulseDelay('lan-ok')}"></div>
      <div><b>Телефон в той же Wi-Fi — больше ничего не нужно</b><span class="flow-step-meta">Роутер настраивать не надо. ${escapeHtml(tail)}</span></div>
    </div>`;
}

// Шаги про сеть: в домашнем режиме проверять нечего (роутер в этом разговоре не
// участвует), в интернет-режиме — прежний чек-лист с адресом и портами.
function netSteps(host, portItems) {
  if (effectiveNetMode() === 'lan') {
    return [{ key: 'lan-note', html: lanStepHtml() }];
  }
  return [{ key: 'ip', html: ipStepHtml(host) }, ...portItems];
}

function noHostWarningHtml() {
  return `<div class="flow-warn">Панель не знает адрес этого компьютера. Закрой её и запусти ярлык «Запустить трансляцию» на рабочем столе ещё раз.</div>`;
}

function noHostWarningItems() {
  return [{ key: 'warn', html: noHostWarningHtml() }];
}

// Шаг "IP статический" — не результат проверки порта, а просто напоминание какой
// адрес будет использован ниже в этой же ветке; зелёный, пока адрес вообще известен.
function ipStepHtml(host) {
  if (!host) {
    return `<div class="flow-step"><div class="flow-step-dot bad"></div><div><b>Адрес компьютера неизвестен</b><span class="flow-step-meta">Запусти ярлык «Запустить трансляцию» на рабочем столе</span></div></div>`;
  }
  return `<div class="flow-step"><div class="flow-step-dot dot-live" style="${pulseDelay('static-ip')}"></div><div><b>Адрес этого компьютера</b><span class="flow-step-meta">${escapeHtml(host.mobileSrtlaHost)} — по нему телефон его и найдёт</span></div></div>`;
}

// Раньше здесь было «прогрессивное раскрытие»: шаг с закрытым обязательным портом
// обрывал список, и всё, что ниже — в том числе финальный QR и адреса, — просто не
// показывалось. На практике это давало тупик: у человека закрыт порт (VPN, нет
// проброса — самый частый случай), и панель не даёт ему ВООБЩЕ ничего, кроме
// красной строки. При этом в домашней сети всё бы прекрасно работало.
//
// Теперь наоборот: чек-лист показывает состояние сети, но ничего не прячет. Пустые
// (выключенные) шаги отфильтровываем — их формируют сами ветки, см. netSteps ниже.
function gateSteps(items) {
  return items.filter((item) => item.html);
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
  const host = activeHost();
  if (!host) return noHostWarningItems();
  const finalStep = `
    <div class="flow-step flow-step-final">
      <div class="flow-step-final-head"><div class="flow-step-dot dot-live-accent" style="${pulseDelay('qr-scan')}"></div><b>Наведи телефон на этот код</b></div>
      <div class="name-row">
        <input type="text" id="streamName" value="${escapeHtml(currentStreamName)}" placeholder="имя стрима" />
        <button type="button" class="dice-btn" id="regenName" title="Придумать другое имя">🎲</button>
      </div>
      <img src="${host.qrDataUrl}" alt="Код для подключения" style="display:block;margin:4px auto;border-radius:8px;width:160px;height:160px;background:#fff" />
      <div class="row-meta" style="text-align:center">Открой Bondcast → Настройки → значок камеры → наведи → «Стримить»</div>
    </div>`;
  return gateSteps([
    { key: 'net-mode', html: netModeStepHtml() },
    ...netSteps(host, [
      { key: 'port-5000', html: portStepHtml(PORTS_TO_CHECK[0]) },
      { key: 'port-4455', html: portStepHtml(PORTS_TO_CHECK[2]) },
    ]),
    { key: 'final', html: finalStep },
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
      <div class="app-seg seg">
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
    ? 'В PRISM Live: настройки трансляции → RTMP-вход'
    : isLarix
      ? 'В Larix: Settings → Connections → New connection'
      : 'В Moblin: Настройки → Стримы → «Создать» → «Пользовательский» → SRT(LA)';
  const addrRows = isPrism
    ? pathHtml(path) + addrRow('URL трансляции', host.rtmpUrl) + addrRow('Ключ трансляции', currentStreamName)
    : pathHtml(path) +
      addrRow('URL', host.obsSrtUrl) +
      addrRow(
        isLarix ? 'streamid' : 'Идентификатор стрима',
        host.obsSrtStreamId,
        isLarix ? 'Без него Larix будет смотреть чужой эфир, а не вести свой.' : null,
      );
  const finalStep = `
    <div class="flow-step flow-step-final">
      <div class="flow-step-final-head"><div class="flow-step-dot dot-live-accent" style="${pulseDelay('choose-app')}"></div><b>Чем снимаешь?</b></div>
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
    { key: 'net-mode', html: netModeStepHtml() },
    ...netSteps(host, steps),
    { key: 'final', html: finalStep },
  ]);
}

function inviteFlowBody() {
  const host = inviteHosts.find((h) => h.isPublic);
  if (!host) {
    return [{ key: 'warn', html: '<div class="flow-warn">Внешний адрес компьютера узнать не удалось — без него друг снаружи не достучится. Проверь интернет и запусти ярлык «Запустить трансляцию» заново.</div>' }];
  }
  const finalStep = `
    <div class="flow-step flow-step-final">
      <div class="flow-step-final-head"><div class="flow-step-dot dot-live-accent" style="${pulseDelay('obs-friend-data')}"></div><b>Отправь другу эти две строки</b></div>
      ${pathHtml('Друг вставляет их у себя в OBS: Настройки → Трансляция → Служба «Настраиваемый»')}
      ${addrRow('Сервер', host.obsSrtUrl)}
      <div class="name-row">
        <input type="text" id="inviteName" value="${escapeHtml(host.obsSrtStreamId)}" readonly style="font-family:'SF Mono',Consolas,monospace" />
        <button type="button" class="dice-btn" id="regenInvite" title="Сделать другой ключ">🎲</button>
      </div>
      <div class="addr-where">↑ Ключ потока</div>
    </div>`;
  // Режима «дома по Wi-Fi» у этой ветки нет: друг сидит не в твоей квартире,
  // домашний адрес ему бесполезен.
  return gateSteps([
    { key: 'ip', html: ipStepHtml(host) },
    { key: 'port-10080', html: portStepHtml(PORTS_TO_CHECK[1]) },
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
  contentEl.querySelectorAll('[data-netmode]').forEach((btn) => {
    btn.onclick = () => setNetMode(btn.dataset.netmode);
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
        <button type="button" class="copy-addr" data-value="${escapeHtml(obsPlayUrl(s.name))}">Ссылка для OBS</button>
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
      <b>Свободное место</b>
      <div class="hint">Здесь появится каждый, кто начнёт стримить на этот компьютер</div>
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
  // .flow-warn красит весь свой текст в красный цвет ошибки (это ок для самой
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
    const extra = e.code === 'obs_unreachable' ? OBS_WEBSOCKET_HOWTO : '';
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
      'Не вижу ни одной сцены OBS. Запусти OBS и включи в нём WebSocket-сервер — без этого панель не может им управлять.',
      OBS_WEBSOCKET_HOWTO,
    );
    return;
  }
  postSceneSwitcher({ enabled: true, watchStreamName, fallbackScene, delaySec: sceneSwitcherState.delaySec, minBitrateKbps: sceneSwitcherState.minBitrateKbps });
};

function renderSceneSwitcherBody() {
  if (obsScenesError) {
    sceneSwitcherBodyEl.innerHTML = `
      <div class="row-meta">Не удалось получить список сцен из OBS: ${escapeHtml(obsScenesError)}.</div>
      ${OBS_WEBSOCKET_HOWTO}
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
  const stateNote = { watching: ' · слежу за сигналом', switched: ' · сейчас показываю заглушку' }[sceneSwitcherState.state] || '';
  // Чипсы вместо выпадающих списков: и стримов, и сцен обычно единицы, и держать
  // их за кликом «раскрой select» незачем — так весь набор виден сразу.
  const chips = (values, active, attr) =>
    values
      .map((v) => `<button type="button" class="chip ${v === active ? 'is-active' : ''}" ${attr}="${escapeHtml(v)}">${escapeHtml(v)}</button>`)
      .join('');
  sceneSwitcherBodyEl.innerHTML = `
    <div>
      <label class="field-label">За каким стримом следить</label>
      <div class="chip-row">${chips(streamNames, sceneSwitcherState.watchStreamName, 'data-watch-stream')}</div>
    </div>
    <div style="margin-top:12px">
      <label class="field-label">Какую сцену показать</label>
      <div class="chip-row">${chips(availableObsScenes, sceneSwitcherState.fallbackScene, 'data-scene')}</div>
    </div>
    <div class="range-field" style="margin-top:8px">
      <div class="range-head"><span>Ждать перед переключением${escapeHtml(stateNote)}</span><output id="switchDelayOut">${sceneSwitcherState.delaySec} сек</output></div>
      <input type="range" id="switchDelay" min="0" max="30" value="${sceneSwitcherState.delaySec}" />
      <div class="row-meta" style="margin-top:6px">Столько терпим после пропажи картинки. Вернётся — сами вернём рабочую сцену</div>
    </div>
    <div class="range-field" style="margin-top:8px">
      <div class="range-head"><span>Считать обрывом, если качество ниже</span><output id="minBitrateOut">${sceneSwitcherState.minBitrateKbps ? sceneSwitcherState.minBitrateKbps + ' кбит/с' : 'выкл'}</output></div>
      <input type="range" id="minBitrate" min="0" max="20000" step="100" value="${sceneSwitcherState.minBitrateKbps || 0}" />
      <div class="row-meta" style="margin-top:6px">Связь не оборвалась, но картинка развалилась в кашу — тоже покажем заглушку. «Выкл» — реагировать только на полную пропажу</div>
    </div>
    ${sceneSwitcherState.lastError ? `<div class="flow-warn">${escapeHtml(sceneSwitcherState.lastError)}</div>` : ''}
  `;
  sceneSwitcherBodyEl.querySelectorAll('[data-watch-stream]').forEach((btn) => {
    btn.onclick = () => postSceneSwitcher({ enabled: true, watchStreamName: btn.dataset.watchStream, fallbackScene: sceneSwitcherState.fallbackScene, delaySec: sceneSwitcherState.delaySec, minBitrateKbps: sceneSwitcherState.minBitrateKbps });
  });
  sceneSwitcherBodyEl.querySelectorAll('[data-scene]').forEach((btn) => {
    btn.onclick = () => postSceneSwitcher({ enabled: true, watchStreamName: sceneSwitcherState.watchStreamName, fallbackScene: btn.dataset.scene, delaySec: sceneSwitcherState.delaySec, minBitrateKbps: sceneSwitcherState.minBitrateKbps });
  });
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
  return parts.join(' · ') || 'формат пока не определился';
}

// --- Стадии карточки: установка ПО -> список стримов -> подключено ---------
function applyCaptionsStage() {
  const ready = captionsState.imageExists;
  capBuildStageEl.hidden = ready;
  capReadyStageEl.hidden = !ready;
  const buildBtn = capBuildStageEl.querySelector('.cap-build');
  buildBtn.disabled = captionsState.buildStatus === 'building';
  buildBtn.textContent = captionsState.buildStatus === 'building' ? 'Скачиваю…' : 'Скачать';
}
capBuildStageEl.querySelector('.cap-build').onclick = buildCaptions;
addVoiceBtnEl.title = `${ENROLL_DURATION_SEC} секунд. Говорить всё это время должен только один человек`;
addVoiceBtnEl.onclick = () => {
  if (captionsState.streamName) enrollVoice(captionsState.streamName);
};

// Настройки распознавания применяются сами (см. applyRecognitionModelIfConnected)
// — отдельной кнопки "Применить" тут больше нет. "Смотреть" тоже убрали:
// в контексте подключения субтитров превью стрима не нужно, это не тот экран.
function streamRowHtml(s) {
  const isThisConnected = captionsState.connected && captionsState.streamName === s.name;
  const actionHtml = isThisConnected
    ? '<button class="cap-disconnect primary">Выключить субтитры</button>'
    : `<button class="cap-connect primary" data-name="${escapeHtml(s.name)}">Включить субтитры</button>`;
  const loadingHtml = isThisConnected && !captionsState.ready
    ? '<div class="row-meta" style="margin-top:8px">⏳ Готовлюсь. В первый раз надо скачать модель (~1 ГБ) — это минута-две, дальше будет сразу.</div>'
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
    streamCardsEl.innerHTML = '<div class="row"><span class="row-label"><span class="row-meta">Сначала запусти стрим — тогда будет к чему подключать субтитры.</span></span></div>';
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
  obsUrlRowEl.innerHTML = addrRow('Ссылка на субтитры для OBS', captionsState.overlayUrl, 'В OBS: Источники → + → Браузер → вставить в поле «Адрес». Один раз: внешний вид меняется прямо здесь, ссылку заново копировать не надо.');
  bindCopyButtons(obsUrlRowEl);
}

// --- Мини-список голосов ------------------------------------------------------
function voiceEnrollProgressHtml() {
  const elapsed = enrollStartedAt ? (Date.now() - enrollStartedAt) / 1000 : 0;
  const capturing = elapsed < ENROLL_DURATION_SEC;
  const label = capturing
    ? `🎙 Говори без пауз — ещё ${Math.max(0, Math.ceil(ENROLL_DURATION_SEC - elapsed))}с`
    : '⏳ Запоминаю голос…';
  return `<span class="row-meta">${label}</span><progress value="${Math.min(elapsed, ENROLL_DURATION_SEC).toFixed(1)}" max="${ENROLL_DURATION_SEC}" style="width:100%;margin-top:6px"></progress>`;
}

function voiceRowHtml(voice) {
  const isEnrollingThis = captionsState.enrollStatus === 'running' && captionsState.enrollVoiceId === voice.id;
  const busy = captionsState.enrollStatus === 'running';
  const body = isEnrollingThis
    ? voiceEnrollProgressHtml()
    : `<div class="range-field">
        <div class="range-head"><span>Насколько строго узнавать</span><output class="voice-threshold-out">${Number(voice.threshold).toFixed(2)}</output></div>
        <input type="range" class="voice-threshold-input" min="0" max="1" step="0.01" value="${voice.threshold}" ${busy ? 'disabled' : ''} />
      </div>`;
  return `
    <div class="voice-row" data-voice-id="${escapeHtml(voice.id)}">
      <div class="voice-row-main">
        <input type="text" class="voice-name-input" value="${escapeHtml(voice.name)}" maxlength="40" ${busy ? 'disabled' : ''} />
        <button type="button" class="voice-rerecord" title="Записать заново" ${busy ? 'disabled' : ''}>🔁</button>
        <button type="button" class="voice-delete" title="Удалить голос" ${busy ? 'disabled' : ''}>✕</button>
      </div>
      ${body}
      ${!voice.hasEmbedding && !isEnrollingThis ? '<div class="row-meta">Голос ещё не записан до конца</div>' : ''}
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
    .catch((e) => showCapError(`Не получилось сохранить голос: ${e.message}`));
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
  addVoiceBtnEl.textContent = enrollingNew ? '🎙 Идёт запись…' : '🎙 Записать голос';

  if (voicesListEl.contains(document.activeElement)) return; // юзер сейчас печатает/тащит слайдер — не трогаем DOM

  const errorHtml = captionsState.enrollStatus === 'error'
    ? `<div class="flow-warn">Не получилось записать голос: ${escapeHtml(captionsState.enrollError || '')}</div>`
    : '';
  const rows = captionsState.voices.map(voiceRowHtml).join('');
  const pendingRow = enrollingNew ? `<div class="voice-row">${voiceEnrollProgressHtml()}</div>` : '';
  const body = rows + pendingRow;
  voicesListEl.innerHTML = errorHtml + (body || '<div class="row-meta">Голосов пока нет — всё, что услышим, будет подписано «Кто-то»</div>');
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
    showCapError(`Не получилось скачать: ${e.message}`);
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
    showCapError(`Субтитры не включились: ${e.message}`);
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
    showCapError(`Не получилось записать голос: ${e.message}`);
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

function resetUpdateButton() {
  updateConfirmPending = false;
  updateBannerBtnEl.textContent = 'Обновить';
  updateBannerBtnEl.classList.remove('primary');
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
  } else if (data.status === 'done') {
    clearUpdateStartTimeout();
    updateProgressBarEl.value = 100;
    updateProgressTextEl.textContent = 'Готово! Закрой это окно и запусти ярлык «Запустить трансляцию» заново.';
    stopUpdateProgressPolling();
  } else if (data.status === 'error') {
    clearUpdateStartTimeout();
    updateProgressBarEl.removeAttribute('value');
    updateProgressTextEl.textContent = `Не получилось: ${data.message || 'непонятно что случилось'}`;
    stopUpdateProgressPolling();
  } else {
    updateProgressBarEl.removeAttribute('value');
    updateProgressTextEl.textContent = 'Запускаю обновление…';
  }
}

async function pollUpdateProgress() {
  try {
    const res = await fetch('/api/update/progress');
    const data = await res.json();
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
    updateBannerBtnEl.textContent = 'Точно? Скачаю и установлю сам';
    updateBannerBtnEl.classList.add('primary');
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
    updateProgressTextEl.innerHTML = 'Не запускается — похоже, установлена версия без этой функции. ' +
      '<a href="https://github.com/i30mb1/Bondcast/releases/latest" target="_blank" rel="noopener">Скачай последнюю версию вручную</a> ' +
      'и поставь один раз — дальше обновления заработают сами.';
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

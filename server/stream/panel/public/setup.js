const ALLOWED = ['srs', 'srtla-rec'];

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

function dot(state) {
  // state: true = ok, false = bad, null = в процессе
  const cls = state === true ? 'ok' : state === false ? 'bad' : '';
  return `<span class="dot ${cls}"></span>`;
}

async function checkDocker() {
  const el = document.getElementById('dockerStatus');
  try {
    const res = await fetch('/api/status');
    const data = await res.json();
    const allUp = ALLOWED.every((name) => data.find((c) => c.name === name)?.running);
    el.innerHTML = allUp
      ? `${dot(true)}Всё запущено`
      : `${dot(false)}Не все сервисы подняты — открой расширенную панель и нажми Start`;
  } catch (e) {
    el.innerHTML = `${dot(false)}Панель не отвечает: ${e.message}`;
  }
}

async function checkPort() {
  const el = document.getElementById('portStatus');
  const hint = document.getElementById('portHint');
  el.innerHTML = `${dot(null)}Стучусь снаружи через check-host.net…`;
  try {
    // 5000/udp — порт srtla-rec, его слушает мобильное приложение (бондинг включён по умолчанию).
    const res = await fetch('/api/reachability?port=5000&proto=udp');
    const data = await res.json();
    if (data.error) throw new Error(data.error);

    if (data.reachable) {
      el.innerHTML = `${dot(true)}Порт 5000/UDP открыт (${data.targetIp})`;
      hint.textContent = '';
    } else {
      el.innerHTML = `${dot(false)}Порт 5000/UDP снаружи не виден (${data.targetIp})`;
      hint.textContent = data.vpnLikely
        ? 'Выключи VPN и запусти ярлык «Запустить трансляцию» заново.'
        : data.natLikely
          ? 'Настрой проброс порта 5000 UDP на роутере.'
          : 'Выключи антивирус/файрвол и попробуй снова.';
    }
  } catch (e) {
    el.innerHTML = `${dot(false)}Проверка не удалась: ${e.message}`;
  }
}

async function renderQr() {
  const name = document.getElementById('streamName').value.trim() || 'livestream';
  const img = document.getElementById('qr');
  try {
    const res = await fetch(`/api/connections?name=${encodeURIComponent(name)}`);
    const data = await res.json();
    const host = data.hosts && data.hosts[0];
    if (!host) return;
    img.src = host.qrDataUrl; // рендерится на сервере (см. /api/connections) — без внешних CDN
  } catch (e) {
    // тихо — шаги 1/2 важнее QR
  }
}

document.getElementById('streamName').value = getOrCreateStreamName();
document.getElementById('streamName').addEventListener('input', () => {
  localStorage.setItem(STREAM_NAME_KEY, document.getElementById('streamName').value.trim());
  renderQr();
});
document.getElementById('regenName').addEventListener('click', () => {
  document.getElementById('streamName').value = regenerateStreamName();
  renderQr();
});
checkDocker();
checkPort();
renderQr();

// Замер «где теряются пакеты». SRS знает, сколько пакетов до него не дошло, но не
// знает где. Ответ даёт сравнение двух счётчиков за одни и те же секунды: сколько
// байт пришло в сетевую карту Windows и сколько дошло до контейнера SRS.
//   • Потерянные пакеты в карту не приходили (карта ≈ контейнер) — их отбросили ещё
//     до компьютера: сеть кодера, роутер, провайдер.
//   • Потерянные пакеты в карту пришли (карта больше контейнера как раз на них) —
//     они пропали уже на этом компьютере: Windows, Docker.
// Счётчики Windows снимает net-check.ps1 на хосте (панель из контейнера их не
// видит), счётчики контейнера — сама панель через docker exec. Здесь только разбор
// и вывод, без сети и Docker — чтобы пороги можно было гонять в тестах.

// Пакет SRT с MPEG-TS на проводе: 1316 байт нагрузки + заголовки SRT 16, UDP 8, IP 20, Ethernet 14.
const WIRE_BYTES = 1374;
// Недостачу «карта минус контейнер» сравниваем с потоком потерянных пакетов.
// До четверти — потерянное в карту не приходило. Посторонний трафик недостачу
// только увеличивает, поэтому малая недостача — вывод надёжный.
const BEFORE_PC_SHARE = 0.25;
// От 60 % — потерянное в карту пришло. Одних байтов тут мало (их даёт и любая
// загрузка на этом же компьютере), поэтому то же самое должно быть видно и по
// числу UDP-датаграмм, дошедших до программ Windows.
const INSIDE_PC_SHARE = 0.6;
// Контейнер получил заметно больше, чем пришло в карту: часть потока идёт к SRS
// изнутри компьютера, сравнение не работает.
const BYPASS_SHARE = -0.5;
// То же самое, если Windows не видела и половины датаграмм, дошедших до SRS: эфир
// идёт к нему не через сеть Windows, а байты на карте — посторонний трафик, который
// мог случайно совпасть со скоростью эфира.
const UDP_SEEN_SHARE = 0.5;
// Потерь меньше — их не отличить от обычного фонового трафика на карте.
const MIN_LOST_MBPS = 0.5;
const MIN_LOST_OF_STREAM = 0.1;
// Ниже этого потерь, считай, нет — мерить нечего.
const MIN_LOSS_SHARE = 0.01;
// Буфер приёма SRS выбросил не меньше половины потерянного — искать дальше незачем.
const BUFFER_SHARE = 0.5;
const MIN_WINDOW_SEC = 5;

// Счётчик — неотрицательное число (или строка с ним: большие значения PowerShell
// может отдать и так). Всё остальное — «нет значения».
const num = (v) => {
  if ((typeof v !== 'number' && typeof v !== 'string') || v === '') return null;
  const n = Number(v);
  return Number.isFinite(n) && n >= 0 ? n : null;
};
// Строки попадают в лог и на страницу: режем длину и убираем переводы строк, иначе
// одним отчётом можно было бы вписать в лог поддельную строку.
const str = (v, max) => String(v == null ? '' : v).replace(/\s+/g, ' ').trim().slice(0, max);

// Отчёт приходит по публичному маршруту — берём из него только ожидаемые поля
// ожидаемых типов, всё остальное отбрасываем.
function sanitizeHostSample(raw) {
  if (!raw || typeof raw !== 'object') return null;
  const t = num(raw.t);
  const udp = raw.udp && typeof raw.udp === 'object' ? raw.udp : {};
  if (t == null || !Array.isArray(raw.adapters)) return null;
  const adapters = raw.adapters.slice(0, 32).map((a) => ({
    name: str(a && a.name, 80),
    ips: (Array.isArray(a && a.ips) ? a.ips : []).slice(0, 8).map((ip) => str(ip, 45)),
    physical: Boolean(a && a.physical === true),
    bytesReceived: num(a && a.bytesReceived),
    discarded: num(a && a.discarded) || 0,
    errors: num(a && a.errors) || 0,
  })).filter((a) => a.bytesReceived != null);
  if (!adapters.length) return null;
  return {
    t,
    udp: { received: num(udp.received), discarded: num(udp.discarded) || 0, errors: num(udp.errors) || 0 },
    adapters,
  };
}

// /proc/net/dev: «  eth0: <rx bytes> <rx packets> <errs> <drop> ...». Складываем все
// интерфейсы, кроме петли: имя у сетевого интерфейса контейнера не обязано быть eth0.
function parseProcNetDev(text) {
  let rxBytes = 0;
  let found = false;
  for (const row of String(text).split('\n')) {
    const m = /^\s*([^\s:]+):\s*(\d+)\s+(\d+)\s+\d+\s+(\d+)/.exec(row);
    if (!m || m[1] === 'lo') continue;
    found = true;
    rxBytes += Number(m[2]);
  }
  return found ? { rxBytes } : null;
}

// /proc/net/snmp: две строки «Udp:» — имена полей и значения. Порядок полей между
// ядрами менялся, поэтому берём по имени, а не по позиции.
function parseProcSnmpUdp(text) {
  const rows = String(text).split('\n').filter((row) => row.startsWith('Udp:'));
  if (rows.length < 2) return null;
  const names = rows[0].trim().split(/\s+/).slice(1);
  const values = rows[1].trim().split(/\s+/).slice(1).map(Number);
  const get = (name) => {
    const i = names.indexOf(name);
    return i >= 0 && Number.isFinite(values[i]) ? values[i] : 0;
  };
  return { inDatagrams: get('InDatagrams'), inErrors: get('InErrors'), rcvbufErrors: get('RcvbufErrors') };
}

// text — вывод `cat /proc/net/dev /proc/net/snmp` из контейнера SRS. Файлы режем по
// первой строке второго («Ip:»): строки счётчиков протоколов выглядят так же, как
// строки интерфейсов («имя: числа»), и без этого «Udp:» посчитался бы сетевой картой.
function parseContainerSample(text, t) {
  const source = String(text);
  const cut = source.search(/^Ip:/m);
  const dev = parseProcNetDev(cut >= 0 ? source.slice(0, cut) : source);
  const udp = parseProcSnmpUdp(source);
  if (!dev || !udp) return null;
  return { t, ...dev, ...udp };
}

// Карты, через которые эфир может прийти снаружи, — настоящие, с адресом из LAN_IPS
// (его при запуске снимает get-host-ips.ps1 тоже только с физических адаптеров).
// Адрес с тех пор мог смениться — тогда верим пометке «физическая» из отчёта.
// Виртуальные (vEthernet, WSL, VPN) не берём никогда: через них идёт обратный поток
// из контейнеров на этот же компьютер, и он раздул бы «пришло снаружи».
function pickAdapters(hostA, hostB, lanIps) {
  const pairs = [];
  for (const b of hostB.adapters) {
    const a = hostA.adapters.find((x) => x.name === b.name);
    if (!a || b.bytesReceived < a.bytesReceived) continue; // карта появилась или счётчик сбросился
    pairs.push({
      name: b.name,
      physical: b.physical,
      byIp: b.ips.some((ip) => lanIps.includes(ip)),
      bytes: b.bytesReceived - a.bytesReceived,
      discarded: Math.max(0, b.discarded - a.discarded) + Math.max(0, b.errors - a.errors),
    });
  }
  const byIp = pairs.filter((p) => p.byIp);
  return byIp.length ? byIp : pairs.filter((p) => p.physical);
}

// srt — сумма статистики издателей за окно замера (srt-health.totalsBetween).
// enhanceActive — «Улучшение картинки» публикует в SRS свой поток изнутри компьютера.
function locateLoss({ hostA, hostB, containerA, containerB, lanIps = [], srt, enhanceActive = false }) {
  if (!hostA || !hostB) return { verdict: 'error', reason: 'нет счётчиков Windows' };
  if (!containerA || !containerB) return { verdict: 'error', reason: 'нет счётчиков контейнера SRS' };
  const hostSec = (hostB.t - hostA.t) / 1000;
  const containerSec = (containerB.t - containerA.t) / 1000;
  if (hostSec < MIN_WINDOW_SEC || containerSec < MIN_WINDOW_SEC) return { verdict: 'error', reason: 'слишком короткий замер' };
  if (containerB.rxBytes < containerA.rxBytes || containerB.inDatagrams < containerA.inDatagrams) {
    return { verdict: 'error', reason: 'SRS перезапустился во время замера' };
  }
  const adapters = pickAdapters(hostA, hostB, lanIps);
  if (!adapters.length) return { verdict: 'error', reason: 'не нашлась сетевая карта этого компьютера' };

  const mbps = (bytes, sec) => (bytes * 8) / sec / 1e6;
  const hostBytes = adapters.reduce((acc, a) => acc + a.bytes, 0);
  const facts = {
    seconds: Math.round(hostSec),
    adapter: adapters.map((a) => a.name).join(', '),
    hostMbps: mbps(hostBytes, hostSec),
    containerMbps: mbps(containerB.rxBytes - containerA.rxBytes, containerSec),
    hostUdpPps: hostA.udp.received != null && hostB.udp.received != null && hostB.udp.received >= hostA.udp.received
      ? (hostB.udp.received - hostA.udp.received) / hostSec
      : null,
    containerUdpPps: (containerB.inDatagrams - containerA.inDatagrams) / containerSec,
    hostDiscarded: adapters.reduce((acc, a) => acc + a.discarded, 0)
      + Math.max(0, hostB.udp.discarded - hostA.udp.discarded)
      + Math.max(0, hostB.udp.errors - hostA.udp.errors),
    containerBufferDrops: Math.max(0, containerB.rcvbufErrors - containerA.rcvbufErrors),
    srt: srt || null,
  };

  if (!srt || !srt.intervals || srt.recv === 0) return { verdict: 'no-stream', ...facts };
  const own = srt.recv - srt.retrans + srt.loss;
  facts.lossShare = own > 0 ? srt.loss / own : 0;
  if (facts.lossShare < MIN_LOSS_SHARE && srt.drop === 0) return { verdict: 'no-loss', ...facts };

  // Сколько мегабит в секунду не дошло до SRS — с этим и сравниваем недостачу.
  facts.lostMbps = (srt.lossPps * WIRE_BYTES * 8) / 1e6;
  if (facts.lostMbps < Math.max(MIN_LOST_MBPS, facts.containerMbps * MIN_LOST_OF_STREAM)) return { verdict: 'too-small', ...facts };

  // Буфер приёма в самом контейнере переполнен — пакеты дошли до SRS и не влезли.
  // Сравнивать с картой уже незачем: место потери известно точно.
  if (facts.containerBufferDrops / containerSec >= srt.lossPps * BUFFER_SHARE) return { verdict: 'server-busy', ...facts };

  if (enhanceActive) return { verdict: 'enhance-on', ...facts };

  const share = (facts.hostMbps - facts.containerMbps) / facts.lostMbps;
  facts.deficitShare = share;
  const udpSeen = facts.hostUdpPps == null || facts.hostUdpPps >= facts.containerUdpPps * UDP_SEEN_SHARE;
  if (share < BYPASS_SHARE || !udpSeen) return { verdict: 'bypass', ...facts };
  if (share <= BEFORE_PC_SHARE) return { verdict: 'before-pc', ...facts };
  const udpShare = facts.hostUdpPps != null ? (facts.hostUdpPps - facts.containerUdpPps) / srt.lossPps : null;
  facts.udpDeficitShare = udpShare;
  if (share >= INSIDE_PC_SHARE && udpShare != null && udpShare >= INSIDE_PC_SHARE) return { verdict: 'inside-pc', ...facts };
  return { verdict: 'unclear', ...facts };
}

module.exports = { sanitizeHostSample, parseProcNetDev, parseProcSnmpUdp, parseContainerSample, locateLoss };

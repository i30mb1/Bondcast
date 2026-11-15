// Здоровье приёма SRT по строкам, которые SRS пишет на каждого издателя раз в 10 с:
//   [..][INFO][1][<cid>] <- SRT_CPB Transport Stats # pktRecv=N, pktRcvLoss=N, pktRcvRetrans=N, pktRcvDrop=N
// Числа — за интервал, не накопленные. libsrt считает их в момент прихода датаграммы,
// до того как SRS что-либо прочитал, поэтому по ним видно сеть, а не нагрузку сервера.
// Вынесено из server.js, чтобы правило можно было гонять в тестах на строках из
// настоящих логов: снаружи приходят только строки и часы.

const INTERVAL_SEC = 10;
// Сколько интервалов помним на издателя: 5 минут хватает и правилу, и замеру сети.
const KEEP_INTERVALS = 30;
// Издатель молчит дольше — его сессия кончилась, вердикт по нему больше не показываем.
const STALE_MS = 25 * 1000;
// Полезная нагрузка SRT-пакета с MPEG-TS: 7 пакетов по 188 байт.
const PAYLOAD_BYTES = 1316;

// Потолок: три интервала подряд принято одно и то же число пакетов (разброс до 2 %) —
// ограничитель скорости на пути пропускает ровно свою норму. Ровный приём сам по
// себе ничего не значит (так шлёт любой кодер с постоянным битрейтом), поэтому
// нужен второй признак, и у разных кодеров он разный:
//   • кодер шлёт повторы без ограничений (Moblin со своей реализацией SRT, ffmpeg) —
//     не меньше половины принятого составляют повторы;
//   • кодер держит повторы в рамках (libsrt с maxbw/oheadbw, Moblin «Official») —
//     повторов мало, зато доходит заметно меньше, чем кодер шлёт сам, и остаток
//     каждый интервал выбрасывается как опоздавший.
// Обычная потеря пакетов даёт рваные числа, а восстановленная — приём не ниже
// собственного потока; чистый эфир — ноль повторов.
const PLATEAU_SPREAD = 0.02;
const PLATEAU_RETRANS_SHARE = 0.5;
// «Заметно меньше»: при случайных потерях с повторами приём падает ниже 95 % от
// собственного потока только когда теряется больше пятой части пакетов.
const PLATEAU_SHORTFALL = 0.95;
// Меньше 50 пакетов в секунду — разгон или простой, ровность там ничего не значит.
const MIN_RECV = 500;
// Кодер держит битрейт выше выставленного (в среднем на 6 %, в пиках на 18 %),
// поэтому советуем не потолок, а 70 % от него.
const SAFE_SHARE = 0.7;
// На сколько должен сдвинуться потолок, чтобы мы его пересчитали.
const CEILING_DRIFT = 0.03;
// На сколько приём должен превысить потолок, чтобы считать, что потолка больше нет.
const CEILING_GONE = 0.05;
// Потери кончились: три интервала почти без повторов и без опозданий.
const RECOVERED_RETRANS_SHARE = 0.05;

// Строка целиком, от начала до конца: [время][уровень][pid][cid] <- SRT_CPB Transport Stats …
// Привязка к началу нужна, чтобы за статистику не сошёл такой же текст в середине
// другой строки — SRS пишет в лог и то, что прислал клиент (например, streamid).
// В начале и в конце допускаем цветовые коды терминала: SRS ставит их в свой вывод.
const ANSI = '(?:\\x1b\\[[0-9;]*m)*';
const STATS_RE = new RegExp(
  `^${ANSI}\\[[^\\]]*\\]\\[[^\\]]*\\]\\[[^\\]]*\\]\\[([^\\]\\s]+)\\]\\s*<- SRT_CPB Transport Stats # ` +
  `pktRecv=(\\d+), pktRcvLoss=(-?\\d+), pktRcvRetrans=(-?\\d+), pktRcvDrop=(-?\\d+)\\s*${ANSI}$`,
);

function parseStatsLine(line) {
  const m = STATS_RE.exec(line);
  if (!m) return null;
  return {
    cid: m[1],
    recv: Number(m[2]),
    loss: Math.max(0, Number(m[3])),
    retrans: Math.max(0, Number(m[4])),
    drop: Math.max(0, Number(m[5])),
  };
}

function median(values) {
  const sorted = [...values].sort((a, b) => a - b);
  const mid = Math.floor(sorted.length / 2);
  return sorted.length % 2 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2;
}

const sum = (list, key) => list.reduce((acc, item) => acc + item[key], 0);

// Собственный поток кодера за интервал: дошедшее с первого раза плюс потерянное.
const ownOf = (i) => i.recv - i.retrans + i.loss;

// null — потолка нет; иначе по какому признаку он найден: 'retrans' | 'shortfall'.
function plateauReason(last3) {
  if (last3.length < 3) return null;
  const recv = last3.map((i) => i.recv);
  if (Math.min(...recv) < MIN_RECV) return null;
  if ((Math.max(...recv) - Math.min(...recv)) / median(recv) > PLATEAU_SPREAD) return null;
  if (sum(last3, 'retrans') / sum(last3, 'recv') >= PLATEAU_RETRANS_SHARE) return 'retrans';
  const short = last3.every((i) => i.drop > 0 && i.recv <= ownOf(i) * PLATEAU_SHORTFALL);
  return short ? 'shortfall' : null;
}

function isRecovered(last3) {
  if (last3.length < 3) return false;
  return last3.every((i) => i.drop === 0 && (i.recv === 0 || i.retrans / i.recv < RECOVERED_RETRANS_SHARE));
}

// Три интервала подряд принято заметно больше найденного потолка — его больше нет
// (ограничение сняли или подняли), даже если мелкие потери остались. Один такой
// интервал не в счёт: после затишья ограничитель пропускает накопленный запас.
function isAboveCeiling(last3, ceilingPps) {
  if (last3.length < 3) return false;
  return last3.every((i) => i.recv > ceilingPps * INTERVAL_SEC * (1 + CEILING_GONE));
}

// Битрейт для кодера округляем вниз до привычного шага настроек.
function suggestKbps(ceilingMbps) {
  const kbps = ceilingMbps * SAFE_SHARE * 1000;
  const step = kbps >= 2000 ? 500 : 100;
  return Math.max(step, Math.floor(kbps / step) * step);
}

function createSrtHealth({ now = Date.now } = {}) {
  const publishers = new Map(); // cid -> { intervals: [{ t, recv, loss, retrans, drop }], limited: null | { ceilingPps, sinceMs } }

  function prune() {
    const t = now();
    for (const [cid, p] of publishers) {
      const last = p.intervals[p.intervals.length - 1];
      if (!last || t - last.t > KEEP_INTERVALS * INTERVAL_SEC * 1000) publishers.delete(cid);
    }
  }

  function verdictOf(p) {
    const last3 = p.intervals.slice(-3);
    const recv = sum(last3, 'recv');
    const dropped = sum(last3, 'drop');
    const retransShare = recv ? sum(last3, 'retrans') / recv : 0;
    if (p.limited) {
      const ceilingMbps = (p.limited.ceilingPps * PAYLOAD_BYTES * 8) / 1e6;
      return {
        state: 'limited',
        reason: p.limited.reason,
        ceilingPps: p.limited.ceilingPps,
        ceilingMbps,
        suggestKbps: suggestKbps(ceilingMbps),
        retransShare,
        dropped,
        sinceMs: p.limited.sinceMs,
      };
    }
    // Пакеты опаздывают и выбрасываются — это уже видно на картинке, даже если
    // ровного потолка нет. Один интервал не считаем: единичный всплеск бывает у всех.
    if (last3.filter((i) => i.drop > 0).length >= 2) {
      const own = last3.reduce((acc, i) => acc + ownOf(i), 0);
      return { state: 'dropping', dropped, lossShare: own > 0 ? sum(last3, 'loss') / own : 0, retransShare };
    }
    return null;
  }

  // Возвращает { cid, changed, verdict } для строки со статистикой издателя, иначе null.
  // changed — вердикт сменил состояние (ok → limited и т. п.): по нему пишут в лог.
  function feed(line) {
    const stats = parseStatsLine(line);
    if (!stats) return null;
    prune();
    let p = publishers.get(stats.cid);
    if (!p) {
      p = { intervals: [], limited: null };
      publishers.set(stats.cid, p);
    }
    const before = verdictOf(p);
    p.intervals.push({ t: now(), recv: stats.recv, loss: stats.loss, retrans: stats.retrans, drop: stats.drop });
    if (p.intervals.length > KEEP_INTERVALS) p.intervals.shift();

    const last3 = p.intervals.slice(-3);
    const reason = plateauReason(last3);
    if (reason) {
      const ceilingPps = median(last3.map((i) => i.recv)) / INTERVAL_SEC;
      // Найденный потолок держим, пока он не сдвинулся заметно: от интервала к
      // интервалу он гуляет на доли процента, и у границы шага совет по битрейту
      // в панели прыгал бы туда-сюда (3,0 ↔ 3,5 Мбит/с).
      const moved = !p.limited || Math.abs(ceilingPps - p.limited.ceilingPps) / p.limited.ceilingPps > CEILING_DRIFT;
      if (moved) p.limited = { ceilingPps, reason, sinceMs: p.limited ? p.limited.sinceMs : last3[0].t };
    } else if (p.limited && (isRecovered(last3) || isAboveCeiling(last3, p.limited.ceilingPps))) {
      p.limited = null;
    }
    const verdict = verdictOf(p);
    const stateOf = (v) => (v ? v.state : 'ok');
    return { cid: stats.cid, changed: stateOf(before) !== stateOf(verdict), verdict };
  }

  // Вердикт по издателю для показа в панели; null — всё в порядке или сессии уже нет.
  function verdictFor(cid) {
    const p = publishers.get(cid);
    if (!p || !p.intervals.length) return null;
    if (now() - p.intervals[p.intervals.length - 1].t > STALE_MS) return null;
    return verdictOf(p);
  }

  // Сумма по всем издателям за окно — замеру сети нужно знать, были ли в это время
  // потери и сколько пакетов в секунду терялось. Скорости считаем по каждому
  // издателю отдельно и складываем: у двух эфиров интервалов вдвое больше, а
  // секунд столько же.
  function totalsBetween(fromMs, toMs) {
    const totals = { intervals: 0, recv: 0, loss: 0, retrans: 0, drop: 0, recvPps: 0, lossPps: 0 };
    for (const p of publishers.values()) {
      const inside = p.intervals.filter((i) => i.t > fromMs && i.t <= toMs);
      if (!inside.length) continue;
      const seconds = inside.length * INTERVAL_SEC;
      totals.intervals += inside.length;
      totals.recv += sum(inside, 'recv');
      totals.loss += sum(inside, 'loss');
      totals.retrans += sum(inside, 'retrans');
      totals.drop += sum(inside, 'drop');
      totals.recvPps += sum(inside, 'recv') / seconds;
      totals.lossPps += sum(inside, 'loss') / seconds;
    }
    return totals;
  }

  // Короткая сводка по недавним издателям — в шапку выгрузки логов.
  function snapshot() {
    prune();
    const rows = [];
    for (const [cid, p] of publishers) {
      const v = verdictOf(p);
      rows.push({
        cid,
        state: v ? v.state : 'ok',
        ceilingMbps: v && v.state === 'limited' ? v.ceilingMbps : null,
        suggestKbps: v && v.state === 'limited' ? v.suggestKbps : null,
        intervals: p.intervals.length,
        recv: sum(p.intervals, 'recv'),
        loss: sum(p.intervals, 'loss'),
        retrans: sum(p.intervals, 'retrans'),
        drop: sum(p.intervals, 'drop'),
        lastMs: p.intervals[p.intervals.length - 1].t,
      });
    }
    return rows;
  }

  return { feed, verdictFor, totalsBetween, snapshot };
}

module.exports = { createSrtHealth, parseStatsLine, suggestKbps, INTERVAL_SEC };

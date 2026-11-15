const test = require('node:test');
const assert = require('node:assert');

const { sanitizeHostSample, parseProcNetDev, parseProcSnmpUdp, parseContainerSample, locateLoss } = require('./net-check');

const PROC = (rxBytes, inDatagrams, rcvbufErrors = 0) => `Inter-|   Receive                                                |  Transmit
 face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed
    lo:    4096      40    0    0    0     0          0         0     4096      40    0    0    0     0       0          0
  eth0: ${rxBytes}   12345    0    0    0     0          0         0   999999    5000    0    0    0     0       0          0
Ip: Forwarding DefaultTTL InReceives
Ip: 1 64 12345
Udp: InDatagrams NoPorts InErrors OutDatagrams RcvbufErrors SndbufErrors InCsumErrors IgnoredMulti MemErrors
Udp: ${inDatagrams} 3 ${rcvbufErrors} 5000 ${rcvbufErrors} 0 0 0 0
UdpLite: InDatagrams NoPorts InErrors OutDatagrams RcvbufErrors SndbufErrors InCsumErrors IgnoredMulti MemErrors
UdpLite: 0 0 0 0 0 0 0 0 0
`;

// Эфир, упёршийся в потолок 5,1 Мбит/с (числа из логов 13–14 ноября): за 10 с
// принято 4840 пакетов, 3300 потеряно, 3400 повторов. Потерянное — 3,6 Мбит/с.
const PLATEAU = { intervals: 3, recv: 14520, loss: 9900, retrans: 10200, drop: 210, recvPps: 484, lossPps: 330 };

// Замер 30 с. mbps/pps — что прошло за окно через карту Windows и через контейнер.
function scenario({ hostMbps, hostUdpPps, containerMbps = 5.32, containerPps = 585, bufferDrops = 0, hostDiscarded = 0, srt = PLATEAU, enhanceActive = false }) {
  const sec = 30;
  const bytes = (mbps) => Math.round((mbps * 1e6 * sec) / 8);
  const host = (t, rx, udp, discarded) => ({
    t,
    udp: { received: udp, discarded: 0, errors: 0 },
    adapters: [
      { name: 'Ethernet', ips: ['192.168.1.2'], physical: true, bytesReceived: rx, discarded, errors: 0 },
      // Через виртуальную карту идёт обратный поток из контейнеров — в расчёт не берётся.
      { name: 'vEthernet (WSL)', ips: ['172.28.0.1'], physical: false, bytesReceived: 5_000_000_000 + rx * 3, discarded: 0, errors: 0 },
    ],
  });
  return {
    hostA: host(1_000_000, 7_000_000_000, 900_000, 10),
    hostB: host(1_000_000 + sec * 1000, 7_000_000_000 + bytes(hostMbps), 900_000 + hostUdpPps * sec, 10 + hostDiscarded),
    containerA: parseContainerSample(PROC(1_000_000, 50_000), 2_000_000),
    containerB: parseContainerSample(PROC(1_000_000 + bytes(containerMbps), 50_000 + containerPps * sec, bufferDrops), 2_000_000 + sec * 1000),
    lanIps: ['192.168.1.2'],
    srt,
    enhanceActive,
  };
}

test('разбор /proc: байты без петли, поля UDP по имени', () => {
  assert.deepStrictEqual(parseProcNetDev('    lo: 4096 40 0 0 0 0 0 0 4096 40 0 0 0 0 0 0\n  eth0: 123456 99 0 0 0 0 0 0 5 5 0 0 0 0 0 0\n'), { rxBytes: 123456 });
  assert.deepStrictEqual(parseProcSnmpUdp(PROC(1, 777, 9)), { inDatagrams: 777, inErrors: 9, rcvbufErrors: 9 });
  // Строки «Udp: числа» из второго файла не должны попасть в байты интерфейсов.
  assert.strictEqual(parseContainerSample(PROC(123456, 777), 1).rxBytes, 123456);
  assert.strictEqual(parseContainerSample('мусор', 1), null);
});

test('отчёт с хоста: лишнее и кривое отбрасывается', () => {
  assert.strictEqual(sanitizeHostSample(null), null);
  assert.strictEqual(sanitizeHostSample({ t: 1, adapters: [] }), null);
  assert.strictEqual(sanitizeHostSample({ t: 'вчера', adapters: [{ name: 'x', bytesReceived: 1 }] }), null);
  const s = sanitizeHostSample({
    t: 5,
    extra: '<script>',
    udp: { received: '100', discarded: -1, errors: null },
    adapters: [
      { name: `Wi-Fi\n2026-11-15 04:00:00.000  ERROR  srs  подделка${'a'.repeat(500)}`, ips: ['192.168.1.2', 7], physical: 'да', bytesReceived: 10, discarded: 'x' },
      { name: 'битая' },
      { name: 'ложь', bytesReceived: true },
    ],
  });
  assert.strictEqual(s.adapters.length, 1);
  assert.strictEqual(s.adapters[0].name.length, 80);
  assert.ok(!/[\r\n]/.test(s.adapters[0].name), 'перевод строки в имени карты дал бы поддельную строку в логе');
  assert.deepStrictEqual(s.adapters[0].ips, ['192.168.1.2', '7']);
  assert.strictEqual(s.adapters[0].physical, false);
  assert.strictEqual(s.adapters[0].discarded, 0);
  assert.deepStrictEqual(s.udp, { received: 100, discarded: 0, errors: 0 });
  assert.strictEqual(s.extra, undefined);
});

test('потерянное в карту не приходило — режет до компьютера', () => {
  // В карту 5,45 Мбит/с (эфир + фон), до SRS 5,32: недостача 0,13 при потерянных 3,6.
  const r = locateLoss(scenario({ hostMbps: 5.45, hostUdpPps: 600 }));
  assert.strictEqual(r.verdict, 'before-pc');
  assert.strictEqual(r.adapter, 'Ethernet');
  assert.ok(Math.abs(r.hostMbps - 5.45) < 0.01);
  assert.ok(Math.abs(r.containerMbps - 5.32) < 0.01);
  assert.ok(Math.abs(r.lostMbps - 3.63) < 0.01);
});

test('до компьютера — даже если OBS на нём же тянет эфир по SRT и удваивает счётчик UDP', () => {
  const r = locateLoss(scenario({ hostMbps: 5.5, hostUdpPps: 1200 }));
  assert.strictEqual(r.verdict, 'before-pc');
});

test('потерянное в карту пришло — и по байтам, и по UDP — теряется на компьютере', () => {
  // Кодер шлёт эфир и повторы: в карту 9,4 Мбит/с и 960 пакетов/с, до SRS — 5,32 и 585.
  const r = locateLoss(scenario({ hostMbps: 9.4, hostUdpPps: 960, hostDiscarded: 400 }));
  assert.strictEqual(r.verdict, 'inside-pc');
  assert.strictEqual(r.hostDiscarded, 400);
  assert.ok(r.deficitShare > 1 && r.udpDeficitShare > 1);
});

test('байтов в карте больше, а UDP столько же — загрузка или отбрасывает Windows, вывода нет', () => {
  const r = locateLoss(scenario({ hostMbps: 40, hostUdpPps: 620 }));
  assert.strictEqual(r.verdict, 'unclear');
});

test('недостача между порогами — вывода нет', () => {
  // 5,32 + 0,4 × 3,63 = 6,77: ни «не приходило», ни «пришло».
  const r = locateLoss(scenario({ hostMbps: 6.77, hostUdpPps: 720 }));
  assert.strictEqual(r.verdict, 'unclear');
});

test('небольшие потери внутри компьютера не выдаются за «до компьютера»', () => {
  // Проброс порта теряет 10 %: в карту 6,0, до SRS 5,4. Раньше такое сравнение по
  // отношению скоростей (1,11) сошло бы за «столько же».
  const srt = { intervals: 3, recv: 14700, loss: 1650, retrans: 1650, drop: 0, recvPps: 490, lossPps: 55 };
  const r = locateLoss(scenario({ hostMbps: 6.0, hostUdpPps: 650, containerMbps: 5.4, containerPps: 590, srt }));
  assert.strictEqual(r.verdict, 'inside-pc');
});

test('потерь слишком мало, чтобы отличить их от фона, — так и говорим', () => {
  const srt = { intervals: 3, recv: 16500, loss: 600, retrans: 600, drop: 12, recvPps: 550, lossPps: 20 };
  const r = locateLoss(scenario({ hostMbps: 6.1, hostUdpPps: 640, containerMbps: 6.0, containerPps: 650, srt }));
  assert.strictEqual(r.verdict, 'too-small');
});

test('SRS получил больше, чем пришло в карту, — поток идёт изнутри компьютера', () => {
  assert.strictEqual(locateLoss(scenario({ hostMbps: 0.3, hostUdpPps: 30 })).verdict, 'bypass');
  // Кодер на этом же компьютере шлёт по SRT: Windows эти датаграммы считает, карта — нет.
  assert.strictEqual(locateLoss(scenario({ hostMbps: 0.3, hostUdpPps: 900 })).verdict, 'bypass');
});

test('байты на карте случайно совпали с эфиром, но Windows его пакетов не видела — не «до компьютера»', () => {
  // Стенд: эфир шёл внутри вложенного Docker, а по карте в это время качалось 5 Мбит/с постороннего.
  assert.strictEqual(locateLoss(scenario({ hostMbps: 5.2, hostUdpPps: 12 })).verdict, 'bypass');
  // Счётчик UDP с хоста не пришёл вовсе — судим по одним байтам.
  const s = scenario({ hostMbps: 5.45, hostUdpPps: 600 });
  s.hostA.udp.received = null;
  assert.strictEqual(locateLoss(s).verdict, 'before-pc');
});

test('включено улучшение картинки — просим выключить, а не гадаем', () => {
  const r = locateLoss(scenario({ hostMbps: 5.45, hostUdpPps: 600, containerMbps: 9.3, enhanceActive: true }));
  assert.strictEqual(r.verdict, 'enhance-on');
});

test('переполнен буфер приёма в контейнере — виноват сам сервер', () => {
  // 330 потерянных пакетов в секунду, из них 200 выбросил буфер SRS.
  const r = locateLoss(scenario({ hostMbps: 9.4, hostUdpPps: 960, bufferDrops: 6000 }));
  assert.strictEqual(r.verdict, 'server-busy');
  // Единичные переполнения на фоне больших потерь вердикт не меняют.
  assert.strictEqual(locateLoss(scenario({ hostMbps: 5.45, hostUdpPps: 600, bufferDrops: 300 })).verdict, 'before-pc');
});

test('потерь за окно не было или эфира нет — так и говорим', () => {
  const clean = locateLoss(scenario({ hostMbps: 5.9, hostUdpPps: 560, containerMbps: 5.8, containerPps: 550, srt: { intervals: 3, recv: 16500, loss: 20, retrans: 20, drop: 0, recvPps: 550, lossPps: 0.7 } }));
  assert.strictEqual(clean.verdict, 'no-loss');
  const idle = locateLoss(scenario({ hostMbps: 0.1, hostUdpPps: 5, containerMbps: 0.01, containerPps: 1, srt: { intervals: 0, recv: 0, loss: 0, retrans: 0, drop: 0, recvPps: 0, lossPps: 0 } }));
  assert.strictEqual(idle.verdict, 'no-stream');
});

test('какие карты считаем: по адресу, иначе физические, виртуальные — никогда', () => {
  const s = scenario({ hostMbps: 5.45, hostUdpPps: 600 });
  // Адрес сменился после запуска ярлыка — берём физическую, а не самую нагруженную.
  assert.strictEqual(locateLoss({ ...s, lanIps: ['10.0.0.7'] }).adapter, 'Ethernet');
  // Провод и Wi-Fi одновременно — эфир может прийти через любую, складываем обе.
  const two = scenario({ hostMbps: 5.45, hostUdpPps: 600 });
  for (const sample of [two.hostA, two.hostB]) {
    sample.adapters.push({ name: 'Wi-Fi', ips: ['192.168.1.3'], physical: true, bytesReceived: sample === two.hostA ? 100 : 100 + 375_000, discarded: 0, errors: 0 });
  }
  const r = locateLoss({ ...two, lanIps: ['192.168.1.2', '192.168.1.3'] });
  assert.strictEqual(r.adapter, 'Ethernet, Wi-Fi');
  assert.ok(Math.abs(r.hostMbps - 5.55) < 0.01);
  // Ни адреса, ни пометки — лучше ошибка, чем счёт по виртуальной карте.
  const none = scenario({ hostMbps: 5.45, hostUdpPps: 600 });
  for (const sample of [none.hostA, none.hostB]) sample.adapters[0].physical = false;
  assert.strictEqual(locateLoss({ ...none, lanIps: [] }).verdict, 'error');
});

test('битые входные данные дают ошибку с причиной, а не вывод', () => {
  const s = scenario({ hostMbps: 5.45, hostUdpPps: 600 });
  assert.strictEqual(locateLoss({ ...s, hostB: null }).verdict, 'error');
  assert.strictEqual(locateLoss({ ...s, containerA: null }).verdict, 'error');
  assert.strictEqual(locateLoss({ ...s, hostB: { ...s.hostB, t: s.hostA.t + 1000 } }).verdict, 'error');
  // SRS перезапустили посреди замера — счётчики контейнера начались с нуля.
  assert.strictEqual(locateLoss({ ...s, containerB: parseContainerSample(PROC(10, 10), s.containerB.t) }).verdict, 'error');
});

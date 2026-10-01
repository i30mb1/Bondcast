const test = require('node:test');
const assert = require('node:assert');

const { createSceneSwitcher } = require('./scene-switcher');

// Поддельный мир: OBS помнит текущую сцену, часы и таймер ручные — тик монитора
// крутится раз в 2с, двигаем время сами и сами «стреляем» отложенным переключением.
function setup({ scene = 'Камера', obsFails = false } = {}) {
  const obs = { scene, calls: [], fails: obsFails };
  const logs = [];
  let clock = 0;
  let timer = null;
  const sw = createSceneSwitcher({
    obsCall: async (type, data) => {
      obs.calls.push(type);
      if (obs.fails) throw new Error('OBS недоступен');
      if (type === 'GetCurrentProgramScene') return { sceneName: obs.scene };
      if (type === 'SetCurrentProgramScene') obs.scene = data.sceneName;
      return {};
    },
    logger: { log: (service, level, message) => logs.push({ level, message }) },
    now: () => clock,
    setTimer: (fn, ms) => (timer = { fn, at: clock + ms }),
    clearTimer: (t) => { if (timer === t) timer = null; },
  });

  // Стрим «в эфире» с заданным битрейтом: recv_bytes копится, recv_30s задаём явно
  // (у SRS это скользящее среднее, тут его подставляет сам сценарий).
  let bytes = 0;
  const stream = (kbps, kbps30 = kbps) => {
    bytes += (kbps * 2000) / 8;
    return [{ name: 'potato', kbpsRecv30s: kbps30, recvBytes: bytes }];
  };

  // Один тик монитора: сдвигаем время на 2с, срабатывает таймер, если пора.
  async function tick(streams) {
    clock += 2000;
    if (timer && clock >= timer.at) {
      const { fn } = timer;
      timer = null;
      await fn();
    }
    await sw.tick(streams);
  }

  const enable = (extra = {}) =>
    sw.applySettings({ enabled: true, watchStreamName: 'potato', fallbackScene: 'Заглушка', delaySec: 3, minBitrateKbps: 0, ...extra });

  return { sw, obs, logs, tick, stream, enable, resetBytes: () => { bytes = 0; } };
}

test('обрыв → заглушка, сигнал вернулся → прежняя сцена', async () => {
  const { sw, obs, tick, stream, enable } = setup();
  await enable();
  await tick(stream(5000));
  await tick([]); // пропал — ставим таймер на 3с
  assert.strictEqual(obs.scene, 'Камера');
  await tick([]);
  await tick([]); // таймер сработал
  assert.strictEqual(obs.scene, 'Заглушка');
  assert.strictEqual(sw.state.state, 'switched');
  await tick(stream(5000));
  assert.strictEqual(obs.scene, 'Камера');
  assert.strictEqual(sw.state.state, 'watching');
});

test('короткая просадка меньше задержки — сцену не трогаем', async () => {
  const { obs, tick, stream, enable } = setup();
  await enable();
  await tick(stream(5000));
  await tick([]);
  await tick(stream(5000)); // вернулся раньше 3с
  await tick(stream(5000));
  await tick(stream(5000));
  assert.deepStrictEqual(obs.calls, []);
});

test('с порогом: возврат по свежему битрейту, не ждёт 30-секундного среднего', async () => {
  const { sw, obs, tick, stream, enable, resetBytes } = setup();
  await enable({ minBitrateKbps: 3000 });
  await tick(stream(5000));
  for (let i = 0; i < 3; i++) await tick([]);
  assert.strictEqual(obs.scene, 'Заглушка');

  // Переподключились: телефон шлёт 5000, а recv_30s ещё тянет нули обрыва.
  resetBytes();
  let returnedAfterSec = null;
  for (let sec = 2; sec <= 30; sec += 2) {
    await tick(stream(5000, 800));
    if (sw.state.state === 'watching') { returnedAfterSec = sec; break; }
  }
  assert.ok(returnedAfterSec != null && returnedAfterSec <= 8, `вернулись через ${returnedAfterSec} с`);
  assert.strictEqual(obs.scene, 'Камера');
});

test('с порогом: битрейт ниже порога — остаёмся на заглушке', async () => {
  const { sw, tick, stream, enable, resetBytes } = setup();
  await enable({ minBitrateKbps: 3000 });
  await tick(stream(5000));
  for (let i = 0; i < 3; i++) await tick([]);
  resetBytes();
  for (let i = 0; i < 10; i++) await tick(stream(1500, 1500));
  assert.strictEqual(sw.state.state, 'switched');
});

test('правка настроек во время заглушки не ломает возврат', async () => {
  const { sw, obs, tick, stream, enable } = setup();
  await enable();
  await tick(stream(5000));
  for (let i = 0; i < 3; i++) await tick([]);
  assert.strictEqual(obs.scene, 'Заглушка');

  await enable({ delaySec: 10 }); // сдвинули ползунок, пока висит заглушка
  assert.strictEqual(sw.state.state, 'switched');

  await tick(stream(5000));
  assert.strictEqual(obs.scene, 'Камера');
});

test('смена заглушки во время показа сразу видна в OBS', async () => {
  const { obs, tick, stream, enable } = setup();
  await enable();
  await tick(stream(5000));
  for (let i = 0; i < 3; i++) await tick([]);
  await enable({ fallbackScene: 'Перерыв' });
  assert.strictEqual(obs.scene, 'Перерыв');
  await tick(stream(5000));
  assert.strictEqual(obs.scene, 'Камера');
});

test('заглушка не запоминается как рабочая сцена', async () => {
  const { obs, tick, stream, enable } = setup();
  await enable();
  await tick(stream(5000));
  for (let i = 0; i < 3; i++) await tick([]);
  await tick(stream(5000)); // первый цикл: запомнили «Камеру» и вернулись на неё

  // Второй обрыв застал OBS уже на заглушке (переключили руками) — вернуть надо
  // всё равно на рабочую сцену, а не «на заглушку же».
  obs.scene = 'Заглушка';
  for (let i = 0; i < 3; i++) await tick([]);
  await tick(stream(5000));
  assert.strictEqual(obs.scene, 'Камера');
});

test('с порогом: полный обрыв сразу после возврата тоже ловится', async () => {
  const { obs, tick, stream, enable, resetBytes } = setup();
  await enable({ minBitrateKbps: 3000 });
  await tick(stream(5000));
  for (let i = 0; i < 3; i++) await tick([]);
  resetBytes();
  for (let i = 0; i < 4; i++) await tick(stream(5000, 800)); // вернулись по короткому окну
  assert.strictEqual(obs.scene, 'Камера');

  for (let i = 0; i < 3; i++) await tick([]); // recv_30s всё ещё ниже порога, а стрим снова пропал
  assert.strictEqual(obs.scene, 'Заглушка');
});

test('OBS не ответил при возврате — пишем в лог и пробуем на следующем тике', async () => {
  const { sw, obs, logs, tick, stream, enable } = setup();
  await enable();
  await tick(stream(5000));
  for (let i = 0; i < 3; i++) await tick([]);

  obs.fails = true;
  await tick(stream(5000));
  assert.strictEqual(sw.state.state, 'switched');
  assert.strictEqual(sw.state.lastError, 'OBS недоступен');
  assert.ok(logs.some((l) => l.level === 'error' && l.message.includes('не удалось вернуть')));

  obs.fails = false;
  await tick(stream(5000));
  assert.strictEqual(obs.scene, 'Камера');
  assert.strictEqual(sw.state.lastError, null);
});

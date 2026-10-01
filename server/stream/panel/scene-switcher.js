// «Умный переключатель сцен»: следит за выбранным стримом в SRS и, когда сигнал
// пропал, через delaySec показывает в OBS заглушку, а когда вернулся — прежнюю
// сцену. Вынесен из server.js, чтобы логику можно было гонять в тестах без
// SRS/OBS: всё внешнее (вызовы OBS, лог, часы, таймеры) приходит параметрами.

// Короткое окно битрейта для решения «сигнал вернулся». recv_30s из SRS после
// переподключения ещё полминуты тянет в себе нули с момента обрыва, а телефонный
// ABR разгоняется постепенно — по нему возврат сцены затягивался на минуту и
// дольше. Считаем сами по приросту recv_bytes за последние ~6с.
const RETURN_WINDOW_MS = 6000;

function createSceneSwitcher({
  obsCall,
  logger,
  now = Date.now,
  setTimer = setTimeout,
  clearTimer = clearTimeout,
}) {
  const state = {
    enabled: false,
    watchStreamName: null,
    fallbackScene: null,
    delaySec: 3,
    minBitrateKbps: 0, // 0 — не проверять, переключать только при полном пропадании паблиша
    state: 'idle', // idle (выключен) | watching (включён, ждёт) | switched (сейчас на резервной сцене)
    lastError: null,
  };
  let rememberedLiveScene = null; // сцена, на которую вернёмся, когда сигнал появится снова
  let pendingSwitchTimer = null;
  let watchedStreamWasLive = null; // null — ещё не знаем (только включили/сменили стрим); дальше true/false для детекта фронта
  let watchedStreamWasPresent = false; // паблиш был на прошлом тике, независимо от порога битрейта
  let switchBackInFlight = false; // не плодим параллельные возвраты, если OBS отвечает дольше тика
  let recvSamples = []; // [{ t, bytes }] отслеживаемого стрима

  const log = (level, message) => logger.log('panel', level, `сцены: ${message}`);

  function trackRecvBytes(watched) {
    if (!watched || watched.recvBytes == null) {
      recvSamples = [];
      return;
    }
    const t = now();
    const last = recvSamples[recvSamples.length - 1];
    if (last && watched.recvBytes < last.bytes) recvSamples = []; // счётчик сбросился — новый паблиш
    recvSamples.push({ t, bytes: watched.recvBytes });
    while (recvSamples.length > 2 && t - recvSamples[1].t >= RETURN_WINDOW_MS) recvSamples.shift();
  }

  // null — данных на окно ещё не набралось
  function shortWindowKbps() {
    if (recvSamples.length < 2) return null;
    const first = recvSamples[0];
    const last = recvSamples[recvSamples.length - 1];
    if (last.t - first.t < RETURN_WINDOW_MS / 2) return null;
    return ((last.bytes - first.bytes) * 8) / (last.t - first.t); // байт/мс → кбит/с
  }

  function clearPendingSwitch() {
    if (pendingSwitchTimer) {
      clearTimer(pendingSwitchTimer);
      pendingSwitchTimer = null;
    }
  }

  async function switchToFallback() {
    pendingSwitchTimer = null;
    try {
      const current = await obsCall('GetCurrentProgramScene');
      // Уже стоим на заглушке (переключили руками или прошлый цикл) — не затираем
      // запомненную рабочую сцену, иначе «вернём» на ту же заглушку.
      if (current.sceneName !== state.fallbackScene) rememberedLiveScene = current.sceneName;
      await obsCall('SetCurrentProgramScene', { sceneName: state.fallbackScene });
      state.state = 'switched';
      state.lastError = null;
      log('warn', `сигнал "${state.watchStreamName}" пропал — включена заглушка "${state.fallbackScene}" (вернём на "${rememberedLiveScene}")`);
    } catch (e) {
      state.lastError = e.message;
      log('error', `не удалось включить заглушку: ${e.message}`);
    }
  }

  async function switchBackToLive() {
    if (switchBackInFlight) return;
    switchBackInFlight = true;
    try {
      if (rememberedLiveScene) {
        await obsCall('SetCurrentProgramScene', { sceneName: rememberedLiveScene });
      }
      state.state = 'watching';
      state.lastError = null;
      log('info', `сигнал вернулся — снова "${rememberedLiveScene ?? '(сцена не запомнена)'}"`);
    } catch (e) {
      state.lastError = e.message;
      log('error', `не удалось вернуть рабочую сцену: ${e.message}`);
    } finally {
      switchBackInFlight = false;
    }
  }

  // Вызывается общим поллингом раз в 2с со списком активных стримов SRS —
  // переключает через delaySec после пропажи (короткая просадка сама отменяет
  // ещё не сработавший таймер) и возвращает прежнюю сцену, как только сигнал
  // придёт снова.
  async function tick(streams) {
    if (!state.enabled || !state.watchStreamName || !state.fallbackScene) return;
    const watched = streams.find((s) => s.name === state.watchStreamName);
    // minBitrateKbps=0 — только публикует/нет. С порогом канал формально в эфире,
    // но битрейта в нём уже недостаточно, тоже считаем "не живым" — kbpsRecv30s
    // ещё null первые секунды после коннекта, тогда тоже "недостаточно".
    const bitrateOk = !state.minBitrateKbps || (watched && watched.kbpsRecv30s != null && watched.kbpsRecv30s >= state.minBitrateKbps);
    const isLive = Boolean(watched) && bitrateOk;

    // Возврат — по короткому окну (см. trackRecvBytes): на заглушке висим, пока
    // свежий битрейт не дотянет до порога, а не пока 30-секундное среднее отмоется
    // от нулей обрыва. Без порога — достаточно самого факта паблиша.
    trackRecvBytes(watched);
    const recentKbps = shortWindowKbps();
    const isBack = Boolean(watched) && (!state.minBitrateKbps || (recentKbps != null ? recentKbps >= state.minBitrateKbps : bitrateOk));

    if (isLive && pendingSwitchTimer) {
      clearPendingSwitch(); // сигнал вернулся раньше, чем истёк delay — переключать не нужно
    } else if (!isLive && (watchedStreamWasLive || (!watched && watchedStreamWasPresent)) && !pendingSwitchTimer && state.state !== 'switched') {
      // Второе условие — полная пропажа сразу после возврата по короткому окну,
      // пока recv_30s ещё ниже порога и isLive не успел стать true.
      pendingSwitchTimer = setTimer(switchToFallback, state.delaySec * 1000);
    } else if (isBack && state.state === 'switched') {
      await switchBackToLive();
    }
    watchedStreamWasLive = isLive;
    watchedStreamWasPresent = Boolean(watched);
  }

  // Настройки уже провалидированы вызывающим (server.js), включая доступность OBS.
  async function applySettings({ enabled, watchStreamName, fallbackScene, delaySec, minBitrateKbps }) {
    // Смена отслеживаемого стрима или выключение — сбрасываем текущий цикл
    // переключения, чтобы не словить лишнее переключение сцены на стыке смены настроек.
    if (watchStreamName !== state.watchStreamName || !enabled) {
      clearPendingSwitch();
      if (state.state === 'switched') await switchBackToLive();
      watchedStreamWasLive = null;
      watchedStreamWasPresent = false;
      rememberedLiveScene = null;
      recvSamples = [];
    }

    // Сменили заглушку прямо во время показа заглушки — сразу показываем новую.
    if (enabled && state.state === 'switched' && fallbackScene !== state.fallbackScene) {
      try {
        await obsCall('SetCurrentProgramScene', { sceneName: fallbackScene });
      } catch (e) {
        state.lastError = e.message;
      }
    }

    state.enabled = enabled;
    state.watchStreamName = enabled ? watchStreamName : null;
    state.fallbackScene = enabled ? fallbackScene : state.fallbackScene; // помним выбор даже выключенным
    state.delaySec = delaySec;
    state.minBitrateKbps = minBitrateKbps;
    // Правка задержки/порога/заглушки во время показа заглушки не должна
    // «забывать», что мы на ней — иначе возврат на рабочую сцену не случится.
    state.state = !enabled ? 'idle' : state.state === 'switched' ? 'switched' : 'watching';
    if (enabled && state.state !== 'switched') state.lastError = null;
    return state;
  }

  return { state, tick, applySettings };
}

module.exports = { createSceneSwitcher, RETURN_WINDOW_MS };

"""Улучшение картинки живого эфира: SRS -> NVIDIA VSR -> SRS.

Забирает live/<ENHANCE_SOURCE> из SRS по SRT, прогоняет каждый кадр через
VideoSuperRes (апскейл или очистку от артефактов сжатия в том же разрешении),
кодирует NVENC и публикует обратно в SRS как live/<ENHANCE_OUTPUT>. Звук идёт
копией с исходными метками времени — картинка и звук не разъезжаются.

Обратно публикуем по RTMP, а не по SRT: приём SRT у SRS держит пол задержки
recvlatency 500 мс (см. srs/srs.conf) — это полсекунды сверху ни за что, ведь
воркер и SRS на одной машине. OBS всё равно смотрит HTTP-FLV.

Падение любого звена (телефон пропал, SRS перезапустился) — не выход из
процесса: сессия закрывается и через секунду начинается заново. Панель
пересоздаёт контейнер только при смене стрима/настроек.
"""

import io
import logging
import os
import queue
import signal
import subprocess
import sys
import threading
import time
from fractions import Fraction

import av
import cupy as cp
import numpy as np
import nvvfx

from .colors import BT601, BT709, rgb_to_nv12, yuv420_to_rgb

logging.basicConfig(level=logging.INFO, format='%(asctime)s %(levelname)s %(message)s', stream=sys.stdout)
log = logging.getLogger('enhance')

SOURCE = os.environ['ENHANCE_SOURCE']
OUTPUT = os.environ.get('ENHANCE_OUTPUT', f'{SOURCE}_enh')
# auto — до 1080p, если меньше, иначе чистка в том же разрешении; same — только
# чистка; 1080/1440/2160 — короткая сторона на выходе.
TARGET = os.environ.get('ENHANCE_TARGET', 'auto')
STRENGTH = float(os.environ.get('ENHANCE_STRENGTH', '1.0'))
SRT_IN = os.environ.get('ENHANCE_SRT', 'srs:10080')
RTMP_OUT = os.environ.get('ENHANCE_RTMP', 'srs:1935')
# Нет пакетов дольше этого — считаем, что источник пропал, и переподключаемся.
STALL_SEC = 5.0
STATS_EVERY_SEC = 5.0

Q = nvvfx.VideoSuperRes.QualityLevel


def plan_output(w, h):
    """Размер выхода и режим VSR под входной кадр."""
    short = min(w, h)
    if TARGET == 'same':
        target = short
    elif TARGET == 'auto':
        target = 1080 if short < 1080 else short
    else:
        target = int(TARGET)
    if target <= short:
        # Режимы очистки работают только при выходе того же размера, что вход.
        return w, h, [Q.DENOISE_MEDIUM]
    scale = min(target / short, 4.0)
    ow = int(round(w * scale / 2)) * 2
    oh = int(round(h * scale / 2)) * 2
    # STREAMING_* сделан ровно под наш случай (живое сжатое видео с камеры: шум,
    # блоки, мыло), но только с Ampere. На Turing откатываемся на HIGH.
    return ow, oh, [Q.STREAMING_ULTRA, Q.HIGH]


def open_effect(w, h, ow, oh, modes):
    last_error = None
    for mode in modes:
        sr = nvvfx.VideoSuperRes(quality=mode, strength=STRENGTH, device=0)
        sr.output_width = ow
        sr.output_height = oh
        try:
            sr.load()
            return sr, mode
        except nvvfx.NvVFXError as e:
            last_error = e
            log.warning('режим %s не загрузился (%s), пробую следующий', mode.name, e)
            sr.close()
    raise RuntimeError(f'VSR не загрузился ни в одном режиме: {last_error}')


# Коды AVColorSpace: 1 — BT.709, 5/6 — варианты BT.601.
_COLORSPACE_601 = (5, 6)
_COLORSPACE_709 = (1,)


def pick_matrix(frame):
    cs = int(getattr(frame, 'colorspace', 2) or 2)
    if cs in _COLORSPACE_709:
        return BT709
    if cs in _COLORSPACE_601:
        return BT601
    return BT709 if frame.height >= 720 or frame.width >= 1280 else BT601


def pick_fps(vin):
    # После ремукса TS через ffmpeg average_rate бывает равен тактам таймбазы
    # (90000) — с таким gop_size ключевых кадров не было бы вовсе. Берём первое
    # правдоподобное значение.
    for rate in (vin.average_rate, vin.guessed_rate, vin.base_rate):
        if rate and 1 <= float(rate) <= 120:
            return Fraction(rate)
    return Fraction(30)


def spawn_input():
    url = f'srt://{SRT_IN}?streamid=#!::r=live/{SOURCE},m=request&latency=120000&connect_timeout=3000'
    cmd = [
        'ffmpeg', '-hide_banner', '-nostdin', '-loglevel', 'error',
        '-fflags', 'nobuffer', '-probesize', '500000', '-analyzeduration', '500000',
        '-i', url,
        '-map', '0:v:0', '-map', '0:a:0?', '-c', 'copy',
        '-f', 'mpegts', '-muxdelay', '0', '-muxpreload', '0', '-flush_packets', '1', 'pipe:1',
    ]
    return subprocess.Popen(cmd, stdout=subprocess.PIPE, stdin=subprocess.DEVNULL)


def spawn_output(audio_codec):
    # AAC копируем как есть (ffmpeg сам переупакует ADTS для FLV); всё остальное
    # FLV не возьмёт — перекодируем в AAC.
    audio_args = ['-c:a', 'copy'] if audio_codec in (None, 'aac') else ['-c:a', 'aac', '-b:a', '192k']
    cmd = [
        'ffmpeg', '-hide_banner', '-nostdin', '-loglevel', 'error',
        # Пробинг заканчивается, как только у всех дорожек есть параметры (это
        # первые же миллисекунды), а лимиты — только потолок. Маленький потолок
        # ломал старт на 1440p/4K: 40 Мбит видео забивали его раньше, чем
        # приходил первый кадр звука, и FLV не открывался («sample rate not set»).
        '-fflags', 'nobuffer', '-probesize', '20000000', '-analyzeduration', '2000000',
        '-f', 'mpegts', '-i', 'pipe:0',
        '-map', '0', '-c:v', 'copy', *audio_args,
        '-f', 'flv', '-flvflags', 'no_duration_filesize', f'rtmp://{RTMP_OUT}/live/{OUTPUT}',
    ]
    return subprocess.Popen(cmd, stdin=subprocess.PIPE)


def stop_proc(proc):
    if proc is None:
        return
    try:
        if proc.stdin:
            proc.stdin.close()
    except Exception:
        pass
    try:
        proc.terminate()
        proc.wait(timeout=2)
    except Exception:
        proc.kill()


class Session:
    """Одна непрерывная сессия: от подключения к источнику до его пропажи."""

    def __init__(self):
        self.src = None
        self.dst = None
        self.inp = None
        self.out = None
        self.sr = None
        self.vout = None
        self.aout = None
        self.last_packet_at = time.monotonic()
        self.closed = False
        self.enc_q = None
        self.enc_thread = None
        self.enc_error = None

    def watchdog(self):
        # ffmpeg на входе может висеть на мёртвом SRT-сокете дольше, чем нам
        # нужно — убиваем его, чтобы demux() получил EOF и сессия закрылась.
        while not self.closed:
            time.sleep(1)
            if time.monotonic() - self.last_packet_at > STALL_SEC and self.src and self.src.poll() is None:
                log.warning('источник молчит %d с — переподключаюсь', STALL_SEC)
                self.src.kill()
                return

    def start_encoder(self):
        """NVENC и мукс — в своём потоке, третьей ступенью конвейера.

        Иначе основной поток ждал бы кодировщик после каждого кадра: на 60 к/с
        1080p->1440p упиралось в ~56 к/с, 4K — в ~39. Выходной контейнер трогает
        только этот поток, поэтому звук идёт сюда же, в общей очереди с картинкой.
        """
        self.enc_q = queue.Queue(maxsize=4)
        key_type = av.video.frame.PictureType.I

        def run():
            while True:
                item = self.enc_q.get()
                if item is None:
                    return
                kind, payload = item
                try:
                    if kind == 'a':
                        self.out.mux(payload)
                        continue
                    nv12, pts, time_base, key = payload
                    out_frame = av.VideoFrame.from_ndarray(nv12, format='nv12')
                    out_frame.pts = pts
                    out_frame.time_base = time_base
                    if key:
                        out_frame.pict_type = key_type
                    for out_packet in self.vout.encode(out_frame):
                        self.out.mux(out_packet)
                except (av.error.FFmpegError, ValueError):
                    pass  # одиночный битый пакет/метка — не повод рвать эфир
                except Exception as e:  # noqa: BLE001 — выход умер (сломанная труба)
                    self.enc_error = e
                    return

        self.enc_thread = threading.Thread(target=run, daemon=True)
        self.enc_thread.start()

    def to_encoder(self, item):
        while True:
            if self.enc_error is not None:
                raise self.enc_error
            try:
                self.enc_q.put(item, timeout=0.5)
                return
            except queue.Full:
                continue

    def start_output(self, w, h, vin, ain):
        ow, oh, modes = plan_output(w, h)
        self.sr, mode = open_effect(w, h, ow, oh, modes)
        fps = pick_fps(vin)
        self.dst = spawn_output(ain.codec_context.name if ain else None)
        self.out = av.open(io.FileIO(self.dst.stdin.fileno(), 'wb', closefd=False), 'w', format='mpegts')
        self.vout = self.out.add_stream('h264_nvenc', rate=fps)
        cc = self.vout.codec_context
        cc.width = ow
        cc.height = oh
        cc.pix_fmt = 'nv12'
        cc.time_base = vin.time_base
        cc.framerate = fps
        # Ключевой кадр раз в секунду: OBS, переключённый на этот поток посреди
        # эфира, получит картинку максимум через секунду (gop_cache в SRS).
        cc.gop_size = max(1, int(round(float(fps))))
        cc.max_b_frames = 0
        # Это локальный хоп (до OBS на этой же машине) — полосу не экономим,
        # иначе потеряем то, что только что вытащили из картинки.
        cc.bit_rate = 40_000_000 if max(ow, oh) <= 1920 else 80_000_000
        cc.options = {
            'preset': 'p4', 'tune': 'll', 'rc': 'vbr', 'cq': '19', 'bf': '0',
            'zerolatency': '1', 'delay': '0', 'maxrate': str(cc.bit_rate * 2),
        }
        cc.color_primaries = 1  # BT.709 — колоры считаем в colors.py именно в ней
        cc.color_trc = 1
        cc.colorspace = 1
        cc.color_range = 1  # ограниченный (tv)
        if ain is not None:
            self.aout = self.out.add_stream_from_template(ain)
        self.start_encoder()
        log.info('READY вход %dx%d -> выход %dx%d, режим %s, %.2f к/с', w, h, ow, oh, mode.name, float(fps))
        return ow, oh, mode

    def decoded(self, demux_streams):
        """Демукс и декод — в отдельном потоке, обработка — в основном.

        Пока видеокарта и NVENC заняты кадром N, процессор уже декодирует N+1:
        при последовательной работе 1080p->4K стоил 26 мс на кадр и не влезал
        в 60 к/с (16.7 мс). Очередь короткая — если не успеваем, декодер
        притормаживает, и это честно видно в lag_ms, а не копится в памяти.
        Звук идёт через ту же очередь, чтобы не перемешать порядок с картинкой.
        """
        q = queue.Queue(maxsize=8)

        # Сессию могут бросить посреди потока (сменилось разрешение) — тогда
        # читателя никто не ждёт, и вечный q.put() оставил бы висеть поток.
        def put(item):
            while not self.closed:
                try:
                    q.put(item, timeout=0.5)
                    return True
                except queue.Full:
                    continue
            return False

        def reader():
            try:
                for packet in self.inp.demux(*demux_streams):
                    self.last_packet_at = time.monotonic()
                    if packet.dts is None:
                        continue
                    if packet.stream.type == 'audio':
                        if not put(('a', packet)):
                            return
                    else:
                        for frame in packet.decode():
                            if not put(('v', frame)):
                                return
            except Exception as e:  # noqa: BLE001 — передаём в основной поток
                put(('err', e))
            finally:
                put(('end', None))

        threading.Thread(target=reader, daemon=True).start()
        while True:
            kind, item = q.get()
            if kind == 'end':
                return
            if kind == 'err':
                raise item
            yield kind, item

    def run(self):
        self.src = spawn_input()
        threading.Thread(target=self.watchdog, daemon=True).start()
        self.inp = av.open(io.FileIO(self.src.stdout.fileno(), 'rb', closefd=False), format='mpegts',
                           options={'fflags': 'nobuffer', 'probesize': '500000', 'analyzeduration': '500000'})
        vin = self.inp.streams.video[0]
        ain = self.inp.streams.audio[0] if self.inp.streams.audio else None
        # Покадровые потоки декодера дают задержку в (потоки-1) кадров — два
        # потока = один лишний кадр, а 1080p HEVC в один поток на CPU успевает.
        vin.codec_context.thread_type = 'FRAME'
        vin.codec_context.thread_count = 2

        size = None
        ow = oh = 0
        mode = None
        last_vpts = None
        last_adts = None
        stats_at = time.monotonic()
        frames = 0
        gpu_time = 0.0
        loop_time = 0.0
        clock0 = None
        lag_ms = 0.0
        min_lag_ms = None
        next_key_sec = None

        demux_streams = [vin] + ([ain] if ain else [])
        for kind, item in self.decoded(demux_streams):
            if kind == 'a':
                packet = item
                if self.out is None or self.aout is None:
                    continue  # звук до первого кадра картинки — выхода ещё нет
                if last_adts is not None and packet.dts <= last_adts:
                    continue
                last_adts = packet.dts
                packet.stream = self.aout
                self.to_encoder(('a', packet))
                continue

            frame = item
            t_start = time.monotonic()
            if size is None:
                size = (frame.width, frame.height)
                ow, oh, mode = self.start_output(frame.width, frame.height, vin, ain)
            elif size != (frame.width, frame.height):
                log.info('разрешение источника сменилось %s -> %s — перезапуск сессии', size, (frame.width, frame.height))
                return

            if frame.pts is None or (last_vpts is not None and frame.pts <= last_vpts):
                continue  # немонотонные метки с телефона (см. TS-муксер) — кадр выкидываем
            last_vpts = frame.pts

            fmt = frame.format.name
            full_range = fmt == 'yuvj420p' or int(getattr(frame, 'color_range', 0) or 0) == 2
            matrix = pick_matrix(frame)
            if fmt not in ('yuv420p', 'yuvj420p'):
                frame = frame.reformat(format='yuv420p')
            yuv = cp.asarray(frame.to_ndarray().reshape(-1))

            t_gpu = time.monotonic()
            rgb = yuv420_to_rgb(yuv, size[0], size[1], full_range, matrix)
            result = self.sr.run(rgb)
            # RGB8-путь SDK может дописывать выход на нулевом потоке — ждём его
            # явно, прежде чем читать буфер (так советует документация DLPack).
            cp.cuda.runtime.deviceSynchronize()
            upscaled = cp.from_dlpack(result.image)
            nv12 = rgb_to_nv12(upscaled, ow, oh).get()
            gpu_time += time.monotonic() - t_gpu

            # Ключевой кадр раз в секунду по времени потока — не полагаемся
            # только на gop_size: частота кадров с телефона плавает.
            media_sec = float(frame.pts * frame.time_base)
            key = next_key_sec is None or media_sec >= next_key_sec
            if key:
                next_key_sec = media_sec + 1.0
            self.to_encoder(('v', (nv12.reshape(oh * 3 // 2, ow), frame.pts, frame.time_base, key)))

            now = time.monotonic()
            loop_time += now - t_start
            frames += 1
            # Отставание = сколько реального времени прошло минус сколько
            # прошло по меткам потока, от лучшего момента сессии (на старте
            # ffmpeg отдаёт пачку накопленного при пробинге — это не отставание).
            # Растёт — не успеваем за эфиром.
            if clock0 is None:
                clock0 = (now, media_sec)
            raw_lag = ((now - clock0[0]) - (media_sec - clock0[1])) * 1000
            min_lag_ms = raw_lag if min_lag_ms is None else min(min_lag_ms, raw_lag)
            lag_ms = raw_lag - min_lag_ms

            if now - stats_at >= STATS_EVERY_SEC:
                elapsed = now - stats_at
                log.info('STATS fps=%.1f in=%dx%d out=%dx%d mode=%s gpu_ms=%.1f frame_ms=%.1f lag_ms=%.0f',
                         frames / elapsed, size[0], size[1], ow, oh, mode.name,
                         gpu_time / frames * 1000, loop_time / frames * 1000, lag_ms)
                stats_at = now
                frames = 0
                gpu_time = 0.0
                loop_time = 0.0

    def close(self):
        self.closed = True
        if self.enc_thread is not None:
            try:
                self.enc_q.put(None, timeout=2)
            except queue.Full:
                pass
            self.enc_thread.join(timeout=3)
        if self.vout is not None and self.out is not None and not (self.enc_thread and self.enc_thread.is_alive()):
            try:
                for out_packet in self.vout.encode(None):
                    self.out.mux(out_packet)
            except Exception:
                pass
        for obj in (self.out, self.inp):
            try:
                if obj is not None:
                    obj.close()
            except Exception:
                pass
        if self.sr is not None:
            try:
                self.sr.close()
            except Exception:
                pass
        stop_proc(self.dst)
        stop_proc(self.src)


def main():
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))
    log.info('улучшение картинки: live/%s -> live/%s, цель %s, VFX SDK %s',
             SOURCE, OUTPUT, TARGET, nvvfx.get_sdk_version())
    while True:
        session = Session()
        try:
            session.run()
            log.info('источник закончился — жду его снова')
        except (av.error.InvalidDataError, av.error.EOFError, BrokenPipeError) as e:
            log.info('сессия прервалась (%s) — жду источник снова', e)
        except Exception as e:  # noqa: BLE001 — воркер не должен умирать от одной плохой сессии
            log.exception('сессия упала: %s', e)
        finally:
            session.close()
        time.sleep(1)


if __name__ == '__main__':
    main()

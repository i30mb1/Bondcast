"""Перевод кадра между YUV (как его отдаёт декодер и ждёт NVENC) и планарным RGB
float32 0..1 (как его ждёт VideoSuperRes) — прямо на видеокарте.

На CPU (swscale) эти два перевода для 1440p съедали бы ~15 мс на кадр, то есть
половину бюджета кадра при 30 к/с. Здесь они стоят доли миллисекунды, а по шине
ходит только YUV — он в два раза легче RGB.

Вход — в той матрице, что указана в потоке (BT.709 или BT.601; без пометки
HD считаем 709, SD — 601, как это делает OBS). Выход всегда BT.709, ограниченный
диапазон (16-235) — так он и помечается в кодировщике. Полный диапазон на входе
приходит только у «jpeg»-форматов (yuvj420p), их помечаем флагом.
"""

import cupy as cp

_YUV420_TO_RGB = cp.ElementwiseKernel(
    'raw uint8 src, int32 w, int32 h, int32 full, float32 rv, float32 gu, float32 gv, float32 bu',
    'raw float32 rgb',
    r'''
    const int n = w * h;
    const int px = i % w;
    const int py = i / w;
    const int ci = (py >> 1) * (w >> 1) + (px >> 1);
    float Y = (float)src[i];
    float U = (float)src[n + ci] - 128.f;
    float V = (float)src[n + (n >> 2) + ci] - 128.f;
    if (full) {
        Y = Y * (1.f / 255.f);
        U = U * (1.f / 255.f);
        V = V * (1.f / 255.f);
    } else {
        Y = (Y - 16.f) * (1.f / 219.f);
        U = U * (1.f / 224.f);
        V = V * (1.f / 224.f);
    }
    rgb[i]         = fminf(fmaxf(Y + rv * V, 0.f), 1.f);
    rgb[n + i]     = fminf(fmaxf(Y - gu * U - gv * V, 0.f), 1.f);
    rgb[2 * n + i] = fminf(fmaxf(Y + bu * U, 0.f), 1.f);
    ''',
    'bondcast_yuv420_to_rgb',
)

_RGB_TO_NV12_Y = cp.ElementwiseKernel(
    'raw float32 rgb, int32 n',
    'raw uint8 dst',
    r'''
    float r = fminf(fmaxf(rgb[i], 0.f), 1.f);
    float g = fminf(fmaxf(rgb[n + i], 0.f), 1.f);
    float b = fminf(fmaxf(rgb[2 * n + i], 0.f), 1.f);
    dst[i] = (unsigned char)(16.f + 219.f * (0.2126f * r + 0.7152f * g + 0.0722f * b) + 0.5f);
    ''',
    'bondcast_rgb_to_nv12_y',
)

# Один поток на пару UV — усредняем RGB по квадрату 2x2 и только потом считаем цвет.
_RGB_TO_NV12_UV = cp.ElementwiseKernel(
    'raw float32 rgb, int32 w, int32 h',
    'raw uint8 dst',
    r'''
    const int n = w * h;
    const int cw = w >> 1;
    const int cx = i % cw;
    const int cy = i / cw;
    const int p = (2 * cy) * w + 2 * cx;
    float r = 0.25f * (rgb[p] + rgb[p + 1] + rgb[p + w] + rgb[p + w + 1]);
    float g = 0.25f * (rgb[n + p] + rgb[n + p + 1] + rgb[n + p + w] + rgb[n + p + w + 1]);
    float b = 0.25f * (rgb[2 * n + p] + rgb[2 * n + p + 1] + rgb[2 * n + p + w] + rgb[2 * n + p + w + 1]);
    r = fminf(fmaxf(r, 0.f), 1.f);
    g = fminf(fmaxf(g, 0.f), 1.f);
    b = fminf(fmaxf(b, 0.f), 1.f);
    float U = 128.f + 224.f * (-0.11457f * r - 0.38543f * g + 0.5f * b);
    float V = 128.f + 224.f * (0.5f * r - 0.45415f * g - 0.04585f * b);
    dst[n + 2 * i]     = (unsigned char)fminf(fmaxf(U + 0.5f, 0.f), 255.f);
    dst[n + 2 * i + 1] = (unsigned char)fminf(fmaxf(V + 0.5f, 0.f), 255.f);
    ''',
    'bondcast_rgb_to_nv12_uv',
)


def _matrix(kr, kb):
    kg = 1.0 - kr - kb
    return (
        cp.float32(2 * (1 - kr)),
        cp.float32(2 * kb * (1 - kb) / kg),
        cp.float32(2 * kr * (1 - kr) / kg),
        cp.float32(2 * (1 - kb)),
    )


BT709 = _matrix(0.2126, 0.0722)
BT601 = _matrix(0.299, 0.114)


def yuv420_to_rgb(yuv_flat, w, h, full_range, matrix=BT709):
    """yuv_flat — плоский uint8 на GPU: Y (w*h), затем U и V (по w*h/4)."""
    rgb = cp.empty((3, h, w), dtype=cp.float32)
    _YUV420_TO_RGB(yuv_flat, w, h, 1 if full_range else 0, *matrix, rgb, size=w * h)
    return rgb


def rgb_to_nv12(rgb, w, h):
    """rgb — (3, h, w) float32 на GPU. Возвращает плоский uint8 NV12 на GPU."""
    dst = cp.empty(w * h * 3 // 2, dtype=cp.uint8)
    _RGB_TO_NV12_Y(rgb, w * h, dst, size=w * h)
    _RGB_TO_NV12_UV(rgb, w, h, dst, size=(w // 2) * (h // 2))
    return dst

# Улучшение картинки эфира на видеокарте NVIDIA (VSR из Maxine VFX SDK).
# Тяжёлый GPU-образ — как и субтитры, собирается лениво по кнопке в панели
# (POST /api/enhance/build), а не на обычном старте.
FROM python:3.12-slim

# ffmpeg — только транспорт: забрать поток из SRS по SRT и отдать обратно.
# Декод/кодирование и сама обработка идут в Python (PyAV + CuPy + nvvfx), чтобы
# звук шёл копией с исходными метками времени и не разъезжался с картинкой.
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /srv
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app ./app

# Без NVIDIA_DRIVER_CAPABILITIES=all в контейнер не пробрасывается libnvidia-ngx
# (NGX — рантайм, на котором работает VSR), и эффект падает на load() с кодом -12.
# Значение по умолчанию у --gpus — только compute,utility. Здесь на случай прямого
# docker run; панель передаёт то же самое в Env сама.
ENV NVIDIA_DRIVER_CAPABILITIES=all PYTHONUNBUFFERED=1

CMD ["python", "-m", "app.main"]

FROM debian:bookworm-slim AS build
RUN apt-get update && apt-get install -y git build-essential && rm -rf /var/lib/apt/lists/*
# Коммит зафиксирован (а не master/main) - иначе апстрим в любой момент молча
# меняет то, что реально соберётся в образ.
RUN git clone https://github.com/BELABOX/srtla /srtla && \
    git -C /srtla checkout 37862da3d0c13b46956efd3f88877053293d97d6 && \
    make -C /srtla

FROM debian:bookworm-slim
COPY --from=build /srtla/srtla_rec /usr/local/bin/srtla_rec
EXPOSE 5000/udp
ENTRYPOINT ["srtla_rec"]
# порт SRTLA, хост SRT-сервера, порт SRT; локально переопределяется на "5000 srs 10080"
CMD ["5000", "127.0.0.1", "10080"]

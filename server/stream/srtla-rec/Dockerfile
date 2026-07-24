FROM debian:bookworm-slim AS build
RUN apt-get update && apt-get install -y git build-essential && rm -rf /var/lib/apt/lists/*
RUN git clone https://github.com/BELABOX/srtla /srtla && make -C /srtla

FROM debian:bookworm-slim
COPY --from=build /srtla/srtla_rec /usr/local/bin/srtla_rec
EXPOSE 5000/udp
ENTRYPOINT ["srtla_rec"]
# порт SRTLA, хост SRT-сервера, порт SRT; локально переопределяется на "5000 srs 10080"
CMD ["5000", "127.0.0.1", "10080"]

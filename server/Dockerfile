FROM eclipse-temurin:21-jdk AS build
WORKDIR /source
COPY . .
RUN chmod +x gradlew && ./gradlew :server:installDist --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /opt/netscan-server
RUN useradd --system --uid 10001 --home /opt/netscan-server netscan
COPY --from=build /source/server/build/install/netscan-server/ ./
USER 10001
EXPOSE 8081
ENTRYPOINT ["/opt/netscan-server/bin/netscan-server"]

FROM gradle:8.7-jdk17 AS build
WORKDIR /workspace
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY server ./server
RUN gradle -p server installDist

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /workspace/server/build/install/server /app
EXPOSE 8080
ENTRYPOINT ["/app/bin/server"]

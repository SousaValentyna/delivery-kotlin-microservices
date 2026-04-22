FROM gradle:8.7-jdk17 AS build
WORKDIR /app
COPY . .
RUN gradle installDist --no-daemon

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
COPY --from=build /app/build/install/kitchen-service/ ./
ENV PORT=8082
EXPOSE 8082
ENTRYPOINT ["/app/bin/kitchen-service"]

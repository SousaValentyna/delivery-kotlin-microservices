FROM gradle:8.7-jdk17 AS build
WORKDIR /app
COPY . .
RUN gradle installDist --no-daemon

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
COPY --from=build /app/build/install/order-service/ ./
ENV PORT=8081
EXPOSE 8081
ENTRYPOINT ["/app/bin/order-service"]

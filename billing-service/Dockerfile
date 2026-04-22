FROM gradle:8.7-jdk17 AS build
WORKDIR /app
COPY . .
RUN gradle installDist --no-daemon

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
COPY --from=build /app/build/install/billing-service/ ./
ENV PORT=8083
EXPOSE 8083
ENTRYPOINT ["/app/bin/billing-service"]

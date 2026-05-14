FROM eclipse-temurin:21-jre-alpine

ARG SERVICE_MODULE
ENV SERVICE_MODULE=${SERVICE_MODULE}

RUN addgroup -S appgroup && adduser -S appuser -G appgroup

COPY ${SERVICE_MODULE}/target/*.jar /app/app.jar

RUN mkdir -p /app/data/storage && chown -R appuser:appgroup /app/data

WORKDIR /app
USER appuser

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "mkdir -p /app/data/storage && exec java -jar /app/app.jar"]

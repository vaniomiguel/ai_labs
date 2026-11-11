FROM node:24-bookworm-slim AS frontend
WORKDIR /ui
COPY frontend/package*.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

FROM maven:3.9.11-eclipse-temurin-25 AS backend
WORKDIR /build
COPY backend/pom.xml ./pom.xml
RUN mvn -B -q dependency:go-offline
COPY backend/src ./src
COPY --from=frontend /ui/dist/conta/browser/ ./src/main/resources/static/
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:25-jre
RUN apt-get update && apt-get install -y --no-install-recommends fontconfig fonts-dejavu-core curl && rm -rf /var/lib/apt/lists/* \
    && groupadd -g 10001 conta && useradd -u 10001 -g conta -m conta && mkdir -p /data/documents && chown -R conta:conta /data
WORKDIR /app
COPY --from=backend /build/target/accounting-0.1.0.jar app.jar
USER conta
ENV SPRING_PROFILES_ACTIVE=prod DOCUMENT_STORAGE=/data/documents JAVA_TOOL_OPTIONS="-Xmx2g -Djava.awt.headless=true"
EXPOSE 8081
HEALTHCHECK --interval=30s --timeout=5s CMD curl -fsS http://localhost:8081/actuator/health || exit 1
ENTRYPOINT ["java","-jar","/app/app.jar"]

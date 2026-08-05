# Stage 1: Build Frontend
FROM node:22-alpine AS frontend-build
WORKDIR /app
COPY frontend/package*.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

# Stage 2: Build Backend
FROM eclipse-temurin:21-jdk-alpine AS backend-build
WORKDIR /app

# Copy gradle wrapper and config
COPY gradle/ gradle/
COPY gradlew ./
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gnm-app/build.gradle.kts gnm-app/

RUN chmod +x ./gradlew

# Copy source code
COPY gnm-app/src/ gnm-app/src/

# Copy frontend build to quarkus resources
COPY --from=frontend-build /app/dist/ gnm-app/src/main/resources/META-INF/resources/

# Build Quarkus application in fast-jar format
RUN ./gradlew :gnm-app:build -Dquarkus.package.type=fast-jar -x test --no-daemon

# Stage 3: Runtime
FROM eclipse-temurin:21-jre-alpine

# Install libpcap for pcap4j (network scanning)
RUN apk add --no-cache libpcap

WORKDIR /app

# Ensure keys directory exists and is writable for JWT keypair generation
RUN mkdir -p /app/keys && chmod 777 /app/keys

# Copy the fast-jar directories from the build stage
COPY --from=backend-build /app/gnm-app/build/quarkus-app/app/ ./app/
COPY --from=backend-build /app/gnm-app/build/quarkus-app/lib/ ./lib/
COPY --from=backend-build /app/gnm-app/build/quarkus-app/quarkus/ ./quarkus/
COPY --from=backend-build /app/gnm-app/build/quarkus-app/quarkus-run.jar ./

EXPOSE 8080
CMD ["java", "-jar", "quarkus-run.jar"]

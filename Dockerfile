FROM eclipse-temurin:21-jdk AS build

WORKDIR /app

COPY gradlew gradlew
COPY gradle gradle
COPY build.gradle settings.gradle ./
COPY src src

RUN chmod +x gradlew && ./gradlew bootJar --no-daemon -x test

FROM eclipse-temurin:21-jre

# 빌드한 커밋 SHA (GET /api/version으로 확인)
ARG APP_VERSION=dev
ENV APP_VERSION=${APP_VERSION}

WORKDIR /app

RUN mkdir -p /app/uploads

COPY --from=build /app/build/libs/*.jar /app/app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]

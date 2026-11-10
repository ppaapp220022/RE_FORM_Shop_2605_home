FROM eclipse-temurin:21-jdk AS build

WORKDIR /app

COPY gradlew gradlew
COPY gradle gradle
# Gradle 배포판 다운로드를 소스와 다른 레이어로 둬, 소스만 바뀐 빌드에서는 캐시된 레이어를 쓴다
RUN chmod +x gradlew && ./gradlew --version --no-daemon

# 의존성도 소스와 다른 레이어로 둔다. build.gradle·settings.gradle이 바뀔 때만 다시 내려받는다
COPY build.gradle settings.gradle ./
RUN ./gradlew resolveDependencies --no-daemon

COPY src src

RUN ./gradlew bootJar --no-daemon -x test

FROM eclipse-temurin:21-jre

# 빌드한 커밋 SHA (GET /api/version으로 확인)
ARG APP_VERSION=dev
ENV APP_VERSION=${APP_VERSION}

WORKDIR /app

RUN mkdir -p /app/uploads

COPY --from=build /app/build/libs/*.jar /app/app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]

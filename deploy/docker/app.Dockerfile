# 后端：在容器内用 JDK 21 构建，运行时只带 JRE
FROM maven:3.9-eclipse-temurin-21 AS build
ARG MAVEN_MIRROR_URL=https://maven.aliyun.com/repository/public
WORKDIR /src
RUN mkdir -p /root/.m2 && if [ -n "$MAVEN_MIRROR_URL" ]; then \
      printf '<settings><mirrors><mirror><id>build-mirror</id><mirrorOf>central</mirrorOf><url>%s</url></mirror></mirrors></settings>' "$MAVEN_MIRROR_URL" > /root/.m2/settings.xml; \
    fi
COPY . .
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -q -B -DskipTests package -pl ruoyi-admin -am

FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /src/ruoyi-admin/target/ruoyi-admin.jar app.jar
ENV SERVER_PORT=8080 LANG=C.UTF-8 LC_ALL=C.UTF-8 TZ=Asia/Shanghai JAVA_OPTS="-XX:+UseZGC"
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java -Djava.security.egd=file:/dev/./urandom -Dserver.port=${SERVER_PORT} ${JAVA_OPTS} -jar app.jar"]

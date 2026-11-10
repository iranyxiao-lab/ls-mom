# DCS 模拟器（演示 / 验收用）：向独立时序库写入点位值与报警，覆盖断网、重复、乱序、坏质量位、时钟偏差场景
FROM maven:3.9-eclipse-temurin-21 AS build
ARG MAVEN_MIRROR_URL=https://maven.aliyun.com/repository/public
WORKDIR /src
RUN mkdir -p /root/.m2 && if [ -n "$MAVEN_MIRROR_URL" ]; then \
      printf '<settings><mirrors><mirror><id>build-mirror</id><mirrorOf>central</mirrorOf><url>%s</url></mirror></mirrors></settings>' "$MAVEN_MIRROR_URL" > /root/.m2/settings.xml; \
    fi
COPY . .
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -q -B -DskipTests package -pl ls-mom-dcs-simulator -am

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/ls-mom-dcs-simulator/target/ls-mom-dcs-simulator-exec.jar simulator.jar
ENV LANG=C.UTF-8 LC_ALL=C.UTF-8 TZ=Asia/Shanghai
ENTRYPOINT ["java", "-jar", "simulator.jar"]
CMD ["--mode", "live", "--backfill-hours", "6"]

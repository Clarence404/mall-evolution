# 一条命令体验（docker compose --profile demo up -d --build）用的镜像：在容器里用 Maven 构建，不要求本机装 JDK。
# 四个服务共用这一个镜像（只构建一次），运行时由环境变量 MODULE 决定启动哪个 jar；依赖缓存在 BuildKit 缓存卷里。
# 生产镜像仍用仓库根目录的 Dockerfile（CI 先 ./mvnw -B verify，再按模块构建）。

FROM maven:3.9.9-eclipse-temurin-21 AS build
# 可选的 Maven 镜像仓库，如 https://maven.aliyun.com/repository/public；不给就直连 Maven Central
ARG MAVEN_MIRROR=
ARG OTEL_AGENT_VERSION=2.33.1
WORKDIR /src
COPY . .
# 同一个 RUN 里顺带取 OpenTelemetry Java Agent（也从 Maven 仓库下载，不走 GitHub），最后删掉 target 让这一层只留 /out
RUN --mount=type=cache,target=/root/.m2/repository \
    if [ -n "$MAVEN_MIRROR" ]; then \
      printf '<settings><mirrors><mirror><id>mirror</id><mirrorOf>*</mirrorOf><url>%s</url></mirror></mirrors></settings>' \
        "$MAVEN_MIRROR" > /root/.m2/settings.xml; \
    fi \
    && mvn -B -ntp -Dmaven.test.skip=true -Djacoco.skip=true \
         -pl mall-app,inventory-service,payment-service,mall-gateway -am package \
    && mvn -B -ntp dependency:copy \
         -Dartifact=io.opentelemetry.javaagent:opentelemetry-javaagent:${OTEL_AGENT_VERSION} \
         -DoutputDirectory=/out/otel -Dmdep.stripVersion=true \
    && mkdir -p /out/jars \
    && cp mall-app/target/mall-app.jar inventory-service/target/inventory-service.jar \
          payment-service/target/payment-service.jar mall-gateway/target/mall-gateway.jar /out/jars/ \
    && rm -rf /src/*/target

FROM eclipse-temurin:21-jre
# 要有家目录：Nacos、RocketMQ 客户端把本地缓存和日志写在 ~ 下
RUN useradd --system --create-home --uid 10001 app
WORKDIR /app
COPY --from=build /out/otel/opentelemetry-javaagent.jar /opt/otel/opentelemetry-javaagent.jar
COPY --from=build /out/jars/ /app/
USER app
# agent 由 JAVA_TOOL_OPTIONS 挂上，导出器在 docker-compose.yml 里设为 none：只要 traceId，不需要 Collector
ENV MODULE=mall-app JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/$MODULE.jar"]

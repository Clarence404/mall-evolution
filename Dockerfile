# 每个服务一个镜像，同一个 Dockerfile：CI 先 ./mvnw -B verify，再按模块构建
#   docker build --build-arg MODULE=inventory-service -t mall/inventory-service:<git-sha> .

# 从可执行 jar 里取出 Flyway 迁移脚本，供 Argo CD 的 PreSync Job 用（网关没有迁移脚本，目录为空）
FROM eclipse-temurin:21-jdk AS migrations
ARG MODULE
COPY ${MODULE}/target/${MODULE}.jar /tmp/app.jar
RUN mkdir -p /out/BOOT-INF/classes/db/migration \
    && cd /out && jar xf /tmp/app.jar BOOT-INF/classes/db/migration || true

FROM eclipse-temurin:21-jre
ARG MODULE
# OpenTelemetry Java Agent 2.x 打进镜像：零代码采集 HTTP、JDBC、Lettuce、RocketMQ 5 客户端的链路
ARG OTEL_AGENT_VERSION=2.33.1
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 app \
    && mkdir -p /opt/otel \
    && curl -fsSL -o /opt/otel/opentelemetry-javaagent.jar \
       "https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_AGENT_VERSION}/opentelemetry-javaagent.jar"
WORKDIR /app
COPY ${MODULE}/target/${MODULE}.jar /app/app.jar
COPY --from=migrations /out/BOOT-INF/classes/db/migration /app/db/migration

USER app
EXPOSE 8080 18080
# 8080 业务端口；18080 管理端口（prod profile：健康检查与 /actuator/prometheus，只给集群内探针和 Prometheus 访问）
# 容器里按 cgroup 限额算堆：limits.memory 的 75% 给堆。agent 由环境变量 JAVA_TOOL_OPTIONS 挂上（见 deploy/k8s）
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar --spring.profiles.active=${SPRING_PROFILES_ACTIVE:-prod}"]

# 每个服务一个镜像，同一个 Dockerfile：CI 先 ./mvnw -B verify，再按模块构建
#   docker build --build-arg MODULE=inventory-service -t mall/inventory-service:1.4.0 .
FROM eclipse-temurin:21-jre

ARG MODULE
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 app
WORKDIR /app
COPY ${MODULE}/target/${MODULE}.jar /app/app.jar

USER app
EXPOSE 8080
# 容器里按 cgroup 限额算堆：limits.memory 的 75% 给堆，其余留给元空间、线程栈、直接内存
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar --spring.profiles.active=${SPRING_PROFILES_ACTIVE:-prod}"]

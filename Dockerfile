# ============ 构建阶段：Maven + JDK21 打可执行 jar（产物为 Java 17 字节码）============
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY mvnw mvnw
COPY .mvn .mvn
COPY src ./src
# 直接用 maven:3.9.9 镜像已预装的同版本 mvn；slim 镜像无 unzip，
# 用 ./mvnw 会下载 Maven 分发包并因缺 unzip 解压失败（exit 127）。
RUN mvn -B -ntp clean package -DskipTests

# ============ 运行阶段 ============
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
# 装 curl 供 HEALTHCHECK 探测 /api/health/deps
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /workspace/target/*.jar app.jar
# 向量索引持久化目录（compose 挂卷到此处，避免容器重启丢索引）
ENV APP_RAG_INDEX_DIR=/app/data/rag-index
VOLUME /app/data/rag-index
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --retries=5 \
    CMD curl -fsS http://localhost:8080/api/health/deps || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]

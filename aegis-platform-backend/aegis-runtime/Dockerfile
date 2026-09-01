# Aegis Runtime 服务（AgentScope 运行时 + 对话核心）
# build context: aegis-platform-backend (父目录)
#   docker build -t aegis-runtime -f aegis-runtime/Dockerfile .
#
# 设计：容器内 Maven 多阶段构建，用户无需本地安装 JDK/Maven，仅凭 Docker 即可一键构建。
# 加速：注入阿里云 Maven 镜像，解决容器内拉取 Maven 中央仓库慢/失败问题。
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /app

# 注入阿里云 Maven 镜像加速（解决容器内 DNS/网络拉取 Maven Central 慢或失败）
COPY docker/maven-settings.xml /root/.m2/settings.xml

# 拷贝父 pom + 全部子模块 pom（父 pom 声明了 6 个 module，-am 需全部模块目录存在）
COPY pom.xml .
COPY aegis-core/pom.xml aegis-core/
COPY aegis-core/aegis-core-domain/pom.xml aegis-core/aegis-core-domain/
COPY aegis-core/aegis-core-spi/pom.xml aegis-core/aegis-core-spi/
COPY aegis-core/aegis-core-infra/pom.xml aegis-core/aegis-core-infra/
COPY aegis-dal/pom.xml aegis-dal/
COPY aegis-gateway/pom.xml aegis-gateway/
COPY aegis-admin/pom.xml aegis-admin/
COPY aegis-runtime/pom.xml aegis-runtime/
COPY aegis-mcp-demo/pom.xml aegis-mcp-demo/

# 预下载依赖
RUN mvn dependency:go-offline -pl aegis-runtime -am -DskipTests -q || true

# 仅拷贝编译所需模块源码（runtime + 其依赖 aegis-core/aegis-dal）
COPY aegis-core/ aegis-core/
COPY aegis-dal/ aegis-dal/
COPY aegis-runtime/ aegis-runtime/

# 构建 runtime 及其依赖模块
RUN mvn clean package -pl aegis-runtime -am -DskipTests -q

# 运行时镜像：使用 glibc 基础镜像（alpine 与 Netty native transport 不兼容）
FROM eclipse-temurin:21-jre
WORKDIR /app
ENV JAVA_OPTS="-Xms512m -Xmx1024m -XX:+UseZGC -XX:+ZGenerational -XX:+ExitOnOutOfMemoryError" \
    TZ=Asia/Shanghai
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone
# runtime 使用 spring-boot-maven-plugin 的 classifier=exec 产出的 fat jar（-exec.jar）
COPY --from=builder /app/aegis-runtime/target/aegis-runtime-*-exec.jar app.jar
EXPOSE 8081
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]

# Aegis Admin 服务（管理后台 API）
# build context: aegis-platform-backend (父目录)
#   docker build -t aegis-admin -f aegis-admin/Dockerfile .
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /app

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
RUN mvn dependency:go-offline -pl aegis-admin -am -DskipTests -q || true

# 仅拷贝编译所需模块源码（admin + 其依赖 aegis-core/aegis-dal）
COPY aegis-core/ aegis-core/
COPY aegis-dal/ aegis-dal/
COPY aegis-admin/ aegis-admin/

# 构建 admin 及其依赖模块
RUN mvn clean package -pl aegis-admin -am -DskipTests -q

FROM eclipse-temurin:21-jre
WORKDIR /app
ENV JAVA_OPTS="-Xms256m -Xmx768m -XX:+UseZGC -XX:+ZGenerational -XX:+ExitOnOutOfMemoryError" \
    TZ=Asia/Shanghai
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone
COPY --from=builder /app/aegis-admin/target/aegis-admin-*.jar app.jar
EXPOSE 8082
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]

# Aegis Runtime 服务（AgentScope 运行时）
# build context: aegis-platform-backend (父目录)
# 依赖：宿主机已 mvn package 生成 target/*-exec.jar
FROM eclipse-temurin:21-jre
WORKDIR /app

ENV JAVA_OPTS="-Xms256m -Xmx1024m -XX:+UseZGC -XX:+ZGenerational -XX:+ExitOnOutOfMemoryError" \
    TZ=Asia/Shanghai

RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

COPY aegis-runtime/target/aegis-runtime-*-exec.jar app.jar

EXPOSE 8081

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]

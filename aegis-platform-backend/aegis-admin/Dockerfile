# Aegis Admin 服务（管理后台）
# build context: aegis-platform-backend (父目录)
# 依赖：宿主机已 mvn package 生成 target/*.jar
FROM eclipse-temurin:21-jre
WORKDIR /app

ENV JAVA_OPTS="-Xms256m -Xmx768m -XX:+UseZGC -XX:+ZGenerational -XX:+ExitOnOutOfMemoryError" \
    TZ=Asia/Shanghai

RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

COPY aegis-admin/target/aegis-admin-*.jar app.jar

EXPOSE 8082

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]

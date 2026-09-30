# 运行镜像 = JRE + 宿主 mvn 产物（编译刻意留在宿主：复用本地 .m2、离线可复现，镜像只多一层 jar）。
# 基镜像用 jammy 而不是 alpine：eclipse-temurin:17-jre-alpine 无 arm64 manifest，Apple Silicon 拉不动。
# 构建上下文由 scripts/build-images.sh 现造（只放本文件改名后的 Dockerfile + app.jar），
# 所以这里 COPY 的是 context 根的 app.jar，不把四个 fat jar 一起喂给 daemon。
ARG BASE_IMAGE=eclipse-temurin:17-jre-jammy
FROM ${BASE_IMAGE}

ARG MODULE
LABEL org.opencontainers.image.title="xsl-app" \
      org.opencontainers.image.description="Xingyan ShortLink Platform module ${MODULE}"

WORKDIR /app
COPY app.jar /app/app.jar

# MaxRAMPercentage 让堆随 compose mem_limit 自动缩放，无需为每实例硬编码 -Xmx；
# SerialGC 在小堆（单机数据面）下常驻比 G1 更省，跳转链路无长停顿诉求。
ENV TZ=Asia/Shanghai \
    JAVA_OPTS="-XX:MaxRAMPercentage=55 -XX:+UseSerialGC -Djava.security.egd=file:/dev/./urandom"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]

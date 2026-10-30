# 构建阶段
FROM maven:3.9.6-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

# 运行阶段
FROM eclipse-temurin:21-jre
WORKDIR /app
# JVM 参数说明（数值均来自生产实测，非估算）：
#
# -Xmx512m 保持原值。业务可用堆上限不变，不牺牲并发与响应能力。
#
# -Xms256m -> -Xms64m。实测依据（生产 87.83.207.7，2026-10-30）：
#   jcmd 1 GC.heap_info  显示 committed(total) 262144K = 256MB，
#   而 used 三次采样分别为 82156K / 40276K / 54314K，即堆里 79%~85% 是空的；
#   /proc/<pid>/smaps 显示堆所在匿名 VMA (start=0xe0000000) 的 Rss 达 243712K = 238MB。
#   在 THP=always 下，-Xms 设定的初始提交几乎全部被 touch 成常驻页，
#   于是 256MB 里约 184MB 是「已提交、未使用、却占物理内存」。
#   降到 64m 后 G1 仍会按需向上扩到 -Xmx512m，功能与容量上限完全不变。
#
# -XX:MaxMetaspaceSize=192m。实测依据：
#   jcmd 1 VM.metaspace 显示 committed 69.81MB / used 69.28MB（>99% 已提交），
#   加载器 551~560 个、类 13299 个（含 1401 个共享类），无 classloader 泄漏。
#   192m 约为实测用量的 2.7 倍余量，既给出显式护栏，又不会误触发
#   OutOfMemoryError: Metaspace。
ENV JAVA_OPTS="-Xms64m -Xmx512m -XX:MaxMetaspaceSize=192m -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai"
ENV LANG=en_US.UTF-8
ENV LANGUAGE=en_US:en
ENV LC_ALL=en_US.UTF-8

RUN sed -i 's/archive.ubuntu.com/mirrors.ustc.edu.cn/g' /etc/apt/sources.list \
    && sed -i 's/security.ubuntu.com/mirrors.ustc.edu.cn/g' /etc/apt/sources.list \
    && apt-get update \
    && apt-get install -y --no-install-recommends fontconfig fonts-dejavu sqlite3 \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /app/target/*.jar app.jar

EXPOSE 6365

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]

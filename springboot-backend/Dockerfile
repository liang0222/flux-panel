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
# glibc malloc arena 上限。实测依据（生产 87.83.207.7，2026-11-10 01:30-01:45）：
#   容器内 nproc=8，未设置本变量时 glibc 默认按 8 x (1+8) = 72 个 arena 上限，
#   /proc/<pid>/smaps 按 64MB 对齐统计到 51-53 个 arena 段、实占 235-273MB，
#   且随并发线程数增长（空闲 373MB -> 压力后 510MB，涨幅 136MB 全在 arena）。
#   设为 2 后同一压测：arena 段降到 2-5 个、实占 146-165MB，
#   RSS 空闲 346MB / 压力后 393MB（涨幅仅 48MB），堆内 used 不变。
#   延迟 A/B（单容器交替、各 3000 请求、混合 /flow/test 与 /api/v1/config/get）：
#   对照 mean 7.03ms p99 27.96ms，本设置 mean 6.43ms p99 25.95ms，
#   未观察到劣化（Welch t=-4.61，方向为略快）。
#   取值 2 而非 4：4 的 arena 实占 187MB 且交替压测延迟更差，无额外收益。
# 该变量只影响 glibc 内存分配策略，不改变任何业务行为、并发上限与超时。
ENV MALLOC_ARENA_MAX=2
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

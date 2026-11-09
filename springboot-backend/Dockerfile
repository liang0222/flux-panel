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
# 原生内存定时归还。实测依据（生产 87.83.207.7，2026-11-10 02:45-03:05）：
#   MALLOC_ARENA_MAX=2 之后仍有约 136MB 常驻在 glibc arena 的空闲页里（RSS 356MB，
#   堆 used 仅 46MB、Metaspace 约 61MB），glibc 默认不会主动把这些空闲页还给内核。
#   -XX:TrimNativeHeapInterval=15000 让 JVM 每 15 秒对所有 malloc arena 调用一次
#   malloc_trim，把完全空闲的页 madvise(MADV_DONTNEED) 归还。
#   同机双容器 A/B（同镜像、同 DB 快照、同 JVM 参数，唯一变量本开关）：
#     对照 RSS 354,644-356,932 kB；本设置 RSS 271,376-274,804 kB，稳定差约 83MB（-23%）。
#   延迟 A/B（各 9 轮 x 1200 请求 = 10,800 请求，正反两种顺序交替）：
#     对照 mean 5.57ms、本设置 mean 5.65ms，差异 1.5%，小于轮间波动（4.92-6.52ms），
#     未观察到可测量的劣化。
#   该参数只影响空闲内存何时归还，不改变堆上限、并发能力、超时与任何业务行为。
# 最终生效的 JAVA_OPTS（唯一一处）。上面第 1~4 条注释是对应参数取值的实测依据；
# 注意本行必须包含全部参数，Dockerfile 里重复声明 ENV 会覆盖而不是追加。
ENV JAVA_OPTS="-Xms64m -Xmx512m -XX:MaxMetaspaceSize=192m -XX:TrimNativeHeapInterval=15000 -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai"
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

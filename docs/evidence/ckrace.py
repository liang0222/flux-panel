#!/usr/bin/env python3
"""决定性实验：TRUNCATE checkpoint 与流量上报并发时，上报要等多久。

生产事实：
- 连接串只有 ?busy_timeout=5000 → 驱动默认 synchronous=FULL
- SQLiteConfig 每 300 秒执行一次 PRAGMA wal_checkpoint(TRUNCATE)
- 上报端点对 109 条转发各做一条独立 autocommit UPDATE
- 生产 wal 文件曾达 1.8MB，说明 checkpoint 时确实有积压

前三轮基准在无 checkpoint 时 109 条 UPDATE 只要 41~111ms，解释不了 5 秒超时。
本实验在真实生产卷上复现「checkpoint 与上报撞车」，分别测 TRUNCATE 与 PASSIVE。

关键点：SQLite 在最后一个连接关闭时会自动做一次 checkpoint 并删除 WAL，
所以必须用一个常驻连接维持 WAL 积压，否则 checkpoint 无活可干（log=-1 done=-1）。
"""
import os
import sqlite3
import statistics
import threading
import time

SRC = "/var/lib/docker/volumes/sqlite_data/_data/gost.db"
WORK = "/var/lib/docker/volumes/sqlite_data/_data/_ckrace.db"
PROD_PARAMS = "?busy_timeout=5000"  # 与生产 URL 完全一致

_keeper = None  # 常驻连接，防止 WAL 被自动 checkpoint 掉


def _wipe():
    for p in (WORK, WORK + "-wal", WORK + "-shm"):
        if os.path.exists(p):
            os.remove(p)


def prepare(fill_rows=6000):
    """复制生产库并制造真实 WAL 积压（约 2.4MB，与生产峰值同量级）。"""
    global _keeper
    _wipe()
    src = sqlite3.connect("file:%s?mode=ro" % SRC, uri=True)
    dst = sqlite3.connect("file:" + WORK, uri=True)
    src.backup(dst)
    src.close()
    dst.execute("PRAGMA journal_mode=WAL")
    dst.execute("CREATE TABLE IF NOT EXISTS _fill (id INTEGER PRIMARY KEY, pad TEXT)")
    dst.executemany("INSERT INTO _fill (pad) VALUES (?)",
                    [("x" * 400,) for _ in range(fill_rows)])
    dst.commit()
    _keeper = dst  # 故意不关闭：保持 WAL 有积压
    wal = WORK + "-wal"
    sz = os.path.getsize(wal) if os.path.exists(wal) else 0
    print("   准备：库 %d 字节，WAL 积压 %d 字节（%.1f MB）"
          % (os.path.getsize(WORK), sz, sz / 1048576.0))


def open_prod_conn(timeout=5.0):
    """完全模拟生产的连接创建方式：只带 busy_timeout。

    注意：python 的 sqlite3.connect 只有 uri=True 才会解析 "?参数"，
    否则会把整段参数当文件名的一部分，静默打开一个全新空库
    （与 sqlite-jdbc 遇到未知参数时的行为同源）。这里必须用 file: URI。
    """
    return sqlite3.connect("file:" + WORK + PROD_PARAMS, uri=True,
                           isolation_level=None, timeout=timeout)


def fwd_ids():
    c = sqlite3.connect("file:" + WORK, uri=True)
    r = [row[0] for row in c.execute("SELECT id FROM forward LIMIT 109")]
    c.close()
    return r


def upload_once(conn, ids):
    """一次上报：109 条独立 autocommit UPDATE（与 flushFlows 一致）。"""
    t0 = time.time()
    try:
        for fid in ids:
            conn.execute(
                "UPDATE forward SET in_flow = in_flow + 78, out_flow = out_flow + 78 WHERE id = ?",
                (fid,),
            )
            conn.commit()
        return (time.time() - t0) * 1000, None
    except Exception as e:
        return (time.time() - t0) * 1000, "%s: %s" % (type(e).__name__, str(e)[:60])


def run_case(mode, ids, rounds=6):
    # 1) 一个持有读快照的连接（生产上 = 正在跑的长查询/统计采集）
    reader = sqlite3.connect("file:" + WORK, uri=True, isolation_level=None)
    reader.execute("BEGIN")
    reader.execute("SELECT COUNT(*) FROM _fill").fetchone()
    wal_before = os.path.getsize(WORK + "-wal")
    done = threading.Event()

    def do_checkpoint():
        time.sleep(0.03)
        ck = open_prod_conn(timeout=10.0)
        t0 = time.time()
        try:
            r = ck.execute("PRAGMA wal_checkpoint(%s)" % mode).fetchone()
            res = "busy=%s log=%s done=%s" % r
        except Exception as e:
            res = "EXC %s: %s" % (type(e).__name__, str(e)[:50])
        ck.close()
        print("   [checkpoint %s] %5.0fms  %s  (WAL 前 %.1f MB)"
              % (mode, (time.time() - t0) * 1000, res, wal_before / 1048576.0))
        done.set()

    ck_thread = threading.Thread(target=do_checkpoint)
    ck_thread.start()

    # 2) checkpoint 正在跑的时候，上报线程开始写
    time.sleep(0.12)
    conn = open_prod_conn()
    sync = conn.execute("PRAGMA synchronous").fetchone()[0]
    results = []
    while not done.is_set() and len(results) < rounds:
        results.append(upload_once(conn, ids))
    conn.close()
    ck_thread.join()

    # 3) checkpoint 结束后再写几次，看是否恢复
    conn = open_prod_conn()
    after = [upload_once(conn, ids) for _ in range(3)]
    conn.close()
    reader.execute("ROLLBACK")
    reader.close()

    if results:
        lat = [r[0] for r in results]
        fails = [r for r in results if r[1]]
        print("   上报（checkpoint 期间，synchronous=%s）：%d 次  p50=%.0fms max=%.0fms  失败=%d/%d"
              % (sync, len(results), statistics.median(lat), max(lat), len(fails), len(results)))
        if fails:
            print("   首次失败：%.0fms  %s" % (fails[0][0], fails[0][1]))
    else:
        print("   上报（checkpoint 期间）：没能在窗口内发起写入")
    al = [a[0] for a in after]
    af = [a for a in after if a[1]]
    print("   上报（checkpoint 结束后）：%d 次  max=%.0fms  失败=%d/%d"
          % (len(after), max(al), len(af), len(after)))


if __name__ == "__main__":
    print("=== 场景 A：生产现状 checkpoint(TRUNCATE) 与上报撞车 ===")
    prepare()
    ids = fwd_ids()
    run_case("TRUNCATE", ids)
    _keeper.close()

    print("")
    print("=== 场景 B：修复后 checkpoint(PASSIVE) 与上报撞车 ===")
    prepare()
    ids = fwd_ids()
    run_case("PASSIVE", ids)
    _keeper.close()

    _wipe()

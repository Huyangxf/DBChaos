#!/usr/bin/env python3
"""Record real idle_transaction_buildup runs and create one CSV/SVG per mode."""
import argparse, csv, math, os, shlex, subprocess, time
from html import escape

MODES = ("fixed", "ramp", "random_hold")

def query_count(a):
    if a.db == "mysql":
        sql = ("SELECT COUNT(*) FROM information_schema.INNODB_TRX t "
               "JOIN information_schema.PROCESSLIST p ON p.ID=t.trx_mysql_thread_id "
               "WHERE p.USER='" + a.user.replace("'", "''") + "' AND p.DB='" +
               a.database.replace("'", "''") + "';")
        cmd = shlex.split(a.mysql_command) + ["--batch", "--skip-column-names", "-e", sql]
    else:
        sql = ("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() "
               "AND usename='" + a.user.replace("'", "''") +
               "' AND state='idle in transaction' AND query LIKE 'SELECT * FROM " +
               a.table + " LIMIT 1%';")
        cmd = shlex.split(a.psql_command) + ["-h", a.host, "-p", str(a.port),
             "-U", a.user, "-d", a.database, "-At", "-c", sql]
    try:
        r = subprocess.run(cmd, universal_newlines=True, stdout=subprocess.PIPE,
                           stderr=subprocess.PIPE, timeout=10)
        if r.returncode:
            return None, r.stderr.strip()
        return int(r.stdout.strip().splitlines()[-1]), ""
    except Exception as e:
        return None, str(e)

def write_csv(path, rows):
    with open(path, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=["elapsed_sec", "phase",
                                           "active_transactions", "sample_error"])
        w.writeheader(); w.writerows(rows)

def write_svg(path, rows, mode, before, duration, target):
    width, height = 1120, 620
    left, right, top, bottom = 90, 40, 80, 90
    times = [float(r["elapsed_sec"]) for r in rows]
    values = [int(r["active_transactions"]) for r in rows
              if r["active_transactions"] != ""]
    max_t = max(times) if times else 1
    max_y = max([target] + values + [1])
    pw, ph = width-left-right, height-top-bottom
    X = lambda t: left + t/max_t*pw
    Y = lambda v: top + ph - v/max_y*ph
    raw_tick = max_t / 7.0
    magnitude = 10 ** math.floor(math.log10(raw_tick)) if raw_tick > 0 else 1
    tick_step = next(multiplier * magnitude for multiplier in (1, 2, 5, 10)
                     if multiplier * magnitude >= raw_tick)
    segments, current = [], []
    for row in rows:
        if row["active_transactions"] == "":
            if current:
                segments.append(" ".join(current))
                current = []
            continue
        current.append(f"{X(float(row['elapsed_sec'])):.1f},{Y(int(row['active_transactions'])):.1f}")
    if current:
        segments.append(" ".join(current))
    s1, s2 = X(before), X(before + duration)
    out = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}">',
           '<rect width="100%" height="100%" fill="white"/>',
           f'<text x="560" y="35" text-anchor="middle" font-family="Arial" font-size="24" font-weight="bold">空闲事务积压 - {escape(mode)}（真实采样）</text>',
           f'<rect x="{left}" y="{top}" width="{s1-left}" height="{ph}" fill="#eef2f7"/>',
           f'<rect x="{s1}" y="{top}" width="{s2-s1}" height="{ph}" fill="#fff3df"/>',
           f'<rect x="{s2}" y="{top}" width="{left+pw-s2}" height="{ph}" fill="#edf8ee"/>']
    for tick in range(0, max_y+1, max(1, max_y//5)):
        yy = Y(tick)
        out += [f'<line x1="{left}" y1="{yy}" x2="{left+pw}" y2="{yy}" stroke="#d9dee7"/>',
                f'<text x="{left-10}" y="{yy+5}" text-anchor="end" font-family="Arial" font-size="13">{tick}</text>']
    out += [f'<line x1="{left}" y1="{top+ph}" x2="{left+pw}" y2="{top+ph}" stroke="#333"/>',
            f'<line x1="{left}" y1="{top}" x2="{left}" y2="{top+ph}" stroke="#333"/>',
            *[f'<polyline points="{pts}" fill="none" stroke="#c43d32" stroke-width="4"/>' for pts in segments],
            f'<text x="{(left+s1)/2}" y="{top-18}" text-anchor="middle" font-family="Arial">注入前</text>',
            f'<text x="{(s1+s2)/2}" y="{top-18}" text-anchor="middle" font-family="Arial">注入中</text>',
            f'<text x="{(s2+left+pw)/2}" y="{top-18}" text-anchor="middle" font-family="Arial">注入后</text>',
            f'<text x="{left+pw/2}" y="{height-25}" text-anchor="middle" font-family="Arial">时间（秒）</text>',
            f'<text x="20" y="{top+ph/2}" transform="rotate(-90 20 {top+ph/2})" text-anchor="middle" font-family="Arial">实际未结束事务数</text>',
            '</svg>']
    tick = 0.0
    while tick <= max_t + tick_step * 1e-9:
        xx = X(tick)
        out.insert(-1, f'<line x1="{xx:.1f}" y1="{top+ph}" x2="{xx:.1f}" y2="{top+ph+6}" stroke="#333"/>')
        out.insert(-1, f'<text x="{xx:.1f}" y="{top+ph+24}" text-anchor="middle" font-family="Arial" font-size="13">{tick:g}</text>')
        tick += tick_step
    if not values:
        out.insert(-1, f'<text x="{left+pw/2}" y="{top+ph/2}" text-anchor="middle" font-family="Arial" font-size="18" fill="#a22">没有有效采样值，未绘制曲线</text>')
    with open(path, "w", encoding="utf-8") as f: f.write("\n".join(out))

def main():
    p = argparse.ArgumentParser(description="真实采样空闲事务积压并生成 CSV/SVG")
    p.add_argument("--db", choices=("mysql", "postgresql"), default="mysql")
    p.add_argument("--mode", choices=MODES, default=None,
                   help="单个模式；不指定则依次运行三个模式")
    p.add_argument("--output-dir", default="static/yxf_demos/IdleTransactionBuildup")
    p.add_argument("--count", type=int, default=20,
                   help="每种模式尝试建立的事务数，默认 20")
    p.add_argument("--duration", type=int, default=60000,
                   help="注入窗口毫秒数，默认 60000")
    p.add_argument("--before", type=float, default=10,
                   help="注入前采样秒数，默认 10")
    p.add_argument("--after", type=float, default=15,
                   help="注入后采样秒数，默认 15")
    p.add_argument("--interval", type=float, default=1)
    p.add_argument("--table", default="dbchaos_txn_demo")
    p.add_argument("--database", default="chaos_test")
    p.add_argument("--user", default="chaos_user")
    p.add_argument("--host", default="127.0.0.1")
    p.add_argument("--port", default=15432)
    p.add_argument("--mysql-command", default="sudo -n mysql --protocol=socket")
    p.add_argument("--psql-command", default="psql")
    p.add_argument("--java", default="/usr/lib/jvm/java-8-openjdk-amd64/bin/java")
    p.add_argument("--classpath", default="config:target/DBChaos-0.0.1.jar")
    p.add_argument("--min-hold", type=int, default=20000,
                   help="random_hold 最短保持毫秒数，默认 20000")
    a = p.parse_args()
    if a.duration <= 0 or a.interval <= 0 or a.before < 0 or a.after < 0:
        p.error("duration、interval 必须为正数；before/after 不能为负数")
    os.makedirs(a.output_dir, exist_ok=True)
    if a.db == "mysql":
        print("请完成 sudo 身份验证，以便读取 InnoDB 活跃事务；可能会提示输入系统密码。", flush=True)
        if subprocess.call(["sudo", "-v"]) != 0:
            p.error("sudo 身份验证失败，未启动故障注入")
    initial_count, initial_error = query_count(a)
    if initial_error:
        p.error("数据库监控查询失败，未启动故障注入：" + initial_error)
    print("数据库监控查询正常，当前事务数:", initial_count, flush=True)
    for mode in MODES if a.mode is None else (a.mode,):
        if a.db == "mysql" and subprocess.call(["sudo", "-v"]) != 0:
            p.error("sudo 身份验证失效，未启动 " + mode + " 模式")
        current_count, current_error = query_count(a)
        if current_error:
            p.error("启动 " + mode + " 前数据库监控查询失败，未注入：" + current_error)
        print("开始真实采样:", mode, flush=True)
        rows, start = [], time.monotonic()
        cmd = [a.java, "-cp", a.classpath, "chaos.Main", "--db", a.db,
               "session", "idle_transaction_buildup", "-table", a.table,
               "-mode", mode, "-count", str(a.count), "-duration", str(a.duration)]
        if mode == "random_hold":
            cmd += ["-min-hold", str(a.min_hold)]
        total = a.before + a.duration/1000 + a.after
        proc = None
        while time.monotonic()-start <= total:
            elapsed = time.monotonic()-start
            if proc is None and elapsed >= a.before:
                proc = subprocess.Popen(cmd, stdout=subprocess.PIPE,
                                        stderr=subprocess.STDOUT,
                                        universal_newlines=True)
            phase = ("before" if elapsed < a.before else
                     ("injection" if elapsed < a.before+a.duration/1000 else "after"))
            count, error = query_count(a)
            rows.append({"elapsed_sec": f"{elapsed:.3f}", "phase": phase,
                         "active_transactions": "" if count is None else str(count),
                         "sample_error": error})
            time.sleep(a.interval)
        output, _ = proc.communicate(timeout=30)
        print(output, end="")
        base = os.path.join(a.output_dir, "idle_transaction_buildup_"+mode)
        write_csv(base+".csv", rows)
        write_svg(base+".svg", rows, mode, a.before, a.duration/1000, a.count)
        print("CSV:", base+".csv"); print("SVG:", base+".svg")

if __name__ == "__main__":
    main()


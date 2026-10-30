#!/usr/bin/env python3
"""Run one DBChaos PostgreSQL case while sampling before/during/after metrics.

Uses only Python's standard library plus psql. The output is a CSV time series
and a self-contained SVG chart, so matplotlib is not required.
"""

import argparse
import csv
import datetime
import html
import os
import shlex
import subprocess
import sys
import threading
import time


JAVA_DEFAULT = "/usr/lib/jvm/java-8-openjdk-amd64/bin/java"
PSQL_DEFAULT = "/usr/lib/postgresql/10/bin/psql"
JAR_DEFAULT = "target/DBChaos-0.0.1.jar"
PGJDBC_DEFAULT = "lib/postgresql-42.7.2.jar"
SOCKET_DEFAULT = "/home/yangxf/dbchaos-pg/socket"

METRIC_SQL = """
SELECT
  (SELECT count(*) FROM pg_stat_activity
   WHERE datname = current_database()
     AND application_name IS DISTINCT FROM 'dbchaos_visualizer'),
  (SELECT count(*) FROM pg_stat_activity
   WHERE datname = current_database() AND state = 'active'
     AND application_name IS DISTINCT FROM 'dbchaos_visualizer'),
  (SELECT count(*) FROM pg_stat_activity
   WHERE datname = current_database() AND wait_event_type = 'Lock'
     AND application_name IS DISTINCT FROM 'dbchaos_visualizer'),
  (SELECT count(*) FROM pg_stat_activity
   WHERE datname = current_database() AND state = 'idle in transaction'
     AND application_name IS DISTINCT FROM 'dbchaos_visualizer'),
  xact_commit,
  xact_rollback
FROM pg_stat_database
WHERE datname = current_database();
"""


def parse_args():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--case", required=True, choices=("lock", "conn-storm", "rollback"))
    p.add_argument("--java", default=JAVA_DEFAULT)
    p.add_argument("--psql", default=PSQL_DEFAULT)
    p.add_argument("--jar", default=JAR_DEFAULT)
    p.add_argument("--pgjdbc", default=PGJDBC_DEFAULT)
    p.add_argument("--socket", default=SOCKET_DEFAULT,
                   help="PostgreSQL Unix socket directory used by the monitor (peer auth as OS user)")
    p.add_argument("--port", type=int, default=15432)
    p.add_argument("--database", default="chaos_test")
    p.add_argument("--monitor-user", default="yangxf")
    p.add_argument("--case-user", default="chaos_user")
    p.add_argument("--interval", type=float, default=0.5, help="sampling interval in seconds")
    p.add_argument("--before", type=float, default=15, help="baseline duration in seconds")
    p.add_argument("--after", type=float, default=15, help="recovery observation duration in seconds")
    p.add_argument("--duration-ms", type=int, default=30000, help="injection duration in milliseconds")
    p.add_argument("--count", type=int, default=10, help="connection-storm worker count")
    p.add_argument("--threads", type=int, default=2, help="rollback worker count")
    p.add_argument("--rate", type=float, default=0.9, help="rollback probability")
    p.add_argument("--output-dir", default="static/yxf_demos")
    return p.parse_args()


class Sampler(threading.Thread):
    def __init__(self, args, csv_path):
        threading.Thread.__init__(self)
        self.daemon = True
        self.args = args
        self.csv_path = csv_path
        self.stop_event = threading.Event()
        self.phase_lock = threading.Lock()
        self.phase = "before"
        self.rows = []
        self.errors = 0
        self.started = time.time()

    def set_phase(self, phase):
        with self.phase_lock:
            self.phase = phase

    def run(self):
        while not self.stop_event.is_set():
            wall = time.time()
            elapsed = wall - self.started
            with self.phase_lock:
                phase = self.phase
            env = os.environ.copy()
            env["PGAPPNAME"] = "dbchaos_visualizer"
            cmd = [self.args.psql, "-X", "-q", "-A", "-t", "-F", ",",
                   "-h", self.args.socket, "-p", str(self.args.port),
                   "-U", self.args.monitor_user, "-d", self.args.database,
                   "-c", METRIC_SQL]
            try:
                result = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                        universal_newlines=True, timeout=4, env=env)
                values = result.stdout.strip().split(",") if result.returncode == 0 else []
                if len(values) == 6:
                    metrics = [int(v.strip()) for v in values]
                else:
                    self.errors += 1
                    metrics = [None] * 6
            except (OSError, subprocess.SubprocessError, ValueError):
                self.errors += 1
                metrics = [None] * 6
            self.rows.append([wall, elapsed, phase] + metrics)
            remaining = self.args.interval - (time.time() - wall)
            if remaining > 0:
                self.stop_event.wait(remaining)

    def finish(self):
        self.stop_event.set()
        self.join()
        fields = ["epoch_seconds", "elapsed_seconds", "phase", "connections",
                  "active_sessions", "lock_waits", "idle_in_transaction",
                  "xact_commit", "xact_rollback"]
        with open(self.csv_path, "w", newline="") as f:
            writer = csv.writer(f)
            writer.writerow(fields)
            for row in self.rows:
                writer.writerow(["" if v is None else ("%.3f" % v if i < 2 else v)
                                 for i, v in enumerate(row)])


def psql_command(args, sql, app_name="dbchaos_visualizer_setup", timeout=10):
    env = os.environ.copy()
    env["PGAPPNAME"] = app_name
    cmd = [args.psql, "-X", "-q", "-A", "-t", "-v", "ON_ERROR_STOP=1", "-h", args.socket,
           "-p", str(args.port), "-U", args.monitor_user,
           "-d", args.database, "-c", sql]
    return subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                          universal_newlines=True, timeout=timeout, env=env)


def make_output_paths(args):
    stamp = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
    out_dir = os.path.abspath(args.output_dir)
    if not os.path.isdir(out_dir):
        os.makedirs(out_dir)
    base = "%s_%s" % (args.case, stamp)
    return os.path.join(out_dir, base + ".csv"), os.path.join(out_dir, base + ".svg")


def java_command(args):
    cp = os.pathsep.join(["config", args.pgjdbc, args.jar])
    prefix = [args.java, "-cp", cp, "chaos.Main"]
    if args.case == "lock":
        table = "dbchaos_visual_lock"
        return prefix + ["txn", "uncommitted_txn", "-duration", str(args.duration_ms),
                        "-table", table, "-holders", "1", "-rows", "1"]
    if args.case == "conn-storm":
        return prefix + ["session", "max_connection", "-mode", "conn_storm",
                        "-duration", str(args.duration_ms), "-count", str(args.count)]
    return prefix + ["log", "massive_rollback", "-duration", str(args.duration_ms),
                     "-threads", str(args.threads), "-rate", str(args.rate)]


def setup_case(args):
    if args.case == "lock":
        sql = ("CREATE TABLE IF NOT EXISTS dbchaos_visual_lock "
               "(id INTEGER PRIMARY KEY, value INTEGER NOT NULL); "
               "INSERT INTO dbchaos_visual_lock (id, value) VALUES (1, 0) "
               "ON CONFLICT (id) DO NOTHING; "
               "GRANT SELECT, UPDATE ON TABLE dbchaos_visual_lock TO chaos_user;")
        result = psql_command(args, sql)
        if result.returncode != 0:
            raise RuntimeError(result.stderr.strip() or "could not prepare lock probe table")
    elif args.case == "rollback":
        result = psql_command(args, "SELECT to_regclass('chaos_rollback_heavy');")
        if result.returncode != 0:
            raise RuntimeError(result.stderr.strip() or "could not inspect rollback table")
        if result.stdout.strip():
            raise RuntimeError("chaos_rollback_heavy already exists; inspect it before running rollback case")


def start_lock_probe(args, path):
    env = os.environ.copy()
    env["PGAPPNAME"] = "dbchaos_lock_visual_probe"
    sql = ("BEGIN; SET LOCAL statement_timeout = '120s'; "
           "UPDATE dbchaos_visual_lock SET value = value + 1 WHERE id = 1; ROLLBACK;")
    cmd = [args.psql, "-X", "-v", "ON_ERROR_STOP=1", "-h", "127.0.0.1",
           "-p", str(args.port), "-U", args.case_user, "-d", args.database, "-c", sql]
    with open(path, "w") as log:
        return subprocess.Popen(cmd, stdout=log, stderr=subprocess.STDOUT, env=env)


def nice_max(values):
    values = [v for v in values if v is not None]
    top = max(values or [1])
    if top <= 5:
        return max(1, top)
    step = 10 ** max(0, len(str(int(top))) - 1)
    return ((int(top) + step - 1) // step) * step


def svg_chart(csv_path, svg_path, title):
    with open(csv_path, "r", newline="") as f:
        raw = list(csv.DictReader(f))
    rows = []
    for r in raw:
        try:
            x = float(r["elapsed_seconds"])
        except (ValueError, KeyError):
            continue
        row = {"x": x, "phase": r["phase"]}
        for key in ("connections", "active_sessions", "lock_waits", "idle_in_transaction",
                    "xact_commit", "xact_rollback"):
            try:
                row[key] = int(r[key]) if r[key] else None
            except ValueError:
                row[key] = None
        rows.append(row)
    if len(rows) < 2:
        raise RuntimeError("not enough metric samples to draw a chart")

    # Convert cumulative transaction counters to per-second rates.
    for i, row in enumerate(rows):
        row["commit_rate"] = None
        row["rollback_rate"] = None
        if i and row["x"] > rows[i - 1]["x"]:
            prev = rows[i - 1]
            dt = row["x"] - prev["x"]
            for key, counter in (("commit_rate", "xact_commit"),
                                 ("rollback_rate", "xact_rollback")):
                if row[counter] is not None and prev[counter] is not None:
                    row[key] = max(0, row[counter] - prev[counter]) / dt

    width, height = 1120, 800
    left, right = 78, 1080
    panel_h, gap, top = 185, 34, 92
    x_max = max(rows[-1]["x"], 1)
    colors = {"before": "#e8f5e9", "injecting": "#ffebee", "after": "#e8eefc"}
    panels = [
        ("Database connections", [("connections", "#1565c0"), ("active_sessions", "#ef6c00")]),
        ("Lock pressure / open transactions", [("lock_waits", "#c62828"),
                                                  ("idle_in_transaction", "#6a1b9a")]),
        ("Transaction rate (per second)", [("commit_rate", "#2e7d32"),
                                            ("rollback_rate", "#ad1457")]),
    ]
    out = ['<svg xmlns="http://www.w3.org/2000/svg" width="%d" height="%d" viewBox="0 0 %d %d">' % (width, height, width, height),
           '<rect width="100%" height="100%" fill="#ffffff"/>',
           '<style>text{font-family:Arial,sans-serif;fill:#263238}.small{font-size:12px}.axis{stroke:#90a4ae;stroke-width:1}.grid{stroke:#cfd8dc;stroke-width:1;stroke-dasharray:3 4}</style>',
           '<text x="%d" y="34" font-size="22" font-weight="bold">%s</text>' % (left, html.escape(title))]
    phase_ranges = []
    start = 0
    current = rows[0]["phase"]
    for i in range(1, len(rows)):
        if rows[i]["phase"] != current:
            phase_ranges.append((rows[start]["x"], rows[i - 1]["x"], current))
            start, current = i, rows[i]["phase"]
    phase_ranges.append((rows[start]["x"], rows[-1]["x"], current))

    for panel_i, (label, series) in enumerate(panels):
        y_top = top + panel_i * (panel_h + gap)
        y_bottom = y_top + panel_h - 28
        plot_h = y_bottom - y_top
        values = [r[s[0]] for r in rows for s in series if r[s[0]] is not None]
        ymax = nice_max(values)
        out.append('<text x="%d" y="%d" font-size="15" font-weight="bold">%s</text>' % (left, y_top - 9, html.escape(label)))
        for xa, xb, phase in phase_ranges:
            x1 = left + (xa / x_max) * (right - left)
            x2 = left + (xb / x_max) * (right - left)
            out.append('<rect x="%.1f" y="%d" width="%.1f" height="%d" fill="%s" opacity="0.75"/>' % (x1, y_top, max(1, x2 - x1), plot_h, colors.get(phase, "#fff")))
        for tick in range(5):
            val = ymax * tick / 4.0
            y = y_bottom - plot_h * tick / 4.0
            out.append('<line x1="%d" y1="%.1f" x2="%d" y2="%.1f" class="grid"/>' % (left, y, right, y))
            out.append('<text x="%d" y="%.1f" text-anchor="end" class="small">%g</text>' % (left - 9, y + 4, val))
        out.append('<line x1="%d" y1="%d" x2="%d" y2="%d" class="axis"/>' % (left, y_top, left, y_bottom))
        out.append('<line x1="%d" y1="%d" x2="%d" y2="%d" class="axis"/>' % (left, y_bottom, right, y_bottom))
        for si, (key, color) in enumerate(series):
            points, segments = [], []
            for r in rows:
                val = r[key]
                if val is None:
                    if points:
                        segments.append(points)
                        points = []
                    continue
                x = left + (r["x"] / x_max) * (right - left)
                y = y_bottom - (min(val, ymax) / float(ymax or 1)) * plot_h
                points.append("%.1f,%.1f" % (x, y))
            if points:
                segments.append(points)
            for segment in segments:
                if len(segment) == 1:
                    x, y = segment[0].split(",")
                    out.append('<circle cx="%s" cy="%s" r="2" fill="%s"/>' % (x, y, color))
                else:
                    out.append('<polyline fill="none" stroke="%s" stroke-width="2" points="%s"/>' % (color, " ".join(segment)))
            lx = left + 15 + si * 190
            out.append('<line x1="%d" y1="%d" x2="%d" y2="%d" stroke="%s" stroke-width="3"/>' % (lx, y_top + 16, lx + 24, y_top + 16, color))
            out.append('<text x="%d" y="%d" class="small">%s</text>' % (lx + 30, y_top + 20, html.escape(key.replace("_", " "))))
        for fraction in (0, 0.25, 0.5, 0.75, 1):
            x = left + fraction * (right - left)
            out.append('<text x="%.1f" y="%d" text-anchor="middle" class="small">%.1fs</text>' % (x, y_bottom + 17, fraction * x_max))
    out.append('<text x="%d" y="%d" class="small">Phase: green = before, red = injection, blue = recovery</text>' % (left, height - 16))
    out.append('</svg>')
    with open(svg_path, "w", encoding="utf-8") as f:
        f.write("\n".join(out))


def main():
    args = parse_args()
    if args.interval <= 0 or args.before < 0 or args.after < 0 or args.duration_ms <= 0:
        print("interval and duration must be positive; before/after cannot be negative", file=sys.stderr)
        return 2
    if args.case == "rollback" and not (0 <= args.rate <= 1):
        print("rollback rate must be between 0 and 1", file=sys.stderr)
        return 2
    for path in (args.java, args.psql, args.jar, args.pgjdbc):
        if not os.path.exists(path):
            print("Required file not found: %s" % path, file=sys.stderr)
            return 2
    try:
        setup_case(args)
        csv_path, svg_path = make_output_paths(args)
        probe_path = os.path.splitext(csv_path)[0] + ".lock-probe.log"
        sampler = Sampler(args, csv_path)
        sampler.start()
        print("Collecting baseline for %.1f seconds..." % args.before, flush=True)
        time.sleep(args.before)
        sampler.set_phase("injecting")
        command = java_command(args)
        print("Starting case: %s" % " ".join(shlex.quote(part) for part in command), flush=True)
        process = subprocess.Popen(command)
        probe = None
        if args.case == "lock":
            time.sleep(1)
            probe = start_lock_probe(args, probe_path)
        result = process.wait()
        sampler.set_phase("after")
        if probe is not None:
            try:
                probe.wait(timeout=125)
            except subprocess.TimeoutExpired:
                probe.terminate()
                probe.wait()
                print("Lock observation probe exceeded its 120s statement timeout", file=sys.stderr)
        print("Collecting recovery for %.1f seconds..." % args.after, flush=True)
        time.sleep(args.after)
        sampler.finish()
        svg_chart(csv_path, svg_path, "DBChaos %s: before / injection / recovery" % args.case)
        print("CSV: %s" % csv_path)
        print("SVG: %s" % svg_path)
        print("Samples: %d; monitor query failures: %d" % (len(sampler.rows), sampler.errors))
        if args.case == "lock":
            print("Lock probe log: %s" % probe_path)
        return result
    except (OSError, RuntimeError, subprocess.SubprocessError) as e:
        print("ERROR: %s" % e, file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())

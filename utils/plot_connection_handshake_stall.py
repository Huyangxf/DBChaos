#!/usr/bin/env python3
"""Run both connection_handshake_stall modes and visualize real injector output."""
import argparse
import csv
import os
import queue
import re
import subprocess
import sys
import threading
import time

try:
    from .plot_case_visualization import _write_svg
except ImportError:
    from plot_case_visualization import _write_svg


PROJECT_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
STALLED_RE = re.compile(r"连接\s+(\d+)\s*\|.*已进入停滞")
RESULT_RE = re.compile(r"连接\s+(\d+)\s*\|\s*(.*?)\s*\|\s*停滞观察")
SUMMARY_RE = re.compile(
    r"汇总\s*\|\s*TCP 建立:\s*(\d+)\s*\|\s*进入停滞:\s*(\d+)"
    r"\s*\|\s*停滞后对端 EOF:\s*(\d+)\s*\|\s*停滞后传输错误:\s*(\d+)"
    r"\s*\|\s*实验到期主动关闭:\s*(\d+)\s*\|\s*建连/准备失败:\s*(\d+)"
    r"\s*\|\s*取消:\s*(\d+)"
)

FIELDS = ["tcp_prepared", "active_stalled", "expired_closed", "peer_eof",
          "transport_errors", "preparation_failures", "cancelled"]


def read_output(stream, output_queue):
    try:
        for line in iter(stream.readline, ""):
            output_queue.put(line.rstrip("\r\n"))
    finally:
        stream.close()
        output_queue.put(None)


def row(elapsed, before, injection_end, values, event, exit_code=""):
    if elapsed < before:
        phase = "before"
    elif injection_end is None or elapsed <= injection_end:
        phase = "injection"
    else:
        phase = "after"
    result = {"elapsed_sec": "{:.3f}".format(elapsed), "phase": phase,
              "event": event, "process_exit_code": exit_code}
    result.update({key: str(values[key]) for key in FIELDS})
    return result


def record_line(line, values, active_ids):
    match = STALLED_RE.search(line)
    if match:
        connection_id = match.group(1)
        active_ids.add(connection_id)
        values["tcp_prepared"] = len(active_ids) + values["expired_closed"] + values["peer_eof"] + values["transport_errors"]
        return "entered_stall"

    match = RESULT_RE.search(line)
    if match:
        connection_id, result = match.groups()
        active_ids.discard(connection_id)
        if result.startswith("实验到期主动关闭"):
            values["expired_closed"] += 1
        elif result.startswith("对端 EOF"):
            values["peer_eof"] += 1
        elif result.startswith("传输错误"):
            values["transport_errors"] += 1
        return "connection_result"

    if "失败:" in line and line.startswith("连接 "):
        values["preparation_failures"] += 1
        return "connection_failure"

    match = SUMMARY_RE.search(line)
    if match:
        connected, prepared, eof, errors, expired, failures, cancelled = map(int, match.groups())
        values.update({"tcp_prepared": prepared, "peer_eof": eof,
                       "transport_errors": errors, "expired_closed": expired,
                       "preparation_failures": failures, "cancelled": cancelled,
                       "active_stalled": max(0, prepared - eof - errors - expired - cancelled)})
        return "summary_tcp_connected_{}".format(connected)
    return None


def write_csv(path, rows):
    columns = ["elapsed_sec", "phase", "event", "process_exit_code"] + FIELDS
    with open(path, "w", newline="", encoding="utf-8") as output:
        writer = csv.DictWriter(output, fieldnames=columns)
        writer.writeheader()
        writer.writerows(rows)


def run_mode(args, mode):
    output_dir = os.path.abspath(args.output_dir)
    os.makedirs(output_dir, exist_ok=True)
    prefix = "connection_handshake_stall_{}_{}".format(args.db, mode)
    csv_path = os.path.join(output_dir, prefix + ".csv")
    svg_path = os.path.join(output_dir, prefix + ".svg")
    command = [args.java, "-cp", args.classpath, "chaos.Main", "--db", args.db,
               "session", "connection_handshake_stall", "-mode", mode,
               "-count", str(args.count), "-duration", str(args.duration),
               "-connect-timeout", str(args.connect_timeout),
               "-greeting-timeout", str(args.greeting_timeout)]
    if args.url:
        command.extend(["-url", args.url])
    print("開始真实采样: connection_handshake_stall | mode={} | {}".format(
        mode, " ".join(command)), flush=True)

    values = dict((key, 0) for key in FIELDS)
    active_ids = set()
    rows = []
    output_queue = queue.Queue()
    start = time.monotonic()
    launch_elapsed = None
    injection_end = None
    process = None
    reader = None
    output_finished = False
    next_sample = 0.0
    exit_code = ""
    timed_out = False
    rows.append(row(0.0, args.before, None, values, "start"))

    while True:
        elapsed = time.monotonic() - start
        if process is None and elapsed >= args.before:
            launch_elapsed = elapsed
            try:
                process = subprocess.Popen(command, cwd=PROJECT_ROOT, stdout=subprocess.PIPE,
                                           stderr=subprocess.STDOUT, universal_newlines=True,
                                           bufsize=1)
            except OSError as error:
                print("启动 DBChaos 失败: {}".format(error), file=sys.stderr)
                injection_end = time.monotonic() - start
                break
            reader = threading.Thread(target=read_output, args=(process.stdout, output_queue))
            reader.daemon = True
            reader.start()

        if process is None:
            if elapsed >= next_sample:
                rows.append(row(elapsed, args.before, None, values, "sample"))
                next_sample += args.sample_interval
            time.sleep(min(0.1, max(0.01, args.sample_interval / 4)))
            continue

        try:
            line = output_queue.get(timeout=min(0.2, args.sample_interval))
        except queue.Empty:
            line = "__NO_LINE__"
        if line is None:
            output_finished = True
        elif line != "__NO_LINE__":
            print(line, flush=True)
            event = record_line(line, values, active_ids)
            values["active_stalled"] = len(active_ids)
            if event:
                event_elapsed = time.monotonic() - start
                rows.append(row(event_elapsed, args.before, None, values, event))

        elapsed = time.monotonic() - start
        if elapsed >= next_sample:
            rows.append(row(elapsed, args.before, None, values, "sample"))
            next_sample += args.sample_interval

        if elapsed - launch_elapsed > args.process_timeout and process.poll() is None:
            timed_out = True
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
        if process.poll() is not None and output_finished:
            exit_code = str(process.returncode)
            injection_end = time.monotonic() - start
            break

    if process is not None:
        if process.poll() is None:
            process.wait()
        exit_code = str(process.returncode)
        if reader:
            reader.join(timeout=5)
        injection_end = injection_end if injection_end is not None else time.monotonic() - start
    elif injection_end is None:
        injection_end = time.monotonic() - start

    values["active_stalled"] = len(active_ids)
    rows.append(row(injection_end, args.before, injection_end, values, "injection_complete", exit_code))
    after_deadline = injection_end + args.after
    while time.monotonic() - start <= after_deadline:
        elapsed = time.monotonic() - start
        rows.append(row(elapsed, args.before, injection_end, values, "sample", exit_code))
        time.sleep(min(args.sample_interval, max(0.0, after_deadline - elapsed)))

    rows.sort(key=lambda item: (float(item["elapsed_sec"]),
                                0 if item["event"] not in ("sample", "start") else 1))
    for item in rows:
        elapsed = float(item["elapsed_sec"])
        item["phase"] = "before" if elapsed < args.before else (
            "injection" if elapsed <= injection_end else "after")
        if not item["process_exit_code"]:
            item["process_exit_code"] = exit_code
    write_csv(csv_path, rows)

    svg_config = {
        "title": "连接握手停滞 - {}".format(args.db),
        "y_label": "连接数",
        "fields": FIELDS,
        "metric_count": sum(1 for item in rows if item["event"] not in ("sample", "start", "injection_complete")),
        "series": [
            {"field": "active_stalled", "label": "仍处于停滞的连接", "color": "#c43d32", "width": 4},
            {"field": "expired_closed", "label": "实验到期主动关闭", "color": "#3976a8", "dash": True, "width": 2},
            {"field": "peer_eof", "label": "停滞后对端 EOF", "color": "#d28b26", "dash": True, "width": 2},
            {"field": "transport_errors", "label": "停滞后传输错误", "color": "#688b45", "dash": True, "width": 2},
        ],
    }
    _write_svg(svg_path, rows, svg_config, mode, args.before, injection_end)
    print("CSV: {}".format(csv_path))
    print("SVG: {}".format(svg_path))
    if timed_out:
        print("警告：{} 模式达到运行超时，进程已停止。".format(mode), file=sys.stderr)
    return exit_code == "0" and not timed_out and values["tcp_prepared"] > 0


def main():
    default_output_dir = os.path.join(PROJECT_ROOT, "static", "yxf_demos", "ConnectionHandshakeStall")
    parser = argparse.ArgumentParser(
        description="真实运行连接握手停滞的 silent/partial 模式并生成 CSV/SVG。默认文件目录：./DBChaos/static/yxf_demos/ConnectionHandshakeStall",
        epilog="默认会自动创建输出目录；可用 --output-dir 修改。")
    parser.add_argument("--db", required=True, choices=("postgresql", "opengauss", "mysql"),
                        help="目标数据库；必须明确选择，以匹配数据库配置和驱动")
    parser.add_argument("--url", help="本次握手注入专用 JDBC URL 覆盖；必须是单地址且不带 ? 查询参数")
    parser.add_argument("--classpath", required=True,
                        help="配置目录、对应 JDBC 驱动和 DBChaos jar，例如 config:lib/postgresql-42.7.2.jar:target/DBChaos-0.0.1.jar")
    parser.add_argument("--java", default="java", help="Java 可执行文件，默认 java")
    parser.add_argument("--output-dir", default=default_output_dir,
                        help="CSV/SVG 输出目录；默认 ./DBChaos/static/yxf_demos/ConnectionHandshakeStall（目录不存在时自动创建）")
    parser.add_argument("--count", type=int, default=50, help="每种模式连接数，默认 50（1-200）")
    parser.add_argument("--duration", type=int, default=30000, help="每个连接停滞观察时长 ms，默认 30000")
    parser.add_argument("--connect-timeout", type=int, default=10000, help="TCP 建连超时 ms，默认 10000")
    parser.add_argument("--greeting-timeout", type=int, default=10000, help="MySQL partial 问候读取超时 ms，默认 10000")
    parser.add_argument("--before", type=float, default=5.0, help="注入前采样秒数，默认 5")
    parser.add_argument("--after", type=float, default=10.0, help="注入结束后采样秒数，默认 10")
    parser.add_argument("--sample-interval", type=float, default=1.0, help="普通 CSV 采样间隔秒数，默认 1")
    parser.add_argument("--process-timeout", type=float, default=120.0, help="每个模式的进程超时秒数，默认 120")
    args = parser.parse_args()
    if not (1 <= args.count <= 200):
        parser.error("count 必须在 1 到 200 之间")
    if not (1 <= args.duration <= 3600000 and 1 <= args.connect_timeout <= 60000
            and 1 <= args.greeting_timeout <= 60000):
        parser.error("duration/connect-timeout/greeting-timeout 超出允许范围")
    if args.url and ("?" in args.url or "#" in args.url or "," in args.url):
        parser.error("--url 不能包含查询参数、片段或多地址列表；握手停滞只支持单地址非 TLS URL")
    if args.before < 0 or args.after < 0 or args.sample_interval <= 0 or args.process_timeout <= 0:
        parser.error("before/after 不能为负数；sample-interval/process-timeout 必须为正数")
    os.makedirs(args.output_dir, exist_ok=True)
    success = True
    for mode in ("silent", "partial"):
        if not run_mode(args, mode):
            success = False
    return 0 if success else 1


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
"""Shared real-run collector and SVG renderer for session fault cases."""
import argparse
import csv
import math
import os
import queue
import re
import subprocess
import sys
import threading
import time
from html import escape


PROJECT_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TOKEN_RE = re.compile(r"([a-z_]+)=([^\s]+)")


def make_parser(description, output_dir):
    parser = argparse.ArgumentParser(description=description)
    parser.add_argument("--db", choices=("postgresql", "opengauss", "mysql"),
                        help="数据库类型；省略时使用 DBChaos 的默认配置")
    parser.add_argument("--java", default="java", help="Java 可执行文件，默认 java")
    parser.add_argument("--classpath", default="config:target/DBChaos-0.0.1.jar",
                        help="DBChaos classpath，默认读取 config 和 target jar")
    parser.add_argument("--output-dir", default=os.path.join(PROJECT_ROOT, output_dir),
                        help="输出目录；默认写入项目对应的 static/yxf_demos 目录")
    parser.add_argument("--before", type=float, default=5.0,
                        help="每个模式注入前采样秒数，默认 5")
    parser.add_argument("--after", type=float, default=10.0,
                        help="每个模式结束后采样秒数，默认 10")
    parser.add_argument("--sample-interval", type=float, default=1.0,
                        help="CSV 常规采样间隔秒数，默认 1")
    parser.add_argument("--process-timeout", type=float, default=600.0,
                        help="单次模式最大运行秒数，默认 600")
    return parser


def _read_output(stream, output_queue):
    try:
        for line in iter(stream.readline, ""):
            output_queue.put(line.rstrip("\r\n"))
    finally:
        stream.close()
        output_queue.put(None)


def _metric(line, prefix):
    if not line.startswith(prefix + " "):
        return None
    return dict(TOKEN_RE.findall(line[len(prefix):]))


def _parse_int(metric, key, default=0):
    try:
        return int(metric[key])
    except (KeyError, TypeError, ValueError):
        return default


def _phase(elapsed, before, injection_end):
    if elapsed < before:
        return "before"
    if injection_end is None or elapsed <= injection_end:
        return "injection"
    return "after"


def _new_row(elapsed, before, injection_end, values, event, metric_received, exit_code=""):
    row = {"elapsed_sec": "{:.3f}".format(max(0.0, elapsed)),
           "phase": _phase(elapsed, before, injection_end),
           "event": event,
           "metric_received": "yes" if metric_received else "no",
           "process_exit_code": exit_code}
    row.update(values)
    return row


def _write_csv(path, rows, fields):
    names = ["elapsed_sec", "phase", "event", "metric_received", "process_exit_code"] + fields
    with open(path, "w", newline="", encoding="utf-8") as output:
        writer = csv.DictWriter(output, fieldnames=names)
        writer.writeheader()
        writer.writerows(rows)


def _nice_step(max_value, target_ticks=6):
    if max_value <= 0:
        return 1.0
    raw = max_value / float(target_ticks)
    magnitude = 10 ** int(math.floor(math.log10(raw)))
    for factor in (1, 2, 5, 10):
        if factor * magnitude >= raw:
            return factor * magnitude
    return 10 * magnitude


def _write_svg(path, rows, config, mode, before, injection_end):
    width, height = 1160, 650
    left, right, top, bottom = 100, 45, 95, 100
    plot_width, plot_height = width - left - right, height - top - bottom
    times = [float(row["elapsed_sec"]) for row in rows]
    max_t = max(times + [before + 1.0])
    plot_end = left + plot_width
    x = lambda value: left + value / max_t * plot_width

    series = config["series"]
    all_values = []
    for row in rows:
        for item in series:
            try:
                all_values.append(float(row[item["field"]]))
            except (KeyError, ValueError):
                pass
    max_y = max(all_values + [1.0])
    y_step = _nice_step(max_y)
    y_max = max(y_step, math.ceil(max_y / y_step) * y_step)
    y = lambda value: top + plot_height - value / y_max * plot_height

    output = [
        '<svg xmlns="http://www.w3.org/2000/svg" width="{}" height="{}" viewBox="0 0 {} {}">'.format(width, height, width, height),
        '<rect width="100%" height="100%" fill="#ffffff"/>',
        '<text x="{}" y="38" text-anchor="middle" font-family="Arial,sans-serif" font-size="23" font-weight="bold">{}</text>'.format(width / 2, escape(config["title"] + " - " + mode + "（真实注入采样）")),
        '<rect x="{}" y="{}" width="{}" height="{}" fill="#eef2f7"/>'.format(left, top, max(0, x(before) - left), plot_height),
        '<rect x="{}" y="{}" width="{}" height="{}" fill="#fff3df"/>'.format(x(before), top, max(0, x(injection_end) - x(before)), plot_height),
        '<rect x="{}" y="{}" width="{}" height="{}" fill="#edf8ee"/>'.format(x(injection_end), top, max(0, plot_end - x(injection_end)), plot_height),
    ]

    tick = 0.0
    while tick <= y_max + y_step * 1e-9:
        yy = y(tick)
        output.append('<line x1="{}" y1="{:.1f}" x2="{}" y2="{:.1f}" stroke="#d9dee7"/>'.format(left, yy, plot_end, yy))
        output.append('<text x="{}" y="{:.1f}" text-anchor="end" font-family="Arial,sans-serif" font-size="13" fill="#333">{}</text>'.format(left - 10, yy + 5, int(tick) if tick.is_integer() else tick))
        tick += y_step
    output.extend([
        '<line x1="{}" y1="{}" x2="{}" y2="{}" stroke="#333"/>'.format(left, top + plot_height, plot_end, top + plot_height),
        '<line x1="{}" y1="{}" x2="{}" y2="{}" stroke="#333"/>'.format(left, top, left, top + plot_height),
        '<text x="{}" y="{}" text-anchor="middle" font-family="Arial,sans-serif" font-size="14">注入前</text>'.format((left + x(before)) / 2, top - 20),
        '<text x="{}" y="{}" text-anchor="middle" font-family="Arial,sans-serif" font-size="14">注入中</text>'.format((x(before) + x(injection_end)) / 2, top - 20),
        '<text x="{}" y="{}" text-anchor="middle" font-family="Arial,sans-serif" font-size="14">注入后</text>'.format((x(injection_end) + plot_end) / 2, top - 20),
    ])

    x_step = _nice_step(max_t, 8)
    tick = 0.0
    while tick <= max_t + x_step * 1e-9:
        xx = x(tick)
        output.append('<line x1="{:.1f}" y1="{}" x2="{:.1f}" y2="{}" stroke="#333"/>'.format(xx, top + plot_height, xx, top + plot_height + 6))
        output.append('<text x="{:.1f}" y="{}" text-anchor="middle" font-family="Arial,sans-serif" font-size="12" fill="#333">{}</text>'.format(xx, top + plot_height + 23, "{:g}".format(tick)))
        tick += x_step

    if config.get("metric_count", 0) == 0:
        output.append('<text x="{}" y="{}" text-anchor="middle" font-family="Arial,sans-serif" font-size="17" fill="#a22">没有收到注入进度数据；请用更新后的 DBChaos jar 重新打包运行</text>'.format(left + plot_width / 2, top + plot_height / 2))

    for item in series:
        points = []
        for row in rows:
            try:
                value = float(row[item["field"]])
            except (KeyError, ValueError):
                continue
            points.append("{:.1f},{:.1f}".format(x(float(row["elapsed_sec"])), y(value)))
        if points:
            dash = ' stroke-dasharray="8 6"' if item.get("dash") else ""
            output.append('<polyline points="{}" fill="none" stroke="{}" stroke-width="{}"{} stroke-linejoin="round" stroke-linecap="round"/>'.format(" ".join(points), item["color"], item.get("width", 3), dash))

    legend_x = left + 10
    for item in series:
        dash = ' stroke-dasharray="8 6"' if item.get("dash") else ""
        output.append('<line x1="{}" y1="{}" x2="{}" y2="{}" stroke="{}" stroke-width="3"{}/>'.format(legend_x, height - 54, legend_x + 28, height - 54, item["color"], dash))
        output.append('<text x="{}" y="{}" font-family="Arial,sans-serif" font-size="13">{}</text>'.format(legend_x + 36, height - 49, escape(item["label"])))
        legend_x += 260

    output.extend([
        '<text x="{}" y="{}" text-anchor="middle" font-family="Arial,sans-serif" font-size="15">时间（秒）</text>'.format(left + plot_width / 2, height - 15),
        '<text x="22" y="{}" transform="rotate(-90 22 {})" text-anchor="middle" font-family="Arial,sans-serif" font-size="15">{}</text>'.format(top + plot_height / 2, top + plot_height / 2, escape(config["y_label"])),
        '</svg>',
    ])
    with open(path, "w", encoding="utf-8") as output_file:
        output_file.write("\n".join(output))


def _snapshot_values(values, fields):
    row = {}
    for field in fields:
        row[field] = str(values.get(field, 0))
    return row


def run_one(args, config, mode, mode_args):
    directory = os.path.abspath(args.output_dir)
    os.makedirs(directory, exist_ok=True)
    base = os.path.join(directory, config["filename"] + "_" + mode)
    csv_path, svg_path = base + ".csv", base + ".svg"
    fields = config["fields"]
    values = {field: 0 for field in fields}
    rows = []
    output_queue = queue.Queue()
    reader = None
    process = None
    metric_count = 0
    exit_code = ""
    launch_elapsed = None
    start = time.monotonic()
    next_sample = 0.0
    injection_end = None
    stdout_eof = False
    timed_out = False
    command = [args.java, "-cp", args.classpath, "chaos.Main"]
    if args.db:
        command.extend(["--db", args.db])
    command.extend(["session", config["case_key"]] + mode_args)

    rows.append(_new_row(0.0, args.before, None, _snapshot_values(values, fields), "start", False))
    print("开始真实采样: {} | mode={} | {}".format(config["case_key"], mode, " ".join(command)), flush=True)

    while True:
        now_elapsed = time.monotonic() - start
        if process is None and now_elapsed >= args.before:
            launch_elapsed = now_elapsed
            try:
                process = subprocess.Popen(command, cwd=PROJECT_ROOT, stdout=subprocess.PIPE,
                                           stderr=subprocess.STDOUT, universal_newlines=True,
                                           bufsize=1)
            except OSError as error:
                print("启动 DBChaos 失败: {}".format(error), file=sys.stderr)
                injection_end = time.monotonic() - start
                break
            reader = threading.Thread(target=_read_output, args=(process.stdout, output_queue))
            reader.daemon = True
            reader.start()

        if process is not None:
            try:
                line = output_queue.get(timeout=min(0.2, args.sample_interval))
            except queue.Empty:
                line = "__NO_LINE__"
            if line is None:
                stdout_eof = True
            elif line != "__NO_LINE__":
                print(line, flush=True)
                metric = _metric(line, config["metric_prefix"])
                if metric is not None:
                    metric_count += 1
                    values = config["parse_metric"](metric, values)
                    event_elapsed = launch_elapsed + _parse_int(metric, "elapsed_ms") / 1000.0
                    event_name = metric.get("event", "progress")
                    rows.append(_new_row(event_elapsed, args.before, None,
                                         _snapshot_values(values, fields), event_name, True))

            now_elapsed = time.monotonic() - start
            if now_elapsed >= next_sample:
                rows.append(_new_row(now_elapsed, args.before, None,
                                     _snapshot_values(values, fields), "sample", metric_count > 0))
                next_sample += args.sample_interval

            if now_elapsed - launch_elapsed > args.process_timeout and process.poll() is None:
                timed_out = True
                process.terminate()
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()
            if process.poll() is not None and stdout_eof:
                exit_code = str(process.returncode)
                injection_end = time.monotonic() - start
                break
        else:
            if now_elapsed >= next_sample:
                rows.append(_new_row(now_elapsed, args.before, None,
                                     _snapshot_values(values, fields), "sample", False))
                next_sample += args.sample_interval
            time.sleep(min(0.1, max(0.01, args.sample_interval / 4)))

    if process is not None:
        if process.poll() is None:
            process.wait()
        exit_code = str(process.returncode)
        if reader is not None:
            reader.join(timeout=5)
        injection_end = injection_end if injection_end is not None else time.monotonic() - start
    elif injection_end is None:
        injection_end = time.monotonic() - start

    rows.append(_new_row(injection_end, args.before, injection_end,
                         _snapshot_values(values, fields), "injection_complete", metric_count > 0, exit_code))
    after_deadline = injection_end + args.after
    while time.monotonic() - start <= after_deadline:
        now_elapsed = time.monotonic() - start
        rows.append(_new_row(now_elapsed, args.before, injection_end,
                             _snapshot_values(values, fields), "sample", metric_count > 0, exit_code))
        time.sleep(min(args.sample_interval, max(0.0, after_deadline - now_elapsed)))

    rows.sort(key=lambda row: (float(row["elapsed_sec"]), 0 if row["event"] not in ("sample", "start") else 1))
    # Recompute phase after the observed process end is known.
    for row in rows:
        row["phase"] = _phase(float(row["elapsed_sec"]), args.before, injection_end)
        if not row["process_exit_code"]:
            row["process_exit_code"] = exit_code

    _write_csv(csv_path, rows, fields)
    config_for_svg = dict(config)
    config_for_svg["metric_count"] = metric_count
    _write_svg(svg_path, rows, config_for_svg, mode, args.before, injection_end)
    print("CSV: {}".format(csv_path))
    print("SVG: {}".format(svg_path))
    if timed_out:
        print("警告：{} 模式达到运行超时，进程已停止。".format(mode), file=sys.stderr)
    if metric_count == 0:
        print("错误：{} 模式未收到进度指标。请确认重新打包了包含 DBCHAOS 指标输出的 jar。".format(mode), file=sys.stderr)
    elif exit_code != "0":
        print("警告：{} 模式退出码为 {}；CSV/SVG 保留已采集数据。".format(mode, exit_code), file=sys.stderr)

    result_ok = config.get("result_ok", lambda result: True)(values)
    if not result_ok:
        print("错误：{} 模式的采集结果未达到该故障的预期；请检查终端输出和 CSV。".format(mode), file=sys.stderr)
    return metric_count > 0 and exit_code == "0" and not timed_out and result_ok


def run_visualization(config, modes, output_dir):
    parser = make_parser(config["description"], output_dir)
    args = parser.parse_args()
    if args.before < 0 or args.after < 0 or args.sample_interval <= 0 or args.process_timeout <= 0:
        parser.error("before/after 不能为负数，sample-interval/process-timeout 必须为正数")
    os.makedirs(args.output_dir, exist_ok=True)
    success = True
    for mode, mode_args in modes:
        if not run_one(args, config, mode, mode_args):
            success = False
    if not success:
        print("部分模式没有得到完整进度数据；请检查上面的错误和 CSV。", file=sys.stderr)
        return 1
    return 0

#!/usr/bin/env python3
"""Run both session_forced_termination modes and record their real progress."""
try:
    from .plot_case_visualization import run_visualization
except ImportError:
    from plot_case_visualization import run_visualization


def parse_session_metric(metric, previous):
    return {
        "established_sessions": str(int(metric.get("established", previous["established_sessions"]))),
        "terminated_sessions": str(int(metric.get("terminated", previous["terminated_sessions"]))),
        "active_sessions": str(int(metric.get("active", previous["active_sessions"]))),
        "failed_terminations": str(int(metric.get("failed_terminations", previous["failed_terminations"]))),
    }


def termination_run_succeeded(result):
    return int(result["established_sessions"]) > 0 and int(result["terminated_sessions"]) > 0


CONFIG = {
    "description": "依次真实运行会话强制终止的 serial/burst 模式，保存 CSV 和单图 SVG",
    "filename": "session_forced_termination",
    "case_key": "session_forced_termination",
    "metric_prefix": "DBCHAOS_SESSION_METRIC",
    "parse_metric": parse_session_metric,
    "result_ok": termination_run_succeeded,
    "title": "会话强制终止",
    "y_label": "目标会话数",
    "fields": ["established_sessions", "terminated_sessions", "active_sessions", "failed_terminations"],
    "series": [
        {"field": "active_sessions", "label": "当前活动目标会话", "color": "#c43d32", "width": 4},
        {"field": "terminated_sessions", "label": "成功终止累计数", "color": "#3976a8", "dash": True, "width": 2},
    ],
}

MODES = [
    ("serial", ["-mode", "serial", "-count", "50", "-interval", "250"]),
    ("burst", ["-mode", "burst", "-count", "50", "-concurrency", "10"]),
]


if __name__ == "__main__":
    raise SystemExit(run_visualization(CONFIG, MODES, "static/yxf_demos/SessionForcedTermination"))

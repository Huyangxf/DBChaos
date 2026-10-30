#!/usr/bin/env python3
"""Run both connection_auth_failure modes and record their real progress."""
try:
    from .plot_case_visualization import run_visualization
except ImportError:
    from plot_case_visualization import run_visualization


def parse_auth_metric(metric, previous):
    return {
        "completed_attempts": str(int(metric.get("attempts", previous["completed_attempts"]))),
        "auth_rejections": str(int(metric.get("auth_rejections", previous["auth_rejections"]))),
        "other_connection_errors": str(int(metric.get("other_failures", previous["other_connection_errors"]))),
        "unexpected_successes": str(int(metric.get("unexpected_successes", previous["unexpected_successes"]))),
    }


def auth_run_succeeded(result):
    return (int(result["completed_attempts"]) == 50
            and int(result["auth_rejections"]) == 50
            and int(result["other_connection_errors"]) == 0
            and int(result["unexpected_successes"]) == 0)


CONFIG = {
    "description": "依次真实运行连接认证失败的 serial/burst 模式，保存 CSV 和单图 SVG",
    "filename": "connection_auth_failure",
    "case_key": "connection_auth_failure",
    "metric_prefix": "DBCHAOS_AUTH_METRIC",
    "parse_metric": parse_auth_metric,
    "result_ok": auth_run_succeeded,
    "title": "连接认证失败",
    "y_label": "累计次数",
    "fields": ["completed_attempts", "auth_rejections", "other_connection_errors", "unexpected_successes"],
    "series": [
        {"field": "auth_rejections", "label": "认证拒绝累计数", "color": "#c43d32", "width": 4},
        {"field": "completed_attempts", "label": "完成尝试累计数", "color": "#3976a8", "dash": True, "width": 2},
    ],
}

MODES = [
    ("serial", ["-mode", "serial", "-count", "50", "-interval", "500", "-login-timeout", "10"]),
    ("burst", ["-mode", "burst", "-count", "50", "-concurrency", "10", "-login-timeout", "10"]),
]


if __name__ == "__main__":
    raise SystemExit(run_visualization(CONFIG, MODES, "static/yxf_demos/ConnectionAuthFailure"))

"""
Coordinate space calibration for VLM output.

Sends two probe images (with colored corner blocks) to the VLM and infers
how the model maps image content to output coordinates.  The result is a
probe dict consumed by `map_point_by_probe` to convert raw VLM coordinates
to physical screen pixels via an affine transform derived from the probe.

Two modes are detected:
  fixed_native_range  - model always outputs in a model-native space
                        (e.g., 0-1000), independent of image resolution.
  image_pixel         - model outputs coordinates proportional to the
                        actual image pixel dimensions.
"""

from __future__ import annotations

import io
import json
import re
from typing import Any, Callable

from PIL import Image, ImageDraw

# Two probe images with deliberately different aspect ratios so that
# pixel-mode vs fixed-range mode can be distinguished by comparing spans.
_PROBE_SIZES: list[tuple[int, int]] = [(1280, 720), (720, 1280)]

_PROBE_PROMPT = (
    "Coordinate Calibration Task.\n"
    "You are given a synthetic image with a black background and four colored corner blocks:\n"
    "- top-left: RED\n"
    "- top-right: GREEN\n"
    "- bottom-left: BLUE\n"
    "- bottom-right: YELLOW\n"
    "Return ONLY JSON with this exact schema:\n"
    '{"tl":[x,y],"tr":[x,y],"bl":[x,y],"br":[x,y]}\n'
    "Rules:\n"
    "1) Output numbers only — no markdown, no explanation.\n"
    "2) Use your native coordinate space; do NOT convert on purpose.\n"
    "3) For each corner return the colored point nearest to that image corner.\n"
    "4) Be precise; this output is used for coordinate range calibration.\n"
)


# ---------------------------------------------------------------------------
# Image builder
# ---------------------------------------------------------------------------

def _build_probe_image(width: int, height: int) -> bytes:
    img = Image.new("RGB", (width, height), (0, 0, 0))
    draw = ImageDraw.Draw(img)
    dot = max(18, min(width, height) // 24)
    draw.rectangle([0, 0, dot, dot], fill=(255, 0, 0))                              # TL red
    draw.rectangle([width - dot - 1, 0, width - 1, dot], fill=(0, 255, 0))          # TR green
    draw.rectangle([0, height - dot - 1, dot, height - 1], fill=(0, 100, 255))      # BL blue
    draw.rectangle([width - dot - 1, height - dot - 1, width - 1, height - 1],
                   fill=(255, 220, 0))                                               # BR yellow
    buf = io.BytesIO()
    img.save(buf, format="PNG")
    return buf.getvalue()


# ---------------------------------------------------------------------------
# Response parsing
# ---------------------------------------------------------------------------

def _parse_response(raw: str) -> dict[str, tuple[float, float]]:
    text = (raw or "").strip()
    obj: dict | None = None
    try:
        tmp = json.loads(text)
        if isinstance(tmp, dict):
            obj = tmp
    except Exception:
        m = re.search(r"\{[\s\S]*\}", text)
        if m:
            try:
                tmp = json.loads(m.group(0))
                if isinstance(tmp, dict):
                    obj = tmp
            except Exception:
                pass

    if not isinstance(obj, dict):
        return {}

    out: dict[str, tuple[float, float]] = {}
    for k in ("tl", "tr", "bl", "br"):
        v = obj.get(k)
        if not isinstance(v, (list, tuple)) or len(v) < 2:
            return {}
        try:
            out[k] = (float(v[0]), float(v[1]))
        except Exception:
            return {}
    return out


def _canonicalize(points: dict[str, tuple[float, float]]) -> dict[str, tuple[float, float]]:
    """Re-assign tl/tr/bl/br by geometry to handle model mislabeling."""
    vals = list(points.values())
    if len(vals) != 4:
        return {}
    by_y = sorted(vals, key=lambda p: (p[1], p[0]))
    top = sorted(by_y[:2], key=lambda p: p[0])
    bot = sorted(by_y[2:], key=lambda p: p[0])
    return {"tl": top[0], "tr": top[1], "bl": bot[0], "br": bot[1]}


# ---------------------------------------------------------------------------
# Single probe
# ---------------------------------------------------------------------------

def _probe_one(
    call_fn: Callable[[str, bytes], str],
    width: int,
    height: int,
) -> dict[str, Any]:
    image_bytes = _build_probe_image(width, height)
    raw = call_fn(_PROBE_PROMPT, image_bytes)
    points_raw = _parse_response(raw)
    if not points_raw:
        return {}
    points = _canonicalize(points_raw)
    if not points:
        return {}

    x_min = (points["tl"][0] + points["bl"][0]) / 2.0
    x_max = (points["tr"][0] + points["br"][0]) / 2.0
    y_min = (points["tl"][1] + points["tr"][1]) / 2.0
    y_max = (points["bl"][1] + points["br"][1]) / 2.0
    span_x = max(1e-6, x_max - x_min)
    span_y = max(1e-6, y_max - y_min)
    if span_x < 80.0 or span_y < 80.0:
        return {}  # degenerate

    return {
        "width": width, "height": height,
        "points": points,
        "x_min": x_min, "x_max": x_max,
        "y_min": y_min, "y_max": y_max,
        "span_x": span_x, "span_y": span_y,
        "max_x": max(v[0] for v in points.values()),
        "max_y": max(v[1] for v in points.values()),
    }


# ---------------------------------------------------------------------------
# Mode detection
# ---------------------------------------------------------------------------

def _detect_mode(s1: dict[str, Any], s2: dict[str, Any]) -> str:
    w_ratio = s1["width"] / s2["width"]
    h_ratio = s1["height"] / s2["height"]
    sx_ratio = s1["span_x"] / s2["span_x"]
    sy_ratio = s1["span_y"] / s2["span_y"]
    x_err = abs(sx_ratio / w_ratio - 1.0)
    y_err = abs(sy_ratio / h_ratio - 1.0)
    return "image_pixel" if x_err <= 0.25 and y_err <= 0.25 else "fixed_native_range"


# ---------------------------------------------------------------------------
# Public API
# ---------------------------------------------------------------------------

def calibrate(
    call_fn: Callable[[str, bytes], str],
    attempts: int = 3,
) -> dict[str, Any]:
    """
    Run coordinate calibration using two probe images.

    Args:
        call_fn: Function (prompt: str, image_bytes: bytes) -> str  — a single
                 VLM call that returns the raw text response.
        attempts: Number of retry attempts per probe image.

    Returns:
        Probe dict with keys: mode, x_min, x_max, y_min, y_max, span_x,
        span_y, max_x, max_y.  Empty dict on failure.
    """
    samples: list[dict[str, Any]] = []
    for w, h in _PROBE_SIZES:
        best: dict[str, Any] = {}
        best_score = -1.0
        for _ in range(attempts):
            s = _probe_one(call_fn, w, h)
            if not s:
                continue
            score = s["span_x"] * s["span_y"]
            if score > best_score:
                best = s
                best_score = score
        if best:
            samples.append(best)

    if len(samples) < 2:
        return {}

    s1, s2 = samples[0], samples[1]
    mode = _detect_mode(s1, s2)

    x_min = (s1["x_min"] + s2["x_min"]) / 2.0
    x_max = (s1["x_max"] + s2["x_max"]) / 2.0
    y_min = (s1["y_min"] + s2["y_min"]) / 2.0
    y_max = (s1["y_max"] + s2["y_max"]) / 2.0
    return {
        "mode": mode,
        "x_min": round(x_min, 4), "x_max": round(x_max, 4),
        "y_min": round(y_min, 4), "y_max": round(y_max, 4),
        "span_x": round(max(1e-6, x_max - x_min), 4),
        "span_y": round(max(1e-6, y_max - y_min), 4),
        "max_x": round(max(s1["max_x"], s2["max_x"]), 4),
        "max_y": round(max(s1["max_y"], s2["max_y"]), 4),
    }


def map_point(
    probe: dict[str, Any],
    raw_x: float,
    raw_y: float,
    screen_w: int,
    screen_h: int,
) -> tuple[int, int]:
    """
    Convert a raw VLM coordinate to a physical screen pixel using probe data.

    If probe is empty (calibration failed), returns the raw value as-is.
    """
    if not probe or screen_w <= 1 or screen_h <= 1:
        return int(round(raw_x)), int(round(raw_y))

    if probe.get("mode") == "image_pixel":
        rx = max(0, min(screen_w - 1, int(round(raw_x))))
        ry = max(0, min(screen_h - 1, int(round(raw_y))))
        return rx, ry

    x_min = float(probe.get("x_min") or 0.0)
    x_max = float(probe.get("x_max") or 0.0)
    y_min = float(probe.get("y_min") or 0.0)
    y_max = float(probe.get("y_max") or 0.0)

    if x_max > x_min and y_max > y_min:
        nx = (raw_x - x_min) / (x_max - x_min)
        ny = (raw_y - y_min) / (y_max - y_min)
    else:
        max_x = float(probe.get("max_x") or 1.0)
        max_y = float(probe.get("max_y") or 1.0)
        nx = raw_x / max(1e-6, max_x)
        ny = raw_y / max(1e-6, max_y)

    rx = max(0, min(screen_w - 1, int(round(nx * (screen_w - 1)))))
    ry = max(0, min(screen_h - 1, int(round(ny * (screen_h - 1)))))
    return rx, ry

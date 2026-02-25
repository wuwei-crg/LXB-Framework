"""
LXB Web Console - Flask Backend
用于可视化调试 LXB-Link 协议的 Web 控制台
"""

from flask import Flask, render_template, request, jsonify, Response
from flask_cors import CORS
import sys
import os
import base64
import json
import io
import threading
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Dict, Optional

# 尝试加载 python-dotenv (如果存在)
try:
    from dotenv import load_dotenv
    # 加载 .env 文件
    env_path = os.path.join(os.path.dirname(__file__), '..', '.env')
    load_dotenv(env_path)
except ImportError:
    pass

# 设置 HF_TOKEN 环境变量 (用于 Hugging Face 模型下载)
if os.getenv('HF_TOKEN'):
    os.environ['HF_TOKEN'] = os.getenv('HF_TOKEN')
    print("[app.py] HF_TOKEN 已设置")

# 添加项目根目录到 Python 路径
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), '..')))

from src.lxb_link.client import LXBLinkClient
from src.lxb_link.constants import (
    KEY_HOME,
    KEY_BACK,
    KEY_ENTER,
    KEY_MENU,
    KEY_RECENT,
)

app = Flask(__name__)
CORS(app)  # 允许跨域请求

# 全局客户端实例
client = None
connection_info = {
    'connected': False,
    'host': None,
    'port': None,
    'connection_id': None,
    'running_tasks': 0,
    'total_connections': 0,
}

TASKS = {}
TASKS_LOCK = threading.Lock()
LOG_ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.dirname(__file__)), 'logs', 'tasks'))

CONNECTIONS: Dict[str, "ConnectionRecord"] = {}
CONNECTIONS_LOCK = threading.RLock()
CURRENT_CONNECTION_ID: Optional[str] = None

CORTEX_LLM_CONFIG_FILE = os.path.abspath(
    os.path.join(os.path.dirname(os.path.dirname(__file__)), '.cortex_llm_planner.json')
)


@dataclass
class ConnectionRecord:
    connection_id: str
    host: str
    port: int
    source: str
    client: LXBLinkClient
    created_at: str = field(default_factory=lambda: datetime.now(timezone.utc).isoformat())
    last_seen: str = field(default_factory=lambda: datetime.now(timezone.utc).isoformat())
    status: str = "connected"
    running_tasks: int = 0
    lock: threading.RLock = field(default_factory=threading.RLock)


def _now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def _connection_public(record: ConnectionRecord) -> dict:
    return {
        'connection_id': record.connection_id,
        'host': record.host,
        'port': record.port,
        'source': record.source,
        'status': record.status,
        'created_at': record.created_at,
        'last_seen': record.last_seen,
        'running_tasks': int(record.running_tasks),
    }


def _sync_connection_info() -> None:
    global client, connection_info
    with CONNECTIONS_LOCK:
        current = CONNECTIONS.get(CURRENT_CONNECTION_ID) if CURRENT_CONNECTION_ID else None
        total_running = sum(int(c.running_tasks) for c in CONNECTIONS.values())
        connection_info = {
            'connected': bool(current and current.status == 'connected'),
            'host': current.host if current else None,
            'port': current.port if current else None,
            'connection_id': current.connection_id if current else None,
            'running_tasks': int(total_running),
            'total_connections': len(CONNECTIONS),
        }
        client = current.client if current else None


def _create_connection(host: str, port: int, source: str = 'manual', set_current: bool = True) -> ConnectionRecord:
    global CURRENT_CONNECTION_ID
    c = LXBLinkClient(host, port, timeout=2.0)
    c.connect()
    c.handshake()
    record = ConnectionRecord(
        connection_id=str(uuid.uuid4()),
        host=str(host),
        port=int(port),
        source=source,
        client=c,
    )
    with CONNECTIONS_LOCK:
        CONNECTIONS[record.connection_id] = record
        if set_current:
            CURRENT_CONNECTION_ID = record.connection_id
    _sync_connection_info()
    return record


def _find_connection_by_host_port(host: str, port: int) -> Optional[ConnectionRecord]:
    with CONNECTIONS_LOCK:
        for rec in CONNECTIONS.values():
            if rec.host == str(host) and int(rec.port) == int(port) and rec.status == 'connected':
                return rec
    return None


def _select_connection(connection_id: str) -> ConnectionRecord:
    global CURRENT_CONNECTION_ID
    with CONNECTIONS_LOCK:
        rec = CONNECTIONS.get(connection_id)
        if not rec:
            raise RuntimeError('connection_not_found')
        if rec.status != 'connected':
            raise RuntimeError('connection_not_connected')
        CURRENT_CONNECTION_ID = connection_id
        rec.last_seen = _now_iso()
    _sync_connection_info()
    return rec


def _get_connection(connection_id: Optional[str] = None, require: bool = True) -> Optional[ConnectionRecord]:
    with CONNECTIONS_LOCK:
        cid = connection_id or CURRENT_CONNECTION_ID
        rec = CONNECTIONS.get(cid) if cid else None
        if rec and rec.status == 'connected':
            rec.last_seen = _now_iso()
            return rec
    if require:
        raise RuntimeError('device not connected')
    return None


def _require_client_response():
    """Return a Flask error response when no active client is available."""
    _sync_connection_info()
    if client:
        return None
    return jsonify({'success': False, 'message': 'device not connected'}), 400


def _disconnect_connection(connection_id: Optional[str] = None) -> None:
    global CURRENT_CONNECTION_ID
    with CONNECTIONS_LOCK:
        cid = connection_id or CURRENT_CONNECTION_ID
        rec = CONNECTIONS.get(cid) if cid else None
        if not rec:
            return
        try:
            rec.client.disconnect()
        except Exception:
            pass
        rec.status = 'disconnected'
        rec.last_seen = _now_iso()
        if cid in CONNECTIONS:
            del CONNECTIONS[cid]
        if CURRENT_CONNECTION_ID == cid:
            CURRENT_CONNECTION_ID = next(iter(CONNECTIONS.keys()), None)
    _sync_connection_info()


def _default_cortex_llm_config() -> dict:
    return {
        'api_base_url': os.getenv('CORTEX_LLM_API_BASE_URL', ''),
        'api_key': os.getenv('CORTEX_LLM_API_KEY', ''),
        'model_name': os.getenv('CORTEX_LLM_MODEL_NAME', 'qwen-plus'),
        'temperature': float(os.getenv('CORTEX_LLM_TEMPERATURE', '0.1')),
        'timeout': int(os.getenv('CORTEX_LLM_TIMEOUT', '30')),
        'vision_jpeg_quality': int(os.getenv('CORTEX_VISION_JPEG_QUALITY', '35')),
        'node_exists_retries': int(os.getenv('CORTEX_NODE_EXISTS_RETRIES', '3')),
        'node_exists_interval_sec': float(os.getenv('CORTEX_NODE_EXISTS_INTERVAL_SEC', '0.6')),
        'touch_mode': os.getenv('CORTEX_TOUCH_MODE', 'shell_first'),
        # Route/FSM runtime defaults (persisted in same config file).
        'map_filepath': '',
        'package_name': '',
        'reconnect_before_run': True,
        'use_llm_planner': True,
        'route_recovery_enabled': False,
        'max_route_restarts': 0,
        'use_vlm_takeover': False,
        'fsm_max_turns': 40,
        'fsm_max_commands_per_turn': 1,
        'fsm_max_vision_turns': 20,
        'fsm_action_interval_sec': 0.8,
        'fsm_screenshot_settle_sec': 0.6,
        'fsm_tap_bind_clickable': False,
        'fsm_tap_jitter_sigma_px': 2.0,
        'fsm_swipe_jitter_sigma_px': 4.0,
        'fsm_swipe_duration_jitter_ratio': 0.12,
        'fsm_xml_stable_interval_sec': 0.3,
        'fsm_xml_stable_samples': 4,
        'fsm_xml_stable_timeout_sec': 4.0,
    }


def _load_cortex_llm_config() -> dict:
    cfg = _default_cortex_llm_config()
    if not os.path.exists(CORTEX_LLM_CONFIG_FILE):
        return cfg
    try:
        with open(CORTEX_LLM_CONFIG_FILE, 'r', encoding='utf-8') as f:
            stored = json.load(f)
        if isinstance(stored, dict):
            cfg.update(stored)
    except Exception:
        pass
    return cfg


def _save_cortex_llm_config(config: dict) -> None:
    current = _default_cortex_llm_config()
    current.update(config or {})
    os.makedirs(os.path.dirname(CORTEX_LLM_CONFIG_FILE), exist_ok=True)
    with open(CORTEX_LLM_CONFIG_FILE, 'w', encoding='utf-8') as f:
        json.dump(current, f, ensure_ascii=False, indent=2)


def _task_create(task_type: str, connection_id: str = '', user_task: str = '') -> str:
    task_id = str(uuid.uuid4())
    with TASKS_LOCK:
        TASKS[task_id] = {
            'task_id': task_id,
            'type': task_type,
            'created_at': datetime.now(timezone.utc).isoformat(),
            'started_at': None,
            'ended_at': None,
            'status': 'created',
            'connection_id': connection_id,
            'user_task': user_task,
            'events': [],
            'done': False,
            'success': False,
            'result': None,
            'message': '',
            'cancel_requested': False,
            'log_file': '',
            'summary_file': '',
        }
    return task_id


def _task_append(task_id: str, event: dict) -> None:
    with TASKS_LOCK:
        t = TASKS.get(task_id)
        if t:
            if not t.get('started_at'):
                t['started_at'] = _now_iso()
                t['status'] = 'running'
            t['events'].append(event)


def _task_finish(task_id: str, success: bool, result: dict = None, message: str = '') -> None:
    snapshot = None
    with TASKS_LOCK:
        t = TASKS.get(task_id)
        if not t:
            return
        t['done'] = True
        t['success'] = bool(success)
        t['result'] = result or {}
        t['message'] = message or ''
        t['ended_at'] = _now_iso()
        t['status'] = 'success' if success else ('cancelled' if message == 'task_cancelled' else 'failed')
        snapshot = dict(t)
    if snapshot:
        _task_persist(snapshot)


def _task_persist(task_snapshot: dict) -> None:
    try:
        created = task_snapshot.get('created_at') or _now_iso()
        day = created.split('T', 1)[0] or 'unknown'
        day_dir = os.path.join(LOG_ROOT, day)
        os.makedirs(day_dir, exist_ok=True)
        task_id = task_snapshot.get('task_id') or str(uuid.uuid4())
        log_file = os.path.join(day_dir, f'{task_id}.jsonl')
        summary_file = os.path.join(day_dir, f'{task_id}.summary.json')
        with open(log_file, 'w', encoding='utf-8') as f:
            for e in task_snapshot.get('events') or []:
                f.write(json.dumps(e, ensure_ascii=False) + '\n')
        summary_payload = {
            'task_id': task_id,
            'type': task_snapshot.get('type'),
            'connection_id': task_snapshot.get('connection_id'),
            'status': task_snapshot.get('status'),
            'success': bool(task_snapshot.get('success')),
            'message': task_snapshot.get('message') or '',
            'user_task': task_snapshot.get('user_task') or '',
            'created_at': task_snapshot.get('created_at'),
            'started_at': task_snapshot.get('started_at'),
            'ended_at': task_snapshot.get('ended_at'),
            'event_count': len(task_snapshot.get('events') or []),
            'result': task_snapshot.get('result') or {},
            'log_file': log_file,
        }
        with open(summary_file, 'w', encoding='utf-8') as f:
            json.dump(summary_payload, f, ensure_ascii=False, indent=2)
        with TASKS_LOCK:
            t = TASKS.get(task_id)
            if t:
                t['log_file'] = log_file
                t['summary_file'] = summary_file
    except Exception:
        pass


def _task_is_cancel_requested(task_id: str) -> bool:
    with TASKS_LOCK:
        t = TASKS.get(task_id)
        if not t:
            return False
        return bool(t.get('cancel_requested'))


def _ensure_connected(host: str, port: int) -> None:
    """Ensure at least one selected active connection."""
    rec = _find_connection_by_host_port(host, int(port))
    if rec:
        _select_connection(rec.connection_id)
        return
    _create_connection(host, int(port), source='mobile_auto', set_current=False)


def _prepare_link_for_task(run_client, reconnect: bool = True) -> None:
    """
    Recover link state after abrupt task interruption.
    """
    if not run_client:
        raise RuntimeError('device not connected')
    if reconnect:
        run_client.reconnect(handshake=True)
    else:
        try:
            run_client.reset_runtime_state(reset_seq=False)
        except Exception:
            pass


def _resolve_run_connection(data: dict, allow_mobile_auto: bool = False) -> ConnectionRecord:
    connection_id = str(data.get('connection_id') or '').strip()
    if connection_id:
        return _get_connection(connection_id=connection_id, require=True)

    if allow_mobile_auto:
        lxb_port = data.get('lxb_port')
        if lxb_port:
            host = request.remote_addr or ''
            rec = _find_connection_by_host_port(host, int(lxb_port))
            if rec:
                return rec
            return _create_connection(host, int(lxb_port), source='mobile_auto', set_current=False)

    return _get_connection(connection_id=None, require=True)


def _build_llm_complete(config: dict):
    from openai import OpenAI

    api_base_url = (config.get('api_base_url') or '').strip()
    api_key = (config.get('api_key') or '').strip()
    model_name = (config.get('model_name') or '').strip()
    if not api_base_url or not api_key or not model_name:
        raise ValueError('LLM 配置不完整：api_base_url / api_key / model_name 必填')

    client = OpenAI(
        base_url=api_base_url,
        api_key=api_key,
        timeout=float(config.get('timeout', 30)),
    )
    temperature = float(config.get('temperature', 0.1))

    def complete(prompt: str) -> str:
        response = client.chat.completions.create(
            model=model_name,
            temperature=temperature,
            messages=[
                {
                    "role": "system",
                    "content": (
                        "You are a route planner. Output strict JSON only with keys: "
                        "package_name, target_page."
                    ),
                },
                {"role": "user", "content": prompt},
            ],
        )
        return (response.choices[0].message.content or '').strip()

    return complete


def _build_llm_complete_fsm(config: dict):
    from openai import OpenAI

    api_base_url = (config.get('api_base_url') or '').strip()
    api_key = (config.get('api_key') or '').strip()
    model_name = (config.get('model_name') or '').strip()
    if not api_base_url or not api_key or not model_name:
        raise ValueError('LLM 配置不完整：api_base_url / api_key / model_name 必填')

    client = OpenAI(
        base_url=api_base_url,
        api_key=api_key,
        timeout=float(config.get('timeout', 30)),
    )
    temperature = float(config.get('temperature', 0.1))

    def _detect_state(prompt: str) -> str:
        text = (prompt or '')
        for line in text.splitlines():
            line = line.strip()
            if line.startswith('State='):
                return line.split('=', 1)[1].strip().upper()
        return ""

    def _state_system_prompt(state: str) -> str:
        base = (
            "You are a finite-state mobile planner.\n"
            "Follow the state-specific output format strictly.\n"
            "Output must contain exactly one <command>...</command>.\n"
            "Do not output markdown.\n"
        )
        if state == "APP_RESOLVE":
            return base + (
                "Current state: APP_RESOLVE.\n"
                "Analyze app candidates first, then output one command.\n"
                "Use <app_analysis>...</app_analysis> plus <command>...\n"
                "Inside <app_analysis>, include a short <reflection> lesson.\n"
            )
        if state == "ROUTE_PLAN":
            return base + (
                "Current state: ROUTE_PLAN.\n"
                "Analyze target page candidates first, then output one command.\n"
                "Use <route_plan_analysis>...</route_plan_analysis> plus <command>...\n"
                "Inside <route_plan_analysis>, include a short <reflection> lesson.\n"
            )
        if state == "VISION_ACT":
            return base + (
                "Current state: VISION_ACT.\n"
                "Analyze current page first, then reason next step, then output one command.\n"
                "Use <vision_analysis>...</vision_analysis> plus <command>...\n"
                "Inside <vision_analysis>, include <step_review> for recent multi-step outcomes, and <reflection> as cumulative lesson from recent 3~5 steps with action intent to avoid next.\n"
                "One turn = one command.\n"
            )
        return base + "Current state unknown. Follow user prompt format exactly.\n"

    def complete(prompt: str) -> str:
        state = _detect_state(prompt)
        response = client.chat.completions.create(
            model=model_name,
            temperature=temperature,
            messages=[
                {
                    "role": "system",
                    "content": _state_system_prompt(state),
                },
                {"role": "user", "content": prompt},
            ],
        )
        return (response.choices[0].message.content or '').strip()

    return complete


def _build_llm_complete_with_image(config: dict):
    from openai import OpenAI

    api_base_url = (config.get('api_base_url') or '').strip()
    api_key = (config.get('api_key') or '').strip()
    model_name = (config.get('model_name') or '').strip()
    if not api_base_url or not api_key or not model_name:
        raise ValueError('LLM 配置不完整：api_base_url / api_key / model_name 必填')

    client = OpenAI(
        base_url=api_base_url,
        api_key=api_key,
        timeout=float(config.get('timeout', 30)),
    )
    temperature = float(config.get('temperature', 0.1))
    jpeg_quality = int(config.get('vision_jpeg_quality', 35))

    def _detect_state(prompt: str) -> str:
        text = (prompt or '')
        for line in text.splitlines():
            line = line.strip()
            if line.startswith('State='):
                return line.split('=', 1)[1].strip().upper()
        return ""

    def _state_system_prompt(state: str) -> str:
        base = (
            "You are a mobile VLM planner.\n"
            "Follow the state-specific output format strictly.\n"
            "Output must contain exactly one <command>...</command>.\n"
            "Do not output markdown.\n"
        )
        if state == "VISION_ACT":
            return base + (
                "Current state: VISION_ACT.\n"
                "First describe page_state, then next_step_reasoning, then one command.\n"
                "Use <vision_analysis>...</vision_analysis> plus <command>...\n"
                "Inside <vision_analysis>, include <step_review> for recent multi-step outcomes, and <reflection> as cumulative lesson from recent 3~5 steps with action intent to avoid next.\n"
                "One turn = one command.\n"
            )
        return base + "Follow user prompt format exactly.\n"

    def _reencode_jpeg(image_bytes: bytes, quality: int) -> bytes:
        quality = max(10, min(95, int(quality)))
        try:
            from PIL import Image
            with Image.open(io.BytesIO(image_bytes)) as img:
                if img.mode not in ('RGB', 'L'):
                    img = img.convert('RGB')
                out = io.BytesIO()
                img.save(out, format='JPEG', quality=quality, optimize=True)
                return out.getvalue()
        except Exception:
            return image_bytes

    def complete_with_image(prompt: str, image_bytes: bytes) -> str:
        state = _detect_state(prompt)
        compressed = _reencode_jpeg(image_bytes, jpeg_quality)
        image_b64 = base64.b64encode(compressed).decode('ascii')
        response = client.chat.completions.create(
            model=model_name,
            temperature=temperature,
            messages=[
                {
                    "role": "system",
                    "content": _state_system_prompt(state),
                },
                {
                    "role": "user",
                    "content": [
                        {"type": "text", "text": prompt},
                        {"type": "image_url", "image_url": {"url": f"data:image/jpeg;base64,{image_b64}"}},
                    ],
                },
            ],
        )
        return (response.choices[0].message.content or '').strip()

    return complete_with_image


def _build_llm_task_summary(config: dict):
    from openai import OpenAI

    api_base_url = (config.get('api_base_url') or '').strip()
    api_key = (config.get('api_key') or '').strip()
    model_name = (config.get('model_name') or '').strip()
    if not api_base_url or not api_key or not model_name:
        raise ValueError('LLM config missing: api_base_url / api_key / model_name')

    client = OpenAI(
        base_url=api_base_url,
        api_key=api_key,
        timeout=float(config.get('timeout', 30)),
    )

    def summarize(user_task: str, run_result: dict) -> str:
        llm_hist = (run_result.get('llm_history') or [])[-12:]
        route_trace = (run_result.get('route_trace') or [])[-12:]
        status = str(run_result.get('status') or '')
        state = str(run_result.get('state') or '')
        reason = str(run_result.get('reason') or '')
        prompt = "\n".join([
            "请根据任务执行信息，输出一段简洁任务总结。",
            "要求：",
            "1) 只输出总结正文，不要加标题。",
            "2) 如果任务成功，优先回答用户真正关心的结果内容。",
            "3) 如果任务失败，说明失败阶段和可能原因。",
            "4) 中文输出，2~6句。",
            f"用户意图: {user_task}",
            f"任务状态: {status}",
            f"结束状态机: {state}",
            f"失败原因: {reason}",
            f"路由轨迹(JSON): {json.dumps(route_trace, ensure_ascii=False)}",
            f"执行历史(JSON): {json.dumps(llm_hist, ensure_ascii=False)}",
        ])
        response = client.chat.completions.create(
            model=model_name,
            temperature=0.2,
            messages=[
                {
                    "role": "system",
                    "content": "你是移动自动化任务总结助手，只根据给定信息生成事实性总结，不要臆测。",
                },
                {"role": "user", "content": prompt},
            ],
        )
        return (response.choices[0].message.content or '').strip()

    return summarize


def _fallback_task_summary(user_task: str, run_result: dict) -> str:
    status = str(run_result.get('status') or '')
    state = str(run_result.get('state') or '')
    reason = str(run_result.get('reason') or '')
    llm_hist = run_result.get('llm_history') or []

    if status == 'success':
        observations = []
        for item in reversed(llm_hist):
            s = item.get('structured') or {}
            ps = str(s.get('page_state') or '').strip()
            if ps:
                observations.append(ps)
            if len(observations) >= 2:
                break
        if observations:
            return f"任务已完成。针对“{user_task}”，最后观察到：{'；'.join(reversed(observations))}。"
        return f"任务已完成。已按意图“{user_task}”执行结束。"
    return f"任务未完成。结束于状态 {state}，原因：{reason or 'unknown'}。"


def _extract_json_object(text: str) -> dict:
    text = (text or '').strip()
    if not text:
        return {}
    try:
        obj = json.loads(text)
        return obj if isinstance(obj, dict) else {}
    except Exception:
        pass
    start = text.find('{')
    end = text.rfind('}')
    if start == -1 or end == -1 or end <= start:
        return {}
    try:
        obj = json.loads(text[start:end + 1])
        return obj if isinstance(obj, dict) else {}
    except Exception:
        return {}


def _infer_app_name_from_package(package_name: str) -> str:
    if not package_name:
        return ''
    parts = [x for x in package_name.split('.') if x]
    if not parts:
        return package_name
    tail = parts[-1]
    return tail.replace('_', ' ')


def _normalize_installed_apps(raw_apps) -> list:
    out = []
    for item in raw_apps or []:
        if isinstance(item, dict):
            package_name = str(item.get('package') or '').strip()
            if not package_name:
                continue
            name = str(item.get('name') or item.get('label') or '').strip()
            out.append({
                'package': package_name,
                'name': name or _infer_app_name_from_package(package_name),
            })
            continue

        package_name = str(item or '').strip()
        if not package_name:
            continue
        out.append({
            'package': package_name,
            'name': _infer_app_name_from_package(package_name),
        })
    return out


def _has_map_for_package(base_dir: str, package_name: str) -> bool:
    pkg_path = os.path.join(base_dir, package_name.replace('.', '_'))
    if not os.path.isdir(pkg_path):
        return False
    import glob
    return len(glob.glob(os.path.join(pkg_path, 'nav_map_*.json'))) > 0


def _select_package_by_llm(
    llm_complete,
    user_task: str,
    app_candidates: list,
) -> dict:
    rows = []
    for app in app_candidates[:120]:
        rows.append({
            'package': app.get('package', ''),
            'name': app.get('name', ''),
        })

    prompt = (
        "You are selecting an Android app package for user intent routing.\n"
        "Output JSON only:\n"
        '{"package_name":"...","reason":"..."}\n'
        "Rules:\n"
        "1) package_name must be chosen from candidate package list.\n"
        "2) prefer semantic app name match first, then package token match.\n\n"
        f"user_task:\n{user_task}\n\n"
        f"candidates:\n{json.dumps(rows, ensure_ascii=False)}"
    )
    raw = llm_complete(prompt)
    payload = _extract_json_object(raw)
    package_name = str(payload.get('package_name') or '').strip()
    reason = str(payload.get('reason') or '').strip()
    return {'package_name': package_name, 'reason': reason, 'raw': raw}


def _select_target_page_by_llm(
    llm_complete,
    user_task: str,
    map_path: str,
) -> dict:
    with open(map_path, 'r', encoding='utf-8') as f:
        raw_map = json.load(f)

    pages = raw_map.get('pages') or {}
    transitions = raw_map.get('transitions') or []

    page_rows = []
    for page_id, page in pages.items():
        page_rows.append({
            'page_id': page_id,
            'legacy_page_id': page.get('legacy_page_id', ''),
            'name': page.get('name', ''),
            'description': page.get('description', ''),
            'features': (page.get('features') or [])[:12],
            'aliases': (page.get('target_aliases') or [])[:8],
        })

    edge_rows = []
    for t in transitions:
        edge_rows.append({
            'from': t.get('from', ''),
            'to': t.get('to', ''),
            'trigger': t.get('description', ''),
        })

    prompt = (
        "You are selecting target_page for mobile app routing.\n"
        "Output JSON only:\n"
        '{"target_page":"...","reason":"..."}\n'
        "Rules:\n"
        "1) target_page must be one page_id or legacy_page_id from pages.\n"
        "2) You MUST use semantic fields: name, description, features, aliases.\n"
        "3) Prefer the page whose semantics most directly satisfy the user task.\n\n"
        f"user_task:\n{user_task}\n\n"
        f"map:\n{json.dumps({'package': raw_map.get('package', ''), 'pages': page_rows, 'transitions': edge_rows}, ensure_ascii=False)}"
    )
    raw = llm_complete(prompt)
    payload = _extract_json_object(raw)
    target_page = str(payload.get('target_page') or '').strip()
    reason = str(payload.get('reason') or '').strip()
    return {'target_page': target_page, 'reason': reason, 'raw': raw}


def _build_page_candidates_from_map(map_path: str) -> list:
    try:
        with open(map_path, 'r', encoding='utf-8') as f:
            raw = json.load(f)
    except Exception:
        return []

    rows = []
    pages = raw.get('pages', {}) if isinstance(raw, dict) else {}
    for page_id, page in pages.items():
        if not isinstance(page, dict):
            continue
        rows.append({
            'page_id': str(page_id),
            'legacy_page_id': str(page.get('legacy_page_id') or ''),
            'name': str(page.get('name') or ''),
            'description': str(page.get('description') or ''),
            'features': list(page.get('features') or [])[:8],
            'aliases': list(page.get('target_aliases') or [])[:6],
        })
    return rows


class _FixedPlanPlanner:
    def __init__(self, package_name: str, target_page: str):
        self.package_name = package_name
        self.target_page = target_page

    def plan(self, user_task, route_map):
        from src.cortex import RoutePlan
        pkg = self.package_name or route_map.package
        return RoutePlan(pkg, self.target_page)


class _FSMPlannerBridge:
    """
    Bridge planner for FSM mode.
    - APP_RESOLVE is pinned to selected package when available.
    - Other states delegate to FSM LLM planner when provided.
    - Without LLM planner, VISION_ACT defaults to DONE for route-only debugging.
    """

    def __init__(self, selected_package: str = "", llm_planner=None):
        self.selected_package = (selected_package or "").strip()
        self.llm_planner = llm_planner

    def plan(self, state, prompt, context):
        state_name = getattr(state, "value", str(state))
        if state_name == "APP_RESOLVE" and self.selected_package:
            return f"SET_APP {self.selected_package}"

        if self.llm_planner is not None:
            return self.llm_planner.plan(state, prompt, context)

        if state_name == "VISION_ACT":
            return "DONE"
        return "FAIL llm_planner_disabled"

    def plan_vision(self, state, prompt, context, screenshot):
        if self.llm_planner is not None and hasattr(self.llm_planner, 'plan_vision'):
            return self.llm_planner.plan_vision(state, prompt, context, screenshot)
        return self.plan(state, prompt, context)


@app.route('/')
def index():
    """控制台导航壳页面（内部切换子页面）"""
    return render_template('index.html')


@app.route('/command_studio')
def command_studio():
    """指令调试页面"""
    return render_template('command_studio.html')


@app.route('/map_builder')
def map_builder():
    """Map Builder 页面"""
    return render_template('map_builder.html')


@app.route('/map_viewer')
def map_viewer():
    """Map Viewer 页面"""
    return render_template('map_viewer.html')


@app.route('/cortex_route')
def cortex_route():
    """Cortex Route 调试页面"""
    return render_template('cortex_route.html')


@app.route('/api/connect', methods=['POST'])
def connect():
    """兼容旧接口：创建连接并选为当前连接。"""
    data = request.json or {}
    host = (data.get('host') or '192.168.1.100').strip()
    port = int(data.get('port') or 12345)
    try:
        existed = _find_connection_by_host_port(host, port)
        if existed:
            _select_connection(existed.connection_id)
            return jsonify({'success': True, 'message': f'已切换到 {host}:{port}', 'connection': _connection_public(existed)})
        rec = _create_connection(host, port, source='manual', set_current=True)
        return jsonify({'success': True, 'message': f'成功连接到 {host}:{port}', 'connection': _connection_public(rec)})
    except Exception as e:
        return jsonify({'success': False, 'message': f'连接失败: {str(e)}'}), 500


@app.route('/api/disconnect', methods=['POST'])
def disconnect():
    """兼容旧接口：断开当前选中连接。"""
    try:
        _disconnect_connection(None)
        return jsonify({'success': True, 'message': '已断开连接'})
    except Exception as e:
        return jsonify({'success': False, 'message': f'断开失败: {str(e)}'}), 500


@app.route('/api/status', methods=['GET'])
def status():
    """获取当前连接状态及连接汇总。"""
    _sync_connection_info()
    with CONNECTIONS_LOCK:
        connections = [_connection_public(x) for x in CONNECTIONS.values()]
    payload = dict(connection_info)
    payload['connections'] = connections
    return jsonify(payload)


@app.route('/api/connections/create', methods=['POST'])
def connections_create():
    data = request.json or {}
    host = (data.get('host') or '192.168.1.100').strip()
    port = int(data.get('port') or 12345)
    source = (data.get('source') or 'manual').strip() or 'manual'
    set_current = bool(data.get('set_current', True))
    try:
        existed = _find_connection_by_host_port(host, port)
        if existed:
            if set_current:
                _select_connection(existed.connection_id)
            return jsonify({'success': True, 'connection': _connection_public(existed), 'reused': True})
        rec = _create_connection(host, port, source=source, set_current=set_current)
        return jsonify({'success': True, 'connection': _connection_public(rec)})
    except Exception as e:
        return jsonify({'success': False, 'message': str(e)}), 500


@app.route('/api/connections/list', methods=['GET'])
def connections_list():
    _sync_connection_info()
    with CONNECTIONS_LOCK:
        rows = [_connection_public(x) for x in CONNECTIONS.values()]
    return jsonify({'success': True, 'current_connection_id': connection_info.get('connection_id'), 'data': rows})


@app.route('/api/connections/select', methods=['POST'])
def connections_select():
    data = request.json or {}
    connection_id = (data.get('connection_id') or '').strip()
    if not connection_id:
        return jsonify({'success': False, 'message': 'connection_id is required'}), 400
    try:
        rec = _select_connection(connection_id)
        return jsonify({'success': True, 'connection': _connection_public(rec)})
    except Exception as e:
        return jsonify({'success': False, 'message': str(e)}), 400


@app.route('/api/connections/<connection_id>/disconnect', methods=['POST'])
def connections_disconnect(connection_id):
    try:
        _disconnect_connection(connection_id)
        return jsonify({'success': True, 'connection_id': connection_id})
    except Exception as e:
        return jsonify({'success': False, 'message': str(e)}), 500


# =============================================================================
# Link Layer (0x00-0x0F)
# =============================================================================

@app.route('/api/command/handshake', methods=['POST'])
def cmd_handshake():
    """发送握手命令"""
    error_response = _require_client_response()
    if error_response:
        return error_response

    try:
        response = client.handshake()
        return jsonify({
            'success': True,
            'message': '握手成功',
            'response': {
                'length': len(response)
            }
        })
    except Exception as e:
        return jsonify({'success': False, 'message': str(e)}), 500


@app.route('/api/command/heartbeat', methods=['POST'])
def cmd_heartbeat():
    """发送 HEARTBEAT 命令"""
    error_response = _require_client_response()
    if error_response:
        return error_response

    try:
        response = client.heartbeat()
        return jsonify({
            'success': True,
            'message': '心跳成功',
            'response': {
                'length': len(response),
                'data': list(response) if response else []
            }
        })
    except Exception as e:
        return jsonify({'success': False, 'message': str(e)}), 500


# =============================================================================
# Input Layer (0x10-0x1F)
# =============================================================================

@app.route('/api/command/tap', methods=['POST'])
def cmd_tap():
    """发送 TAP 命令"""
    error_response = _require_client_response()
    if error_response:
        return error_response

    data = request.json
    x = data.get('x', 500)
    y = data.get('y', 800)

    try:
        response = client.tap(x, y)
        return jsonify({
            'success': True,
            'message': f'TAP ({x}, {y}) 成功',
            'response': {
                'length': len(response),
                'data': list(response) if response else []
            }
        })
    except Exception as e:
        return jsonify({'success': False, 'message': str(e)}), 500


@app.route('/api/command/swipe', methods=['POST'])
def cmd_swipe():
    """发送 SWIPE 命令"""
    error_response = _require_client_response()
    if error_response:
        return error_response

    data = request.json
    x1 = data.get('x1', 500)
    y1 = data.get('y1', 1000)
    x2 = data.get('x2', 500)
    y2 = data.get('y2', 500)
    duration = data.get('duration', 300)

    try:
        response = client.swipe(x1, y1, x2, y2, duration)
        return jsonify({
            'success': True,
            'message': f'SWIPE ({x1},{y1})→({x2},{y2}) 成功',
            'response': {
                'length': len(response),
                'data': list(response) if response else []
            }
        })
    except Exception as e:
        return jsonify({'success': False, 'message': str(e)}), 500


@app.route('/api/command/long_press', methods=['POST'])
def cmd_long_press():
    """发送 LONG_PRESS 命令"""
    error_response = _require_client_response()
    if error_response:
        return error_response

    data = request.json
    x = data.get('x', 500)
    y = data.get('y', 800)
    duration = data.get('duration', 1000)

    try:
        response = client.long_press(x, y, duration)
        return jsonify({
            'success': True,
            'message': f'LONG_PRESS ({x}, {y}) {duration}ms 成功',
            'response': {
                'length': len(response),
                'data': list(response) if response else []
            }
        })
    except Exception as e:
        return jsonify({'success': False, 'message': str(e)}), 500


@app.route('/api/command/unlock', methods=['POST'])
def cmd_unlock():
    """发送 UNLOCK 命令"""
    error_response = _require_client_response()
    if error_response:
        return error_response

    try:
        success = client.unlock()
        return jsonify({
            'success': success,
            'message': '解锁成功' if success else '解锁失败'
        })
    except Exception as e:
        return jsonify({'success': False, 'message': str(e)}), 500


# =============================================================================
# Input Extension (0x20-0x2F)
# =============================================================================

@app.route('/api/command/input_text', methods=['POST'])
def cmd_input_text():
    """发送 INPUT_TEXT 命令"""
    error_response = _require_client_response()
    if error_response:
        return error_response

    data = request.json
    text = data.get('text', 'Hello LXB')
    clear_first = data.get('clear_first', False)
    press_enter = data.get('press_enter', False)

    try:
        status, actual_method = client.input_text(
            text,
            clear_first=clear_first,
            press_enter=press_enter
        )
        return jsonify({
            'success': status == 1,
            'message': f'输入文本 "{text}" 成功' if status == 1 else '输入文本失败',
            'response': {
                'status': status,
                'method': actual_method
            }
        })
    except Exception as e:
        return jsonify({'success': False, 'message': str(e)}), 500


@app.route('/api/command/key_event', methods=['POST'])
def cmd_key_event():
    """发送 KEY_EVENT 命令"""
    error_response = _require_client_response()
    if error_response:
        return error_response

    data = request.json
    keycode = data.get('keycode', KEY_BACK)
    action = data.get('action', 2)  # 2 = CLICK

    # 支持按名称指定按键
    key_map = {
        'home': KEY_HOME,
        'back': KEY_BACK,
        'enter': KEY_ENTER,
        'menu': KEY_MENU,
        'recent': KEY_RECENT
    }
    if isinstance(keycode, str):
        keycode = key_map.get(keycode.lower(), KEY_BACK)

    try:
        response = client.key_event(keycode, action)
        return jsonify({
            'success': True,
            'message': f'KEY_EVENT keycode={keycode} 成功',
            'response': {
                'length': len(response) if response else 0
            }
        })
    except Exception as e:
        return jsonify({'success': False, 'message': str(e)}), 500


# =============================================================================
# Sense Layer (0x30-0x3F)
# =============================================================================

@app.route('/api/command/get_activity', methods=['POST'])
def cmd_get_activity():
    """发送 GET_ACTIVITY 命令"""
    error_response = _require_client_response()
    if error_response:
        return error_response

    try:
        success, package_name, activity_name = client.get_activity()
        return jsonify({
            'success': success,
            'message': '获取 Activity 成功' if success else '获取 Activity 失败',
            'response': {
                'package': package_name,
                'activity': activity_name
            }
        })
    except Exception as e:
        return jsonify({'success': False, 'message': str(e)}), 500


@app.route('/api/command/get_screen_state', methods=['POST'])
def cmd_get_screen_state():
    """发送 GET_SCREEN_STATE 命令"""
    error_response = _require_client_response()
    if error_response:
        return error_response

    try:
        success, state = client.get_screen_state()
        state_names = {0: '关闭', 1: '亮屏已解锁', 2: '亮屏已锁定'}
        return jsonify({
            'success': success,
            'message': f'屏幕状态: {state_names.get(state, "未知")}',
            'response': {
                'state': state,
                'state_name': state_names.get(state, 'unknown')
            }
        })
    except Exception as e:
        return jsonify({'success': False, 'message': str(e)}), 500


@app.route('/api/command/get_screen_size', methods=['POST'])
def cmd_get_screen_size():
    """发送 GET_SCREEN_SIZE 命令"""
    error_response = _require_client_response()
    if error_response:
        return error_response

    try:
        success, width, height, density = client.get_screen_size()
        return jsonify({
            'success': success,
            'message': f'屏幕尺寸: {width}x{height} @{density}dpi',
            'response': {
                'width': width,
                'height': height,
                'density': density
            }
        })
    except Exception as e:
        return jsonify({'success': False, 'message': str(e)}), 500


@app.route('/api/command/touch_mode', methods=['POST'])
def cmd_touch_mode():
    """Set touch execution mode on Android side."""
    error_response = _require_client_response()
    if error_response:
        return error_response
    try:
        data = request.json or {}
        mode = str(data.get('mode') or 'shell_first').strip()
        shell_first = mode != 'uiautomation_first'
        ok = client.set_touch_mode(shell_first=shell_first)
        return jsonify({
            'success': bool(ok),
            'message': f"touch_mode set to {'shell_first' if shell_first else 'uiautomation_first'}",
            'response': {'touch_mode': 'shell_first' if shell_first else 'uiautomation_first'}
        })
    except Exception as e:
        return jsonify({'success': False, 'message': str(e)}), 500


@app.route('/api/command/find_node', methods=['POST'])
def cmd_find_node():
    """发送 FIND_NODE 命令"""
    error_response = _require_client_response()
    if error_response:
        return error_response

    data = request.json
    query = data.get('query', '')
    match_type = data.get('match_type', 1)  # MATCH_CONTAINS_TEXT
    multi_match = data.get('multi_match', False)

    try:
        status, results = client.find_node(
            query,
            match_type=match_type,
            multi_match=multi_match
        )
        return jsonify({
            'success': status == 1,
            'message': f'找到 {len(results)} 个节点' if status == 1 else '未找到节点',
            'response': {
                'status': status,
                'count': len(results),
                'results': results
            }
        })
    except Exception as e:
        return jsonify({'success': False, 'message': str(e)}), 500


@app.route('/api/command/dump_hierarchy', methods=['POST'])
def cmd_dump_hierarchy():
    """发送 DUMP_HIERARCHY 命令，获取完整 UI 层级结构"""
    error_response = _require_client_response()
    if error_response:
        return error_response

    data = request.json
    max_depth = data.get('max_depth', 0)  # 0 = 无限制

    try:
        hierarchy = client.dump_hierarchy(max_depth=max_depth)
        node_count = hierarchy.get('node_count', 0)
        nodes = hierarchy.get('nodes', [])

        # 统计可交互节点
        clickable_count = sum(1 for n in nodes if n.get('clickable', False))
        editable_count = sum(1 for n in nodes if n.get('editable', False))
        scrollable_count = sum(1 for n in nodes if n.get('scrollable', False))

        return jsonify({
            'success': True,
            'message': f'获取 UI 树成功: {node_count} 个节点',
            'response': {
                'version': hierarchy.get('version', 1),
                'node_count': node_count,
                'clickable_count': clickable_count,
                'editable_count': editable_count,
                'scrollable_count': scrollable_count,
                'nodes': nodes
            }
        })
    except Exception as e:
        return jsonify({'success': False, 'message': str(e)}), 500


@app.route('/api/command/dump_actions', methods=['POST'])
def cmd_dump_actions():
    """发送 DUMP_ACTIONS 命令，获取可交互节点 (用于路径规划)"""
    error_response = _require_client_response()
    if error_response:
        return error_response

    try:
        actions = client.dump_actions()
        node_count = actions.get('node_count', 0)
        nodes = actions.get('nodes', [])

        # 统计各类型节点
        clickable_count = sum(1 for n in nodes if n.get('clickable', False))
        editable_count = sum(1 for n in nodes if n.get('editable', False))
        scrollable_count = sum(1 for n in nodes if n.get('scrollable', False))
        text_only_count = sum(1 for n in nodes if n.get('text_only', False))

        return jsonify({
            'success': True,
            'message': f'获取可交互节点成功: {node_count} 个节点',
            'response': {
                'version': actions.get('version', 1),
                'node_count': node_count,
                'clickable_count': clickable_count,
                'editable_count': editable_count,
                'scrollable_count': scrollable_count,
                'text_only_count': text_only_count,
                'nodes': nodes
            }
        })
    except Exception as e:
        return jsonify({'success': False, 'message': str(e)}), 500


# =============================================================================
# Lifecycle Layer (0x40-0x4F)
# =============================================================================

@app.route('/api/command/launch_app', methods=['POST'])
def cmd_launch_app():
    """发送 LAUNCH_APP 命令"""
    error_response = _require_client_response()
    if error_response:
        return error_response

    data = request.json
    package_name = data.get('package', '')
    clear_task = data.get('clear_task', False)

    if not package_name:
        return jsonify({'success': False, 'message': '请输入包名'}), 400

    try:
        success = client.launch_app(package_name, clear_task=clear_task)
        return jsonify({
            'success': success,
            'message': f'启动 {package_name} 成功' if success else f'启动 {package_name} 失败'
        })
    except Exception as e:
        return jsonify({'success': False, 'message': str(e)}), 500


@app.route('/api/command/stop_app', methods=['POST'])
def cmd_stop_app():
    """发送 STOP_APP 命令"""
    error_response = _require_client_response()
    if error_response:
        return error_response

    data = request.json
    package_name = data.get('package', '')

    if not package_name:
        return jsonify({'success': False, 'message': '请输入包名'}), 400

    try:
        success = client.stop_app(package_name)
        return jsonify({
            'success': success,
            'message': f'停止 {package_name} 成功' if success else f'停止 {package_name} 失败'
        })
    except Exception as e:
        return jsonify({'success': False, 'message': str(e)}), 500


@app.route('/api/command/list_apps', methods=['POST'])
def cmd_list_apps():
    """?????????"""
    error_response = _require_client_response()
    if error_response:
        return error_response

    data = request.json or {}
    filter_type = data.get('filter', 'user')  # user / system / all

    try:
        raw_apps = client.list_apps(filter_type)
        apps_with_names = _normalize_installed_apps(raw_apps)

        return jsonify({
            'success': True,
            'message': f'????????: {len(apps_with_names)} ???',
            'response': {
                'filter': filter_type,
                'count': len(apps_with_names),
                'apps': apps_with_names
            }
        })
    except Exception as e:
        return jsonify({'success': False, 'message': str(e)}), 500


# =============================================================================
# Media Layer (0x60-0x6F)
# =============================================================================

@app.route('/api/command/screenshot', methods=['POST'])
def cmd_screenshot():
    """发送 SCREENSHOT 命令 (使用分片传输)"""
    error_response = _require_client_response()
    if error_response:
        return error_response

    try:
        # 使用分片传输方式获取截图
        image_data = client.request_screenshot()

        if image_data and len(image_data) > 0:
            # 截图成功，返回 base64 编码的图片
            image_base64 = base64.b64encode(image_data).decode('utf-8')
            return jsonify({
                'success': True,
                'message': f'截图成功: {len(image_data)} 字节 ({len(image_data)/1024:.1f} KB)',
                'response': {
                    'size': len(image_data),
                    'image': image_base64
                }
            })
        else:
            return jsonify({
                'success': False,
                'message': '截图失败: 无数据返回'
            })
    except Exception as e:
        return jsonify({'success': False, 'message': str(e)}), 500


@app.route('/api/command/screenshot/raw', methods=['GET'])
def cmd_screenshot_raw():
    """获取原始截图图片（可直接嵌入 img 标签）"""
    if not client:
        return Response('未连接', status=400, mimetype='text/plain')

    try:
        # 使用分片传输方式获取截图
        image_data = client.request_screenshot()

        if image_data and len(image_data) > 0:
            # 返回 JPEG 图片 (服务端已压缩为 JPEG)
            return Response(image_data, mimetype='image/jpeg')
        else:
            return Response('截图失败', status=500, mimetype='text/plain')
    except Exception as e:
        return Response(str(e), status=500, mimetype='text/plain')


# =============================================================================
# Auto Map Builder v2/v3
# =============================================================================

# 检测 VLM 是否可用
VLM_AVAILABLE = False
try:
    from src.auto_map_builder import (
        NodeMapBuilder,
        ExplorationConfig
    )
    from src.auto_map_builder.vlm_engine import VLMEngine
    from src.auto_map_builder.fusion_engine import FusionEngine, parse_xml_nodes
    from src.auto_map_builder.page_manager import PageManager
    from src.auto_map_builder.som_annotator import create_annotated_screenshot
    VLM_AVAILABLE = True
except ImportError as e:
    print(f"[app.py] Auto Map Builder 不可用: {e}")

# 全局探索器实例和状态
explorer_instance = None
exploration_result = None
exploration_status = {
    'running': False,
    'package': None,
    'version': 'v2',  # v2 或 v3
    'progress': {
        'pages_discovered': 0,
        'nodes_discovered': 0,
        'current_page': None
    },
    'result': None,
    'logs': []
}


def _legacy_map_builder_disabled_response():
    return jsonify({
        'success': False,
        'message': 'legacy auto_map_builder strategies are archived; use /api/explore/node/start'
    }), 410


@app.route('/api/explore/start', methods=['POST'])
def explore_start():
    return _legacy_map_builder_disabled_response()
    """启动应用探索 (v2 VLM+XML 融合)"""
    global client, explorer_instance, exploration_result, exploration_status

    error_response = _require_client_response()
    if error_response:
        return error_response

    if exploration_status['running']:
        return jsonify({'success': False, 'message': '探索正在进行中'}), 400

    if not VLM_AVAILABLE:
        return jsonify({'success': False, 'message': 'Auto Map Builder 模块不可用'}), 400

    data = request.json
    package_name = data.get('package', '')

    if not package_name:
        return jsonify({'success': False, 'message': '请指定应用包名'}), 400

    try:
        from datetime import datetime

        # 创建配置
        config = ExplorationConfig(
            max_pages=data.get('max_pages', 50),
            max_depth=data.get('max_depth', 10),
            max_time_seconds=data.get('max_time_seconds', 1800),
            enable_od=data.get('enable_od', True),
            enable_ocr=data.get('enable_ocr', True),
            enable_caption=data.get('enable_caption', True),
            # 并发推理配置
            vlm_concurrent_enabled=data.get('vlm_concurrent_enabled', False),
            vlm_concurrent_requests=data.get('vlm_concurrent_requests', 5),
            vlm_occurrence_threshold=data.get('vlm_occurrence_threshold', 2),
            iou_threshold=data.get('iou_threshold', 0.5),
            action_delay_ms=data.get('action_delay_ms', 1000),
            scroll_enabled=data.get('scroll_enabled', True),
            max_scrolls_per_page=data.get('max_scrolls_per_page', 5),
            save_screenshots=data.get('save_screenshots', True),
            output_dir=data.get('output_dir', './maps')
        )

        # 日志回调
        def log_callback(level, message, log_data=None):
            log_entry = {
                'time': datetime.now().strftime('%H:%M:%S'),
                'level': level,
                'message': message,
                'data': log_data
            }
            exploration_status['logs'].append(log_entry)

        # 清空日志
        exploration_status['logs'] = []

        # 创建探索器
        explorer_instance = AutoMapBuilder(client, config, log_callback)

        # 更新状态
        exploration_status['running'] = True
        exploration_status['package'] = package_name
        exploration_status['progress'] = {
            'pages_discovered': 0,
            'nodes_discovered': 0,
            'current_page': None
        }
        exploration_status['result'] = None

        log_callback('info', f'开始探索: {package_name}')

        # 执行探索
        exploration_result = explorer_instance.explore(package_name)

        # 更新结果
        exploration_status['running'] = False
        exploration_status['progress'] = {
            'pages_discovered': exploration_result.page_count,
            'nodes_discovered': sum(len(p.nodes) for p in exploration_result.pages.values()),
            'current_page': 'completed'
        }
        exploration_status['result'] = {
            'pages': exploration_result.page_count,
            'transitions': exploration_result.transition_count,
            'time': round(exploration_result.exploration_time_seconds, 2),
            'vlm_inferences': exploration_result.vlm_inference_count,
            'vlm_time_ms': round(exploration_result.vlm_total_time_ms, 2)
        }

        return jsonify({
            'success': True,
            'message': f'探索完成: {exploration_result.page_count} 个页面',
            'result': exploration_status['result']
        })

    except Exception as e:
        import traceback
        exploration_status['running'] = False
        return jsonify({
            'success': False,
            'message': f'探索失败: {str(e)}',
            'traceback': traceback.format_exc()
        }), 500


@app.route('/api/explore/status', methods=['GET'])
def explore_status():
    """获取探索状态"""
    global explorer_instance

    # 如果有探索器实例，获取实际状态
    if explorer_instance:
        try:
            from src.auto_map_builder import ExplorationStatus
            actual_status = explorer_instance.status
            exploration_status['status'] = actual_status.value
            exploration_status['running'] = actual_status == ExplorationStatus.RUNNING
            exploration_status['paused'] = actual_status == ExplorationStatus.PAUSED
        except Exception:
            pass

    return jsonify(exploration_status)


@app.route('/api/explore/pause', methods=['POST'])
def explore_pause():
    """暂停探索"""
    global explorer_instance

    if not explorer_instance:
        return jsonify({'success': False, 'message': '没有正在进行的探索'}), 400

    try:
        explorer_instance.pause()
        return jsonify({
            'success': True,
            'message': '探索已暂停',
            'status': explorer_instance.status.value
        })
    except Exception as e:
        return jsonify({'success': False, 'message': str(e)}), 500


@app.route('/api/explore/resume', methods=['POST'])
def explore_resume():
    """恢复探索"""
    global explorer_instance

    if not explorer_instance:
        return jsonify({'success': False, 'message': '没有正在进行的探索'}), 400

    try:
        explorer_instance.resume()
        return jsonify({
            'success': True,
            'message': '探索已恢复',
            'status': explorer_instance.status.value
        })
    except Exception as e:
        return jsonify({'success': False, 'message': str(e)}), 500


@app.route('/api/explore/stop', methods=['POST'])
def explore_stop():
    """终止探索"""
    global explorer_instance

    if not explorer_instance:
        return jsonify({'success': False, 'message': '没有正在进行的探索'}), 400

    try:
        explorer_instance.stop()
        return jsonify({
            'success': True,
            'message': '正在终止探索',
            'status': explorer_instance.status.value
        })
    except Exception as e:
        return jsonify({'success': False, 'message': str(e)}), 500


@app.route('/api/explore/logs', methods=['GET'])
def explore_logs():
    """获取探索日志"""
    since = request.args.get('since', 0, type=int)
    logs = exploration_status.get('logs', [])
    return jsonify({
        'success': True,
        'logs': logs[since:],
        'total': len(logs)
    })


@app.route('/api/explore/realtime', methods=['GET'])
def explore_realtime():
    """获取实时探索状态（用于可视化）"""
    global explorer_instance

    if not explorer_instance:
        return jsonify({
            'success': False,
            'message': '没有正在进行的探索'
        }), 400

    try:
        # v3 使用 get_realtime_state 方法
        if hasattr(explorer_instance, 'get_realtime_state'):
            realtime_state = explorer_instance.get_realtime_state()
        # v2 使用 _explorer.get_realtime_state
        elif hasattr(explorer_instance, '_explorer') and explorer_instance._explorer:
            realtime_state = explorer_instance._explorer.get_realtime_state()
        else:
            realtime_state = {}

        return jsonify({
            'success': True,
            'data': realtime_state
        })
    except Exception as e:
        return jsonify({
            'success': False,
            'message': str(e)
        }), 500


# =============================================================================
# Auto Map Builder v3 - 语义探索 API
# =============================================================================

@app.route('/api/explore/v3/start', methods=['POST'])
def explore_v3_start():
    return _legacy_map_builder_disabled_response()
    """启动 v3 语义探索"""
    global client, explorer_instance, exploration_result, exploration_status

    error_response = _require_client_response()
    if error_response:
        return error_response

    if exploration_status['running']:
        return jsonify({'success': False, 'message': '探索正在进行中'}), 400

    if not VLM_AVAILABLE:
        return jsonify({'success': False, 'message': 'Auto Map Builder 模块不可用'}), 400

    data = request.json
    package_name = data.get('package', '')

    if not package_name:
        return jsonify({'success': False, 'message': '请指定应用包名'}), 400

    try:
        from datetime import datetime

        # 创建配置
        config = ExplorationConfig(
            max_pages=data.get('max_pages', 30),
            max_depth=data.get('max_depth', 5),
            max_time_seconds=data.get('max_time_seconds', 1800),
            action_delay_ms=data.get('action_delay_ms', 800),
            output_dir=data.get('output_dir', './maps')
        )

        # 日志回调
        def log_callback(level, message, log_data=None):
            log_entry = {
                'time': datetime.now().strftime('%H:%M:%S'),
                'level': level,
                'message': message,
                'data': log_data
            }
            exploration_status['logs'].append(log_entry)

        # 清空日志
        exploration_status['logs'] = []

        # 创建 v3 探索器
        explorer_instance = SemanticMapBuilder(client, config, log_callback)

        # 更新状态
        exploration_status['running'] = True
        exploration_status['package'] = package_name
        exploration_status['version'] = 'v3'
        exploration_status['progress'] = {
            'pages_discovered': 0,
            'transitions_discovered': 0,
            'current_page': None
        }
        exploration_status['result'] = None

        log_callback('info', f'[v3] 开始语义探索: {package_name}')

        # 执行探索
        exploration_result = explorer_instance.explore(package_name)

        # 更新结果
        exploration_status['running'] = False
        exploration_status['progress'] = {
            'pages_discovered': len(exploration_result.graph.pages),
            'transitions_discovered': len(exploration_result.graph.transitions),
            'current_page': 'completed'
        }
        exploration_status['result'] = {
            'pages': len(exploration_result.graph.pages),
            'transitions': len(exploration_result.graph.transitions),
            'time': round(exploration_result.exploration_time_seconds, 2),
            'vlm_inferences': exploration_result.vlm_inference_count,
            'vlm_time_ms': round(exploration_result.vlm_total_time_ms, 2),
            'actions': exploration_result.total_actions
        }

        return jsonify({
            'success': True,
            'message': f'[v3] 探索完成: {len(exploration_result.graph.pages)} 个页面, {len(exploration_result.graph.transitions)} 个跳转',
            'result': exploration_status['result']
        })

    except Exception as e:
        import traceback
        exploration_status['running'] = False
        return jsonify({
            'success': False,
            'message': f'探索失败: {str(e)}',
            'traceback': traceback.format_exc()
        }), 500


@app.route('/api/explore/v3/graph', methods=['GET'])
def explore_v3_graph():
    return _legacy_map_builder_disabled_response()
    """获取 v3 导航图"""
    global explorer_instance

    if not explorer_instance:
        return jsonify({'success': False, 'message': '没有探索结果'}), 400

    # 检查是否是 v3 探索器
    if not hasattr(explorer_instance, 'graph') or explorer_instance.graph is None:
        return jsonify({'success': False, 'message': '当前不是 v3 探索或没有导航图'}), 400

    try:
        graph = explorer_instance.graph

        # 序列化页面
        pages = []
        for page in graph.pages.values():
            anchors = []
            for anchor in page.nav_anchors:
                anchors.append({
                    'anchor_id': anchor.anchor_id,
                    'role': anchor.role,
                    'description': anchor.description,
                    'locator': {
                        'resource_id': anchor.locator.resource_id,
                        'text': anchor.locator.text,
                        'bounds': list(anchor.locator.bounds) if anchor.locator.bounds else None
                    }
                })

            pages.append({
                'semantic_id': page.semantic_id,
                'page_type': page.page_type,
                'sub_state': page.sub_state,
                'activity': page.activity,
                'description': page.description,
                'nav_anchors': anchors
            })

        # 序列化跳转
        transitions = []
        for trans in graph.transitions:
            transitions.append({
                'from_page': trans.from_page,
                'to_page': trans.to_page,
                'anchor_id': trans.anchor_id,
                'locator': {
                    'resource_id': trans.locator.resource_id if trans.locator else None,
                    'text': trans.locator.text if trans.locator else None,
                    'bounds': list(trans.locator.bounds) if trans.locator and trans.locator.bounds else None
                }
            })

        return jsonify({
            'success': True,
            'data': {
                'pages': pages,
                'transitions': transitions,
                'page_count': len(pages),
                'transition_count': len(transitions)
            }
        })

    except Exception as e:
        import traceback
        return jsonify({
            'success': False,
            'message': str(e),
            'traceback': traceback.format_exc()
        }), 500


@app.route('/api/explore/v3/save', methods=['POST'])
def explore_v3_save():
    return _legacy_map_builder_disabled_response()
    """保存 v3 导航图"""
    global explorer_instance, exploration_result

    if not explorer_instance:
        return jsonify({'success': False, 'message': '没有探索结果'}), 400

    if not hasattr(explorer_instance, 'save'):
        return jsonify({'success': False, 'message': '当前不是 v3 探索器'}), 400

    data = request.json or {}
    filepath = data.get('filepath')

    try:
        explorer_instance.save(filepath)

        return jsonify({
            'success': True,
            'message': f'导航图已保存',
            'filepath': filepath
        })

    except Exception as e:
        import traceback
        return jsonify({
            'success': False,
            'message': str(e),
            'traceback': traceback.format_exc()
        }), 500


@app.route('/api/explore/v3/find_path', methods=['POST'])
def explore_v3_find_path():
    return _legacy_map_builder_disabled_response()
    """查找路径"""
    global explorer_instance

    if not explorer_instance:
        return jsonify({'success': False, 'message': '没有探索结果'}), 400

    if not hasattr(explorer_instance, 'find_path'):
        return jsonify({'success': False, 'message': '当前不是 v3 探索器'}), 400

    data = request.json
    from_page = data.get('from_page', '')
    to_page = data.get('to_page', '')

    if not from_page or not to_page:
        return jsonify({'success': False, 'message': '请指定起始和目标页面'}), 400

    try:
        path = explorer_instance.find_path(from_page, to_page)

        if path is None:
            return jsonify({
                'success': False,
                'message': f'无法找到从 {from_page} 到 {to_page} 的路径'
            })

        # 序列化路径
        path_data = []
        for trans in path:
            path_data.append({
                'from_page': trans.from_page,
                'to_page': trans.to_page,
                'anchor_id': trans.anchor_id
            })

        return jsonify({
            'success': True,
            'message': f'找到路径: {len(path)} 步',
            'data': {
                'path': path_data,
                'steps': len(path)
            }
        })

    except Exception as e:
        import traceback
        return jsonify({
            'success': False,
            'message': str(e),
            'traceback': traceback.format_exc()
        }), 500


@app.route('/api/explore/v3/navigate', methods=['POST'])
def explore_v3_navigate():
    return _legacy_map_builder_disabled_response()
    """执行导航"""
    global explorer_instance

    if not explorer_instance:
        return jsonify({'success': False, 'message': '没有探索结果'}), 400

    if not hasattr(explorer_instance, 'navigate_to'):
        return jsonify({'success': False, 'message': '当前不是 v3 探索器'}), 400

    data = request.json
    target_page = data.get('target_page', '')
    verify = data.get('verify', True)

    if not target_page:
        return jsonify({'success': False, 'message': '请指定目标页面'}), 400

    try:
        success, message = explorer_instance.navigate_to(target_page, verify=verify)

        return jsonify({
            'success': success,
            'message': message
        })

    except Exception as e:
        import traceback
        return jsonify({
            'success': False,
            'message': str(e),
            'traceback': traceback.format_exc()
        }), 500


# =============================================================================
# Auto Map Builder SoM (Set-of-Mark) API
# =============================================================================

@app.route('/api/explore/som/start', methods=['POST'])
def explore_som_start():
    return _legacy_map_builder_disabled_response()
    """启动 SoM 探索（推荐）"""
    global client, explorer_instance, exploration_result, exploration_status

    error_response = _require_client_response()
    if error_response:
        return error_response

    if exploration_status['running']:
        return jsonify({'success': False, 'message': '探索正在进行中'}), 400

    if not VLM_AVAILABLE:
        return jsonify({'success': False, 'message': 'Auto Map Builder 模块不可用'}), 400

    data = request.json
    package_name = data.get('package', '')

    if not package_name:
        return jsonify({'success': False, 'message': '请指定应用包名'}), 400

    try:
        from datetime import datetime

        # 创建配置
        config = ExplorationConfig(
            max_pages=data.get('max_pages', 30),
            max_depth=data.get('max_depth', 5),
            max_time_seconds=data.get('max_time_seconds', 1800),
            action_delay_ms=data.get('action_delay_ms', 800),
            output_dir=data.get('output_dir', './maps')
        )

        # 日志回调
        def log_callback(level, message, log_data=None):
            log_entry = {
                'time': datetime.now().strftime('%H:%M:%S'),
                'level': level,
                'message': message,
                'data': log_data
            }
            exploration_status['logs'].append(log_entry)

        # 清空日志
        exploration_status['logs'] = []

        # 创建 SoM 探索器
        explorer_instance = SoMMapBuilder(client, config, log_callback)

        # 更新状态
        exploration_status['running'] = True
        exploration_status['package'] = package_name
        exploration_status['version'] = 'som'
        exploration_status['progress'] = {
            'pages_discovered': 0,
            'transitions_discovered': 0,
            'current_page': None
        }
        exploration_status['result'] = None

        log_callback('info', f'[SoM] 开始探索: {package_name}')

        # 执行探索
        exploration_result = explorer_instance.explore(package_name)

        # 更新结果
        exploration_status['running'] = False
        exploration_status['progress'] = {
            'pages_discovered': len(exploration_result.graph.pages),
            'transitions_discovered': len(exploration_result.graph.transitions),
            'current_page': 'completed'
        }
        exploration_status['result'] = {
            'pages': len(exploration_result.graph.pages),
            'transitions': len(exploration_result.graph.transitions),
            'time': round(exploration_result.exploration_time_seconds, 2),
            'vlm_inferences': exploration_result.vlm_inference_count,
            'vlm_time_ms': round(exploration_result.vlm_total_time_ms, 2),
            'actions': exploration_result.total_actions
        }

        return jsonify({
            'success': True,
            'message': f'[SoM] 探索完成: {len(exploration_result.graph.pages)} 个页面, {len(exploration_result.graph.transitions)} 个跳转',
            'result': exploration_status['result']
        })

    except Exception as e:
        import traceback
        exploration_status['running'] = False
        return jsonify({
            'success': False,
            'message': f'探索失败: {str(e)}',
            'traceback': traceback.format_exc()
        }), 500


@app.route('/api/explore/coord/start', methods=['POST'])
def explore_coord_start():
    return _legacy_map_builder_disabled_response()
    """启动坐标驱动探索 (v4 推荐)"""
    global client, explorer_instance, exploration_result, exploration_status

    error_response = _require_client_response()
    if error_response:
        return error_response

    if exploration_status['running']:
        return jsonify({'success': False, 'message': '探索正在进行中'}), 400

    if not VLM_AVAILABLE:
        return jsonify({'success': False, 'message': 'Auto Map Builder 模块不可用'}), 400

    data = request.json
    package_name = data.get('package', '')

    if not package_name:
        return jsonify({'success': False, 'message': '请指定应用包名'}), 400

    try:
        from datetime import datetime

        config = ExplorationConfig(
            max_pages=data.get('max_pages', 30),
            max_depth=data.get('max_depth', 5),
            max_time_seconds=data.get('max_time_seconds', 1800),
            action_delay_ms=data.get('action_delay_ms', 800),
            output_dir=data.get('output_dir', './maps')
        )

        def log_callback(level, message, log_data=None):
            log_entry = {
                'time': datetime.now().strftime('%H:%M:%S'),
                'level': level,
                'message': message,
                'data': log_data
            }
            exploration_status['logs'].append(log_entry)

        exploration_status['logs'] = []
        explorer_instance = CoordMapBuilder(client, config, log_callback)

        exploration_status['running'] = True
        exploration_status['package'] = package_name
        exploration_status['version'] = 'coord'
        exploration_status['progress'] = {
            'pages_discovered': 0,
            'transitions_discovered': 0,
            'current_page': None
        }
        exploration_status['result'] = None

        log_callback('info', f'[v4] 坐标驱动探索: {package_name}')

        exploration_result = explorer_instance.explore(package_name)

        exploration_status['running'] = False
        exploration_status['progress'] = {
            'pages_discovered': exploration_result['page_count'],
            'transitions_discovered': exploration_result['transition_count'],
            'current_page': 'completed'
        }
        exploration_status['result'] = {
            'pages': exploration_result['page_count'],
            'transitions': exploration_result['transition_count'],
            'time': round(exploration_result['exploration_time_seconds'], 2),
            'actions': exploration_result['total_actions']
        }

        return jsonify({
            'success': True,
            'message': f'[v4] 探索完成: {exploration_result["page_count"]} 页面, {exploration_result["transition_count"]} 跳转',
            'result': exploration_status['result']
        })

    except Exception as e:
        import traceback
        exploration_status['running'] = False
        return jsonify({
            'success': False,
            'message': f'探索失败: {str(e)}',
            'traceback': traceback.format_exc()
        }), 500


@app.route('/api/explore/node/start', methods=['POST'])
def explore_node_start():
    """启动 Node 驱动探索 (v5 推荐)"""
    global client, explorer_instance, exploration_result, exploration_status

    error_response = _require_client_response()
    if error_response:
        return error_response

    if exploration_status['running']:
        return jsonify({'success': False, 'message': '探索正在进行中'}), 400

    if not VLM_AVAILABLE:
        return jsonify({'success': False, 'message': 'Auto Map Builder 模块不可用'}), 400

    data = request.json
    package_name = data.get('package', '')

    if not package_name:
        return jsonify({'success': False, 'message': '请指定应用包名'}), 400

    try:
        from datetime import datetime
        from src.auto_map_builder import NodeMapBuilder

        config = ExplorationConfig(
            max_pages=data.get('max_pages', 30),
            max_depth=data.get('max_depth', 3),
            max_time_seconds=data.get('max_time_seconds', 1800),
            action_delay_ms=data.get('action_delay_ms', 800),
            output_dir=data.get('output_dir', './maps')
        )

        def log_callback(level, message, log_data=None):
            log_entry = {
                'time': datetime.now().strftime('%H:%M:%S'),
                'level': level,
                'message': message,
                'data': log_data
            }
            exploration_status['logs'].append(log_entry)

        exploration_status['logs'] = []
        explorer_instance = NodeMapBuilder(client, config, log_callback)

        # 设置探索模式和点击延迟
        explore_mode = data.get('explore_mode', 'serial')
        click_delay = data.get('click_delay', 1.5)
        explorer_instance.set_mode(explore_mode)
        explorer_instance.set_click_delay(click_delay)

        exploration_status['running'] = True
        exploration_status['package'] = package_name
        exploration_status['version'] = 'node'
        exploration_status['progress'] = {
            'nodes_discovered': 0,
            'nodes_explored': 0,
            'current_node': None
        }
        exploration_status['result'] = None

        log_callback('info', f'[v5] Node 驱动探索: {package_name}')

        exploration_result = explorer_instance.explore(package_name)

        exploration_status['running'] = False
        exploration_status['progress'] = {
            'pages_discovered': exploration_result['total_pages'],
            'transitions_discovered': exploration_result['total_transitions'],
            'current_node': 'completed'
        }
        exploration_status['result'] = {
            'total_pages': exploration_result['total_pages'],
            'total_transitions': exploration_result['total_transitions'],
            'time': round(exploration_result['exploration_time_seconds'], 2),
            'actions': exploration_result['total_actions']
        }

        return jsonify({
            'success': True,
            'message': f'[v5] 探索完成: {exploration_result["total_pages"]} 页面, {exploration_result["total_transitions"]} 跳转',
            'result': exploration_status['result']
        })

    except Exception as e:
        import traceback
        exploration_status['running'] = False
        return jsonify({
            'success': False,
            'message': f'探索失败: {str(e)}',
            'traceback': traceback.format_exc()
        }), 500


@app.route('/api/maps/list', methods=['GET'])
def maps_list():
    """列出所有已保存的 map 文件"""
    try:
        import os
        import glob
        from datetime import datetime

        base_dir = os.path.join(os.path.dirname(os.path.dirname(__file__)), 'maps')

        if not os.path.exists(base_dir):
            return jsonify({
                'success': True,
                'data': []
            })

        maps = []
        # 遍历所有包目录
        for pkg_dir in os.listdir(base_dir):
            pkg_path = os.path.join(base_dir, pkg_dir)
            if not os.path.isdir(pkg_path):
                continue

            # 查找该包下的所有 nav_map_*.json 文件
            pattern = os.path.join(pkg_path, 'nav_map_*.json')
            for filepath in glob.glob(pattern):
                filename = os.path.basename(filepath)
                stat = os.stat(filepath)
                maps.append({
                    'package': pkg_dir.replace('_', '.'),
                    'filename': filename,
                    'filepath': filepath,
                    'size': stat.st_size,
                    'modified': datetime.fromtimestamp(stat.st_mtime).strftime('%Y-%m-%d %H:%M:%S')
                })

        # 按修改时间倒序排列
        maps.sort(key=lambda x: x['modified'], reverse=True)

        return jsonify({
            'success': True,
            'data': maps
        })

    except Exception as e:
        import traceback
        return jsonify({
            'success': False,
            'message': str(e),
            'traceback': traceback.format_exc()
        }), 500


@app.route('/api/maps/latest', methods=['GET'])
def maps_latest():
    """获取最新的 map 文件内容"""
    try:
        import os
        import glob
        import json

        base_dir = os.path.join(os.path.dirname(os.path.dirname(__file__)), 'maps')

        if not os.path.exists(base_dir):
            return jsonify({
                'success': False,
                'message': '没有找到 map 文件'
            }), 404

        # 查找所有 nav_map_*.json 文件
        all_maps = []
        for pkg_dir in os.listdir(base_dir):
            pkg_path = os.path.join(base_dir, pkg_dir)
            if not os.path.isdir(pkg_path):
                continue

            pattern = os.path.join(pkg_path, 'nav_map_*.json')
            for filepath in glob.glob(pattern):
                stat = os.stat(filepath)
                all_maps.append({
                    'filepath': filepath,
                    'mtime': stat.st_mtime
                })

        if not all_maps:
            return jsonify({
                'success': False,
                'message': '没有找到 map 文件'
            }), 404

        # 找到最新的文件
        latest = max(all_maps, key=lambda x: x['mtime'])

        # 读取文件内容
        with open(latest['filepath'], 'r', encoding='utf-8') as f:
            content = json.load(f)

        return jsonify({
            'success': True,
            'filepath': latest['filepath'],
            'data': content
        })

    except Exception as e:
        import traceback
        return jsonify({
            'success': False,
            'message': str(e),
            'traceback': traceback.format_exc()
        }), 500


@app.route('/api/maps/load', methods=['POST'])
def maps_load():
    """加载指定的 map 文件"""
    try:
        import os
        import json

        data = request.json
        filepath = data.get('filepath', '')

        if not filepath:
            return jsonify({
                'success': False,
                'message': '请指定文件路径'
            }), 400

        # 安全检查：只允许访问 maps 目录下的文件
        base_dir = os.path.abspath(os.path.join(os.path.dirname(os.path.dirname(__file__)), 'maps'))
        abs_filepath = os.path.abspath(filepath)

        if not abs_filepath.startswith(base_dir):
            return jsonify({
                'success': False,
                'message': '非法路径'
            }), 403

        if not os.path.exists(abs_filepath):
            return jsonify({
                'success': False,
                'message': '文件不存在'
            }), 404

        # 读取文件内容
        with open(abs_filepath, 'r', encoding='utf-8') as f:
            content = json.load(f)

        return jsonify({
            'success': True,
            'filepath': abs_filepath,
            'data': content
        })

    except Exception as e:
        import traceback
        return jsonify({
            'success': False,
            'message': str(e),
            'traceback': traceback.format_exc()
        }), 500


@app.route('/api/cortex/llm/config', methods=['GET'])
def cortex_llm_config_get():
    """Get Cortex Route Planner LLM config."""
    try:
        cfg = _load_cortex_llm_config()
        return jsonify({
            'success': True,
            'data': {
                'api_base_url': cfg.get('api_base_url', ''),
                'api_key': '***' if cfg.get('api_key') else '',
                'model_name': cfg.get('model_name', ''),
                'temperature': cfg.get('temperature', 0.1),
                'timeout': cfg.get('timeout', 30),
                'vision_jpeg_quality': int(cfg.get('vision_jpeg_quality', 35)),
                'node_exists_retries': cfg.get('node_exists_retries', 3),
                'node_exists_interval_sec': cfg.get('node_exists_interval_sec', 0.6),
                'touch_mode': cfg.get('touch_mode', 'shell_first'),
                'map_filepath': cfg.get('map_filepath', ''),
                'package_name': cfg.get('package_name', ''),
                'reconnect_before_run': bool(cfg.get('reconnect_before_run', True)),
                'use_llm_planner': bool(cfg.get('use_llm_planner', True)),
                'route_recovery_enabled': bool(cfg.get('route_recovery_enabled', False)),
                'max_route_restarts': int(cfg.get('max_route_restarts', 0)),
                'use_vlm_takeover': bool(cfg.get('use_vlm_takeover', False)),
                'fsm_max_turns': int(cfg.get('fsm_max_turns', 40)),
                'fsm_max_commands_per_turn': int(cfg.get('fsm_max_commands_per_turn', 1)),
                'fsm_max_vision_turns': int(cfg.get('fsm_max_vision_turns', 20)),
                'fsm_action_interval_sec': float(cfg.get('fsm_action_interval_sec', 0.8)),
                'fsm_screenshot_settle_sec': float(cfg.get('fsm_screenshot_settle_sec', 0.6)),
                'fsm_tap_bind_clickable': bool(cfg.get('fsm_tap_bind_clickable', False)),
                'fsm_tap_jitter_sigma_px': float(cfg.get('fsm_tap_jitter_sigma_px', 2.0)),
                'fsm_swipe_jitter_sigma_px': float(cfg.get('fsm_swipe_jitter_sigma_px', 4.0)),
                'fsm_swipe_duration_jitter_ratio': float(cfg.get('fsm_swipe_duration_jitter_ratio', 0.12)),
                'fsm_xml_stable_interval_sec': float(cfg.get('fsm_xml_stable_interval_sec', 0.3)),
                'fsm_xml_stable_samples': int(cfg.get('fsm_xml_stable_samples', 4)),
                'fsm_xml_stable_timeout_sec': float(cfg.get('fsm_xml_stable_timeout_sec', 4.0)),
            }
        })
    except Exception as e:
        import traceback
        return jsonify({
            'success': False,
            'message': str(e),
            'traceback': traceback.format_exc()
        }), 500


@app.route('/api/cortex/llm/config', methods=['POST'])
def cortex_llm_config_set():
    """Save Cortex Route Planner LLM config."""
    try:
        data = request.json or {}
        current = _load_cortex_llm_config()

        cfg = {
            'api_base_url': (data.get('api_base_url', current.get('api_base_url')) or '').strip(),
            'api_key': current.get('api_key', ''),
            'model_name': (data.get('model_name', current.get('model_name')) or '').strip(),
            'temperature': float(data.get('temperature', current.get('temperature', 0.1))),
            'timeout': int(data.get('timeout', current.get('timeout', 30))),
            'vision_jpeg_quality': int(data.get('vision_jpeg_quality', current.get('vision_jpeg_quality', 35))),
            'node_exists_retries': int(data.get('node_exists_retries', current.get('node_exists_retries', 3))),
            'node_exists_interval_sec': float(data.get('node_exists_interval_sec', current.get('node_exists_interval_sec', 0.6))),
            'touch_mode': (data.get('touch_mode', current.get('touch_mode', 'shell_first')) or 'shell_first'),
            'map_filepath': (data.get('map_filepath', current.get('map_filepath', '')) or '').strip(),
            'package_name': (data.get('package_name', current.get('package_name', '')) or '').strip(),
            'reconnect_before_run': bool(data.get('reconnect_before_run', current.get('reconnect_before_run', True))),
            'use_llm_planner': bool(data.get('use_llm_planner', current.get('use_llm_planner', True))),
            'route_recovery_enabled': bool(data.get('route_recovery_enabled', current.get('route_recovery_enabled', False))),
            'max_route_restarts': int(data.get('max_route_restarts', current.get('max_route_restarts', 0))),
            'use_vlm_takeover': bool(data.get('use_vlm_takeover', current.get('use_vlm_takeover', False))),
            'fsm_max_turns': int(data.get('fsm_max_turns', current.get('fsm_max_turns', 40))),
            'fsm_max_commands_per_turn': int(data.get('fsm_max_commands_per_turn', current.get('fsm_max_commands_per_turn', 1))),
            'fsm_max_vision_turns': int(data.get('fsm_max_vision_turns', current.get('fsm_max_vision_turns', 20))),
            'fsm_action_interval_sec': float(data.get('fsm_action_interval_sec', current.get('fsm_action_interval_sec', 0.8))),
            'fsm_screenshot_settle_sec': float(data.get('fsm_screenshot_settle_sec', current.get('fsm_screenshot_settle_sec', 0.6))),
            'fsm_tap_bind_clickable': bool(data.get('fsm_tap_bind_clickable', current.get('fsm_tap_bind_clickable', False))),
            'fsm_tap_jitter_sigma_px': float(data.get('fsm_tap_jitter_sigma_px', current.get('fsm_tap_jitter_sigma_px', 2.0))),
            'fsm_swipe_jitter_sigma_px': float(data.get('fsm_swipe_jitter_sigma_px', current.get('fsm_swipe_jitter_sigma_px', 4.0))),
            'fsm_swipe_duration_jitter_ratio': float(data.get('fsm_swipe_duration_jitter_ratio', current.get('fsm_swipe_duration_jitter_ratio', 0.12))),
            'fsm_xml_stable_interval_sec': float(data.get('fsm_xml_stable_interval_sec', current.get('fsm_xml_stable_interval_sec', 0.3))),
            'fsm_xml_stable_samples': int(data.get('fsm_xml_stable_samples', current.get('fsm_xml_stable_samples', 4))),
            'fsm_xml_stable_timeout_sec': float(data.get('fsm_xml_stable_timeout_sec', current.get('fsm_xml_stable_timeout_sec', 4.0))),
        }
        raw_key = data.get('api_key')
        if raw_key and raw_key != '***':
            cfg['api_key'] = raw_key.strip()

        _save_cortex_llm_config(cfg)
        return jsonify({'success': True, 'message': 'LLM config saved'})
    except Exception as e:
        import traceback
        return jsonify({
            'success': False,
            'message': str(e),
            'traceback': traceback.format_exc()
        }), 500


@app.route('/api/cortex/llm/test', methods=['POST'])
def cortex_llm_test():
    """Test Cortex Route Planner LLM connectivity."""
    try:
        cfg = _load_cortex_llm_config()
        data = request.json or {}
        prompt = (data.get('prompt') or 'Return {"package_name":"com.test.app","target_page":"home"}').strip()
        complete = _build_llm_complete(cfg)
        output = complete(prompt)
        return jsonify({
            'success': True,
            'message': f'LLM test ok: {cfg.get("model_name", "")}',
            'response': output[:1200]
        })
    except Exception as e:
        import traceback
        return jsonify({
            'success': False,
            'message': f'LLM test failed: {str(e)}',
            'traceback': traceback.format_exc()
        }), 500


@app.route('/api/cortex/route/run', methods=['POST'])
@app.route('/api/cortex/route_then_act/run', methods=['POST'])
def cortex_route_then_act_run():
    """Run route stage: resolve app -> target_page -> BFS -> device routing."""
    try:
        from src.cortex import RouteThenActCortex, RouteConfig, MapPromptPlanner

        data = request.json or {}
        conn = _resolve_run_connection(data, allow_mobile_auto=False)
        run_client = conn.client
        with conn.lock:
            _prepare_link_for_task(run_client, reconnect=bool(data.get('reconnect_before_run', True)))
        user_task = (data.get('user_task') or '').strip()
        if not user_task:
            return jsonify({'success': False, 'message': 'user_task is required'}), 400

        base_dir = os.path.abspath(os.path.join(os.path.dirname(os.path.dirname(__file__)), 'maps'))
        map_path = (data.get('map_filepath') or '').strip()
        manual_package = (data.get('package_name') or '').strip()

        planner_cfg = _load_cortex_llm_config()
        llm_complete = _build_llm_complete(planner_cfg) if bool(data.get('use_llm_planner', True)) else None

        app_resolution = {
            'mode': 'manual' if manual_package else 'auto',
            'selected_package': manual_package or None,
            'reason': '',
            'candidate_count': 0,
        }

        round1_app = {}
        round2_page = {}

        # Round-1: resolve package if map path not provided
        if not map_path:
            selected_package = manual_package

            if not selected_package:
                with conn.lock:
                    raw_apps = run_client.list_apps('user')
                installed_apps = _normalize_installed_apps(raw_apps)
                map_ready_apps = [a for a in installed_apps if _has_map_for_package(base_dir, a.get('package', ''))]
                app_resolution['candidate_count'] = len(map_ready_apps)

                if not map_ready_apps:
                    return jsonify({'success': False, 'message': 'no installed app with local map found'}), 404

                if llm_complete:
                    picked = _select_package_by_llm(llm_complete, user_task, map_ready_apps)
                    picked_pkg = picked.get('package_name') or ''
                    round1_app = picked
                    exists = any(a.get('package') == picked_pkg for a in map_ready_apps)
                    if exists:
                        selected_package = picked_pkg
                        app_resolution['mode'] = 'llm'
                        app_resolution['selected_package'] = picked_pkg
                        app_resolution['reason'] = picked.get('reason', '')
                    else:
                        # fallback: first candidate
                        selected_package = map_ready_apps[0].get('package')
                        app_resolution['mode'] = 'fallback_first_candidate'
                        app_resolution['selected_package'] = selected_package
                        app_resolution['reason'] = 'llm package invalid or out of candidates'
                else:
                    selected_package = map_ready_apps[0].get('package')
                    app_resolution['mode'] = 'fallback_no_llm'
                    app_resolution['selected_package'] = selected_package
                    app_resolution['reason'] = 'llm planner disabled'

            map_path = _pick_latest_map_file(base_dir, selected_package or None)

        # map path provided -> derive selected package from file path if possible
        if map_path:
            map_path = os.path.abspath(map_path)
            if not map_path.startswith(base_dir):
                return jsonify({'success': False, 'message': 'invalid map_filepath'}), 403
            if not os.path.exists(map_path):
                return jsonify({'success': False, 'message': 'map_filepath not found'}), 404

        cfg = data.get('route_config') or {}
        route_cfg = RouteConfig(
            node_exists_retries=int(cfg.get('node_exists_retries', 3)),
            node_exists_interval_sec=float(cfg.get('node_exists_interval_sec', 0.6)),
            max_route_restarts=int(cfg.get('max_route_restarts', 0)),
            use_vlm_takeover=False,
            vlm_takeover_timeout_sec=float(cfg.get('vlm_takeover_timeout_sec', 15.0)),
            route_recovery_enabled=bool(cfg.get('route_recovery_enabled', False)),
        )

        planner = None
        if llm_complete:
            if map_path:
                # Round-2: for selected app map, infer target_page
                round2_page = _select_target_page_by_llm(llm_complete, user_task, map_path)
                tp = (round2_page.get('target_page') or '').strip()
                if tp:
                    selected_pkg = app_resolution.get('selected_package') or ''
                    planner = _FixedPlanPlanner(selected_pkg, tp)
                else:
                    planner = MapPromptPlanner(llm_complete)
            else:
                planner = MapPromptPlanner(llm_complete)

        logs = []

        def log_callback(payload):
            logs.append(payload)

        engine = RouteThenActCortex(
            client=run_client,
            planner=planner,
            config=route_cfg,
            log_callback=log_callback,
        )

        with conn.lock:
            result = engine.run(
                user_task=user_task,
                map_path=map_path,
                start_page=(data.get('start_page') or None),
                package_name=selected_package or None,
            )

        plan_event = next((x for x in logs if x.get('event') == 'plan_ready'), {})
        route_steps = [
            {
                'index': x.get('step_index'),
                'from_page': x.get('from_page'),
                'to_page': x.get('to_page'),
                'trigger_node': x.get('trigger_node'),
            }
            for x in logs
            if x.get('event') == 'route_step' and x.get('result') == 'start'
        ]

        return jsonify({
            'success': result.get('status') == 'success',
            'map_path': map_path,
            'app_resolution': app_resolution,
            'llm_rounds': {
                'round1_app': round1_app,
                'round2_target_page': round2_page,
            },
            'planner_output': {
                'package_name': plan_event.get('package_name'),
                'target_page': plan_event.get('target_page'),
                'llm_model': planner_cfg.get('model_name') if planner else None,
            },
            'route_steps': route_steps,
            'result': result,
            'logs': logs,
            'connection_id': conn.connection_id,
        })
    except Exception as e:
        import traceback
        return jsonify({
            'success': False,
            'message': str(e),
            'traceback': traceback.format_exc()
        }), 500


def _run_cortex_fsm_logic(data: dict, log_callback, run_client):
    from src.cortex import CortexFSMEngine, FSMConfig, LLMPlanner, RouteConfig

    _prepare_link_for_task(run_client, reconnect=bool(data.get('reconnect_before_run', True)))

    user_task = (data.get('user_task') or '').strip()
    if not user_task:
        raise ValueError('user_task is required')

    base_dir = os.path.abspath(os.path.join(os.path.dirname(os.path.dirname(__file__)), 'maps'))
    map_path = (data.get('map_filepath') or '').strip()
    manual_package = (data.get('package_name') or '').strip()
    use_llm_planner = bool(data.get('use_llm_planner', True))

    planner_cfg = _load_cortex_llm_config()
    llm_complete_json = _build_llm_complete(planner_cfg) if use_llm_planner else None
    llm_complete_fsm = _build_llm_complete_fsm(planner_cfg) if use_llm_planner else None
    llm_complete_with_image = _build_llm_complete_with_image(planner_cfg) if use_llm_planner else None
    llm_task_summary = _build_llm_task_summary(planner_cfg) if use_llm_planner else None

    app_resolution = {
        'mode': 'manual' if manual_package else 'auto',
        'selected_package': manual_package or None,
        'reason': '',
        'candidate_count': 0,
    }
    round1_app = {}

    # Apply touch mode preference for this run.
    touch_mode = str(data.get('touch_mode') or planner_cfg.get('touch_mode') or 'shell_first').strip()
    try:
        run_client.set_touch_mode(shell_first=(touch_mode != 'uiautomation_first'))
    except Exception:
        pass
    screenshot_quality = int(data.get('vision_jpeg_quality') or planner_cfg.get('vision_jpeg_quality') or 35)
    try:
        run_client.set_screenshot_quality(screenshot_quality)
    except Exception:
        pass

    app_candidates_for_fsm = []
    installed_apps = []
    try:
        installed_apps = _normalize_installed_apps(run_client.list_apps('user'))
    except Exception:
        installed_apps = []

    if not map_path:
        selected_package = manual_package
        if not selected_package:
            map_ready_apps = [a for a in installed_apps if _has_map_for_package(base_dir, a.get('package', ''))]
            app_candidates_for_fsm = installed_apps[:] if installed_apps else map_ready_apps[:]
            app_resolution['candidate_count'] = len(map_ready_apps)

            if llm_complete_json:
                try:
                    picked = _select_package_by_llm(llm_complete_json, user_task, installed_apps or map_ready_apps)
                    picked_pkg = picked.get('package_name') or ''
                    round1_app = picked
                    exists = any(a.get('package') == picked_pkg for a in (installed_apps or map_ready_apps))
                except Exception as e:
                    picked = {}
                    picked_pkg = ''
                    exists = False
                    round1_app = {'error': f'llm_timeout_or_error: {e}'}
                if exists:
                    selected_package = picked_pkg
                    app_resolution['mode'] = 'llm'
                    app_resolution['selected_package'] = picked_pkg
                    app_resolution['reason'] = picked.get('reason', '')
                    if not _has_map_for_package(base_dir, selected_package):
                        app_resolution['mode'] = 'llm_no_map'
                        app_resolution['note'] = 'no map for selected app; will use vision-only mode'
                else:
                    fallback_list = map_ready_apps or installed_apps
                    if fallback_list:
                        selected_package = fallback_list[0].get('package')
                        app_resolution['mode'] = 'fallback_first_candidate'
                        app_resolution['selected_package'] = selected_package
                        app_resolution['reason'] = 'llm timeout/invalid package, fallback to first candidate'
                    else:
                        raise RuntimeError('no installed apps found')
            else:
                fallback_list = map_ready_apps or installed_apps
                if fallback_list:
                    selected_package = fallback_list[0].get('package')
                    app_resolution['mode'] = 'fallback_no_llm'
                    app_resolution['selected_package'] = selected_package
                    app_resolution['reason'] = 'llm planner disabled'
                else:
                    raise RuntimeError('no installed apps found')
        else:
            app_candidates_for_fsm = [{'package': selected_package, 'name': _infer_app_name_from_package(selected_package)}]

        map_path = _pick_latest_map_file(base_dir, selected_package or None)
        # map_path may be None; engine handles no-map by going straight to VISION_ACT
    else:
        map_path = os.path.abspath(map_path)
        if not map_path.startswith(base_dir):
            raise RuntimeError('invalid map_filepath')
        if not os.path.exists(map_path):
            raise RuntimeError('map_filepath not found')
        if manual_package:
            app_candidates_for_fsm = [{'package': manual_package, 'name': _infer_app_name_from_package(manual_package)}]
        else:
            app_candidates_for_fsm = []

    cfg = data.get('route_config') or {}
    route_cfg = RouteConfig(
        node_exists_retries=int(cfg.get('node_exists_retries', 3)),
        node_exists_interval_sec=float(cfg.get('node_exists_interval_sec', 0.6)),
        max_route_restarts=int(cfg.get('max_route_restarts', 0)),
        use_vlm_takeover=bool(cfg.get('use_vlm_takeover', False)),
        vlm_takeover_timeout_sec=float(cfg.get('vlm_takeover_timeout_sec', 15.0)),
        route_recovery_enabled=bool(cfg.get('route_recovery_enabled', False)),
    )
    fsm_cfg_data = data.get('fsm_config') or {}
    fsm_cfg = FSMConfig(
        max_turns=int(fsm_cfg_data.get('max_turns', 30)),
        max_commands_per_turn=int(fsm_cfg_data.get('max_commands_per_turn', 1)),
        max_vision_turns=int(fsm_cfg_data.get('max_vision_turns', 20)),
        action_interval_sec=float(fsm_cfg_data.get('action_interval_sec', 0.8)),
        screenshot_settle_sec=float(fsm_cfg_data.get('screenshot_settle_sec', 0.6)),
        tap_bind_clickable=bool(fsm_cfg_data.get('tap_bind_clickable', False)),
        tap_jitter_sigma_px=float(fsm_cfg_data.get('tap_jitter_sigma_px', 2.0)),
        swipe_jitter_sigma_px=float(fsm_cfg_data.get('swipe_jitter_sigma_px', 4.0)),
        swipe_duration_jitter_ratio=float(fsm_cfg_data.get('swipe_duration_jitter_ratio', 0.12)),
        xml_stable_interval_sec=float(fsm_cfg_data.get('xml_stable_interval_sec', 0.3)),
        xml_stable_samples=int(fsm_cfg_data.get('xml_stable_samples', 4)),
        xml_stable_timeout_sec=float(fsm_cfg_data.get('xml_stable_timeout_sec', 4.0)),
    )

    llm_planner = LLMPlanner(llm_complete_fsm, llm_complete_with_image) if llm_complete_fsm else None
    selected_package = app_resolution.get('selected_package') or ''
    planner = _FSMPlannerBridge(selected_package=selected_package, llm_planner=llm_planner)

    logs = []
    def _log(payload):
        logs.append(payload)
        if log_callback:
            log_callback(payload)

    engine = CortexFSMEngine(
        client=run_client,
        planner=planner,
        route_config=route_cfg,
        fsm_config=fsm_cfg,
        log_callback=_log,
    )
    result = engine.run(
        user_task=user_task,
        map_path=map_path,
        start_page=(data.get('start_page') or None),
        package_name=selected_package or None,
        extra_context={
            'app_candidates': (installed_apps or app_candidates_for_fsm),
            'page_candidates': _build_page_candidates_from_map(map_path) if map_path else [],
        },
    )
    ok = result.get('status') == 'success'
    task_summary = ''
    try:
        if llm_task_summary:
            task_summary = llm_task_summary(user_task, result)
    except Exception:
        task_summary = ''
    if not task_summary:
        task_summary = _fallback_task_summary(user_task, result)
    result['task_summary'] = task_summary
    return {
        'success': ok,
        'message': None if ok else (result.get('reason') or f"fsm_failed@{result.get('state', 'UNKNOWN')}"),
        'task_summary': task_summary,
        'map_path': map_path,
        'app_resolution': app_resolution,
        'llm_rounds': {'round1_app': round1_app},
        'result': result,
        'logs': logs,
    }


@app.route('/api/cortex/fsm/run', methods=['POST'])
def cortex_fsm_run():
    data = request.json or {}
    try:
        conn = _resolve_run_connection(data, allow_mobile_auto=True)
        with conn.lock:
            payload = _run_cortex_fsm_logic(data, log_callback=None, run_client=conn.client)
        payload['connection_id'] = conn.connection_id
        return jsonify(payload)
    except Exception as e:
        import traceback
        return jsonify({'success': False, 'message': str(e), 'traceback': traceback.format_exc()}), 500


@app.route('/api/cortex/fsm/start', methods=['POST'])
def cortex_fsm_start():
    data = request.json or {}
    try:
        conn = _resolve_run_connection(data, allow_mobile_auto=True)
    except Exception as e:
        return jsonify({'success': False, 'message': str(e)}), 400
    task_id = _task_create('cortex_fsm', connection_id=conn.connection_id, user_task=(data.get('user_task') or '').strip())
    with CONNECTIONS_LOCK:
        rec = CONNECTIONS.get(conn.connection_id)
        if rec:
            rec.running_tasks += 1
    _sync_connection_info()

    def _runner():
        try:
            def _log_with_cancel(e):
                if _task_is_cancel_requested(task_id):
                    raise RuntimeError('task_cancelled')
                _task_append(task_id, e)

            with conn.lock:
                payload = _run_cortex_fsm_logic(data, log_callback=_log_with_cancel, run_client=conn.client)
            payload['connection_id'] = conn.connection_id
            if _task_is_cancel_requested(task_id):
                _task_finish(task_id, False, payload, 'task_cancelled')
                return
            _task_finish(task_id, bool(payload.get('success')), payload, payload.get('message') or '')
        except Exception as e:
            _task_finish(task_id, False, {'success': False, 'message': str(e)}, str(e))
        finally:
            with CONNECTIONS_LOCK:
                rec = CONNECTIONS.get(conn.connection_id)
                if rec and rec.running_tasks > 0:
                    rec.running_tasks -= 1
            _sync_connection_info()

    threading.Thread(target=_runner, daemon=True).start()
    return jsonify({'success': True, 'task_id': task_id, 'connection_id': conn.connection_id})


@app.route('/api/cortex/task/<task_id>/poll', methods=['GET'])
def cortex_task_poll(task_id):
    cursor = int(request.args.get('cursor', '0'))
    with TASKS_LOCK:
        t = TASKS.get(task_id)
        if not t:
            return jsonify({'success': False, 'message': 'task_not_found'}), 404
        events = t['events'][cursor:]
        next_cursor = cursor + len(events)
        return jsonify({
            'success': True,
            'task_id': task_id,
            'connection_id': t.get('connection_id', ''),
            'status': t.get('status', 'created'),
            'created_at': t.get('created_at'),
            'started_at': t.get('started_at'),
            'ended_at': t.get('ended_at'),
            'events': events,
            'next_cursor': next_cursor,
            'done': bool(t['done']),
            'task_success': bool(t['success']),
            'cancel_requested': bool(t.get('cancel_requested')),
            'result': t['result'] if t['done'] else None,
            'message': t['message'] if t['done'] else '',
            'log_file': t.get('log_file', ''),
        })


@app.route('/api/cortex/task/<task_id>/cancel', methods=['POST'])
def cortex_task_cancel(task_id):
    with TASKS_LOCK:
        t = TASKS.get(task_id)
        if not t:
            return jsonify({'success': False, 'message': 'task_not_found'}), 404
        if t.get('done'):
            return jsonify({'success': True, 'message': 'task_already_done'})
        t['cancel_requested'] = True
    return jsonify({'success': True, 'message': 'cancel_requested', 'task_id': task_id})


@app.route('/api/tasks/list', methods=['GET'])
def tasks_list():
    connection_id = (request.args.get('connection_id') or '').strip()
    status_filter = (request.args.get('status') or '').strip().lower()
    with TASKS_LOCK:
        rows = []
        for t in TASKS.values():
            if connection_id and t.get('connection_id') != connection_id:
                continue
            st = (t.get('status') or '').lower()
            if status_filter and st != status_filter:
                continue
            rows.append({
                'task_id': t.get('task_id'),
                'type': t.get('type'),
                'connection_id': t.get('connection_id'),
                'status': t.get('status'),
                'success': bool(t.get('success')),
                'done': bool(t.get('done')),
                'message': t.get('message') or '',
                'user_task': t.get('user_task') or '',
                'created_at': t.get('created_at'),
                'started_at': t.get('started_at'),
                'ended_at': t.get('ended_at'),
                'event_count': len(t.get('events') or []),
                'log_file': t.get('log_file') or '',
            })
    rows.sort(key=lambda x: x.get('created_at') or '', reverse=True)
    running = sum(1 for x in rows if x.get('status') == 'running')
    return jsonify({'success': True, 'running': running, 'count': len(rows), 'data': rows})


@app.route('/api/tasks/<task_id>/logs', methods=['GET'])
def task_logs(task_id):
    with TASKS_LOCK:
        t = TASKS.get(task_id)
        if not t:
            return jsonify({'success': False, 'message': 'task_not_found'}), 404
        log_file = t.get('log_file') or ''
        in_memory_events = list(t.get('events') or [])
    if log_file and os.path.exists(log_file):
        events = []
        try:
            with open(log_file, 'r', encoding='utf-8') as f:
                for line in f:
                    line = line.strip()
                    if not line:
                        continue
                    events.append(json.loads(line))
        except Exception:
            events = in_memory_events
    else:
        events = in_memory_events
    return jsonify({'success': True, 'task_id': task_id, 'log_file': log_file, 'events': events})


def _pick_latest_map_file(base_dir: str, package_name: str = None) -> str:
    """Pick latest nav_map_*.json, optionally constrained by package."""
    import os
    import glob

    all_maps = []

    if package_name:
        pkg_dir = package_name.replace('.', '_')
        pkg_path = os.path.join(base_dir, pkg_dir)
        if os.path.isdir(pkg_path):
            pattern = os.path.join(pkg_path, 'nav_map_*.json')
            for filepath in glob.glob(pattern):
                all_maps.append((os.path.getmtime(filepath), filepath))
    else:
        if os.path.isdir(base_dir):
            for pkg_dir in os.listdir(base_dir):
                pkg_path = os.path.join(base_dir, pkg_dir)
                if not os.path.isdir(pkg_path):
                    continue
                pattern = os.path.join(pkg_path, 'nav_map_*.json')
                for filepath in glob.glob(pattern):
                    all_maps.append((os.path.getmtime(filepath), filepath))

    if not all_maps:
        return ""
    all_maps.sort(key=lambda x: x[0], reverse=True)
    return all_maps[0][1]


@app.route('/api/explore/node/save', methods=['POST'])
def explore_node_save():
    """保存 Node 驱动探索结果"""
    global explorer_instance, exploration_status

    if not explorer_instance:
        return jsonify({'success': False, 'message': '没有探索结果'}), 400

    # 检查是否是 NodeMapBuilder
    if exploration_status.get('version') != 'node':
        return jsonify({'success': False, 'message': '当前不是 Node 驱动探索结果'}), 400

    try:
        import os
        from datetime import datetime

        # 获取包名
        package_name = exploration_status.get('package', 'unknown')

        # 创建保存目录: maps/{package_name}/
        base_dir = os.path.join(os.path.dirname(os.path.dirname(__file__)), 'maps')
        save_dir = os.path.join(base_dir, package_name.replace('.', '_'))
        os.makedirs(save_dir, exist_ok=True)

        # 生成文件名（带时间戳）
        timestamp = datetime.now().strftime('%Y%m%d_%H%M%S')
        filename = f'nav_map_{timestamp}.json'
        filepath = os.path.join(save_dir, filename)

        # 保存
        explorer_instance.save(filepath)

        return jsonify({
            'success': True,
            'message': f'已保存到 {filepath}',
            'filepath': filepath
        })

    except Exception as e:
        import traceback
        return jsonify({
            'success': False,
            'message': str(e),
            'traceback': traceback.format_exc()
        }), 500


@app.route('/api/debug/som_annotate', methods=['POST'])
def debug_som_annotate():
    """调试：获取 SoM 标注截图"""
    global client

    error_response = _require_client_response()
    if error_response:
        return error_response

    if not VLM_AVAILABLE:
        return jsonify({'success': False, 'message': 'VLM 模块不可用'}), 400

    try:
        import time

        # 获取屏幕尺寸
        success, width, height, _ = client.get_screen_size()
        if not success:
            width, height = 1080, 2400

        # 获取截图
        screenshot = client.request_screenshot()
        if not screenshot:
            return jsonify({'success': False, 'message': '截图失败'}), 400

        # 获取 XML 节点
        actions = client.dump_actions()
        xml_nodes = actions.get('nodes', [])

        # 创建标注截图
        start = time.time()
        annotated, nodes, description = create_annotated_screenshot(
            screenshot, xml_nodes, width, height
        )
        elapsed = (time.time() - start) * 1000

        # 返回结果
        return jsonify({
            'success': True,
            'message': f'标注完成: {len(nodes)} 个节点',
            'response': {
                'node_count': len(nodes),
                'original_count': len(xml_nodes),
                'annotate_time_ms': round(elapsed, 2),
                'annotated_image': base64.b64encode(annotated).decode(),
                'original_image': base64.b64encode(screenshot).decode(),
                'node_description': description,
                'nodes': [
                    {
                        'index': n.index,
                        'bounds': list(n.bounds),
                        'center': list(n.center),
                        'text': n.text,
                        'resource_id': n.resource_id,
                        'node_type': n.node_type
                    }
                    for n in nodes
                ]
            }
        })

    except Exception as e:
        import traceback
        return jsonify({
            'success': False,
            'message': str(e),
            'traceback': traceback.format_exc()
        }), 500


@app.route('/api/explore/screenshot/<path:filename>', methods=['GET'])
def explore_screenshot(filename):
    """获取探索过程中保存的截图"""
    import os

    # 安全检查：只允许访问 maps 目录下的文件
    base_dir = os.path.abspath('./maps')
    file_path = os.path.abspath(os.path.join(base_dir, filename))

    if not file_path.startswith(base_dir):
        return jsonify({'success': False, 'message': '非法路径'}), 403

    if not os.path.exists(file_path):
        return jsonify({'success': False, 'message': '文件不存在'}), 404

    return Response(
        open(file_path, 'rb').read(),
        mimetype='image/jpeg'
    )


@app.route('/api/explore/result/overview', methods=['GET'])
def explore_result_overview():
    """获取探索结果 - app_overview.json"""
    global explorer_instance, exploration_result

    if not exploration_result:
        return jsonify({'success': False, 'message': '没有探索结果'}), 400

    try:
        overview = explorer_instance.generate_overview_json()
        return jsonify({
            'success': True,
            'data': overview
        })
    except Exception as e:
        import traceback
        return jsonify({
            'success': False,
            'message': str(e),
            'traceback': traceback.format_exc()
        }), 500


@app.route('/api/explore/result/pages', methods=['GET'])
def explore_result_pages():
    """获取所有页面列表"""
    global exploration_result

    if not exploration_result:
        return jsonify({'success': False, 'message': '没有探索结果'}), 400

    try:
        pages_summary = []
        for page_id, page in exploration_result.pages.items():
            pages_summary.append({
                'page_id': page_id,
                'activity': page.activity,
                'description': page.page_description,
                'node_count': len(page.nodes),
                'clickable_count': len(page.clickable_nodes)
            })

        return jsonify({
            'success': True,
            'data': pages_summary
        })
    except Exception as e:
        return jsonify({'success': False, 'message': str(e)}), 500


@app.route('/api/explore/result/page/<page_id>', methods=['GET'])
def explore_result_page(page_id):
    """获取指定页面详情"""
    global exploration_result

    if not exploration_result:
        return jsonify({'success': False, 'message': '没有探索结果'}), 400

    if page_id not in exploration_result.pages:
        return jsonify({
            'success': False,
            'message': f'页面 {page_id} 不存在',
            'available': list(exploration_result.pages.keys())
        }), 404

    try:
        page = exploration_result.pages[page_id]
        nodes_data = []
        for node in page.nodes:
            nodes_data.append({
                'node_id': node.node_id,
                'bounds': list(node.bounds),
                'center': list(node.center),
                'class_name': node.class_name,
                'text': node.text,
                'resource_id': node.resource_id,
                'clickable': node.clickable,
                'editable': node.editable,
                'scrollable': node.scrollable,
                'vlm_label': node.vlm_label,
                'vlm_ocr_text': node.vlm_ocr_text,
                'iou_score': node.iou_score
            })

        return jsonify({
            'success': True,
            'data': {
                'page_id': page.page_id,
                'activity': page.activity,
                'package': page.package,
                'description': page.page_description,
                'structure_hash': page.structure_hash,
                'nodes': nodes_data
            }
        })
    except Exception as e:
        return jsonify({'success': False, 'message': str(e)}), 500


@app.route('/api/explore/save', methods=['POST'])
def explore_save():
    """保存探索结果到文件"""
    global explorer_instance

    if not explorer_instance:
        return jsonify({'success': False, 'message': '没有探索结果'}), 400

    data = request.json or {}
    output_dir = data.get('output_dir', './maps')

    try:
        explorer_instance.save(output_dir)

        return jsonify({
            'success': True,
            'message': f'已保存到 {output_dir}',
            'path': output_dir
        })
    except Exception as e:
        import traceback
        return jsonify({
            'success': False,
            'message': str(e),
            'traceback': traceback.format_exc()
        }), 500


# =============================================================================
# VLM 调试 API
# =============================================================================

@app.route('/api/vlm/config', methods=['GET'])
def vlm_config_get():
    """获取 VLM 配置"""
    if not VLM_AVAILABLE:
        return jsonify({'success': False, 'message': 'VLM 模块不可用'}), 400

    try:
        from src.auto_map_builder.vlm_engine import get_config as get_vlm_config
        config = get_vlm_config()
        return jsonify({
            'success': True,
            'data': {
                'api_base_url': config.api_base_url,
                'api_key': '***' if config.api_key else '',  # 隐藏 API Key
                'model_name': config.model_name,
                'enable_od': config.enable_od,
                'enable_ocr': config.enable_ocr,
                'enable_caption': config.enable_caption,
                'timeout': config.timeout,
                # 并发配置
                'concurrent_enabled': config.concurrent_enabled,
                'concurrent_requests': config.concurrent_requests,
                'occurrence_threshold': config.occurrence_threshold or 2
            }
        })
    except Exception as e:
        return jsonify({'success': False, 'message': str(e)}), 500


@app.route('/api/vlm/config', methods=['POST'])
def vlm_config_set():
    """设置 VLM 配置"""
    if not VLM_AVAILABLE:
        return jsonify({'success': False, 'message': 'VLM 模块不可用'}), 400

    try:
        from src.auto_map_builder.vlm_engine import VLMConfig, set_config, get_config

        data = request.json
        current_config = get_config()

        # 创建新配置，保留未修改的字段
        new_config = VLMConfig(
            api_base_url=data.get('api_base_url', current_config.api_base_url),
            api_key=data.get('api_key', current_config.api_key) if data.get('api_key') != '***' else current_config.api_key,
            model_name=data.get('model_name', current_config.model_name),
            enable_od=data.get('enable_od', current_config.enable_od),
            enable_ocr=data.get('enable_ocr', current_config.enable_ocr),
            enable_caption=data.get('enable_caption', current_config.enable_caption),
            timeout=data.get('timeout', current_config.timeout),
            # 并发配置
            concurrent_enabled=data.get('concurrent_enabled', current_config.concurrent_enabled),
            concurrent_requests=data.get('concurrent_requests', current_config.concurrent_requests),
            occurrence_threshold=data.get('occurrence_threshold', current_config.occurrence_threshold or 2)
        )

        set_config(new_config)

        return jsonify({
            'success': True,
            'message': 'VLM 配置已更新'
        })
    except Exception as e:
        return jsonify({'success': False, 'message': str(e)}), 500


@app.route('/api/vlm/test', methods=['POST'])
def vlm_test():
    """测试 VLM API 连接"""
    if not VLM_AVAILABLE:
        return jsonify({'success': False, 'message': 'VLM 模块不可用'}), 400

    try:
        from src.auto_map_builder.vlm_engine import VLMConfig, VLMEngine
        import base64

        data = request.json
        api_url = data.get('api_base_url', '')
        api_key = data.get('api_key', '')
        model_name = data.get('model_name', 'qwen-vl-plus')

        if not api_url or not api_key:
            return jsonify({'success': False, 'message': '请提供 API URL 和 API Key'}), 400

        # 创建临时配置
        test_config = VLMConfig(
            api_base_url=api_url,
            api_key=api_key,
            model_name=model_name,
            timeout=30
        )

        # 创建引擎并测试
        engine = VLMEngine(test_config)

        # 创建一个简单的测试图片 (1x1 红色像素)
        from PIL import Image
        from io import BytesIO
        img = Image.new('RGB', (100, 100), color='red')
        buffer = BytesIO()
        img.save(buffer, format='PNG')
        test_image = buffer.getvalue()

        # 调用 API
        response = engine._call_api(test_image, "这是什么颜色的图片？请简短回答。")

        return jsonify({
            'success': True,
            'message': f'API 连接成功，模型: {model_name}',
            'response': response[:500] if response else ''
        })

    except Exception as e:
        import traceback
        return jsonify({
            'success': False,
            'message': f'API 测试失败: {str(e)}',
            'traceback': traceback.format_exc()
        }), 500


@app.route('/api/debug/vlm_status', methods=['GET', 'POST'])
def debug_vlm_status():
    """检测 VLM 可用性"""
    try:
        status = {
            'available': VLM_AVAILABLE,
            'message': 'VLM 模块可用' if VLM_AVAILABLE else 'VLM 模块不可用'
        }

        if VLM_AVAILABLE:
            try:
                from src.auto_map_builder.vlm_engine import get_config as get_vlm_config
                config = get_vlm_config()
                engine = VLMEngine()
                status['model_available'] = engine.is_available()
                status['model_name'] = config.model_name
                status['api_configured'] = bool(config.api_base_url and config.api_key)
            except Exception as e:
                status['model_available'] = False
                status['error'] = str(e)

        return jsonify({'success': True, 'data': status})

    except Exception as e:
        return jsonify({'success': False, 'message': str(e)}), 500


@app.route('/api/debug/vlm_test', methods=['POST'])
def debug_vlm_test():
    """测试 VLM 推理"""
    global client

    error_response = _require_client_response()
    if error_response:
        return error_response

    if not VLM_AVAILABLE:
        return jsonify({'success': False, 'message': 'VLM 模块不可用'}), 400

    try:
        import time

        # 获取截图
        screenshot = client.request_screenshot()
        if not screenshot:
            return jsonify({'success': False, 'message': '截图失败'}), 400

        # VLM 推理
        engine = VLMEngine()
        start = time.time()
        result = engine.infer(screenshot)
        elapsed = (time.time() - start) * 1000

        # 序列化结果
        detections = []
        for det in result.detections:
            detections.append({
                'label': det.label,
                'bbox': list(det.bbox),
                'ocr_text': det.ocr_text
            })

        return jsonify({
            'success': True,
            'message': f'VLM 推理成功: {len(detections)} 个检测, {elapsed:.0f}ms',
            'response': {
                'page_caption': result.page_caption,
                'detections': detections,
                'inference_time_ms': elapsed
            }
        })

    except Exception as e:
        import traceback
        return jsonify({
            'success': False,
            'message': str(e),
            'traceback': traceback.format_exc()
        }), 500


@app.route('/api/debug/analyze_page', methods=['POST'])
def debug_analyze_page():
    """分析当前页面 (VLM + XML 融合)"""
    global client

    error_response = _require_client_response()
    if error_response:
        return error_response

    if not VLM_AVAILABLE:
        return jsonify({'success': False, 'message': 'VLM 模块不可用'}), 400

    try:
        import time

        # 获取基础信息
        success, package, activity = client.get_activity()
        if not success:
            return jsonify({'success': False, 'message': '获取 Activity 失败'}), 400

        # 先获取 XML 节点（确保和截图是同一页面）
        actions = client.dump_actions()
        raw_nodes = actions.get('nodes', [])

        # 短暂延迟确保同步
        time.sleep(0.1)

        # 获取截图
        screenshot = client.request_screenshot()

        # VLM 推理 - 使用并发推理（如果已配置）
        vlm_engine = VLMEngine()
        start = time.time()
        vlm_result = vlm_engine.infer_concurrent(screenshot)
        vlm_time = (time.time() - start) * 1000

        # XML 解析
        xml_nodes = parse_xml_nodes(raw_nodes)

        # 融合 - 降低阈值便于调试
        fusion_engine = FusionEngine(iou_threshold=0.2)
        fused_nodes = fusion_engine.fuse(xml_nodes, vlm_result)

        # 计算哈希
        page_manager = PageManager()
        structure_hash = page_manager.compute_structure_hash(fused_nodes)
        page_id = page_manager.generate_page_id(activity, structure_hash)

        # 序列化融合结果
        nodes_data = []
        for node in fused_nodes:
            nodes_data.append({
                'node_id': node.node_id,
                'bounds': list(node.bounds),
                'class_name': node.class_name.split('.')[-1],
                'text': node.text[:100] if node.text else '',
                'resource_id': node.resource_id,
                'content_desc': node.content_desc,
                'clickable': node.clickable,
                'editable': node.editable,
                'vlm_label': node.vlm_label,
                'vlm_ocr_text': node.vlm_ocr_text,
                'iou_score': round(node.iou_score, 3)
            })

        # VLM 原始检测结果（用于调试）
        vlm_raw = []
        for det in vlm_result.detections[:20]:  # 只返回前 20 个
            vlm_raw.append({
                'label': det.label,
                'bbox': list(det.bbox),
                'text': det.ocr_text
            })

        # 获取融合统计
        fusion_stats = fusion_engine.get_stats()

        # XML 节点样本（用于调试）
        xml_sample = []
        for node in xml_nodes[:5]:
            xml_sample.append({
                'bounds': list(node.bounds),
                'class': node.class_name.split('.')[-1],
                'text': node.text[:30] if node.text else '',
                'resource_id': node.resource_id
            })

        return jsonify({
            'success': True,
            'message': f'分析完成: VLM 检测 {len(vlm_result.detections)} 个, 匹配 {len(fused_nodes)} 个',
            'response': {
                'page_id': page_id,
                'activity': activity,
                'package': package,
                'page_description': vlm_result.page_caption,
                'structure_hash': structure_hash,
                'vlm_time_ms': round(vlm_time, 2),
                'node_count': len(fused_nodes),
                'clickable_count': len([n for n in fused_nodes if n.clickable]),
                'image_size': list(vlm_result.image_size),
                # 并发推理信息
                'concurrent_enabled': vlm_result.concurrent_enabled,
                'concurrent_requests': vlm_result.concurrent_requests,
                'concurrent_results': vlm_result.concurrent_results,
                'aggregated_count': vlm_result.aggregated_count,
                'fusion_stats': {
                    'vlm_detections': len(vlm_result.detections),
                    'xml_nodes': fusion_stats.get("total_xml_nodes", 0),
                    'matched': fusion_stats.get("matched_count", 0),
                    'unmatched_vlm': fusion_stats.get("unmatched_vlm", 0)
                },
                'vlm_raw': vlm_raw,  # VLM 原始检测（调试用）
                'xml_sample': xml_sample,  # XML 节点样本（调试用）
                'nodes': nodes_data
            }
        })

    except Exception as e:
        import traceback
        return jsonify({
            'success': False,
            'message': str(e),
            'traceback': traceback.format_exc()
        }), 500


if __name__ == '__main__':
    print("=" * 60)
    print("LXB Web Console")
    print("=" * 60)
    print("访问地址: http://localhost:5000")
    if VLM_AVAILABLE:
        print("VLM 模块: 可用")
    else:
        print("VLM 模块: 不可用 (需要安装 torch, transformers)")
    print("按 Ctrl+C 停止服务")
    print("=" * 60)

    app.run(host='0.0.0.0', port=5000, debug=True, use_reloader=False)

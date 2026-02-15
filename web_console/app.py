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
    CMD_HEARTBEAT,
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
    'port': None
}

CORTEX_LLM_CONFIG_FILE = os.path.abspath(
    os.path.join(os.path.dirname(os.path.dirname(__file__)), '.cortex_llm_planner.json')
)


def _default_cortex_llm_config() -> dict:
    return {
        'api_base_url': os.getenv('CORTEX_LLM_API_BASE_URL', ''),
        'api_key': os.getenv('CORTEX_LLM_API_KEY', ''),
        'model_name': os.getenv('CORTEX_LLM_MODEL_NAME', 'qwen-plus'),
        'temperature': float(os.getenv('CORTEX_LLM_TEMPERATURE', '0.1')),
        'timeout': int(os.getenv('CORTEX_LLM_TIMEOUT', '30')),
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


class _FixedPlanPlanner:
    def __init__(self, package_name: str, target_page: str):
        self.package_name = package_name
        self.target_page = target_page

    def plan(self, user_task, route_map):
        from src.cortex import RoutePlan
        pkg = self.package_name or route_map.package
        return RoutePlan(pkg, self.target_page)


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
    """连接到设备"""
    global client, connection_info

    data = request.json
    host = data.get('host', '192.168.1.100')  # 默认 WiFi 地址
    port = data.get('port', 12345)

    try:
        # 断开旧连接
        if client:
            try:
                client.disconnect()
            except:
                pass

        # 创建新连接
        client = LXBLinkClient(host, port, timeout=2.0)
        client.connect()

        # 尝试握手
        client.handshake()

        connection_info = {
            'connected': True,
            'host': host,
            'port': port
        }

        return jsonify({
            'success': True,
            'message': f'成功连接到 {host}:{port}'
        })

    except Exception as e:
        connection_info['connected'] = False
        return jsonify({
            'success': False,
            'message': f'连接失败: {str(e)}'
        }), 500


@app.route('/api/disconnect', methods=['POST'])
def disconnect():
    """断开连接"""
    global client, connection_info

    try:
        if client:
            client.disconnect()
            client = None

        connection_info['connected'] = False

        return jsonify({
            'success': True,
            'message': '已断开连接'
        })
    except Exception as e:
        return jsonify({
            'success': False,
            'message': f'断开失败: {str(e)}'
        }), 500


@app.route('/api/status', methods=['GET'])
def status():
    """获取连接状态"""
    return jsonify(connection_info)


# =============================================================================
# Link Layer (0x00-0x0F)
# =============================================================================

@app.route('/api/command/handshake', methods=['POST'])
def cmd_handshake():
    """发送握手命令"""
    if not client:
        return jsonify({'success': False, 'message': '未连接'}), 400

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
    if not client:
        return jsonify({'success': False, 'message': '未连接'}), 400

    try:
        response = client._transport.send_reliable(CMD_HEARTBEAT, b'')
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
    if not client:
        return jsonify({'success': False, 'message': '未连接'}), 400

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
    if not client:
        return jsonify({'success': False, 'message': '未连接'}), 400

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
    if not client:
        return jsonify({'success': False, 'message': '未连接'}), 400

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
    if not client:
        return jsonify({'success': False, 'message': '未连接'}), 400

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
    if not client:
        return jsonify({'success': False, 'message': '未连接'}), 400

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
    if not client:
        return jsonify({'success': False, 'message': '未连接'}), 400

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
    if not client:
        return jsonify({'success': False, 'message': '未连接'}), 400

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
    if not client:
        return jsonify({'success': False, 'message': '未连接'}), 400

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
    if not client:
        return jsonify({'success': False, 'message': '未连接'}), 400

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


@app.route('/api/command/find_node', methods=['POST'])
def cmd_find_node():
    """发送 FIND_NODE 命令"""
    if not client:
        return jsonify({'success': False, 'message': '未连接'}), 400

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
    if not client:
        return jsonify({'success': False, 'message': '未连接'}), 400

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
    if not client:
        return jsonify({'success': False, 'message': '未连接'}), 400

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
    if not client:
        return jsonify({'success': False, 'message': '未连接'}), 400

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
    if not client:
        return jsonify({'success': False, 'message': '未连接'}), 400

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
    if not client:
        return jsonify({'success': False, 'message': '???'}), 400

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
    if not client:
        return jsonify({'success': False, 'message': '未连接'}), 400

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
        AutoMapBuilder, SemanticMapBuilder, SoMMapBuilder, CoordMapBuilder,
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
explorer_instance = None  # v2: AutoMapBuilder, v3: SemanticMapBuilder
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


@app.route('/api/explore/start', methods=['POST'])
def explore_start():
    """启动应用探索 (v2 VLM+XML 融合)"""
    global client, explorer_instance, exploration_result, exploration_status

    if not client:
        return jsonify({'success': False, 'message': '未连接设备'}), 400

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
    """启动 v3 语义探索"""
    global client, explorer_instance, exploration_result, exploration_status

    if not client:
        return jsonify({'success': False, 'message': '未连接设备'}), 400

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
    """启动 SoM 探索（推荐）"""
    global client, explorer_instance, exploration_result, exploration_status

    if not client:
        return jsonify({'success': False, 'message': '未连接设备'}), 400

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
    """启动坐标驱动探索 (v4 推荐)"""
    global client, explorer_instance, exploration_result, exploration_status

    if not client:
        return jsonify({'success': False, 'message': '未连接设备'}), 400

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

    if not client:
        return jsonify({'success': False, 'message': '未连接设备'}), 400

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
    global client

    if not client:
        return jsonify({'success': False, 'message': 'device not connected'}), 400

    try:
        from src.cortex import RouteThenActCortex, RouteConfig, MapPromptPlanner

        data = request.json or {}
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
                raw_apps = client.list_apps('user')
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
            if not map_path:
                return jsonify({'success': False, 'message': f'no map file found for package: {selected_package}'}), 404

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
            locator_score_threshold=float(cfg.get('locator_score_threshold', 45.0)),
            locator_ambiguity_delta=float(cfg.get('locator_ambiguity_delta', 8.0)),
            hint_distance_limit_px=float(cfg.get('hint_distance_limit_px', 520.0)),
        )

        planner = None
        if llm_complete:
            # Round-2: for selected app map, infer target_page
            round2_page = _select_target_page_by_llm(llm_complete, user_task, map_path)
            tp = (round2_page.get('target_page') or '').strip()
            if tp:
                selected_pkg = app_resolution.get('selected_package') or ''
                planner = _FixedPlanPlanner(selected_pkg, tp)
            else:
                # Fallback: keep previous planner behavior if round-2 parse failed
                planner = MapPromptPlanner(llm_complete)

        logs = []

        def log_callback(payload):
            logs.append(payload)

        engine = RouteThenActCortex(
            client=client,
            planner=planner,
            config=route_cfg,
            log_callback=log_callback,
        )

        result = engine.run(
            user_task=user_task,
            map_path=map_path,
            start_page=(data.get('start_page') or None),
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
        })
    except Exception as e:
        import traceback
        return jsonify({
            'success': False,
            'message': str(e),
            'traceback': traceback.format_exc()
        }), 500
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

    if not client:
        return jsonify({'success': False, 'message': '未连接设备'}), 400

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

    if not client:
        return jsonify({'success': False, 'message': '未连接设备'}), 400

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

    if not client:
        return jsonify({'success': False, 'message': '未连接设备'}), 400

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

    app.run(host='0.0.0.0', port=5000, debug=True)

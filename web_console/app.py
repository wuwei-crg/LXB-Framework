"""
LXB Web Console - Flask Backend
用于可视化调试 LXB-Link 协议的 Web 控制台
"""

from flask import Flask, render_template, request, jsonify, Response
from flask_cors import CORS
import sys
import os
import base64

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


@app.route('/')
def index():
    """主页"""
    return render_template('index.html')


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


if __name__ == '__main__':
    print("=" * 60)
    print("LXB Web Console")
    print("=" * 60)
    print("访问地址: http://localhost:5000")
    print("按 Ctrl+C 停止服务")
    print("=" * 60)

    app.run(host='0.0.0.0', port=5000, debug=True)

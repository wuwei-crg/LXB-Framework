"""
LXB Web Console - Flask Backend
用于可视化调试 LXB-Link 协议的 Web 控制台
"""

from flask import Flask, render_template, request, jsonify
from flask_cors import CORS
import sys
import os

# 添加项目根目录到 Python 路径
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), '..')))

from src.lxb_link.client import LXBLinkClient
from src.lxb_link.constants import *

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
    host = data.get('host', '127.0.0.1')
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


@app.route('/api/command/get_activity', methods=['POST'])
def cmd_get_activity():
    """发送 GET_ACTIVITY 命令"""
    if not client:
        return jsonify({'success': False, 'message': '未连接'}), 400

    try:
        response = client.get_activity()
        return jsonify({
            'success': True,
            'message': '获取 Activity 成功',
            'response': response
        })
    except Exception as e:
        return jsonify({'success': False, 'message': str(e)}), 500


@app.route('/api/command/heartbeat', methods=['POST'])
def cmd_heartbeat():
    """发送 HEARTBEAT 命令"""
    if not client:
        return jsonify({'success': False, 'message': '未连接'}), 400

    try:
        # 直接使用 transport 发送心跳
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


@app.route('/api/command/input_text', methods=['POST'])
def cmd_input_text():
    """发送 INPUT_TEXT 命令"""
    if not client:
        return jsonify({'success': False, 'message': '未连接'}), 400

    data = request.json
    text = data.get('text', 'Hello LXB')

    try:
        response = client.input_text(text)
        return jsonify({
            'success': True,
            'message': f'输入文本 "{text}" 成功',
            'response': {
                'length': len(response),
                'data': list(response) if response else []
            }
        })
    except Exception as e:
        return jsonify({'success': False, 'message': str(e)}), 500


if __name__ == '__main__':
    print("=" * 60)
    print("LXB Web Console")
    print("=" * 60)
    print("访问地址: http://localhost:5000")
    print("按 Ctrl+C 停止服务")
    print("=" * 60)

    app.run(host='0.0.0.0', port=5000, debug=True)

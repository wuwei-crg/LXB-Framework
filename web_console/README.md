# LXB Web Console

基于 Flask 的可视化调试控制台，用于测试和调试 LXB-Link 协议。

## 功能特性

- ✅ 可视化指令发送（TAP, SWIPE, GET_ACTIVITY 等）
- ✅ 实时日志显示
- ✅ 设备连接管理
- ✅ 命令历史记录
- ✅ 响应数据可视化

## 快速开始

### 1. 安装依赖

```bash
pip install flask flask-cors
```

### 2. 启动服务

```bash
cd web_console
python app.py
```

### 3. 访问界面

打开浏览器访问: `http://localhost:5000`

## 目录结构

```
web_console/
├── app.py              # Flask 后端
├── static/
│   ├── css/
│   │   └── style.css   # 样式表
│   └── js/
│       └── main.js     # 前端逻辑
├── templates/
│   └── index.html      # 主界面
└── README.md
```

## 使用说明

1. **连接设备**: 在顶部输入设备 IP 和端口，点击"连接"
2. **发送命令**: 点击各种指令按钮即可发送
3. **查看日志**: 右侧实时显示命令和响应日志
4. **历史记录**: 自动保存最近 50 条命令历史

## 支持的命令

- **Link Layer**: HANDSHAKE, HEARTBEAT
- **Input Layer**: TAP, SWIPE, LONG_PRESS
- **Sense Layer**: GET_ACTIVITY, FIND_NODE
- **Input Extension**: INPUT_TEXT, KEY_EVENT

## 后续扩展

- [ ] 截图显示与标注
- [ ] UI 树可视化
- [ ] 脚本录制与回放
- [ ] 性能监控图表
- [ ] 多设备管理

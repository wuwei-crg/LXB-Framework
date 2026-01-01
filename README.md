# LXB-Link: Reliable UDP Protocol for Android Device Control

一个专为控制 Android 设备设计的**基于 UDP 的可靠应用层协议栈** (Reliable UDP Protocol)，采用 **Stop-and-Wait ARQ** 机制，在弱网环境下实现指令"必达"。

## 🚀 核心特性

- ✅ **可靠传输**: Stop-and-Wait ARQ 协议确保每个指令必达
- ✅ **数据完整性**: CRC32 校验防止数据损坏
- ✅ **自动重传**: 超时自动重传，最多 3 次
- ✅ **序列号管理**: 32位序列号确保包的顺序性
- ✅ **弱网优化**: 专为高丢包、高延迟环境设计
- ✅ **简洁 API**: Context Manager 支持，易于使用

## 📦 项目结构

```
LXB-Link/
├── src/lxb-link/          # 核心库代码
│   ├── __init__.py       # 包导出
│   ├── constants.py      # 协议常量和异常
│   ├── protocol.py       # 二进制帧封包/解包
│   ├── transport.py      # UDP 传输层 (Stop-and-Wait ARQ)
│   └── client.py         # 用户 API 封装
├── tests/                # 测试代码
│   ├── mock_device.py    # 模拟 Android 设备服务器
│   └── test_basic.py     # 基础测试套件
└── examples/             # 使用示例
    └── basic_usage.py    # 基本用法示例
```

## 🔧 协议规范

### 二进制帧格式 (Little Endian)

| Offset | Field | Type | Size | Description |
|:-------|:------|:-----|:-----|:------------|
| 0 | Magic | uint16 | 2 bytes | 固定魔数 `0xAA55` |
| 2 | Ver | uint8 | 1 byte | 协议版本 `0x01` |
| 3 | Seq | uint32 | 4 bytes | 序列号 (0 到 2^32-1) |
| 7 | Cmd | uint8 | 1 byte | 命令 ID |
| 8 | Len | uint16 | 2 bytes | Payload 长度 |
| 10 | Data | bytes | N bytes | Payload 数据 |
| 10+N | CRC | uint32 | 4 bytes | CRC32 校验和 (不含 CRC 字段本身) |

### 命令集

| Command | ID | Payload | Description |
|:--------|:---|:--------|:------------|
| HANDSHAKE | 0x01 | - | 握手/连接验证 |
| ACK | 0x02 | - | 确认响应 |
| TAP | 0x03 | x[uint16], y[uint16] | 点击坐标 |
| SWIPE | 0x04 | x1, y1, x2, y2, duration[uint16] | 滑动手势 |
| SCREENSHOT | 0x09 | - | 截屏 |
| WAKE | 0x0A | - | 唤醒设备 |

## 📖 快速开始

### 安装

```bash
# 方法 1: 直接使用 (无需安装)
cd LXB-Link
export PYTHONPATH="${PYTHONPATH}:$(pwd)/src"

# 方法 2: 开发安装 (推荐)
pip install -e .
```

### 基本用法

```python
from lxb_link import LXBLinkClient

# 使用 Context Manager (推荐)
with LXBLinkClient('192.168.1.100', port=12345) as client:
    # 握手
    client.handshake()

    # 点击坐标 (500, 800)
    client.tap(500, 800)

    # 滑动手势
    client.swipe(100, 500, 500, 100, duration=300)

    # 截屏
    img_data = client.screenshot()
    with open('screenshot.jpg', 'wb') as f:
        f.write(img_data)

    # 唤醒设备
    client.wake()
```

### 手动连接管理

```python
from lxb_link import LXBLinkClient, LXBTimeoutError

client = LXBLinkClient('192.168.1.100', port=12345, timeout=2.0)

try:
    client.connect()
    client.tap(100, 200)

    # 自定义命令
    response = client.send_custom_command(0xFF, b'payload', reliable=True)

except LXBTimeoutError as e:
    print(f"超时错误: {e}")
finally:
    client.disconnect()
```

## 🧪 运行测试

### 测试套件概览

LXB-Link 提供两套测试程序:

1. **基础测试** (`test_basic.py`) - 验证核心功能
2. **高级测试** (`test_advanced.py`) - 测试丢包重传和大数据传输

### 启动模拟设备服务器

模拟设备支持可配置的丢包率:

```bash
# 终端 1: 无丢包模式 (基础测试)
cd tests
python mock_device.py 12345 0

# 或者: 30% 丢包率 (高级测试)
python mock_device.py 12345 0.3

# 或者: 40% 丢包率 (压力测试)
python mock_device.py 12345 0.4
```

**参数说明:**
- `port`: UDP 端口号 (默认 12345)
- `packet_loss_rate`: 丢包率 0.0-1.0 (默认 0.0)

### 运行基础测试套件

```bash
# 终端 2: 运行基础测试
cd tests
python test_basic.py
```

### 基础测试结果

```
============================================================
LXB-Link Client Test Suite
============================================================

--- Test 1: Handshake ---
✅ Handshake Success!
   Response: b'LXB-Link v1.0'
   Time: 0.50ms

--- Test 2: Tap Command ---
✅ Tap Success!
   Response: b'TAP_OK'
   Time: 0.30ms

--- Test 3: Multiple Taps (Sequence Test) ---
✅ Tap #1 at (100, 100): 0.25ms
✅ Tap #2 at (200, 200): 0.28ms
✅ Tap #3 at (300, 300): 0.26ms

--- Test 4: Swipe Command ---
✅ Swipe Success!
   Response: b'SWIPE_OK'
   Time: 0.35ms

--- Test 5: Screenshot Command ---
✅ Screenshot Success!
   Received: 61,440 bytes (60 KB)
   Time: 20.3ms
   Format: Valid JPEG header detected

--- Test 6: Wake Command ---
✅ Wake Success!
   Response: b'WAKE_OK'
   Time: 0.22ms

--- Test 7: Custom Command ---
✅ Custom Command Success!
   Response: b'UNKNOWN_CMD'
   Time: 0.18ms

============================================================
✅ All tests completed!
============================================================
```

### 运行高级测试套件 (丢包场景)

```bash
# 确保 mock device 以 30-40% 丢包率运行
python tests/mock_device.py 12345 0.4

# 运行高级测试
python tests/test_advanced.py
```

### 高级测试结果 (40% 丢包率)

```
============================================================
Test 1: Retry Mechanism (30% Packet Loss)
============================================================
Sending 10 TAP commands to test retry mechanism...

✅ TAP # 1: Success in    1.0ms
✅ TAP # 2: Success in    0.0ms
✅ TAP # 3: Success in 1000.1ms   ← 重传发生!
✅ TAP # 4: Success in    0.0ms
✅ TAP # 5: Success in    0.0ms
✅ TAP # 6: Success in 1001.5ms   ← 重传发生!
✅ TAP # 7: Success in 1000.6ms   ← 重传发生!
✅ TAP # 8: Success in    0.0ms
✅ TAP # 9: Success in    0.0ms
✅ TAP #10: Success in 1000.2ms   ← 重传发生!

📊 Results:
   Success: 10/10 (100.0%)
   Failed: 0/10
✅ Retry mechanism working correctly!

============================================================
Test 2: Large Data Transfer (Screenshot > MTU)
============================================================
Testing 60KB JPEG transfer (exceeds typical 1500 byte MTU)

Requesting screenshot (60KB)...
✅ Screenshot received successfully!
   Size: 61,440 bytes (60.0 KB)
   Time: 19.4ms
   Throughput: 3090.3 KB/s
   Format: ✅ Valid JPEG header
   Integrity: ✅ Data size matches expected

📊 UDP Fragmentation Analysis:
   Typical MTU: 1500 bytes
   Frame size: 61,454 bytes
   Estimated IP fragments: ~41
   ℹ️  UDP stack handled fragmentation automatically!

============================================================
Test 3: Stress Test with Packet Loss
============================================================

✅ Handshake   : 2000.1ms  ← 多次重传
✅ Tap 1       :    0.0ms
✅ Tap 2       :    0.0ms
✅ Swipe       :    0.0ms
✅ Wake        :    0.0ms
✅ Tap 3       :    0.0ms

📊 Stress Test Results:
   Commands sent: 6
   Success: 6/6 (100.0%)
   Total time: 2000.1ms
   Avg time/cmd: 333.3ms
✅ All commands succeeded despite packet loss!

============================================================
Test Summary
============================================================
✅ PASS - Retry Mechanism
✅ PASS - Large Data Transfer
✅ PASS - Stress Test
✅ PASS - Timeout Behavior

Total: 4/4 tests passed (100%)

🎉 All advanced tests passed!
```

### 测试要点解读

#### 1. 重传机制验证
- ⏱️ **正常延迟**: ~0-1ms (无丢包)
- ⏱️ **重传延迟**: ~1000ms (第1次重传), ~2000ms (第2次重传)
- ✅ **40% 丢包率下仍然 100% 成功**

#### 2. 大数据传输 (60KB Screenshot)
- 📊 **单帧传输**: 60KB 数据在单个 UDP 帧中发送
- 🧩 **IP 层分片**: 自动分片为 ~41 个 IP 分片 (MTU=1500)
- ⚡ **吞吐量**: ~3 MB/s (本地环回)
- ✅ **数据完整性**: CRC32 校验确保无损传输

#### 3. Stop-and-Wait ARQ 性能
- **优点**:
  - 简单可靠，逻辑清晰
  - 适合命令-响应模式
  - 保证顺序和可靠性
- **限制**:
  - 吞吐量受 RTT 限制
  - 不适合高并发场景
  - 单帧传输

## 🏗️ 架构设计

### 洋葱架构 (Layered Architecture)

```
┌─────────────────────────────────────┐
│     client.py (User API Layer)      │  ← 用户友好的高级 API
├─────────────────────────────────────┤
│   transport.py (Transport Layer)    │  ← Stop-and-Wait ARQ 可靠传输
├─────────────────────────────────────┤
│    protocol.py (Protocol Layer)     │  ← 二进制帧封包/解包 + CRC32
├─────────────────────────────────────┤
│   constants.py (Constants Layer)    │  ← 协议常量和异常定义
└─────────────────────────────────────┘
```

### Stop-and-Wait ARQ 状态机

```
       ┌─────┐
       │IDLE │
       └──┬──┘
          │
    Pack Frame (Seq++)
          │
          ▼
       ┌──────┐
       │ SEND │─────────────┐
       └──┬───┘             │
          │                 │
     Set Timeout            │
          │                 │
          ▼                 │
       ┌──────┐             │
   ┌───│ WAIT │             │
   │   └──┬───┘             │
   │      │                 │
   │   Receive              │
   │      │                 │
   │      ├──[Valid ACK + Seq Match]──► SUCCESS
   │      │
   │      ├──[Invalid/Mismatch]──► Discard & Continue
   │      │
   │      └──[Timeout]
   │              │
   │         Retry++
   │              │
   │         ┌────▼─────┐
   │         │ retry <= │ Yes
   │         │   MAX?   │────┘
   │         └────┬─────┘
   │              │ No
   │              ▼
   │        ┌──────────┐
   └────────│  FAILED  │
            └──────────┘
       (Raise LXBTimeoutError)
```

## ⚙️ 配置参数

| 参数 | 默认值 | 说明 |
|:-----|:-------|:-----|
| `DEFAULT_TIMEOUT` | 1.0 秒 | Socket 超时时间 |
| `MAX_RETRIES` | 3 | 最大重传次数 |
| `DEFAULT_PORT` | 12345 | 默认 UDP 端口 |
| `MAX_PAYLOAD_SIZE` | ~65KB | 最大 Payload 大小 |

## 🛡️ 异常处理

```python
from lxb_link import (
    LXBLinkError,        # 基础异常类
    LXBTimeoutError,     # 超时异常
    LXBProtocolError,    # 协议验证失败
    LXBChecksumError     # CRC32 校验失败
)

try:
    with LXBLinkClient('192.168.1.100') as client:
        client.tap(100, 200)
except LXBTimeoutError as e:
    print(f"操作超时: {e}")
except LXBProtocolError as e:
    print(f"协议错误: {e}")
except LXBChecksumError as e:
    print(f"数据损坏: {e}")
```

## 📊 性能特点

- ✅ **低延迟**: 本地网络下 RTT < 1ms
- ✅ **高可靠**: 通过 ACK 确认机制保证送达
- ✅ **容错性强**: 自动处理丢包和乱序
- ✅ **轻量级**: 纯 Python 标准库实现 (struct + zlib)

## 🔍 调试

启用详细日志:

```python
import logging

logging.basicConfig(
    level=logging.DEBUG,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
)

from lxb_link import LXBLinkClient

with LXBLinkClient('192.168.1.100') as client:
    client.tap(100, 200)
```

## 📝 代码规范

- ✅ 遵循 PEP8 规范
- ✅ 完整的类型提示 (Type Hints)
- ✅ 详细的文档字符串 (Docstrings)
- ✅ 全面的异常处理

## 🤝 贡献

欢迎提交 Issue 和 Pull Request！

## 📄 许可证

MIT License

## 👨‍💻 作者

WuWei

---

**注意**: 本项目为教育/演示用途。生产环境使用请根据实际需求调整参数和错误处理策略。

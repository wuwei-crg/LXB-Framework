# LXB-Link 协议规范

## Protocol Specification for LXB-Link Communication Protocol

| 项目         | 内容                                 |
|:-------------|:-------------------------------------|
| **文档版本** | 1.0.0                                |
| **协议版本** | 1.0 (Binary First Architecture)      |
| **作者**     | WuWei                                |
| **创建日期** | 2025-01-01                           |
| **最后更新** | 2026-01-12                           |
| **状态**     | 正式发布 (Production Ready)          |
| **适用范围** | Android 设备控制、AI Agent 感知      |

---

## 文档说明

本文档定义了 LXB-Link 通信协议的完整规范，包括：

- 帧格式与字节序规范
- 分层命令集架构 (Layered ISA)
- Binary First 数据编码标准
- 可靠传输机制 (Stop-and-Wait ARQ)
- 字符串池压缩算法
- 错误处理与扩展指南

**目标读者**: 协议实现者、客户端/服务端开发者、系统集成工程师

---

## 目录

- [协议概述](#协议概述)
- [设计原则](#设计原则)
- [帧格式](#帧格式)
- [分层 ISA 命令集](#分层-isa-命令集)
- [Binary First 数据结构](#binary-first-数据结构)
- [传输模式](#传输模式)
- [字符串常量池](#字符串常量池)
- [错误处理](#错误处理)
- [协议扩展指南](#协议扩展指南)

---

## 协议概述

LXB-Link 是一个基于 UDP 的**工业级嵌入式协议**，专为弱网环境下的 Android 设备控制和 AI Agent 感知设计。

### 核心特性

- ✅ **Binary First**: 拒绝 JSON 膨胀，所有数据采用紧凑二进制编码
- ✅ **Big Endian (Network Byte Order)**: 强制大端序/网络字节序，确保跨平台兼容性
- ✅ **String Pool**: 字符串常量池压缩，节省 96% 重复传输
- ✅ **Layered ISA**: 分层指令集架构，支持 256 条指令扩展
- ✅ **CRC32 Checksum**: 保证数据完整性
- ✅ **Stop-and-Wait ARQ**: 可靠传输机制
- ✅ **Fragmented Transfer**: 应用层分片 + 选择性重传
- ✅ **Computation Offloading**: UI 节点查找下放到设备端执行

### 协议栈

```
┌─────────────────────────────────────┐
│   Client API Layer (client.py)     │  ← 用户友好的高级 API
├─────────────────────────────────────┤
│   Transport Layer (transport.py)   │  ← Stop-and-Wait ARQ
├─────────────────────────────────────┤
│   Protocol Layer (protocol.py)     │  ← Binary Packing/Unpacking
├─────────────────────────────────────┤
│   Constants (constants.py)         │  ← Command IDs, String Pool
├─────────────────────────────────────┤
│   UDP (Unreliable Datagram)        │  ← 网络传输层
└─────────────────────────────────────┘
```

---

## 设计原则

### 1. Binary First (二进制优先)

**原则**: 能用 `struct` 解决的，绝不用 JSON。能用 1 个字节表达的，绝不用 2 个字节。

**对比示例**:

```python
# ❌ 错误 - JSON 膨胀
{
  "nodes": [{"x": 500, "y": 800, "text": "登录"}]
}
# 单个节点 ~60 bytes

# ✅ 正确 - Binary
struct.pack('>HH', 500, 800)  # 4 bytes (节省 93%)
```

### 2. Big Endian / Network Byte Order (强制大端序/网络字节序)

**规则**: 所有多字节整数**必须**使用大端序 (Big Endian / Network Byte Order)。

```python
# Python
struct.pack('>HH', x, y)   # ✅ 正确 (> 表示大端)
struct.pack('HH', x, y)    # ❌ 错误 (字节序不确定)

# Java/Kotlin
ByteBuffer buffer = ByteBuffer.allocate(4);
buffer.order(ByteOrder.BIG_ENDIAN);  // ✅ 必须设置!
buffer.putShort(x);
```

### 3. Zero Copy (零拷贝)

数据结构设计支持零拷贝解析，使用定长字段优先。

### 4. Deterministic Size (确定性大小)

优先使用定长结构，变长数据需显式标记长度。

---

## 帧格式

### 通用帧结构

所有 LXB-Link 帧遵循统一的格式（**大端序/网络字节序**）：

```
┌─────────┬─────────┬─────────┬─────────┬─────────┬─────────┬─────────┐
│  Magic  │ Version │   Seq   │   Cmd   │  Length │ Payload │  CRC32  │
│  2B     │  1B     │   4B    │   1B    │   2B    │  N B    │   4B    │
│ 0xAA55  │  0x01   │ uint32  │  uint8  │ uint16  │  bytes  │ uint32  │
└─────────┴─────────┴─────────┴─────────┴─────────┴─────────┴─────────┘
          ↑                                         ↑
          |- All multi-byte fields use Big Endian (Network Byte Order) -|
```

### 字段说明

| 字段 | 大小 | 类型 (LE) | 说明 |
|:-----|:-----|:----------|:-----|
| **Magic** | 2 字节 | `uint16` | 魔数标识 `0xAA55` (LE: 0x55 0xAA) |
| **Version** | 1 字节 | `uint8` | 协议版本号 (当前: `0x01`) |
| **Sequence** | 4 字节 | `uint32` | 序列号 (0 - 4294967295，循环) |
| **Command** | 1 字节 | `uint8` | 命令 ID (见分层 ISA) |
| **Length** | 2 字节 | `uint16` | Payload 长度 (0 - 65535 字节) |
| **Payload** | 可变 | `bytes` | 命令负载数据 (二进制) |
| **CRC32** | 4 字节 | `uint32` | 整个帧的 CRC32 校验和 |

### 帧大小限制

- **最小帧**: 14 bytes (Header + CRC，无 Payload)
- **最大帧**: 65549 bytes (14 bytes Header + 65535 bytes Payload + 4 bytes CRC)
- **推荐 MTU**: 1400 bytes (避免 IP 层分片)

---

## 分层 ISA 命令集

### 命名空间架构

```
┌─────────────────┬──────────────────┬─────────────────────────────┐
│  Layer          │  Range           │  Purpose                    │
├─────────────────┼──────────────────┼─────────────────────────────┤
│  Link Layer     │  0x00 - 0x0F     │  握手/ACK/心跳/协议控制     │
│  Input Layer    │  0x10 - 0x1F     │  点击/滑动/手势/唤醒        │
│  Input Ext      │  0x20 - 0x2F     │  文本输入/按键事件          │
│  Sense Layer    │  0x30 - 0x3F     │  Activity/UI树/节点查找 ⭐  │
│  Lifecycle      │  0x40 - 0x4F     │  重启/剪贴板/应用管理       │
│  Debug Layer    │  0x50 - 0x5F     │  日志/性能监控/设备信息     │
│  Media Layer    │  0x60 - 0x6F     │  截图/录屏/音频采集         │
│  Reserved       │  0x70 - 0xEF     │  未来扩展                   │
│  System/Vendor  │  0xF0 - 0xFF     │  厂商自定义/实验性功能      │
└─────────────────┴──────────────────┴─────────────────────────────┘
```

### Link Layer (0x00-0x0F) - 协议基础设施

| CMD ID | Name | Direction | Payload | Description |
|:-------|:-----|:----------|:--------|:------------|
| `0x01` | `CMD_HANDSHAKE` | C → S | Variable | 握手连接 |
| `0x02` | `CMD_ACK` | Bidirectional | Variable | 确认响应 |
| `0x03` | `CMD_HEARTBEAT` | C ↔ S | None | 保活心跳 (new) |
| `0x04` | `CMD_NOOP` | C → S | None | 空操作 (延迟测试) (new) |
| `0x05` | `CMD_PROTOCOL_VER` | C → S | 2B | 协议版本协商 (new) |

### Input Layer (0x10-0x1F) - 基础交互

| CMD ID | Name | Direction | Payload | Description |
|:-------|:-----|:----------|:--------|:------------|
| `0x10` | `CMD_TAP` | C → S | 4B | 点击屏幕 (x, y) |
| `0x11` | `CMD_SWIPE` | C → S | 10B | 滑动手势 (x1, y1, x2, y2, duration) |
| `0x12` | `CMD_LONG_PRESS` | C → S | 6B | 长按 (x, y, duration) |
| `0x13` | `CMD_MULTI_TOUCH` | C → S | Variable | 多点触控 |
| `0x14` | `CMD_GESTURE` | C → S | Variable | 复杂手势 (捏合/旋转) |
| `0x1A` | `CMD_WAKE` | C → S | None | 唤醒设备 |
| `0x1B` | `CMD_UNLOCK` | C → S | None | 滑动解锁（无密码） ⭐ |

### Input Extension (0x20-0x2F) - 高级输入

| CMD ID | Name | Direction | Payload | Description |
|:-------|:-----|:----------|:--------|:------------|
| `0x20` | `CMD_INPUT_TEXT` | C → S | **Binary** | 文本输入 (UTF-8 字节流) ⭐ |
| `0x21` | `CMD_KEY_EVENT` | C → S | 6B | 物理按键 (HOME/BACK/ENTER) ⭐ |
| `0x22` | `CMD_PASTE` | C → S | Variable | 粘贴操作 (new) |

### Sense Layer (0x30-0x3F) - 感知能力 ⭐ 核心升级

| CMD ID | Name | Direction | Payload | Description |
|:-------|:-----|:----------|:--------|:------------|
| `0x30` | `CMD_GET_ACTIVITY` | C → S | None | 获取当前 Activity 名 ⭐ |
| `0x31` | `CMD_DUMP_HIERARCHY` | C → S | 4B | 导出 UI 树 (Binary+StringPool) ⭐ |
| `0x32` | `CMD_FIND_NODE` | C → S | Variable | 计算卸载查找节点 ⭐ |
| `0x33` | `CMD_DUMP_ACTIONS` | C → S | None | 获取可交互节点列表 (路径规划) ⭐ |
| `0x34` | `CMD_WAIT_FOR` | C → S | Variable | 等待元素出现 |
| `0x35` | `CMD_OCR_REGION` | C → S | 8B | 设备端 OCR |
| `0x36` | `CMD_GET_SCREEN_STATE` | C → S | None | 获取屏幕状态 ⭐ |
| `0x37` | `CMD_GET_SCREEN_SIZE` | C → S | None | 获取屏幕尺寸 ⭐ |

### Lifecycle Layer (0x40-0x4F) - 应用管理

| CMD ID | Name | Direction | Payload | Description |
|:-------|:-----|:----------|:--------|:------------|
| `0x40` | `CMD_RESTART_APP` | C → S | Variable | 重启应用 |
| `0x41` | `CMD_GET_CLIPBOARD` | C → S | None | 获取剪贴板内容 |
| `0x42` | `CMD_SET_CLIPBOARD` | C → S | Variable | 设置剪贴板内容 |
| `0x43` | `CMD_LAUNCH_APP` | C → S | Variable | 启动应用 ⭐ |
| `0x44` | `CMD_STOP_APP` | C → S | Variable | 强制停止应用 ⭐ |

### Debug Layer (0x50-0x5F) - 调试工具

| CMD ID | Name | Direction | Payload | Description |
|:-------|:-----|:----------|:--------|:------------|
| `0x50` | `CMD_GET_DEVICE_INFO` | C → S | None | 获取设备信息 (new) |
| `0x51` | `CMD_LOGCAT` | C → S | Variable | 获取日志片段 (new) |

### Media Layer (0x60-0x6F) - 媒体采集

| CMD ID | Name | Direction | Payload | Description |
|:-------|:-----|:----------|:--------|:------------|
| `0x60` | `CMD_SCREENSHOT` | C → S | None | 截图 (返回 PNG 图片) ⭐ |
| `0x61` | `CMD_IMG_REQ` | C → S | None | 请求截图 (分片，迁移自 0x10) |
| `0x62` | `CMD_IMG_META` | S → C | 10B | 图片元数据 (迁移自 0x11) |
| `0x63` | `CMD_IMG_CHUNK` | S → C | Variable | 图片分片 (迁移自 0x12) |
| `0x64` | `CMD_IMG_MISSING` | C → S | Variable | 补包请求 (迁移自 0x13) |
| `0x65` | `CMD_IMG_FIN` | C → S | None | 传输完成 (迁移自 0x14) |
| `0x66-0x68` | (Reserved) | - | - | 录屏/音频采集 (预留) |
| `0x69` | `CMD_HIERARCHY_REQ` | C → S | 4B | 请求 UI 树 (format, compress, max_depth) ⭐ |
| `0x6A` | `CMD_HIERARCHY_META` | S → C | 10B | UI 树元数据 (tree_id, total_size, num_chunks) ⭐ |
| `0x6B` | `CMD_HIERARCHY_CHUNK` | S → C | Variable | UI 树分片 (chunk_index + data) ⭐ |
| `0x6C` | `CMD_HIERARCHY_FIN` | C → S | None | UI 树传输完成 ⭐ |

**注**: UI 树分片传输复用 `CMD_IMG_MISSING (0x64)` 指令请求重传丢失的分片

---

## Binary First 数据结构

### CMD_INPUT_TEXT (0x20) - 文本输入 ⭐

**用途**: 在输入框中输入文本 (拒绝 JSON，使用纯 UTF-8 字节流)

**Request Payload** (9+ bytes):
```c
struct InputTextRequest {
    uint8   method;         // 0=ADB, 1=Clipboard, 2=Accessibility
    uint8   flags;          // bit0=clear_first, bit1=press_enter,
                            // bit2=hide_keyboard
    uint16  target_x;       // 目标坐标 (LE) (0=当前焦点)
    uint16  target_y;       // LE
    uint16  delay_ms;       // 每字符延迟 (LE)
    uint16  text_len;       // UTF-8 字节数 (LE)
    uint8   text[];         // UTF-8 字节流 (无 null 终止符)
};
```

**Response Payload** (2 bytes):
```c
struct InputTextResponse {
    uint8   status;         // 0=失败, 1=成功, 2=部分成功
    uint8   actual_method;  // 实际使用的方法 (可能降级)
};
```

**示例**:
```python
# 输入 "微信支付"
method = INPUT_METHOD_CLIPBOARD
flags = INPUT_FLAG_CLEAR_FIRST
text = "微信支付".encode('utf-8')  # 12 bytes

payload = struct.pack('<BBHHHH',
    method,         # 1
    flags,          # 0x01
    0, 0,           # target: (0, 0) = current focus
    50,             # delay_ms
    len(text)       # 12
) + text

# Total: 9 + 12 = 21 bytes
# vs JSON: ~65 bytes (节省 67%)
```

---

### CMD_KEY_EVENT (0x21) - 物理按键 ⭐

**用途**: 模拟物理按键 (HOME/BACK/ENTER 等)

**Request Payload** (6 bytes):
```c
struct KeyEventRequest {
    uint8   keycode;        // Android KeyEvent (3=HOME, 4=BACK, 66=ENTER)
    uint8   action;         // 0=按下, 1=抬起, 2=点击(按下+抬起)
    uint32  meta_state;     // 修饰键状态 (Shift/Ctrl/Alt) (LE)
};
```

**常用 Keycode**:
```python
KEY_HOME    = 3     # HOME 键
KEY_BACK    = 4     # 返回键
KEY_ENTER   = 66    # 回车键
KEY_DELETE  = 67    # 删除键
KEY_RECENT  = 187   # 最近任务
```

**示例**:
```python
# 按下 BACK 键
payload = struct.pack('<BBI', KEY_BACK, 2, 0)
# Total: 6 bytes
```

---

### CMD_FIND_NODE (0x32) - 计算卸载查找 ⭐⭐⭐

**核心创新**: 避免传输巨大 UI 树，将查找逻辑下放到设备端执行

**Request Payload** (7+ bytes):
```c
struct FindNodeRequest {
    uint8   match_type;     // 0=精确文本, 1=包含文本, 2=正则,
                            // 3=resource-id, 4=class, 5=描述
    uint8   return_mode;    // 0=仅坐标, 1=边界框, 2=完整信息
    uint8   multi_match;    // 0=返回首个, 1=返回所有
    uint16  timeout_ms;     // 查找超时 (LE)
    uint16  query_len;      // 查询字符串长度 (LE)
    char    query_str[];    // UTF-8 字符串 (无 null)
};
```

**Response Mode 0 - 仅中心坐标** (1 + 1 + N*4 bytes):
```c
struct FindNodeResponse_Coords {
    uint8   status;         // 0=未找到, 1=成功, 2=超时
    uint8   count;          // 匹配数量

    struct Coord {
        uint16  x;          // LE
        uint16  y;          // LE
    } coords[count];        // 4 bytes per node
};
```

**Response Mode 1 - 边界框** (1 + 1 + N*8 bytes):
```c
struct FindNodeResponse_Boxes {
    uint8   status;
    uint8   count;

    struct BoundingBox {
        uint16  left;       // LE
        uint16  top;        // LE
        uint16  right;      // LE
        uint16  bottom;     // LE
    } boxes[count];         // 8 bytes per node
};
```

**Response Mode 2 - 完整信息** (Variable):
```c
struct FindNodeResponse_Full {
    uint8   status;
    uint8   count;

    struct NodeInfo {
        uint16  center_x, center_y;             // LE
        uint16  left, top, right, bottom;       // LE
        uint8   flags;          // bit0=clickable, bit1=visible, ...
        uint8   class_id;       // 类名索引 (字符串池)
        uint16  text_len;       // LE
        char    text[];         // UTF-8
        uint16  res_id_len;     // LE
        char    resource_id[];  // UTF-8
    } nodes[count];
};
```

**示例**:
```python
# 查找文本包含 "登录" 的按钮 (返回坐标)
query = "登录".encode('utf-8')
payload = struct.pack('<BBBHH',
    MATCH_CONTAINS_TEXT,    # 1
    RETURN_COORDS,          # 0
    0,                      # multi_match=False
    3000,                   # timeout_ms
    len(query)              # 6
) + query

# Request: 7 + 6 = 13 bytes

# Response (找到 1 个节点):
# [0x01][0x01][500][800]
# Total: 1 + 1 + 4 = 6 bytes

# 对比 JSON 方案:
# Request: ~50 bytes
# Response: ~200 bytes
# 节省: 95%!
```

---

### CMD_UNLOCK (0x1B) - 滑动解锁 ⭐

**用途**: 执行滑动解锁手势（适用于无密码锁屏）

**Request Payload**: None

**Response Payload** (1 byte):

```c
struct UnlockResponse {
    uint8   status;         // 0=失败, 1=成功
};
```

**实现说明**:
- 从屏幕底部中央向上滑动
- 或从屏幕中央向上滑动（根据 Android 版本）
- 不支持密码/图案/指纹解锁

---

### CMD_DUMP_ACTIONS (0x33) - 获取可交互节点 ⭐⭐

**用途**: 获取可交互节点列表，用于 AI Agent 路径规划和语义导航

**核心创新**: 只返回有意义的节点 (可点击/可编辑/可滚动/有文本)，自动关联子节点文本到父容器

**Request Payload**: None

**Response Payload** (Variable):
```c
struct DumpActionsResponse {
    uint8   version;        // 编码版本 (0x01)
    uint16  node_count;     // 节点数量

    // 节点数组 (每个 20 字节)
    struct ActionNode {
        uint8   type;           // 类型位掩码
        uint16  left;           // 左边界
        uint16  top;            // 上边界
        uint16  right;          // 右边界
        uint16  bottom;         // 下边界
        uint8   class_id;       // 类名字符串 ID (短池)
        uint16  text_id;        // 文本字符串 ID (长池)
        uint8   res_id;         // Resource ID 字符串 ID (短池)
        uint8   desc_id;        // Content Description 字符串 ID (短池)
        uint8   reserved[6];    // 保留字节
    } nodes[node_count];        // 20 bytes per node

    // 双字符串池
    uint8   short_count;        // 短字符串数量 (class/resId/desc)
    struct ShortEntry {
        uint8   len;
        char    data[];         // UTF-8
    } short_strings[short_count];

    uint16  long_count;         // 长字符串数量 (text)
    struct LongEntry {
        uint16  len;
        char    data[];         // UTF-8
    } long_strings[long_count];
};
```

**Type 位掩码**:
```c
ACTION_CLICKABLE   = 0x01  // bit 0: 可点击
ACTION_EDITABLE    = 0x02  // bit 1: 可编辑 (EditText)
ACTION_SCROLLABLE  = 0x04  // bit 2: 可滚动
ACTION_TEXT_ONLY   = 0x08  // bit 3: 仅文本 (非交互)
```

**文本关联规则**:
- 如果节点自身有 text，直接使用
- 如果交互节点没有 text，递归查找第一个有文本的子节点
- 如果仍为空，使用 content-desc

**示例**:
```python
# Response (2 个可点击节点):
# [0x01]                       # version
# [0x00][0x02]                 # node_count = 2
# [0x01][0x00][0x64]...        # node 1: clickable, left=100, ...
# [0x01][0x01][0x2C]...        # node 2: clickable, left=300, ...
# [0x03]                       # short_count = 3
# [0x10]["android.widget..."]  # class name
# ...
# [0x00][0x02]                 # long_count = 2
# [0x00][0x04]["登录"]          # text
# ...
```

**使用场景**:
- 语义导航: "我要点外卖" → 匹配 "外卖" 文本的可点击节点
- 页面识别: 根据可交互元素识别当前页面状态
- 自动化测试: 快速获取可操作元素

**性能对比**:
```
微信主界面:

DUMP_HIERARCHY (完整树):
- 节点数: 300+
- 数据量: 2.5 KB

DUMP_ACTIONS (过滤后):
- 节点数: 30-50
- 数据量: ~800 bytes
- 节省: 70%
```

---

### CMD_SCREENSHOT (0x60) - 截图 ⭐

**用途**: 获取当前屏幕截图 (PNG 格式)

**Request Payload**: None

**Response Payload** (Variable):
```c
struct ScreenshotResponse {
    uint8   status;         // 0=失败, 1=成功
    uint8   image_data[];   // PNG 图片数据
};
```

**示例**:
```python
# Response (成功):
# [0x01][PNG 数据...]
# PNG 数据以 0x89 0x50 0x4E 0x47 开头

# Response (失败):
# [0x00]
```

**注意事项**:
- 截图数据可能较大 (100KB-1MB)，使用 Stop-and-Wait ARQ 传输
- 对于大截图，建议使用 CMD_IMG_REQ (0x61) 分片传输
- 超时时间应设置为 3-5 秒

---

### CMD_GET_SCREEN_STATE (0x36) - 获取屏幕状态 ⭐

**用途**: 获取当前屏幕状态（亮屏/灭屏/锁定）

**Request Payload**: None

**Response Payload** (2 bytes):

```c
struct ScreenStateResponse {
    uint8   status;         // 0=失败, 1=成功
    uint8   state;          // 0=灭屏, 1=亮屏未锁定, 2=亮屏已锁定
};
```

**状态码**:

| 值 | 状态 | 说明 |
|---|------|------|
| 0 | OFF | 屏幕关闭 |
| 1 | ON_UNLOCKED | 屏幕亮且已解锁 |
| 2 | ON_LOCKED | 屏幕亮但锁定 |

---

### CMD_GET_SCREEN_SIZE (0x37) - 获取屏幕尺寸 ⭐

**用途**: 获取屏幕分辨率和密度（用于坐标计算）

**Request Payload**: None

**Response Payload** (7 bytes):

```c
struct ScreenSizeResponse {
    uint8   status;         // 0=失败, 1=成功
    uint16  width;          // 屏幕宽度 (px)
    uint16  height;         // 屏幕高度 (px)
    uint16  density;        // 屏幕密度 (dpi)
};
```

**示例**:

```python
# Response (1080x2400, 440dpi):
# [0x01][0x0438][0x0960][0x01B8]
# Total: 7 bytes
```

---

### CMD_LAUNCH_APP (0x43) - 启动应用 ⭐

**用途**: 启动指定应用（通过 am start）

**Request Payload** (Variable):

```c
struct LaunchAppRequest {
    uint8   flags;          // bit0=清除任务栈, bit1=等待启动完成
    uint16  package_len;    // 包名长度
    char    package_name[]; // UTF-8 (e.g., "com.tencent.mm")
};
```

**Flags**:

| Bit | 名称 | 说明 |
|-----|------|------|
| 0 | CLEAR_TASK | 清除任务栈后启动 (FLAG_ACTIVITY_CLEAR_TASK) |
| 1 | WAIT | 等待 Activity 完全启动后返回 |

**Response Payload** (1 byte):

```c
struct LaunchAppResponse {
    uint8   status;         // 0=失败, 1=成功
};
```

**示例**:

```python
# 启动微信 (清除任务栈)
package = "com.tencent.mm".encode('utf-8')
payload = struct.pack('>BH', 0x01, len(package)) + package
# Total: 1 + 2 + 14 = 17 bytes
```

---

### CMD_STOP_APP (0x44) - 强制停止应用 ⭐

**用途**: 强制停止指定应用（通过 am force-stop）

**Request Payload** (Variable):

```c
struct StopAppRequest {
    uint16  package_len;    // 包名长度
    char    package_name[]; // UTF-8 (e.g., "com.tencent.mm")
};
```

**Response Payload** (1 byte):

```c
struct StopAppResponse {
    uint8   status;         // 0=失败, 1=成功
};
```

**示例**:

```python
# 停止微信
package = "com.tencent.mm".encode('utf-8')
payload = struct.pack('>H', len(package)) + package
# Total: 2 + 14 = 16 bytes
```

---

### CMD_GET_ACTIVITY (0x30) - 获取 Activity ⭐

**用途**: 获取当前前台应用的 Activity 名 (用于状态机路由)

**Request Payload**: None

**Response Payload** (Variable):
```c
struct GetActivityResponse {
    uint8   success;        // 0=失败, 1=成功
    uint16  package_len;    // 包名长度 (LE)
    char    package_name[]; // UTF-8 (e.g., "com.tencent.mm")
    uint16  activity_len;   // Activity 长度 (LE)
    char    activity_name[];// UTF-8 (e.g., ".ui.LauncherUI")
};
```

**示例**:
```python
# Response:
# [0x01][0x000E]["com.tencent.mm"][0x000F][".ui.LauncherUI"]
# Total: 1 + 2 + 14 + 2 + 15 = 34 bytes
```

---

### CMD_DUMP_HIERARCHY (0x31) - 导出 UI 树 ⭐⭐

**用途**: 获取 UI 层级树 (使用 Binary + StringPool，拒绝 JSON)

**Request Payload** (4 bytes):
```c
struct DumpHierarchyRequest {
    uint8   format;         // 0=XML, 1=JSON, 2=Binary (推荐)
    uint8   compress;       // 0=原始, 1=zlib, 2=lz4
    uint16  max_depth;      // 最大遍历深度 (LE) (0=无限制)
};
```

**Response Payload - Binary Format** (14+ bytes):
```c
struct HierarchyHeader {
    uint8   version;        // 编码版本 (0x01)
    uint8   compress;       // 实际压缩算法
    uint32  original_size;  // 原始数据大小 (LE)
    uint32  compressed_size;// 压缩后大小 (LE)
    uint16  node_count;     // 节点总数 (LE)
    uint16  string_pool_size; // 字符串池条目数 (LE)
};

// 字符串池
struct StringPool {
    struct StringEntry {
        uint8   str_len;    // 最长 255 字节
        char    str[];      // UTF-8
    } entries[string_pool_size];
};

// 节点数组 (扁平化存储)
struct UINode {
    uint8   parent_index;   // 父节点索引 (0xFF=根节点)
    uint8   child_count;    // 子节点数量
    uint8   flags;          // 位掩码 (见下文)
    uint8   class_id;       // 类名在字符串池中的索引
    uint16  left, top;      // LE
    uint16  right, bottom;  // LE
    uint8   text_id;        // 文本索引 (0xFF=空)
    uint8   res_id;         // resource-id 索引 (0xFF=空)
    uint8   desc_id;        // content-desc 索引 (0xFF=空)
} nodes[node_count];        // 15 bytes per node (定长!)
```

**Flags 位掩码**:
```c
FLAG_CLICKABLE  = 0x01  // bit 0
FLAG_VISIBLE    = 0x02  // bit 1
FLAG_ENABLED    = 0x04  // bit 2
FLAG_FOCUSED    = 0x08  // bit 3
FLAG_SCROLLABLE = 0x10  // bit 4
FLAG_EDITABLE   = 0x20  // bit 5
FLAG_CHECKABLE  = 0x40  // bit 6
FLAG_CHECKED    = 0x80  // bit 7
```

**性能对比**:
```
微信主界面 (100 个节点):

JSON 方案:
- 原始: 52 KB
- 压缩: 8 KB

Binary + StringPool:
- 原始: 2.5 KB
- 压缩: 800 bytes

节省: 90%!
```

---

### CMD_HIERARCHY_REQ (0x69) - 请求 UI 树 ⭐

**用途**: 请求设备端导出 UI 层级树（使用 Binary 格式 + StringPool 压缩）

**Request Payload** (4 字节):
```c
struct HierarchyRequest {
    uint8   format;         // 固定 0x02 (Binary)
    uint8   compress;       // 0=原始, 1=zlib
    uint16  max_depth;      // 最大深度 (0=无限制)
};
```

**示例**:
```python
# 请求压缩的 UI 树，无深度限制
payload = struct.pack('>BBH',
    0x02,   # format: Binary
    0x01,   # compress: zlib
    0       # max_depth: unlimited
)
# Total: 4 bytes
```

---

### CMD_HIERARCHY_META (0x6A) - UI 树元数据 ⭐

**用途**: 服务端返回 UI 树的元数据，用于启动分片传输

**Response Payload** (10 字节):
```c
struct HierarchyMeta {
    uint32  tree_id;        // UI 树 ID (用于标识)
    uint32  total_size;     // 总字节数
    uint16  num_chunks;     // 总分片数
};
```

**示例**:
```python
# 返回 50KB UI 树，分为 50 个分片
payload = struct.pack('>IIH',
    12345,      # tree_id
    51200,      # total_size (50KB)
    50          # num_chunks
)
# Total: 10 bytes
```

---

### CMD_HIERARCHY_CHUNK (0x6B) - UI 树分片 ⭐

**用途**: 服务端突发发送 UI 树的分片数据

**Chunk Payload** (可变):
```c
struct HierarchyChunk {
    uint16  chunk_index;    // 分片索引 (0-based)
    uint8   chunk_data[];   // 分片数据（最多 1KB）
};
```

**示例**:

```python
# 发送第 0 个分片
payload = struct.pack('>H', 0) + chunk_data[:1024]
# Total: 2 + 1024 = 1026 bytes
```

---

### CMD_HIERARCHY_FIN (0x6C) - 传输完成确认 ⭐

**用途**: 客户端确认所有分片接收完成

**Request Payload**: None (空 payload)

**传输流程** (类似截图分片):
```
Client                                Server
  |                                      |
  | 1. CMD_HIERARCHY_REQ               |
  |-----------------------------------→ |
  |                                      | 生成 UI 树并切片
  | 2. CMD_HIERARCHY_META              |
  | ←----------------------------------  |
  |                                      |
  | 3. ACK (确认 META)                  |
  |-----------------------------------→ |
  |                                      |
  | 4. CMD_HIERARCHY_CHUNK (0,1,2,...)  |
  | ←----------------------------------  | (突发发送所有分片)
  |                                      |
  | 接收 chunks (300ms timeout)         |
  | 检查完整性                           |
  |                                      |
  | 5. CMD_IMG_MISSING [missing_ids]   |  (复用截图的补包指令)
  |-----------------------------------→ |
  |                                      |
  | 6. 重传丢失 chunks                   |
  | ←----------------------------------  |
  |                                      |
  | 7. CMD_HIERARCHY_FIN               |
  |-----------------------------------→ |
  |                                      |
  | 8. ACK (确认 FIN)                   |
  | ←----------------------------------  |
  V                                      V
```

**性能对比**:
```
微信主界面 (100 个节点):

传统 DUMP_HIERARCHY (单包):
- 数据大小: 2.5 KB (压缩后 800 bytes)
- 传输方式: Stop-and-Wait ARQ
- 适用场景: 小型 UI 树 (< 10KB)

HIERARCHY_REQ 分片传输:
- 数据大小: 50 KB+ (大型 UI 树)
- 传输方式: 突发 + 选择性重传
- 适用场景: 大型 UI 树 (> 10KB)
- 容错能力: 40% 丢包率仍可工作
```

---

## 传输模式

### 1. Stop-and-Wait ARQ (控制命令)

用于 `CMD_TAP`, `CMD_SWIPE`, `CMD_FIND_NODE`, `CMD_INPUT_TEXT` 等小命令。

```
Client                          Server
  |                               |
  | CMD (seq=N)                   |
  |------------------------------>|
  |                               | 处理命令
  |        ACK (seq=N)            |
  |<------------------------------|
  |                               |
```

**特性**:
- 可靠传输: 自动重传，直到收到 ACK
- 最大重试: 3 次
- 超时时间: 1.0 秒
- 序列号匹配: ACK 必须与请求的 seq 一致

### 2. 分片传输 + 选择性重传 (大数据)

用于 `CMD_IMG_REQ` ~ `CMD_IMG_FIN`, `CMD_DUMP_HIERARCHY` (大 UI 树)。

```
Client                                    Server
  |                                         |
  | 1. CMD_IMG_REQ (seq=N)                 |
  |---------------------------------------->|
  |                                         | 生成数据并切片
  | 2. CMD_IMG_META (seq=N)                |
  |<----------------------------------------|
  |                                         |
  | 3. ACK (seq=N)                         |
  |---------------------------------------->| 等待 ACK
  |                                         |
  | 4. 突发发送所有 chunks                   |
  |    CMD_IMG_CHUNK (index=0, 1, 2, ...)  |
  |<----------------------------------------|
  |                                         |
  | 接收 chunks (300ms timeout)             |
  | 检查完整性                               |
  |                                         |
  | 5. CMD_IMG_MISSING [indices]           |
  |---------------------------------------->|
  |                                         |
  | 6. 重传丢失的 chunks                     |
  |<----------------------------------------|
  |                                         |
  | 7. CMD_IMG_FIN                         |
  |---------------------------------------->|
  |                                         |
  | 8. ACK (确认 FIN)                       |
  |<----------------------------------------|
  V                                         V
```

**特性**:
- 应用层分片: 1KB/chunk，避免 IP 分片
- 突发传输: Server 一次性发送所有分片
- 选择性重传: 仅重传丢失的分片
- 乱序容错: 根据 chunk_index 正确组装
- 高丢包容错: 40% 丢包率下仍可工作

---

## 字符串常量池

### 原理

UI 树中 "android.widget.TextView" 会出现 50+ 次，浪费 1.5KB。使用字符串池将类名编码为 1 字节 ID。

### 池结构

```
┌──────────────────┬──────────────────┬─────────────────────┐
│  ID Range        │  Type            │  Example            │
├──────────────────┼──────────────────┼─────────────────────┤
│  0x00 - 0x3F     │  预定义类名 (64) │  TextView = 0x02    │
│  0x40 - 0x7F     │  预定义文本 (64) │  "登录" = 0x4A      │
│  0x80 - 0xFE     │  动态扩展 (126)  │  自定义类名         │
│  0xFF            │  特殊标记        │  空字符串/无值      │
└──────────────────┴──────────────────┴─────────────────────┘
```

### 预定义类名 (0x00-0x3F)

```python
PREDEFINED_CLASSES = [
    "android.view.View",                    # 0x00
    "android.view.ViewGroup",               # 0x01
    "android.widget.TextView",              # 0x02
    "android.widget.EditText",              # 0x03
    "android.widget.Button",                # 0x04
    "android.widget.ImageView",             # 0x05
    # ... 共 64 个常用类
]
```

### 预定义文本 (0x40-0x7F)

```python
PREDEFINED_TEXTS = [
    "",                 # 0x40 (空字符串，高频!)
    "确定",             # 0x41
    "取消",             # 0x42
    "登录",             # 0x4A
    "OK",               # 0x50
    "Cancel",           # 0x51
    # ... 共 64 个常用文本 (中英双语)
]
```

### 编码/解码

```python
# 编码
from constants import CLASS_TO_ID
class_id = CLASS_TO_ID.get("android.widget.TextView", 0xFF)
# → 0x02

# 解码
from constants import PREDEFINED_CLASSES
class_name = PREDEFINED_CLASSES[0x02]
# → "android.widget.TextView"
```

### 收益

```
微信主界面统计:
- TextView: 30 次 × 28 bytes = 840 bytes
- 改用索引: 30 × 1 byte = 30 bytes
- 节省: 96%

总收益 (10 种常用类):
- 原始: ~5000 bytes
- 索引化: ~200 bytes
- 节省: 96%
```

---

## 错误处理

### 错误码

| Code | Name | Description |
|:-----|:-----|:------------|
| `0x00` | `ERR_SUCCESS` | 成功 |
| `0x01` | `ERR_INVALID_MAGIC` | 魔数错误 |
| `0x02` | `ERR_INVALID_VERSION` | 版本不匹配 |
| `0x03` | `ERR_INVALID_CRC` | CRC32 校验失败 |
| `0x05` | `ERR_TIMEOUT` | 操作超时 |
| `0x09` | `ERR_NOT_FOUND` | 元素未找到 (new) |
| `0x0A` | `ERR_INVALID_PARAM` | 参数无效 (new) |

### 异常类

```python
class LXBLinkError(Exception):
    """基础异常类"""
    def __init__(self, message: str, error_code: int):
        self.error_code = error_code

class LXBTimeoutError(LXBLinkError):
    """超时异常"""
    pass

class LXBChecksumError(LXBLinkError):
    """CRC32 校验失败"""
    pass
```

### 处理策略

1. **CRC32 失败**: 丢弃帧，等待超时重传
2. **序列号不匹配**: 丢弃 ACK，继续等待
3. **超时**: 重传请求 (最多 3 次)
4. **元素未找到**: 返回 `ERR_NOT_FOUND` 状态

---

## 协议扩展指南

### 添加新命令

1. **选择命令 ID** (根据分层 ISA)
   ```python
   # 新增感知能力
   CMD_NEW_SENSE = 0x36  # Sense Layer 预留区

   # 新增调试功能
   CMD_NEW_DEBUG = 0x54  # Debug Layer 预留区
   ```

2. **定义 Binary Payload 结构**
   ```c
   struct MyCommandRequest {
       uint8   arg1;
       uint16  arg2;       // LE!
       uint32  arg3;       // LE!
   };
   ```

3. **实现 Pack/Unpack 方法**
   ```python
   @staticmethod
   def pack_my_cmd(seq: int, arg1: int, arg2: int, arg3: int) -> bytes:
       payload = struct.pack('<BHI', arg1, arg2, arg3)  # < 表示 LE
       return ProtocolFrame.pack(seq, CMD_NEW_CMD, payload)

   @staticmethod
   def unpack_my_cmd(payload: bytes) -> Tuple[int, int, int]:
       return struct.unpack('<BHI', payload)
   ```

4. **更新 constants.py**
   ```python
   CMD_NEW_CMD = 0x36  # 添加到对应层
   ```

5. **更新本文档**

### 字节序检查清单

- [ ] 所有 `struct.pack` 使用 `'<'` 前缀
- [ ] 所有 `struct.unpack` 使用 `'<'` 前缀
- [ ] Java/Kotlin 代码设置 `ByteOrder.LITTLE_ENDIAN`
- [ ] 编写跨平台测试用例

---

## 配置参数

| Parameter | Default | Description |
|:----------|:--------|:------------|
| `DEFAULT_PORT` | 12345 | 默认 UDP 端口 |
| `DEFAULT_TIMEOUT` | 1.0 | Socket 超时 (秒) |
| `MAX_RETRIES` | 3 | 最大重试次数 |
| `CHUNK_SIZE` | 1024 | 分片大小 (字节) |
| `CHUNK_RECV_TIMEOUT` | 0.3 | 分片接收超时 (秒) |

---

## 版本历史

### v1.0-dev (2026-01-01) - Binary First Architecture

**核心升级**:
- ✅ 分层 ISA 架构 (Link/Input/Sense/Lifecycle/Debug/Media)
- ✅ 字符串常量池 (节省 96% 重复传输)
- ✅ Binary First 数据结构 (拒绝 JSON)
- ✅ 强制大端序 (Big Endian / Network Byte Order)
- ✅ 感知层指令集 (GET_ACTIVITY, FIND_NODE, DUMP_HIERARCHY)
- ✅ 计算卸载架构 (FIND_NODE)
- ✅ 输入扩展 (INPUT_TEXT, KEY_EVENT)

**新增指令**:
- 30+ 条新指令 (详见分层 ISA)
- 保留旧指令 ID 为 `_LEGACY` 别名 (向后兼容)

**性能提升**:
- FIND_NODE 带宽节约: 95%
- DUMP_HIERARCHY 带宽节约: 90%
- 字符串池压缩: 96%

---

## 参考资料

- [协议升级提案](PROTOCOL_UPGRADE_PROPOSAL.md)
- [协议修正与补丁](PROTOCOL_UPGRADE_PATCH.md)
- [快速开始指南](QUICKSTART.md)
- [项目 README](../README.md)

---

**维护说明**: 本协议遵循 **Binary First** 原则。添加新功能时，严禁使用 JSON 传输核心数据，必须使用紧凑的二进制结构。所有多字节整数必须使用大端序 (Big Endian / Network Byte Order)。

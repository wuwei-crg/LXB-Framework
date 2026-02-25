# benchmark_comm

通信层基准测试（PC 端发指令，手机端复用现有服务）。

## 目标
- 对比 `udp` / `tcp` / `adb_tcp` 三种链路。
- 在强网、弱网、内网穿透三类环境下比较：
  - 小包：`handshake`、`dump`
  - 大包：`screenshot`

## 快速开始
1. 复制配置模板：
   - `copy benchmark_comm\config.example.py benchmark_comm\config.py`
2. 修改 `benchmark_comm/config.py` 中的设备 IP、ADB SERIAL。
3. 运行 smoke：

```bash
python -m benchmark_comm.run --methods udp adb_tcp --profiles strong --rounds 2
```

4. 生成汇总（如需重跑汇总）：

```bash
python -m benchmark_comm.report --file benchmark_comm/results.jsonl --csv benchmark_comm/summary.csv
```

## 说明
- `udp`：走现有 LXB-Link 客户端。
- `adb_tcp`：通过 adb-over-tcp 执行 `echo` / `uiautomator dump` / `screencap`。
- `tcp`：预留可插拔模式，需要手机侧 TCP 服务支持 JSON 行协议。
- 网络环境模拟在 PC 应用层完成（延迟/抖动/丢包），参数见 `NET_PROFILES`。

## 输出
- 明细：`benchmark_comm/results.jsonl`
- 汇总：`benchmark_comm/summary.csv`

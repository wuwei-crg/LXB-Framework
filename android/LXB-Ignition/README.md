# LXB Server 部署指南

## 📦 快速开始

### 1. 一键启动服务器

```bash
.\run.bat
```

这会自动完成：
- ✅ 编译 Java 源代码
- ✅ 打包成 JAR
- ✅ 转换为 Android DEX 格式
- ✅ 推送到设备并启动

你会看到：
```
[LXB] Server starting...
[Perception] Engine initialized
[Execution] Engine initialized
[LXB] Server listening on port 12345
```

**保持这个终端运行**。

### 2. 启动 Web 控制台（新终端）

```bash
cd E:\Project\LXB-Framework\web_console
python app.py
```

浏览器访问：http://localhost:5000

### 3. 连接并测试

#### 方式 1：本地桥接测试（开发阶段）

1. **配置 AVD 模拟器为桥接网络模式**
2. **启动模拟器，查看 IP**：

   ```bash
   adb shell ip addr show eth0
   # 例如：192.168.1.105
   ```

3. **Web Console 连接配置**：
   - 主机地址：`192.168.1.105`（模拟器桥接 IP）
   - 端口：`12345`
   - 点击「连接」→「握手」→「TAP (500, 800)」

#### 方式 2：Tailscale 组网（生产部署）

1. **PC 和 Android 设备均安装 Tailscale**
   - PC：<https://tailscale.com/download>
   - Android：从 Google Play 安装 Tailscale 应用
2. **加入同一 Tailscale 网络**（使用同一账号登录）
3. **Android 设备查看 Tailscale IP**：

   ```bash
   adb shell ip addr show tailscale0
   # 例如：100.64.1.23
   ```

4. **Web Console 连接配置**：
   - 主机地址：`100.64.1.23`（Tailscale 虚拟 IP）
   - 端口：`12345`
   - 点击「连接」→「握手」→「TAP (500, 800)」

> **Tailscale 优势**：
>
> - ✅ 自动 NAT 穿透，无需公网 IP
> - ✅ 端到端加密
> - ✅ 支持 UDP，延迟极低
> - ✅ 免费个人版支持 100 台设备
> - ✅ 支持移动网络（4G/5G）和弱网环境

## 🔧 文件说明

### 主要脚本

- **`build-and-deploy.bat`** - 编译、打包、转换 DEX、推送到设备
- **`start-server.bat`** - 启动 LXB Server
- **`deploy_and_run.bat`** - （已弃用）一键部署和运行

### 构建产物

- **`lxb-core.jar`** - Java 字节码 JAR（临时文件）
- **`lxb-server.zip`** - Android DEX 格式（最终产物）

### 设备路径

- `/data/local/tmp/lxb-server.zip` - 服务器 DEX 文件

## 📱 设备要求

- Android 5.0+ (API 21+)
- 开启 USB 调试
- 无需 Root 权限（使用 `app_process`）

## 🐛 常见问题

### 问题 1：编译失败

确保已安装 JDK 11+ 并配置 `gradle.properties`：
```properties
org.gradle.java.home=C:\\Program Files\\Java\\jdk-17
```

### 问题 2：设备连接失败

检查设备连接：
```bash
adb devices
```

### 问题 3：服务器无法启动

查看设备日志：
```bash
adb logcat | findstr LXB
```

## 🎯 下一步

- [ ] 集成 UiAutomation（替换 mock 响应）
- [ ] 实现截图功能
- [ ] 添加 UI 树解析
- [ ] 优化性能和错误处理

## 📝 协议说明

参考文档：
- `docs/LXB-Link.md` - 协议规范
- `docs/LXB-Server.md` - 服务器架构

---

**注意**：当前版本使用 mock 数据，实际的屏幕操作和 UI 查询功能尚未实现。

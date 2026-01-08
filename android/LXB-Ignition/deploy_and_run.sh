#!/bin/bash
# LXB Server 部署和启动脚本（Linux/Mac 版本）

echo "========================================"
echo "LXB Server 部署脚本"
echo "========================================"
echo ""

# 1. 检查设备连接
echo "[1/5] 检查设备连接..."
adb devices
if [ $? -ne 0 ]; then
    echo "[错误] 无法连接到 adb"
    exit 1
fi

# 2. 编译 jar
echo ""
echo "[2/5] 编译 lxb-core.jar..."
./gradlew :lxb-core:buildServerJar
if [ $? -ne 0 ]; then
    echo "[错误] 编译失败"
    exit 1
fi

# 3. 推送到设备
echo ""
echo "[3/5] 推送 jar 文件到设备..."
adb push lxb-core/build/libs/lxb-core.jar /data/local/tmp/
if [ $? -ne 0 ]; then
    echo "[错误] 推送文件失败"
    exit 1
fi

# 4. 验证文件
echo ""
echo "[4/5] 验证文件..."
adb shell ls -l /data/local/tmp/lxb-core.jar

# 5. 启动服务
echo ""
echo "[5/5] 启动 LXB Server..."
echo "----------------------------------------"
echo "服务正在运行，按 Ctrl+C 停止"
echo "----------------------------------------"
echo ""
adb shell app_process -Djava.class.path=/data/local/tmp/lxb-core.jar /system/bin com.lxb.server.Main

#!/bin/bash
# Beacon-DMR 更新推送系统 —— 一键部署脚本
# 用法：上传 update_server.py 到服务器，然后执行本脚本
#   chmod +x deploy.sh && ./deploy.sh

set -e
PORT=${1:-8080}
APP_DIR="/opt/dmr-update"
APK_DIR="$APP_DIR/apks"

echo "========================================="
echo "  Beacon-DMR 更新推送系统 一键部署"
echo "========================================="
echo "  目录: $APP_DIR"
echo "  端口: $PORT"
echo ""

# 1. 创建目录
mkdir -p "$APK_DIR"
cp update_server.py "$APP_DIR/"
echo "[1/4] 文件就绪"

# 2. 检查 Python3
if ! command -v python3 &>/dev/null; then
    echo "  安装 Python3..."
    if command -v apt &>/dev/null; then
        apt update -qq && apt install -y -qq python3
    elif command -v yum &>/dev/null; then
        yum install -y -q python3
    fi
fi
echo "[2/4] Python3: $(python3 --version)"

# 3. 创建 systemd 服务
cat > /etc/systemd/system/dmr-update.service << EOF
[Unit]
Description=Beacon-DMR Update Server
After=network.target

[Service]
Type=simple
ExecStart=/usr/bin/python3 $APP_DIR/update_server.py --port $PORT --apk-dir $APK_DIR
WorkingDirectory=$APP_DIR
Restart=always
RestartSec=5
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable dmr-update -q
systemctl restart dmr-update
echo "[3/4] 服务已启动"

# 4. 验证
sleep 2
if curl -s "http://127.0.0.1:$PORT/api/manifest" >/dev/null 2>&1; then
    echo "[4/4] ✅ 服务正常！"
else
    echo "[4/4] ⚠ 服务可能未就绪，查看日志: journalctl -u dmr-update -f"
fi

echo ""
echo "========================================="
echo "  部署完成！"
echo "========================================="
echo ""
echo "  管理页: http://$(hostname -I | awk '{print $1}'):$PORT/"
echo "  API:    http://$(hostname -I | awk '{print $1}'):$PORT/api/version"
echo ""
echo "  放入 APK:"
echo "    cp beacon-dmr-xxx-release.apk $APK_DIR/"
echo ""
echo "  App 配置:"
echo "    设置 → 更新服务器 → http://$(hostname -I | awk '{print $1}'):$PORT"
echo ""
echo "  常用命令:"
echo "    systemctl status dmr-update    查看状态"
echo "    journalctl -u dmr-update -f   查看日志"
echo "    systemctl restart dmr-update  重启"
echo ""
echo "  ⚠ 记得在防火墙放行端口 $PORT"
echo ""

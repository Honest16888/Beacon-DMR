#!/bin/bash
# Beacon-DMR 更新推送系统 —— 打包部署文件
# 执行本脚本生成 dmr-update-deploy.tar.gz

set -e
STAGE="dmr-update-deploy"
rm -rf "$STAGE"
mkdir -p "$STAGE/apks"

# 复制文件
cp tools/update_server.py "$STAGE/"
cp tools/deploy.sh "$STAGE/"
cp docs/远程更新推送.md "$STAGE/README.md"

# 放一个示例 APK（如果有）
if [ -f dist/beacon-dmr-2.10.13-release.apk ]; then
    cp dist/beacon-dmr-2.10.13-release.apk "$STAGE/apks/"
fi

# 生成 manifest 示例
cat > "$STAGE/apks/manifest.json" << 'EOF'
{
  "latest": "2.10.13",
  "versions": {
    "2.10.13": {
      "versionCode": 59,
      "apk": "beacon-dmr-2.10.13-release.apk",
      "size": 2319853,
      "changelog": "远程更新推送",
      "force": false
    }
  }
}
EOF

# 打包
tar czf "${STAGE}.tar.gz" "$STAGE"
rm -rf "$STAGE"

echo "✅ 已生成 ${STAGE}.tar.gz"
echo ""
echo "部署方法："
echo "  1. 上传 dmr-update-deploy.tar.gz 到服务器"
echo "  2. tar xzf dmr-update-deploy.tar.gz"
echo "  3. cd dmr-update-deploy"
echo "  4. chmod +x deploy.sh && ./deploy.sh"
echo "  5. 防火墙放行 8080 端口"
echo ""
echo "App 配置："
echo "  设置 → 更新服务器 → http://服务器IP:8080"

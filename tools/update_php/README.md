# Beacon-DMR 更新推送（PHP 版）

**零配置部署**——上传到网站目录就能用，不需要装 Python、不需要跑命令。

## 部署步骤

1. 把 `update/` 文件夹上传到网站根目录（比如 `/www/wwwroot/你的站点/`）
2. 浏览器打开 `http://你的站点/update/`
3. 完成

## 目录结构

```
你的网站根目录/
└── update/
    ├── index.php          管理后台 + API（就是这一个文件）
    └── apks/              APK 文件放这里
        └── *.apk
```

## 功能

- 📊 版本统计（版本数、最新版本、下载次数）
- 📦 上传 APK（点击或拖拽，自动识别版本号）
- 📋 版本列表（编辑更新说明、强制更新开关、删除）
- 📥 下载统计（自动记录每个版本下载次数）
- 🔌 API 接口（App 调用）

## App 配置

设置 → 更新服务器 → `http://你的站点/update/`

## API 接口

| 接口 | 说明 |
|---|---|
| `?action=version&current=x.y.z` | 检查更新 |
| `?action=download&ver=x.y.z` | 下载 APK |
| `?action=manifest` | 版本清单 |

## 发布新版

直接在管理后台上传 APK，或者把 APK 文件丢进 `apks/` 目录。

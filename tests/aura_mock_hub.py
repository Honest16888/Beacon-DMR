#!/usr/bin/env python3
"""AURA Hub 的**本地替身**：只实现 APP_INTEGRATION.md 里普通用户要用的那几条。

用途是让客户端能端到端跑起来（注册 → 绑定 → 登录 → bootstrap → WS join/talk →
收发音频），而不是去连真的 Hub。它**不是**参考实现：没有限流、没有 ACL、
没有踢人与申诉的审批流，凭据也只是内存里的随机串。

    python tests/aura_mock_hub.py --port 8099
    # 客户端里把 Hub 地址填 http://127.0.0.1:8099
    # GET /__stats 看这几件事有没有发生（收到几帧、谁登录过）

实现照协议本体的关键约束来，方便客户端拿它做对照：
  · enrollment_token 900 秒、一次性；temporary_key 一次性（失败也消费）
  · 登录失败统一 401 + {"ok":false,"error":"登录凭据无效或已过期"}
  · WS 用 ?token=，二进制帧首字节 0x01 = PCM s16le 8kHz mono
  · join 必须 channel_id + tg_id；talk 的 on 必须是 JSON boolean
"""
from __future__ import annotations

import argparse
import asyncio
import json
import math
import secrets
import time
import uuid

from aiohttp import web, WSMsgType

CHANNELS = [
    {
        "id": 12, "name": "全国公共频道", "proto_type": "dmr", "target": "BM",
        "owner_uid": "0" * 64, "owner_callsign": "BH6RGQ", "is_public": True,
        "status": "active", "created": 1700000000.0, "config_valid": True,
        "config": {}, "test_status": "ok", "test_at": 1700000000.0,
        "test_error_code": "", "test_error": "", "test_latency_ms": 12,
        "tgs": [
            {"id": 34, "channel_id": 12, "tg": 460, "name": "中国全国组", "created": 1700000000.0},
            {"id": 35, "channel_id": 12, "tg": 860, "name": "全国公共二组", "created": 1700000000.0},
            {"id": 36, "channel_id": 12, "tg": 46001, "name": "测试话组", "created": 1700000000.0},
        ],
    },
    {
        "id": 13, "name": "技术讨论频道", "proto_type": "dmr", "target": "BM",
        "owner_uid": "1" * 64, "owner_callsign": "BG5ABC", "is_public": True,
        "status": "active", "created": 1700000100.0, "config_valid": True,
        "config": {}, "test_status": "ok", "test_at": 1700000100.0,
        "test_error_code": "", "test_error": "", "test_latency_ms": 20,
        "tgs": [
            {"id": 41, "channel_id": 13, "tg": 91, "name": "World-wide", "created": 1700000100.0},
        ],
    },
]

CORS = {
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Headers": "Authorization, Content-Type, Accept",
    "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
}


def now() -> float:
    return time.time()


class Hub:
    def __init__(self):
        self.users: dict[str, dict] = {}     # login_id -> user
        self.by_uid: dict[str, dict] = {}    # uid -> user（bearer 用 uid 反查）
        self.enroll: dict[str, dict] = {}      # token -> {uid, exp}
        self.tempkeys: dict[str, dict] = {}    # key -> {uid, used}
        self.sessions: dict[str, dict] = {}    # token -> {uid, exp}
        self.stats = {"register": 0, "bind": 0, "login_ok": 0, "login_fail": 0,
                      "ws_open": 0, "join": 0, "talk_on": 0, "talk_off": 0,
                      "audio_in": 0, "audio_bytes": 0, "pcm_out": 0}

    # ---------------- helpers ----------------
    def user_by_uid(self, uid: str):
        return self.by_uid.get(uid)

    def user_by_device(self, device: str):
        for u in self.users.values():
            if u.get("device") == device:
                return u
        return None

    def bearer(self, req: web.Request):
        h = req.headers.get("Authorization", "")
        if not h.startswith("Bearer "):
            return None
        tok = h[7:].strip()
        s = self.sessions.get(tok)
        if not s:
            return None
        if s["exp"] < now():
            self.sessions.pop(tok, None)
            return None
        return self.by_uid.get(s["uid"])

    def err(self, status: int, msg: str, code: str = ""):
        body = {"ok": False, "error": msg}
        if code:
            body["error_code"] = code
        return web.json_response(body, status=status, headers=CORS)


HUB = Hub()


# ---------------- auth ----------------
async def register(req: web.Request):
    try:
        b = await req.json()
    except Exception:
        return HUB.err(400, "请求不是合法 JSON")
    login_id = str(b.get("login_id") or "").strip()
    password = str(b.get("password") or "")
    code = str(b.get("security_code") or "")
    if not login_id or not password:
        return HUB.err(400, "login_id / password 不能为空")
    if len(login_id) > 256 or len(password) > 1024:
        return HUB.err(400, "字段超长")
    if not (len(code) == 3 and code.isdigit() and code.isascii()):
        return HUB.err(400, "security_code 必须是三位 ASCII 数字")
    HUB.stats["register"] += 1
    # 测试钩子：密码固定为 bad860 时模拟「860 身份验证失败」（协议 7.2 的 401）
    if password == "bad860":
        return web.json_response({"ok": False, "error": "860 身份验证失败"},
                                 status=401, headers=CORS)


    u = HUB.users.get(login_id)
    if u and not b.get("reverify"):
        return HUB.err(409, "该账号已注册，需要恢复请带 reverify")

    is_new = u is None
    if is_new:
        uid = uuid.uuid4().hex
        u = {"uid": uid, "login_id": login_id, "password": password, "code": code,
             "callsign": "BH6" + secrets.choice("ABC") + "%03d" % secrets.randbelow(999),
             "dmr_id": 460000 + secrets.randbelow(9999), "role": "member",
             "status": "normal", "device": ""}
        HUB.users[login_id] = u
        HUB.by_uid[u["uid"]] = u
    else:
        u["password"] = password
        u["code"] = code
        # 重新核验：撤销旧设备凭据与旧会话
        u["device"] = ""
        for tok, s in list(HUB.sessions.items()):
            if s["uid"] == u["uid"]:
                HUB.sessions.pop(tok, None)

    etok = secrets.token_hex(16)
    HUB.enroll[etok] = {"uid": u["uid"], "exp": now() + 900}
    return web.json_response({
        "ok": True, "uid": u["uid"], "callsign": u["callsign"], "dmr_id": u["dmr_id"],
        "role": u["role"], "status": u["status"], "created": now(),
        "enrollment_token": etok, "enrollment_expires": now() + 900,
    }, status=201, headers=CORS)


async def bind_device(req: web.Request):
    try:
        b = await req.json()
    except Exception:
        return HUB.err(400, "请求不是合法 JSON")
    etok = str(b.get("enrollment_token") or "")
    e = HUB.enroll.get(etok)
    if not e or e["exp"] < now():
        return HUB.err(401, "绑定令牌无效或已过期")
    HUB.enroll.pop(etok, None)           # 一次性
    u = HUB.by_uid.get(e["uid"])
    if not u:
        return HUB.err(401, "绑定令牌无效或已过期")
    dev = "dev_" + secrets.token_hex(24)
    u["device"] = dev
    HUB.stats["bind"] += 1
    return web.json_response({"ok": True, "device_credential": dev}, headers=CORS)


async def issue_key(req: web.Request):
    try:
        b = await req.json()
    except Exception:
        return HUB.err(400, "请求不是合法 JSON")
    dev = str(b.get("device_credential") or "")
    u = HUB.user_by_device(dev)
    if not u:
        return HUB.err(401, "设备凭据无效")
    key = secrets.token_urlsafe(16)[:21]
    HUB.tempkeys[key] = {"uid": u["uid"], "used": False}
    return web.json_response({"ok": True, "temporary_key": key,
                              "expires_at": int(now() + 300)}, headers=CORS)


async def login(req: web.Request):
    try:
        b = await req.json()
    except Exception:
        return HUB.err(400, "请求不是合法 JSON")
    key = str(b.get("temporary_key") or "")
    code = str(b.get("security_code") or "")
    rec = HUB.tempkeys.get(key)
    bad = {"ok": False, "error": "登录凭据无效或已过期"}
    if not rec or rec["used"]:
        HUB.stats["login_fail"] += 1
        return web.json_response(bad, status=401, headers=CORS)
    rec["used"] = True                    # 无论成败都消费
    u = HUB.by_uid.get(rec["uid"])
    if not u or not (len(code) == 3 and code.isdigit()) or code != u["code"]:
        HUB.stats["login_fail"] += 1
        return web.json_response(bad, status=401, headers=CORS)
    tok = "tok_" + secrets.token_hex(24)
    exp = now() + 28800
    HUB.sessions[tok] = {"uid": u["uid"], "exp": exp}
    HUB.stats["login_ok"] += 1
    return web.json_response({
        "ok": True, "token": tok, "expires_in": 28800, "expires_at": exp,
        "role": u["role"], "uid": u["uid"], "callsign": u["callsign"],
    }, headers=CORS)


async def logout(req: web.Request):
    h = req.headers.get("Authorization", "")
    if h.startswith("Bearer "):
        HUB.sessions.pop(h[7:].strip(), None)
    return web.json_response({"ok": True}, headers=CORS)


# ---------------- ptt rest ----------------
async def bootstrap(req: web.Request):
    u = HUB.bearer(req)
    if not u:
        return HUB.err(401, "会话无效")
    return web.json_response({
        "ok": True,
        "user": {"uid": u["uid"], "callsign": u["callsign"], "dmr_id": u["dmr_id"],
                 "role": u["role"]},
        "channels": CHANNELS,
        "stats": {"channels": len(CHANNELS),
                  "tgs": sum(len(c["tgs"]) for c in CHANNELS)},
    }, headers=CORS)


async def my_joins(req: web.Request):
    if not HUB.bearer(req):
        return HUB.err(401, "会话无效")
    return web.json_response({"ok": True, "joins": []}, headers=CORS)


async def my_appeals(req: web.Request):
    if not HUB.bearer(req):
        return HUB.err(401, "会话无效")
    return web.json_response({"ok": True, "appeals": []}, headers=CORS)


async def public_overview(req: web.Request):
    """协议 5.6：无需登录的公开目录。客户端拿它当连通性探针。"""
    return web.json_response({
        "ok": True,
        "channels": [{"id": c["id"], "name": c["name"], "is_public": True,
                      "status": "active"} for c in CHANNELS],
        "operators": [{"callsign": "BH6RGQ", "channels": 1}],
        "stats": {"channels": len(CHANNELS),
                  "tgs": sum(len(c["tgs"]) for c in CHANNELS),
                  "online": 0},
    }, headers=CORS)


async def stats(req: web.Request):
    return web.json_response({"ok": True, "stats": HUB.stats,
                              "users": len(HUB.users),
                              "sessions": len(HUB.sessions)}, headers=CORS)


# ---------------- websocket ----------------
def pcm_tone(n: int, hz: float = 700.0, amp: float = 0.25) -> bytes:
    out = bytearray(1 + n * 2)
    out[0] = 0x01
    for i in range(n):
        v = int(amp * 32767 * math.sin(2 * math.pi * hz * i / 8000))
        v &= 0xFFFF
        out[1 + i * 2] = v & 0xFF
        out[2 + i * 2] = (v >> 8) & 0xFF
    return bytes(out)


async def ws_ptt(req: web.Request):
    tok = req.query.get("token", "")
    s = HUB.sessions.get(tok)
    if not s or s["exp"] < now():
        return web.Response(status=401, text="unauthorized")

    ws = web.WebSocketResponse(heartbeat=20)
    await ws.prepare(req)
    HUB.stats["ws_open"] += 1
    u = HUB.by_uid.get(s["uid"])
    callsign = u["callsign"] if u else "UNKNOWN"
    joined = {"channel_id": 0, "tg_id": 0, "tg": 0}

    await ws.send_json({"type": "welcome", "cmd": "welcome", "uid": s["uid"],
                        "callsign": callsign, "tgs": [], "policy": {}})

    async def peer_talker(on: bool):
        await ws.send_json({"type": "talker", "cmd": "talker",
                            "channel_id": joined["channel_id"], "tg_id": joined["tg_id"],
                            "tg": joined["tg"], "callsign": "BG5ABC", "on": on})

    try:
        async for msg in ws:
            if msg.type == WSMsgType.TEXT:
                try:
                    m = json.loads(msg.data)
                except Exception:
                    continue
                t = m.get("type") or m.get("cmd")
                if t == "join":
                    cid = m.get("channel_id")
                    tid = m.get("tg_id")
                    ch = next((c for c in CHANNELS if c["id"] == cid), None)
                    tg = next((x for x in (ch["tgs"] if ch else []) if x["id"] == tid), None)
                    if not ch or not tg:
                        await ws.send_json({"cmd": "error", "type": "error",
                                            "code": "forbidden", "error": "无权访问该端点",
                                            "message": "无权访问该端点"})
                        continue
                    joined.update({"channel_id": cid, "tg_id": tid, "tg": tg["tg"]})
                    HUB.stats["join"] += 1
                    await ws.send_json({
                        "type": "joined", "cmd": "joined",
                        "channel_id": cid, "tg_id": tid, "tg": tg["tg"],
                        "members": [{"callsign": callsign, "talking": False},
                                    {"callsign": "BG5ABC", "talking": False}],
                    })
                    # 模拟对端在说话：先给 talker，再推一帧 PCM（测下行链路）
                    await peer_talker(True)
                    await ws.send_bytes(pcm_tone(1600))
                    HUB.stats["pcm_out"] += 1
                    await peer_talker(False)
                elif t == "leave":
                    joined.update({"channel_id": 0, "tg_id": 0, "tg": 0})
                    await ws.send_json({"type": "left", "cmd": "left"})
                elif t == "talk":
                    on = m.get("on")
                    if not isinstance(on, bool):
                        await ws.send_json({"cmd": "error", "type": "error",
                                            "code": "invalid_endpoint",
                                            "error": "on 必须是 boolean",
                                            "message": "on 必须是 boolean"})
                        continue
                    if not joined["tg_id"]:
                        await ws.send_json({"cmd": "error", "type": "error",
                                            "code": "not_talking",
                                            "error": "尚未加入端点",
                                            "message": "尚未加入端点"})
                        continue
                    HUB.stats["talk_on" if on else "talk_off"] += 1
                    # 把说话状态广播给「其他人」——本地替身只有一个连接，回显给自己便于观察
                    await ws.send_json({"type": "talker", "cmd": "talker",
                                        "channel_id": joined["channel_id"],
                                        "tg_id": joined["tg_id"], "tg": joined["tg"],
                                        "callsign": callsign, "on": on})
            elif msg.type == WSMsgType.BINARY:
                data = msg.data
                if not data:
                    continue
                tag = data[0]
                body = data[1:]
                if tag == 0x01 and len(body) and len(body) % 2 == 0 and len(body) <= 6400:
                    HUB.stats["audio_in"] += 1
                    HUB.stats["audio_bytes"] += len(body)
                # 真实 Hub 不会把发话者的语音回给他；这里也不回。
    finally:
        await ws.close()
    return ws


def build_app() -> web.Application:
    app = web.Application()
    app.router.add_route("OPTIONS", "/{tail:.*}", lambda r: web.Response(status=204, headers=CORS))
    app.router.add_post("/api/auth/register", register)
    app.router.add_post("/api/auth/bind-device", bind_device)
    app.router.add_post("/api/auth/issue-ptt-key", issue_key)
    app.router.add_post("/api/auth/login", login)
    app.router.add_post("/api/auth/logout", logout)
    app.router.add_get("/api/public/overview", public_overview)
    app.router.add_get("/api/ptt/bootstrap", bootstrap)
    app.router.add_get("/api/ptt/channels", bootstrap)
    app.router.add_get("/api/ptt/my/joins", my_joins)
    app.router.add_get("/api/ptt/my/appeals", my_appeals)
    app.router.add_get("/__stats", stats)
    app.router.add_get("/ws/ptt", ws_ptt)
    return app


def main():
    ap = argparse.ArgumentParser(description="AURA Hub 本地替身")
    ap.add_argument("--port", type=int, default=8099)
    ap.add_argument("--host", default="127.0.0.1")
    a = ap.parse_args()
    print("AURA mock hub on http://%s:%d" % (a.host, a.port), flush=True)
    web.run_app(build_app(), host=a.host, port=a.port, print=None)


if __name__ == "__main__":
    main()

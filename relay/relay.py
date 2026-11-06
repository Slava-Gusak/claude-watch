#!/usr/bin/env python3
"""claude-watch relay: runs on the VPS.

* Signs in to Claude once (the same OAuth client and "paste the code" flow Claude Code uses),
  polls GET /api/oauth/usage and keeps the tokens fresh.
* Receives status snapshots from the PC agent (Claude Desktop / Claude Code state).
* Serves one JSON document to the watch.

Stdlib only. Config: /opt/claude-watch/config.json, state: /opt/claude-watch/state.json
"""
import base64
import hashlib
import html
import json
import os
import secrets
import ssl
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

BASE = os.environ.get("CLAUDE_WATCH_DIR", "/opt/claude-watch")
CONFIG_PATH = os.path.join(BASE, "config.json")
STATE_PATH = os.path.join(BASE, "state.json")

CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"
AUTHORIZE_URL = "https://claude.com/cai/oauth/authorize"
TOKEN_URL = "https://platform.claude.com/v1/oauth/token"
REDIRECT_URI = "https://platform.claude.com/oauth/code/callback"
SCOPES = "user:profile user:inference"
USAGE_URL = "https://api.anthropic.com/api/oauth/usage"
UA = "claude-watch-relay/1.0"

POLL_SECONDS = 90          # usage poll interval
ACTIVE_WINDOW = 6 * 60     # "Claude is busy" if the 5h limit grew within this many seconds
PC_ONLINE_WINDOW = 90      # PC agent counts as online if it reported within this many seconds

lock = threading.RLock()
config = {}
state = {
    "tokens": None,            # {access, refresh, expires_at(ms)}
    "pending_login": None,     # {verifier, state}
    "usage": None,             # {session:{percent,resets_at,severity}, week:{...}, fetched_at}
    "usage_error": None,
    "login_needed": True,
    "history": [],             # [[ts_ms, session_utilization_float]]
    "last_change": None,       # ms when the 5h utilization last grew
    "pc": None,                # last PC snapshot
    "pc_seen": None,           # ms
    "pair": None,              # {code, expires}
}


def now_ms():
    return int(time.time() * 1000)


def log(*a):
    print(datetime.now().strftime("%H:%M:%S"), *a, flush=True)


def load():
    global config
    with open(CONFIG_PATH) as f:
        config = json.load(f)
    if os.path.exists(STATE_PATH):
        try:
            with open(STATE_PATH) as f:
                state.update(json.load(f))
        except Exception as e:  # corrupt state: start clean but keep running
            log("state load failed:", e)


def save():
    with lock:
        tmp = STATE_PATH + ".tmp"
        with open(tmp, "w") as f:
            json.dump(state, f)
        os.chmod(tmp, 0o600)
        os.replace(tmp, STATE_PATH)


# ---------------------------------------------------------------- Claude API

class ApiError(Exception):
    def __init__(self, code, msg):
        super().__init__(msg)
        self.code = code


def http(url, method="GET", bearer=None, payload=None):
    headers = {"User-Agent": UA, "Accept": "application/json"}
    data = None
    if bearer:
        headers["Authorization"] = "Bearer " + bearer
        headers["anthropic-beta"] = "oauth-2025-04-20"
    if payload is not None:
        data = json.dumps(payload).encode()
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=25) as r:
            return json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        body = e.read().decode(errors="replace")[:300]
        raise ApiError(e.code, f"HTTP {e.code}: {body}")


def b64url(b):
    return base64.urlsafe_b64encode(b).rstrip(b"=").decode()


def start_login():
    verifier = b64url(secrets.token_bytes(32))
    st = b64url(secrets.token_bytes(32))
    challenge = b64url(hashlib.sha256(verifier.encode()).digest())
    with lock:
        state["pending_login"] = {"verifier": verifier, "state": st}
    save()
    q = urllib.parse.urlencode({
        "code": "true", "client_id": CLIENT_ID, "response_type": "code",
        "redirect_uri": REDIRECT_URI, "scope": SCOPES, "code_challenge": challenge,
        "code_challenge_method": "S256", "state": st,
    })
    return AUTHORIZE_URL + "?" + q


def token_request(payload, previous_refresh):
    o = http(TOKEN_URL, "POST", payload=payload)
    exp = int(o.get("expires_in") or 0)
    return {
        "access": o["access_token"],
        "refresh": o.get("refresh_token") or previous_refresh,
        "expires_at": now_ms() + exp * 1000 if exp else 0,
    }


def complete_login(pasted):
    with lock:
        pending = state.get("pending_login")
    if not pending:
        raise ValueError("Сначала нажми «Войти в Claude»")
    code, _, st = pasted.strip().partition("#")
    if st and st.strip() != pending["state"]:
        raise ValueError("Код от другой попытки входа — начни заново")
    tokens = token_request({
        "grant_type": "authorization_code", "code": code.strip(), "state": pending["state"],
        "client_id": CLIENT_ID, "redirect_uri": REDIRECT_URI, "code_verifier": pending["verifier"],
    }, None)
    with lock:
        state["tokens"] = tokens
        state["pending_login"] = None
        state["login_needed"] = False
        state["usage_error"] = None
    save()


def renew():
    with lock:
        t = state["tokens"]
    if not t or not t.get("refresh"):
        raise ApiError(401, "no refresh token")
    try:
        nt = token_request({"grant_type": "refresh_token", "refresh_token": t["refresh"],
                            "client_id": CLIENT_ID}, t["refresh"])
    except ApiError as e:
        if e.code in (400, 401, 403):
            with lock:
                state["login_needed"] = True
            save()
        raise
    with lock:
        state["tokens"] = nt
    save()
    return nt


def parse_window(o, severity):
    if not isinstance(o, dict):
        return None
    return {
        "utilization": float(o.get("utilization") or 0.0),
        "percent": max(0, round(float(o.get("utilization") or 0.0))),
        "resets_at": o.get("resets_at"),
        "severity": severity or None,
    }


def poll_usage():
    with lock:
        t = state["tokens"]
    if not t:
        return
    if t.get("expires_at") and t["expires_at"] - now_ms() < 5 * 60_000:
        t = renew()
    try:
        raw = http(USAGE_URL, bearer=t["access"])
    except ApiError as e:
        if e.code != 401:
            raise
        raw = http(USAGE_URL, bearer=renew()["access"])
    sev = {}
    for l in raw.get("limits") or []:
        if isinstance(l, dict):
            sev[l.get("group")] = l.get("severity")
    session = parse_window(raw.get("five_hour"), sev.get("session"))
    week = parse_window(raw.get("seven_day"), sev.get("weekly"))
    ts = now_ms()
    with lock:
        prev = state["history"][-1][1] if state["history"] else None
        cur = session["utilization"] if session else None
        if cur is not None and prev is not None and cur > prev + 1e-9:
            state["last_change"] = ts
        if cur is not None:
            state["history"] = (state["history"] + [[ts, cur]])[-60:]
        state["usage"] = {"session": session, "week": week, "fetched_at": ts}
        state["usage_error"] = None
        state["login_needed"] = False
    save()


def poller():
    backoff = POLL_SECONDS
    while True:
        try:
            poll_usage()
            backoff = POLL_SECONDS
        except Exception as e:
            log("poll error:", e)
            with lock:
                state["usage_error"] = str(e)[:200]
            save()
            if isinstance(e, ApiError) and e.code == 429:
                backoff = min(backoff * 2, 900)
        time.sleep(backoff)


# ---------------------------------------------------------------- status

def status_doc():
    ts = now_ms()
    with lock:
        u = state["usage"]
        pc = state["pc"] or {}
        seen = state["pc_seen"]
        last_change = state["last_change"]
        doc = {
            "now": ts,
            "login_needed": bool(state["login_needed"]),
            "usage": None,
            "usage_error": state["usage_error"],
            "activity": {
                "active": bool(last_change and ts - last_change < ACTIVE_WINDOW * 1000),
                "last_change": last_change,
            },
            "pc": {
                "online": bool(seen and ts - seen < PC_ONLINE_WINDOW * 1000),
                "last_seen": seen,
                "desktop": pc.get("desktop"),
                "code": pc.get("code"),
            },
        }
    if u:
        doc["usage"] = {
            "session": {k: u["session"][k] for k in ("percent", "resets_at", "severity")} if u.get("session") else None,
            "week": {k: u["week"][k] for k in ("percent", "resets_at", "severity")} if u.get("week") else None,
            "fetched_at": u["fetched_at"],
        }
    return doc


# ---------------------------------------------------------------- web

PAGE = """<!doctype html><html lang=ru><meta charset=utf-8>
<meta name=viewport content="width=device-width,initial-scale=1">
<title>claude-watch</title>
<style>body{{font:16px system-ui;background:#1a1915;color:#eee;max-width:560px;margin:auto;padding:20px}}
a.b,button{{display:block;width:100%;padding:14px;margin:10px 0;border:0;border-radius:12px;background:#d97757;color:#fff;font-size:17px;text-align:center;text-decoration:none}}
input{{width:100%;box-sizing:border-box;padding:12px;font-size:16px;border-radius:10px;border:1px solid #555;background:#2a2925;color:#eee}}
.card{{background:#2a2925;border-radius:14px;padding:14px;margin:12px 0}} .pair{{font-size:44px;letter-spacing:8px;text-align:center;font-weight:700}}
small{{color:#aaa}} .err{{color:#ff8a80}}</style>
<h2>claude-watch</h2>{body}</html>"""


def admin_page(msg="", host=""):
    with lock:
        logged = state["tokens"] is not None and not state["login_needed"]
        u = state["usage"]
        pair = state["pair"]
    key = config["admin_token"]
    parts = []
    if msg:
        parts.append(f"<div class='card err'>{html.escape(msg)}</div>")
    if logged:
        line = "ждём первые данные…"
        if u and u.get("session"):
            line = f"5 часов: {u['session']['percent']}%"
            if u.get("week"):
                line += f" · неделя: {u['week']['percent']}%"
        parts.append(f"<div class=card>✅ Вход выполнен<br><small>{html.escape(line)}</small></div>")
    parts.append(f"""<div class=card><b>1. Вход в Claude</b><br><small>Откроется страница Claude: подтверди доступ,
скопируй показанный код и вставь сюда.</small>
<a class=b target=_blank href="/admin/start?key={key}">Войти в Claude</a>
<form method=post action="/admin/code?key={key}"><input name=code placeholder="Вставь код сюда" autocomplete=off>
<button>Сохранить код</button></form></div>""")
    if pair and pair["expires"] > now_ms():
        mins = max(1, (pair["expires"] - now_ms()) // 60000)
        parts.append(f"<div class=card><b>2. Код для часов</b><div class=pair>{pair['code']}</div>"
                     f"<small>Введи его на часах. Действует ещё {mins} мин, один раз.</small></div>")
    else:
        parts.append(f"""<div class=card><b>2. Привязка часов</b>
<form method=post action="/admin/pair?key={key}"><button>Показать код для часов</button></form></div>""")
    host = html.escape(host)
    parts.append(f"""<div class=card><b>3. Для ПК</b> <small>(когда будешь дома: install.bat попросит эту строку)</small>
<input readonly onclick="this.select()" value="https://{host}|{html.escape(config['pc_token'])}"></div>""")
    return PAGE.format(body="".join(parts))


class Handler(BaseHTTPRequestHandler):
    server_version = "claude-watch"

    def log_message(self, fmt, *args):
        pass

    def send(self, code, body, ctype="application/json; charset=utf-8"):
        if isinstance(body, (dict, list)):
            body = json.dumps(body, ensure_ascii=False)
        data = body.encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    def redirect(self, to):
        self.send_response(303)
        self.send_header("Location", to)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def bearer(self):
        h = self.headers.get("Authorization", "")
        return h[7:].strip() if h.startswith("Bearer ") else ""

    def query(self):
        return urllib.parse.parse_qs(urllib.parse.urlsplit(self.path).query)

    def is_admin(self):
        k = (self.query().get("key") or [""])[0]
        return secrets.compare_digest(k, config["admin_token"])

    def body(self, limit=64_000):
        n = int(self.headers.get("Content-Length") or 0)
        return self.rfile.read(min(n, limit))

    def do_GET(self):
        path = urllib.parse.urlsplit(self.path).path
        if path == "/health":
            return self.send(200, {"ok": True})
        if path == "/status":
            if not secrets.compare_digest(self.bearer(), config["watch_token"]):
                return self.send(401, {"error": "unauthorized"})
            return self.send(200, status_doc())
        if path == "/pair":
            code = (self.query().get("code") or [""])[0].strip()
            with lock:
                p = state["pair"]
                ok = p and p["expires"] > now_ms() and code and secrets.compare_digest(code, p["code"])
                if ok:
                    state["pair"] = None
            if not ok:
                time.sleep(1.5)  # slow down guessing
                return self.send(403, {"error": "bad code"})
            save()
            return self.send(200, {"token": config["watch_token"]})
        if path == "/admin":
            if not self.is_admin():
                return self.send(404, "not found", "text/plain")
            return self.send(200, admin_page(host=self.headers.get("Host", "")), "text/html; charset=utf-8")
        if path == "/admin/start":
            if not self.is_admin():
                return self.send(404, "not found", "text/plain")
            return self.redirect(start_login())
        self.send(404, {"error": "not found"})

    def do_POST(self):
        path = urllib.parse.urlsplit(self.path).path
        if path == "/pc":
            if not secrets.compare_digest(self.bearer(), config["pc_token"]):
                return self.send(401, {"error": "unauthorized"})
            try:
                snap = json.loads(self.body() or b"{}")
            except ValueError:
                return self.send(400, {"error": "bad json"})
            with lock:
                state["pc"] = {"desktop": snap.get("desktop"), "code": snap.get("code")}
                state["pc_seen"] = now_ms()
            return self.send(200, {"ok": True})
        if not path.startswith("/admin/") or not self.is_admin():
            return self.send(404, {"error": "not found"})
        if path == "/admin/code":
            form = urllib.parse.parse_qs(self.body().decode(errors="replace"))
            code = (form.get("code") or [""])[0]
            try:
                complete_login(code)
                threading.Thread(target=lambda: _safe(poll_usage), daemon=True).start()
                time.sleep(2)
                return self.send(200, admin_page(host=self.headers.get("Host", "")), "text/html; charset=utf-8")
            except Exception as e:
                return self.send(200, admin_page(f"Не вышло: {e}", self.headers.get("Host", "")), "text/html; charset=utf-8")
        if path == "/admin/pair":
            with lock:
                state["pair"] = {"code": f"{secrets.randbelow(1_000_000):06d}", "expires": now_ms() + 10 * 60_000}
            save()
            return self.redirect(f"/admin?key={config['admin_token']}")
        self.send(404, {"error": "not found"})


def _safe(fn):
    try:
        fn()
    except Exception as e:
        log("error:", e)


class Server(ThreadingHTTPServer):
    daemon_threads = True
    tls = None

    def finish_request(self, request, client_address):
        # TLS handshake per connection, in the worker thread, so a stuck client can't block accept()
        request.settimeout(30)
        if self.tls:
            try:
                request = self.tls.wrap_socket(request, server_side=True)
            except (ssl.SSLError, OSError):
                return
        super().finish_request(request, client_address)


def main():
    load()
    srv = Server((config.get("host", "0.0.0.0"), int(config.get("port", 8443))), Handler)
    if config.get("cert") and config.get("key"):
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ctx.load_cert_chain(config["cert"], config["key"])
        srv.tls = ctx
    threading.Thread(target=poller, daemon=True).start()
    log("listening on", srv.server_address, "tls" if config.get("cert") else "plain")
    srv.serve_forever()


if __name__ == "__main__":
    sys.exit(main())

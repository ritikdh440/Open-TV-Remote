#!/usr/bin/env python3
"""
Foxsky TV remote over ADB.

Usage:
    python tv_remote.py 192.168.1.50
(replace with your TV's IP address; find it in TV Settings > Network)

Then open the address it prints (e.g. http://192.168.1.20:8080) on your phone.
Phone and computer must be on the same Wi-Fi as the TV.
Requires: Python 3 and adb installed and in PATH. No other packages needed.
"""
import socket
import subprocess
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import unquote

TV = sys.argv[1] if len(sys.argv) > 1 else None
PORT = 8080
TARGET = f"{TV}:5555" if TV and ":" not in TV else TV

KEYS = {
    "power": 26, "wake": 224, "sleep": 223,
    "up": 19, "down": 20, "left": 21, "right": 22, "ok": 23,
    "back": 4, "home": 3, "menu": 82, "settings": 176, "input": 178,
    "volup": 24, "voldown": 25, "mute": 164,
    "chup": 166, "chdown": 167,
    "play": 85, "rew": 89, "ff": 90,
}

APPS = {
    "YouTube": "com.google.android.youtube.tv",
    "Netflix": "com.netflix.ninja",
    "Prime": "com.amazon.amazonvideo.livingroom",
    "Hotstar": "in.startv.hotstar",
    "Play Store": "com.android.vending",
}


def adb(*args):
    cmd = ["adb"]
    if TARGET:
        cmd += ["-s", TARGET]
    cmd += list(args)
    try:
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=8)
        return (r.stdout + r.stderr).strip()
    except Exception as e:
        return f"error: {e}"


def connect():
    if TARGET:
        print(adb_raw("connect", TARGET))


def adb_raw(*args):
    try:
        r = subprocess.run(["adb", *args], capture_output=True, text=True, timeout=10)
        return (r.stdout + r.stderr).strip()
    except Exception as e:
        return f"error: {e}"


def local_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))
        return s.getsockname()[0]
    except Exception:
        return "localhost"
    finally:
        s.close()


def btn(label, key, cls=""):
    return f'<button class="{cls}" onclick="k(\'{key}\')">{label}</button>'


def page():
    apps = "".join(
        f'<button class="app" onclick="a(\'{p}\')">{n}</button>' for n, p in APPS.items()
    )
    return f"""<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,user-scalable=no">
<title>TV Remote</title>
<style>
:root{{--bg:#fff;--fg:#111;--btn:#eceef2;--act:#d5d9e2;--acc:#d33}}
@media(prefers-color-scheme:dark){{:root{{--bg:#14161a;--fg:#eee;--btn:#262a31;--act:#3a404a}}}}
body{{margin:0;padding:16px;background:var(--bg);color:var(--fg);font-family:system-ui,sans-serif;
max-width:360px;margin:auto;-webkit-user-select:none;user-select:none}}
.grid{{display:grid;grid-template-columns:repeat(3,1fr);gap:10px;margin-bottom:14px}}
button{{background:var(--btn);color:var(--fg);border:0;border-radius:14px;padding:18px 0;font-size:18px}}
button:active{{background:var(--act)}}
.pw{{background:var(--acc);color:#fff}}
.ok{{font-weight:700}}
.apps{{display:grid;grid-template-columns:repeat(2,1fr);gap:10px;margin-bottom:14px}}
.app{{font-size:15px;padding:14px 0}}
input{{width:100%;box-sizing:border-box;padding:12px;font-size:16px;border-radius:10px;
border:1px solid var(--act);background:var(--bg);color:var(--fg)}}
#s{{text-align:center;font-size:12px;opacity:.6;margin:8px 0;min-height:16px}}
</style></head><body>
<div id="s">ready</div>
<div class="grid">
{btn('⏻','power','pw')}{btn('Input','input')}{btn('Mute','mute')}
{btn('Vol +','volup')}{btn('▲','up')}{btn('Ch +','chup')}
{btn('◀','left')}{btn('OK','ok','ok')}{btn('▶','right')}
{btn('Vol −','voldown')}{btn('▼','down')}{btn('Ch −','chdown')}
{btn('Back','back')}{btn('Home','home')}{btn('Menu','menu')}
{btn('⏪','rew')}{btn('⏯','play')}{btn('⏩','ff')}
</div>
<div class="apps">{apps}</div>
<input id="t" placeholder="Type here, press Enter to send to TV">
<script>
const s=document.getElementById('s');
async function go(u){{try{{const r=await fetch(u);s.textContent=(await r.text()).slice(0,60)||'sent'}}catch(e){{s.textContent='failed'}}}}
function k(x){{go('/key/'+x);if(navigator.vibrate)navigator.vibrate(15)}}
function a(p){{go('/app/'+p)}}
document.getElementById('t').addEventListener('keydown',e=>{{
 if(e.key==='Enter'){{go('/text/'+encodeURIComponent(e.target.value));e.target.value=''}}}});
</script></body></html>"""


class H(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def send(self, body, ctype="text/plain"):
        data = body.encode()
        self.send_response(200)
        self.send_header("Content-Type", ctype + "; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        p = self.path
        if p == "/":
            return self.send(page(), "text/html")
        if p.startswith("/key/"):
            name = p[5:]
            if name == "power":
                # Toggle: wake if asleep, sleep if awake
                out = adb("shell", "dumpsys", "power")
                awake = "mWakefulness=Awake" in out
                code = KEYS["sleep"] if awake else KEYS["wake"]
                adb("shell", "input", "keyevent", str(code))
                return self.send("off" if awake else "on")
            if name in KEYS:
                adb("shell", "input", "keyevent", str(KEYS[name]))
                return self.send("ok")
        if p.startswith("/app/"):
            pkg = p[5:]
            if pkg in APPS.values():
                adb("shell", "monkey", "-p", pkg, "-c",
                    "android.intent.category.LEANBACK_LAUNCHER", "1")
                return self.send("launching")
        if p.startswith("/text/"):
            txt = unquote(p[6:]).replace(" ", "%s")
            adb("shell", "input", "text", txt)
            return self.send("typed")
        self.send_response(404)
        self.end_headers()


if __name__ == "__main__":
    if not TV:
        print("Usage: python tv_remote.py <TV_IP>")
        sys.exit(1)
    connect()
    print(f"\nOpen on your phone: http://{local_ip()}:{PORT}\n(Ctrl+C to stop)")
    HTTPServer(("0.0.0.0", PORT), H).serve_forever()

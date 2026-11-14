# -*- coding: utf-8 -*-
"""大富翁 · 统一启动器（电脑版只有这一个入口）。

启动时先选模式：
  1 = 局域网模式    同一 WiFi 下的手机 / 电脑打开地址就能进，不碰公网；
  2 = 互联网模式    再起一条 serveo 固定隧道（失败退回 Cloudflare），外地朋友也能进。

两种模式跑的是同一个服务端、同一份页面，区别只在有没有隧道：
  - 页面的实时通道自己会协商：公网走 WebSocket，没有 /ws 的服务端退回 SSE；
  - 所以安卓端（只用 SSE）和电脑版（两条都支持）共用 www/index.html 这一份页面。

用法：
  python launcher.py                  # 弹菜单选模式
  python launcher.py --mode lan       # 直接局域网
  python launcher.py --mode net       # 直接互联网
  加 --no-open 不自动开浏览器，加 --no-wait 不等回车（脚本化验收用）
"""

from __future__ import annotations

import logging
import re
import shutil
import socket
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
import webbrowser
from http.server import ThreadingHTTPServer
from pathlib import Path

FROZEN = getattr(sys, "frozen", False)
SRC = Path(__file__).resolve().parent                       # src/
ROOT = Path(sys.executable).resolve().parent if FROZEN else SRC.parent
WEB = (Path(sys._MEIPASS) / "www") if FROZEN else SRC / "www"
PORT = 9000

CLOUDFLARED = ROOT / "cloudflared.exe"
NAME_FILE = ROOT / "serveo_name.txt"
SERVEO_NAME = "suqinghuai"                 # 固定子域名；可用 serveo_name.txt 覆盖
SERVEO_HOST = "serveo.net"
SERVEO_SSH_PORT = "443"                    # 22 常被封，走 443
SERVEO_WAIT = 70.0                         # 旧会话释放固定名可能要等一会
URL_RE = re.compile(r"https://[a-z0-9-]+\.trycloudflare\.com")
UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")

NO_OPEN = "--no-open" in sys.argv
NO_WAIT = "--no-wait" in sys.argv

LOG = logging.getLogger("monopoly")


def setup_logging() -> None:
    if LOG.handlers:
        return
    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(logging.Formatter("%(message)s"))
    LOG.addHandler(handler)
    LOG.setLevel(logging.INFO)
    LOG.propagate = False


def say(text: str = "") -> None:
    LOG.info(text)


def log_dir() -> Path:
    for cand in (ROOT, Path(str(Path.home())), Path(__import__("os").environ.get("TEMP", "."))):
        try:
            probe = cand / ".mpz_write_test"
            probe.write_text("x", encoding="utf-8")
            probe.unlink()
            return cand
        except Exception:
            continue
    return Path(__import__("os").environ.get("TEMP", "."))


LOGS = log_dir()
SERVER_LOG = LOGS / "service.log"
TUNNEL_LOG = LOGS / "tunnel.log"
DB_PATH = ROOT / "monopoly.db"


# ---------------------------------------------------------------- 小工具
def serveo_name() -> str:
    raw = ""
    try:
        if NAME_FILE.exists():
            # utf-8-sig：去掉记事本写进去的 BOM，否则子域名里会夹一个看不见的字符
            raw = NAME_FILE.read_text(encoding="utf-8-sig", errors="replace")
            raw = raw.strip().splitlines()[0].strip() if raw.strip() else ""
    except Exception:
        raw = ""
    raw = raw.strip().strip("\ufeff\u200b")
    cleaned = re.sub(r"[^a-z0-9-]", "", raw.lower())   # serveo 只收小写字母 / 数字 / 连字符
    return cleaned or SERVEO_NAME


def find_ssh() -> str:
    for candidate in (shutil.which("ssh"), r"C:\Windows\System32\OpenSSH\ssh.exe"):
        if candidate and Path(candidate).exists():
            return candidate
    return ""


def listening_pids(port: int) -> list[str]:
    try:
        out = subprocess.run(["netstat", "-ano"], capture_output=True, text=True,
                             errors="ignore").stdout
    except OSError:
        return []
    pids: list[str] = []
    for line in out.splitlines():
        if "LISTENING" in line and (":%d " % port) in line:
            last = line.split()[-1]
            if last.isdigit() and last not in pids:
                pids.append(last)
    return pids


def kill_pid(pid: str) -> None:
    subprocess.run(["taskkill", "/f", "/pid", pid], capture_output=True)


def wait_port(port: int, seconds: float) -> bool:
    deadline = time.time() + seconds
    while time.time() < deadline:
        with socket.socket() as s:
            s.settimeout(1.0)
            if s.connect_ex(("127.0.0.1", port)) == 0:
                return True
        time.sleep(0.4)
    return False


def tail(path: Path, lines: int = 12) -> str:
    if not path.exists():
        return "（没有日志 %s）" % path.name
    text = path.read_text(encoding="utf-8", errors="replace").splitlines()
    return "\n".join(text[-lines:]) or "（日志为空）"


def copy_to_clipboard(text: str) -> bool:
    try:
        subprocess.run(["powershell", "-NoProfile", "-STA", "-Command",
                        "Set-Clipboard -Value '%s'" % text],
                       capture_output=True, timeout=15)
        return True
    except Exception:
        return False


# ---------------------------------------------------------------- serveo
def public_ok(url: str, timeout: float = 12.0) -> bool:
    """公网地址是否通了（200 / 302 都算通；serveo 的访客警告页也是 200）。"""
    req = urllib.request.Request(url, headers={
        "User-Agent": UA,
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language": "zh-CN,zh;q=0.9",
        "Accept-Encoding": "identity",
    })
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status in (200, 301, 302, 307, 308)
    except urllib.error.HTTPError as e:
        return e.code in (301, 302, 307, 308)
    except Exception:
        return False


def strip_ansi(text: str) -> str:
    return re.sub(r"\x1b\[[0-9;]*m", "", text)


def start_serveo(name: str, ssh: str) -> tuple[subprocess.Popen | None, str, str]:
    """起 serveo 反向隧道，返回 (进程, 公网地址, 失败原因)。

    判定以隧道自己的日志为准：打出 "Forwarding HTTP traffic from" 就算成，
    因为固定子域名偶尔要等旧会话释放（几十秒），只看公网探测会误判成失败。
    """
    url = "https://%s.serveousercontent.com" % name
    log = TUNNEL_LOG.open("w", encoding="utf-8")
    proc = subprocess.Popen(
        [ssh, "-T", "-p", SERVEO_SSH_PORT,
         "-o", "StrictHostKeyChecking=accept-new",
         "-o", "ServerAliveInterval=30",
         "-o", "ServerAliveCountMax=3",
         "-o", "ExitOnForwardFailure=yes",
         "-R", "%s:80:127.0.0.1:%d" % (name, PORT),   # 必须 127.0.0.1，localhost 会走 IPv6 ::1 → 502
         SERVEO_HOST],
        cwd=str(LOGS), stdout=log, stderr=subprocess.STDOUT,
    )
    deadline = time.time() + SERVEO_WAIT
    forwarding = False
    while time.time() < deadline:
        text = strip_ansi(TUNNEL_LOG.read_text(encoding="utf-8", errors="replace")) \
            if TUNNEL_LOG.exists() else ""
        if "remote port forwarding failed" in text:
            stop_proc(proc)
            return None, "", "forward-failed"
        if "first need to register" in text:
            stop_proc(proc)
            return None, "", "need-register"
        if "Forwarding HTTP traffic from" in text:
            forwarding = True
            break
        if proc.poll() is not None:
            return None, "", "closed"
        time.sleep(1.0)

    if not forwarding:
        stop_proc(proc)
        return None, "", "timeout"

    if not public_ok(url, timeout=10.0):
        probe_deadline = time.time() + 15.0
        while time.time() < probe_deadline and not public_ok(url, timeout=10.0):
            time.sleep(2.0)
    return proc, url, ""


def stop_proc(proc: subprocess.Popen) -> None:
    try:
        proc.terminate()
        proc.wait(timeout=5)
    except Exception:
        try:
            proc.kill()
        except Exception:
            pass


def supervise_serveo(proc: subprocess.Popen, name: str, ssh: str) -> None:
    """ssh 掉线时自动重连（serveo 偶尔会掐连接）。"""
    restarts = 0
    while True:
        time.sleep(5)
        if proc.poll() is None:
            continue
        restarts += 1
        if restarts > 20:
            say("[提示] serveo 反复断开，已停止重连；可改用 cloudflared（放在同目录即会自动启用）。")
            return
        say("[提示] serveo 断开，正在重连（第 %d 次）..." % restarts)
        new_proc, url, _reason = start_serveo(name, ssh)
        if new_proc is None:
            say("[提示] 重连失败，稍后继续尝试。")
            continue
        proc = new_proc
        say("[提示] 已重连：%s" % url)


# ---------------------------------------------------------------- cloudflare
def read_tunnel_url(seconds: float = 45.0) -> str:
    deadline = time.time() + seconds
    while time.time() < deadline:
        if TUNNEL_LOG.exists():
            found = URL_RE.findall(TUNNEL_LOG.read_text(encoding="utf-8", errors="replace"))
            if found:
                return found[-1]
        time.sleep(1.0)
    return ""


def start_cloudflared() -> tuple[subprocess.Popen | None, str]:
    if not CLOUDFLARED.exists():
        return None, ""
    tunnel = subprocess.Popen(
        [str(CLOUDFLARED), "tunnel", "--protocol", "http2",
         "--url", "http://127.0.0.1:%d" % PORT, "--no-autoupdate"],
        cwd=str(LOGS), stdout=TUNNEL_LOG.open("w", encoding="utf-8"),
        stderr=subprocess.STDOUT,
    )
    return tunnel, read_tunnel_url(45)


# ---------------------------------------------------------------- 服务端
def start_server(want_browser: bool) -> bool:
    import server as core

    core.WEB = WEB
    core.DB_PATH = DB_PATH
    try:
        core.db_init()
        games = len(core.db_list_games())
        say("  存档数据库 : %s（已有 %d 局历史存档）" % (DB_PATH.name, games))
    except Exception as exc:
        say("  存档数据库不可用，将以纯内存模式运行：%s" % exc)

    httpd = ThreadingHTTPServer(("0.0.0.0", PORT), core.Handler)
    httpd.daemon_threads = True

    def run() -> None:
        try:
            httpd.serve_forever()
        except Exception:
            pass

    threading.Thread(target=run, name="monopoly-server", daemon=True).start()
    if not wait_port(PORT, 25):
        say("[错误] 服务没能起来，日志如下：")
        say(tail(SERVER_LOG))
        return False
    return True


def ask_mode() -> str:
    if "--mode" in sys.argv:
        idx = sys.argv.index("--mode") + 1
        value = sys.argv[idx] if idx < len(sys.argv) else ""
        if value in ("lan", "net"):
            return value
    for i, arg in enumerate(sys.argv):
        if arg in ("--lan", "--local"):
            return "lan"
        if arg in ("--net", "--online", "--internet"):
            return "net"

    say("=" * 64)
    say("  大富翁 · 联机")
    say("=" * 64)
    say()
    say("   [1] 局域网模式    同一 WiFi 下一起玩，不碰公网")
    say("   [2] 互联网模式    外地朋友也能进（serveo 固定地址）")
    say()
    try:
        answer = input("  请选择 [1/2]（直接回车 = 1）：").strip()
    except (EOFError, KeyboardInterrupt):
        answer = ""
    return "net" if answer == "2" else "lan"


def main() -> int:
    setup_logging()
    if not (WEB / "index.html").exists():
        say("缺少页面文件：%s" % (WEB / "index.html"))
        return 1

    mode = ask_mode()
    say()

    old = listening_pids(PORT)
    if old:
        say("[提示] 端口 %d 已被占用（进程 %s）。" % (PORT, "、".join(old)))
        try:
            answer = input("       是否结束旧进程并重新启动？(Y/n) ").strip().lower()
        except EOFError:
            answer = "n"
        if answer not in ("", "y", "yes"):
            say("已取消。")
            return 0
        for pid in old:
            kill_pid(pid)
        time.sleep(1.5)

    say("[1/3] 启动游戏服务 ...")
    if not start_server(want_browser=False):
        return 1

    import server as core
    local = "http://127.0.0.1:%d/" % PORT
    lan_ip = core.local_ip()

    if mode == "lan":
        say("[2/3] 局域网模式：不建隧道")
        say()
        say("-" * 64)
        say("  本机打开 : %s" % local)
        if lan_ip:
            say("  同一 WiFi 下的手机/电脑 : http://%s:%d/" % (lan_ip, PORT))
            say()
            say("  把这个地址发给同屋的人（手机连同一个 WiFi 就能开）")
        else:
            say("  没取到局域网 IP，请检查网络")
        say("-" * 64)
        target = local
        tunnel = None
    else:
        name = serveo_name()
        ssh = find_ssh()
        tunnel = None
        url = ""
        kind = ""

        if ssh:
            say("[2/3] 启动 serveo 隧道（固定地址）...")
            tunnel, url, reason = start_serveo(name, ssh)
            if tunnel:
                kind = "serveo"
                threading.Thread(target=supervise_serveo, args=(tunnel, name, ssh),
                                 daemon=True).start()
            else:
                if reason == "need-register":
                    say("[提示] serveo 要你先登记这把钥匙（第一次用才需要）：")
                    say(tail(TUNNEL_LOG, 6))
                    say("       打开日志里 console.serveo.net 的链接，用 GitHub/Google 登录一次即可。")
                elif reason == "forward-failed":
                    say("[提示] serveo 拒绝了固定子域名 %s（可能被上一次会话占着，等 1 分钟再试）。" % name)
                    say(tail(TUNNEL_LOG, 6))
                else:
                    say("[提示] serveo 没起来：")
                    say(tail(TUNNEL_LOG, 6))
        else:
            say("[2/3] 系统里没有 ssh.exe（Win10 1809 之前没自带），跳过 serveo。")

        if not url:
            say("       改用 Cloudflare Quick Tunnel（地址每次随机）...")
            tunnel, url = start_cloudflared()
            if url:
                kind = "cloudflare"
            else:
                say("[错误] 两条隧道都没能起来。")
                if not CLOUDFLARED.exists():
                    say("       本目录没有 cloudflared.exe；把它放进来即可作为备用隧道。")
                say(tail(TUNNEL_LOG, 8))
                return 1

        copied = copy_to_clipboard(url)
        say("[3/3] 隧道已就绪（%s）" % ("serveo 固定地址" if kind == "serveo" else "Cloudflare 随机地址"))
        say()
        say("-" * 64)
        say("  把这个地址发给外地朋友，浏览器打开就能进：")
        say()
        say("      %s" % url)
        say()
        say("  %s" % ("（已复制到剪贴板，直接粘贴发送）" if copied else "（复制失败，请手动选中上面的地址）"))
        if kind == "serveo":
            say("  （固定地址，以后开局都一样，发一次能长期用）")
        say("-" * 64)
        say()
        if kind == "serveo":
            say("说明：serveo 免费隧道会让首次访问的朋友先看到警告页，点「Continue to Site」就进；")
            say("      同一浏览器之后不再弹。地址只在本窗口开着时有效，关掉窗口就失效。")
        else:
            say("注意：Cloudflare 这条地址每次重启都会变；关掉窗口隧道立刻失效。")
        say()
        target = url

    if not NO_OPEN:
        try:
            webbrowser.open(target)
            say("已自动打开浏览器。")
        except Exception:
            pass

    say("按回车键停止服务并关闭隧道（也可以直接关掉本窗口）...")
    try:
        if NO_WAIT:
            while True:
                time.sleep(1)
        input()
    except (KeyboardInterrupt, EOFError):
        pass
    finally:
        say("正在关闭 ...")
        if tunnel is not None:
            stop_proc(tunnel)
        for pid in listening_pids(PORT):
            kill_pid(pid)
        say("已退出。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

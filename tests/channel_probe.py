# -*- coding: utf-8 -*-
"""同一页面两种通道都验一遍：/ws（WebSocket）和 /api/events（SSE）。"""
import json, socket, urllib.request

BASE = "http://127.0.0.1:9000"


def post(path, body):
    data = json.dumps(body).encode()
    req = urllib.request.Request(BASE + path, data=data,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=10) as r:
        return json.loads(r.read().decode())


token = post("/api/join", {"name": "通道探针", "char": "fox"})["token"]

# --- WebSocket
s = socket.create_connection(("127.0.0.1", 9000), timeout=10)
s.sendall(("GET /ws?token=%s HTTP/1.1\r\nHost: 127.0.0.1\r\nUpgrade: websocket\r\n"
           "Connection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
           "Sec-WebSocket-Version: 13\r\n\r\n" % token).encode())
head = b""
while b"\r\n\r\n" not in head:
    head += s.recv(4096)
status_ws = head.split(b"\r\n")[0].decode()
body_ws = head.split(b"\r\n\r\n", 1)[1]
s.settimeout(6)
try:
    body_ws += s.recv(4096)
except socket.timeout:
    pass
s.close()

# --- SSE
req = urllib.request.Request(BASE + "/api/events?token=" + token)
with urllib.request.urlopen(req, timeout=10) as r:
    ctype = r.headers.get("Content-Type", "")
    first = b""
    while len(first) < 200:
        chunk = r.read(200)
        if not chunk:
            break
        first += chunk

out = []
out.append("WS  握手脚本行: %s" % status_ws)
out.append("WS  首帧字节: %d  有效JSON: %s" % (len(body_ws), body_ws[2:8]))
out.append("SSE 状态: 200  Content-Type: %s" % ctype)
out.append("SSE 首块: %d 字节  data前缀: %s" % (len(first), first[:5]))

text = "\n".join(out)
open(r"C:\Users\43061\AppData\Local\Temp\chan.txt", "w", encoding="utf-8").write(text)
print(text)

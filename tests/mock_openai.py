# -*- coding: utf-8 -*-
"""假的 OpenAI 兼容端点，用来测 AI 玩家全链路（不花钱、可造错）。

用法:
    python mock_openai.py 9109 normal           # 正常决策
    python mock_openai.py 9109 badjson          # 前 3 次返回非 JSON（测重试+降级）
    python mock_openai.py 9109 slow             # 第一次故意超时
请求原文会追加到 mock_requests.jsonl 里，方便事后看提示词到底长什么样。
"""
import json
import os
import re
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

STATE = {"n": 0, "compress": 0}
MODE = "normal"
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "mock_requests.jsonl")


def decide(body: dict) -> str:
    msgs = body.get("messages") or []
    system = (msgs[0].get("content") if msgs else "") or ""
    user = (msgs[-1].get("content") if msgs else "") or ""

    def dump(obj):
        return "```json\n" + json.dumps(obj, ensure_ascii=False) + "\n```"

    # 落地后计划：只排这一回合的买 / 升；trade 会存到下回合开局才发出
    # （新流程里上下文是「已掷骰、已落地」，不再是回合开头的整回合计划）
    plan = {
        "why": "先占便宜的地，踩着的地顺手升一下",
        "memory": "长" * 420,                       # 故意写超长，验证就地截断不会再发第二次请求
        "buy": {"want": True, "maxPrice": 200},
        "land": {"upgrade": True, "maxCost": 180},
    }
    m = re.search(r"【别人的地】[^：]*?(\S+) 有 (\S+?)\(", user)
    if m and not STATE.get("traded") and "trade" not in plan:
        STATE["traded"] = True
        plan["trade"] = {"target": m.group(1), "want": m.group(2), "giveCash": 60}
        plan["why"] = "这块地我要定了"
    if STATE["n"] % 5 == 0:
        plan["buy"] = {"want": False, "maxPrice": 0}
        plan["why"] = "这轮先攥着钱"
    return dump(plan)


class H(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        pass

    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length) if length else b"{}"
        body = json.loads(raw.decode("utf-8", "replace") or "{}")
        STATE["n"] += 1
        n = STATE["n"]
        try:
            with open(OUT, "a", encoding="utf-8") as fh:
                fh.write(json.dumps({"n": n, "mode": MODE, "body": body}, ensure_ascii=False) + "\n")
        except OSError:
            pass

        if MODE == "badjson" and n <= 3:
            content = "我觉得这块地值得买，但我不想按格式回答。"      # 非 JSON
        elif MODE == "slow" and n == 1:
            time.sleep(12)                                          # 超过 timeout，逼出降级
            content = json.dumps({"buy": {"want": True, "maxPrice": 200},
                                  "land": {"upgrade": True, "maxCost": 200},
                                  "why": "迟到了", "memory": ""}, ensure_ascii=False)
        elif MODE == "slowall":
            time.sleep(3)                                           # 每次都想 3 秒，方便截「正在思考」
            content = decide(body)
        else:
            content = decide(body)

        payload = {
            "id": "mock-%d" % n,
            "object": "chat.completion",
            "choices": [{"index": 0, "message": {"role": "assistant", "content": content},
                         "finish_reason": "stop"}],
            "usage": {"prompt_tokens": 100, "completion_tokens": 50, "total_tokens": 150},
        }
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(json.dumps({"n": STATE["n"], "compress": STATE["compress"]}).encode())


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 9109
    MODE = sys.argv[2] if len(sys.argv) > 2 else "normal"
    print("mock openai on %d, mode=%s" % (port, MODE), flush=True)
    ThreadingHTTPServer(("127.0.0.1", port), H).serve_forever()

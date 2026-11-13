# tests

只保留仍可运行、且对当前版本有回归价值的两个脚本：

- `selftest.py`：对着一份**干净的隔离服务**跑接口与流程回归。
  ```powershell
  python tests/selftest.py 9021
  ```
  覆盖加入/角色占用、开局权限、掷骰与结算确认、买地/升级、交换、暂停继续、观战、重开、
  等待室设置、电脑入桌、存档载入与认领。要求服务端使用空库，否则存档段会被已有对局干扰。

- `test_settle_timing.py`：不启服务端，直接验证电脑的结算/机会卡确认发生在
  「弹窗回报已显示 + 2 秒」之后，并检查电脑之间的掷骰间隔为 2 秒。
  ```powershell
  python tests/test_settle_timing.py
  ```

- `mock_openai.py`：本地假的 OpenAI 兼容端点，用来调试 AI 座位，不消耗真实额度。
  ```powershell
  python tests/mock_openai.py 9109 normal
  ```
  模式：`normal` 正常决策；`badjson` 前 3 次返回非 JSON（测重试与降级）；
  `slow` 首次超时；`slowall` 每次都超时。请求原文会写到同目录 `mock_requests.jsonl`。

`mock_requests.jsonl` 是运行时生成的调试记录，不入库。

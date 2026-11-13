# -*- coding: utf-8 -*-
"""回归：电脑的结算确认必须等「移动动画播完 + 弹窗显示 2 秒」之后。

不启服务端，直接驱动 Game 的计时器。
用法：python tests/test_settle_timing.py
"""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import server  # noqa: E402


def one_round(lead: float) -> float:
    g = server.Game()
    g.db_ok = False
    g.add_bot()
    g.add_bot()
    g.start()
    p = g.player(g.current)
    g.phase = "settle"
    g.settles = [{
        "type": "park", "pid": p["id"], "tile": 39, "amount": 150,
        "name": "停车场", "icon": "park", "text": "停在停车场，拿奖金 150",
    }]
    g.settle_lead = lead
    t0 = time.time()
    g.bump()
    while g.settles and time.time() - t0 < 12:
        time.sleep(0.05)
    took = time.time() - t0
    if g.bot_timer:
        g.bot_timer.cancel()
    return took


for lead in (0.0, 3.0):
    took = one_round(lead)
    need = lead + server.BOT_DELAY["settle"] + server.SETTLE_SHOW_BUFFER
    assert took >= need - 0.05, "电脑确认太早：lead=%.1f took=%.2f need=%.2f" % (lead, took, need)
    print("[ok] lead=%.1f 确认耗时 %.2fs（至少 %.2fs）" % (lead, took, need))

print("SETTLE TIMING PASS")

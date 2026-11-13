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
    g.bump()
    time.sleep(0.08)
    acked = time.time()
    g.settle_seen("test-client")       # 模拟前端已经画出结算窗
    while g.settles and time.time() - acked < 12:
        time.sleep(0.05)
    took = time.time() - acked
    if g.bot_timer:
        g.bot_timer.cancel()
    return took


for lead in (0.0, 3.0):
    took = one_round(lead)
    need = server.BOT_DELAY["settle"] + server.SETTLE_SHOW_BUFFER
    assert took >= need - 0.05, "电脑确认太早：lead=%.1f took=%.2f need=%.2f" % (lead, took, need)
    print("[ok] lead=%.1f 弹窗确认后 %.2fs（至少 %.2fs）" % (lead, took, need))

assert server.BOT_DELAY["roll"] >= 2.0, "电脑交接间隔必须至少 2 秒"
print("[ok] 电脑到电脑的掷骰间隔 %.1fs" % server.BOT_DELAY["roll"])


def one_chance(lead: float) -> float:
    g = server.Game()
    g.db_ok = False
    g.add_bot()
    g.add_bot()
    g.start()
    p = g.player(g.current)
    g.phase = "chance"
    g.chance = {"pid": p["id"], "name": p["name"], "text": "测试机会卡", "delta": 10,
                "icon": "spark"}
    g.settle_lead = lead
    g.bump()
    time.sleep(0.08)
    acked = time.time()
    g.chance_seen("test-client")
    while g.chance is not None and time.time() - acked < 12:
        time.sleep(0.05)
    took = time.time() - acked
    if g.bot_timer:
        g.bot_timer.cancel()
    return took


for lead in (0.0, 3.0):
    took = one_chance(lead)
    need = server.BOT_DELAY["chance"] + server.SETTLE_SHOW_BUFFER
    assert took >= need - 0.05, "电脑确认机会卡太早：lead=%.1f took=%.2f need=%.2f" % (lead, took, need)
    print("[ok] 机会卡 lead=%.1f 弹窗确认后 %.2fs" % (lead, took))


def one_buy(lead: float) -> float:
    g = server.Game()
    g.db_ok = False
    g.add_bot()
    g.add_bot()
    g.start()
    p = g.player(g.current)
    g.phase = "buy"
    g.pending = 1
    g.last_steps = 3
    g.buy_viewed = False
    g.settle_lead = lead
    g.bump()
    time.sleep(0.08)
    acked = time.time()
    g.buy_seen("test-client")
    while g.phase == "buy" and time.time() - acked < 12:
        time.sleep(0.05)
    took = time.time() - acked
    if g.bot_timer:
        g.bot_timer.cancel()
    return took


for lead in (0.0, 3.0):
    took = one_buy(lead)
    need = server.BOT_DELAY["buy"] + server.SETTLE_SHOW_BUFFER
    assert took >= need - 0.05, "电脑买地/升级决定太早：lead=%.1f took=%.2f need=%.2f" % (lead, took, need)
    print("[ok] 地契卡 lead=%.1f 显示后 %.2fs 才决定" % (lead, took))


def handoff(ack: bool) -> float:
    g = server.Game()
    g.db_ok = False
    g.add_bot()
    g.add_bot()
    g.start()
    g._advance_turn()
    g.bump()
    assert g.phase == "handoff", g.phase
    t0 = time.time()
    if ack:
        g.handoff_seen("test-client")
    while g.phase == "handoff" and time.time() - t0 < 3:
        time.sleep(0.02)
    took = time.time() - t0
    assert g.phase != "handoff", "交接后没有离开交接阶段"
    if g.bot_timer:
        g.bot_timer.cancel()
    return took


for ack in (True, False):
    took = handoff(ack)
    if ack:
        assert 1.9 < took < 2.3, "收到交接回报后没有停满 2 秒：%.2fs" % took
    else:
        assert took < 1.0, "没收到交接回报时兜底太慢：%.2fs" % took
    print("[ok] 回合交接 ack=%s 后 %.2fs 进入下一家掷骰（旧弹窗已清空）" % (ack, took))


def handoff_to_dice() -> float:
    g = server.Game()
    g.db_ok = False
    g.add_bot()
    g.add_bot()
    g.start()
    g._advance_turn()
    g.bump()
    t0 = time.time()
    g.handoff_seen("test-client")
    while time.time() - t0 < 8:
        if any(n.get("type") == "dice" for n in (g.news_pub or [])):
            break
        time.sleep(0.02)
    took = time.time() - t0
    if g.bot_timer:
        g.bot_timer.cancel()
    return took


took = handoff_to_dice()
assert took >= server.BOT_DELAY["roll"] - 0.05, "电脑交接后掷骰太快：%.2fs" % took
print("[ok] 清空弹窗后到下一家电脑掷骰 %.2fs（至少 %.1fs）" % (took, server.BOT_DELAY["roll"]))

print("SETTLE TIMING PASS")

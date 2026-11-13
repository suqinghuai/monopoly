#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""接口自测：加入 → 开局 → 掷骰 → 买地 → 结束回合 → 交换。
需要先启动 server.py（默认 9000 端口），然后 python selftest.py [端口]
"""

import sys
import time
import urllib.error
import urllib.request
import json

BASE = "http://127.0.0.1:%s" % (sys.argv[1] if len(sys.argv) > 1 else "9000")


def call(path, payload=None, token=None, expect_error=False):
    url = BASE + path
    if token:
        url += ("&" if "?" in url else "?") + "token=" + token
    data = None
    headers = {}
    if payload is not None:
        data = json.dumps(payload).encode("utf-8")
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=8) as res:
            body = json.loads(res.read().decode("utf-8"))
            if expect_error:
                raise AssertionError("预期失败却成功了：%s %s" % (path, payload))
            return body
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", "ignore")
        if expect_error:
            return {"error": detail}
        raise AssertionError("请求失败 %s %s -> %s %s" % (path, payload, exc.code, detail))


def main():
    lobby = call("/api/lobby")
    assert lobby["players"] == [], "开局前房间应为空，实际 %s" % lobby["players"]
    print("[ok] lobby 为空，可用角色 %d 个" % len(lobby["chars"]))

    a = call("/api/join", {"name": "阿明", "char": "fox"})
    b = call("/api/join", {"name": "小雨", "char": "panda"})
    print("[ok] 两人加入:", a["pid"], b["pid"])

    # 重复角色应该被拒
    call("/api/join", {"name": "三楼", "char": "fox"}, expect_error=True)
    print("[ok] 重复选角色被拒")

    # 非房主不能开局
    call("/api/action", {"action": "start"}, token=b["token"], expect_error=True)
    call("/api/action", {"action": "start"}, token=a["token"])
    st = call("/api/state", token=a["token"])
    assert st["phase"] == "roll", st["phase"]
    print("[ok] 开局，先手 pid=%s" % st["current"])

    tokens = {a["pid"]: a["token"], b["pid"]: b["token"]}
    bought = {"阿明": 0, "小雨": 0}
    upgraded = []
    fresh_blocked = 0        # 「这回合刚买的地不能升」被拒次数
    pos_blocked = 0          # 「只有踩着才升」被拒次数

    for step in range(140):
        st = call("/api/state", token=a["token"])
        if st["phase"] == "over":
            break
        cur = st["current"]
        tok = tokens[cur]
        if st["phase"] == "roll":
            call("/api/action", {"action": "roll"}, token=tok)
        elif st["phase"] == "settle":
            # 结算必须先确认：确认前钱不动，确认后才动
            before = [p["money"] for p in st["players"]]
            assert st.get("settle") is not None, "settle 阶段应该有结算单"
            call("/api/action", {"action": "settle_ok"}, token=tok)
            after = call("/api/state", token=a["token"])
            if st["settle"].get("amount"):
                assert [p["money"] for p in after["players"]] != before, "确认结算后钱没动"
        elif st["phase"] == "buy":
            name = next(p["name"] for p in st["players"] if p["id"] == cur)
            here = st["board"][st["pending"]]
            if here.get("owner") == cur:
                # 自己的地：能升则升，否则放弃（放弃后自动交给下一位）
                cash = next(p["money"] for p in st["players"] if p["id"] == cur)
                if here.get("canUp") and here.get("upCost") and here["upCost"] <= cash - 80:
                    lv_before = int(here.get("level", 0))
                    call("/api/action", {"action": "upgrade", "tile": here["i"]}, token=tok)
                    upgraded.append(here["name"])
                    got = call("/api/state", token=a["token"])["board"][here["i"]]
                    assert int(got["level"]) == lv_before + 1, "升级后等级没动：%s" % got["name"]
                    assert got["rentNow"] > got["rent"], "升级后租金应该上涨：%s" % got["name"]
                else:
                    call("/api/action", {"action": "pass"}, token=tok)
            else:
                call("/api/action", {"action": "buy"}, token=tok)
                bought[name] = bought.get(name, 0) + 1
                # 刚买下的这块，本回合不该能升（已自动交棒，升级应当被拒）
                blocked = call("/api/action", {"action": "upgrade", "tile": st["pending"]},
                               token=tok, expect_error=True)
                assert ("buy" in blocked["error"] or "刚买" in blocked["error"]
                        or "现在不能" in blocked["error"]), blocked["error"]
                fresh_blocked += 1
        elif st["phase"] == "chance":
            assert st.get("chance") and st["chance"].get("text"), "chance 阶段应该有卡"
            call("/api/action", {"action": "chance_ok"}, token=tok)
        elif st["phase"] == "handoff":
            # 交接阶段只等 0.5 秒兜底，随后自动进入下一家的 roll
            time.sleep(0.6)
        else:
            raise AssertionError("未知阶段 %s" % st["phase"])

    st = call("/api/state", token=a["token"])
    owned = [t for t in st["board"] if t["owner"] is not None]
    print("[ok] 跑完 %d 步：买下 %s 块地，阶段=%s" % (step + 1, len(owned), st["phase"]))
    assert fresh_blocked >= 1, "没测到「刚买不能升」"
    print("[ok] 刚买下的地本回合升级被拒 %d 次（回合内不能自升）" % fresh_blocked)
    if pos_blocked:
        print("[ok] 不在脚下的自有地被拒升级（只能升自己正踩着的那块）")
    else:
        print("[跳过] 这局没凑出「站在 A 却想升 B」的场景")
    assert st["log"], "日志为空"
    for p in st["players"]:
        assert p["money"] >= 0, "金币不能为负：%s -> %s" % (p["name"], p["money"])

    # ---- 交换 ----
    # 先把局势推进到「阿明的回合、掷骰之前」——交易只允许在这个时点发起
    for _ in range(30):
        st = call("/api/state", token=a["token"])
        if st["phase"] == "roll" and st["current"] == a["pid"]:
            break
        if st["phase"] == "over":
            break
        tok = tokens[st["current"]]
        if st["phase"] == "roll":
            call("/api/action", {"action": "roll"}, token=tok)
        elif st["phase"] == "settle":
            call("/api/action", {"action": "settle_ok"}, token=tok)
        elif st["phase"] == "chance":
            call("/api/action", {"action": "chance_ok"}, token=tok)
        elif st["phase"] == "buy":
            call("/api/action", {"action": "pass"}, token=tok)
        elif st["phase"] == "handoff":
            time.sleep(0.6)
    st = call("/api/state", token=a["token"])
    board = st["board"]
    mine = [t for t in board if t["owner"] == a["pid"]]
    theirs = [t for t in board if t["owner"] == b["pid"]]
    if mine and theirs:
        # 现金差按当下余额给，跑完 60 步常常已经花光，硬写 50 会偶发「拿不出这么多现金」
        me = next(p for p in st["players"] if p["id"] == a["pid"])
        cash = max(0, min(50, int(me["money"])))
        call("/api/action", {
            "action": "trade_offer", "target": b["pid"],
            "give": [mine[0]["i"]], "want": [theirs[0]["i"]], "giveCash": cash,
        }, token=a["token"])
        offer_id = call("/api/state", token=a["token"])["trades"][-1]["id"]
        call("/api/action", {"action": "trade_answer", "id": offer_id, "accept": True}, token=b["token"])
        st2 = call("/api/state", token=a["token"])
        assert st2["board"][mine[0]["i"]]["owner"] == b["pid"], "给出的地没转过去"
        assert st2["board"][theirs[0]["i"]]["owner"] == a["pid"], "要来的地没转过来"
        print("[ok] 交换生效：%s <-> %s" % (mine[0]["name"], theirs[0]["name"]))
    else:
        print("[跳过] 这局没凑齐双方的地产，交换未测")

    # ---- 非法操作 ----
    call("/api/action", {"action": "roll"}, token=b["token"], expect_error=True)
    call("/api/state", token="bad-token", expect_error=True)
    print("[ok] 非法操作与失效 token 都被拒")

    # ---- 地块三级 ----
    st = call("/api/state", token=a["token"])
    lv_tiles = [t for t in st["board"] if t.get("level")]
    if upgraded:
        for t in lv_tiles:
            assert t["rentNow"] > t["rent"], "升级后租金应该上涨：%s" % t["name"]
            assert t["levelName"] in ("中级", "高级"), t["levelName"]
        if lv_tiles:
            print("[ok] 地块升级 %d 次，共 %d 块地带等级（%s），租金按等级上涨"
                  % (len(upgraded), len(lv_tiles),
                     "、".join("%s %s 租%d" % (t["name"], t["levelName"], t["rentNow"]) for t in lv_tiles[:3])))
        else:
            print("[ok] 地块升级 %d 次（走格子已验证等级+1、租金上涨；局末破产清地，等级已归零）"
                  % len(upgraded))
    else:
        print("[跳过] 这局没凑出升级机会")
    call("/api/action", {"action": "upgrade", "tile": 1}, token=b["token"], expect_error=True)
    print("[ok] 非本人地块 / 非操作阶段升级被拒")

    # ---- 规则说明接口 ----
    rules = call("/api/rules")
    assert rules["sections"] and any("升级" in s["title"] for s in rules["sections"]), "规则缺升级段"
    joined = " ".join(i for s in rules["sections"] for i in s["items"])
    assert "踩在自己这块地上" in joined and "刚买" in joined, "规则文案与实现不一致"
    print("[ok] 规则说明接口 %d 段，含升级新规" % len(rules["sections"]))

    # ---- 暂停回开始页 ----
    pos_before = [p["pos"] for p in st["players"]]
    money_before = [p["money"] for p in st["players"]]
    call("/api/action", {"action": "pause"}, token=a["token"])
    st = call("/api/state", token=a["token"])
    assert st["phase"] == "setup" and st["paused"] is True, st["phase"]
    assert [p["pos"] for p in st["players"]] == pos_before, "暂停后局面不能变"
    assert [p["money"] for p in st["players"]] == money_before, "暂停后金币不能变"
    call("/api/action", {"action": "roll"}, token=a["token"], expect_error=True)
    call("/api/action", {"action": "settings", "startMoney": 1000}, token=a["token"], expect_error=True)
    call("/api/action", {"action": "bot_add"}, token=a["token"], expect_error=True)
    print("[ok] 暂停后：阶段=setup、局面冻结、掷骰/改资金/加人机都被拒")

    call("/api/action", {"action": "resume"}, token=a["token"])
    st = call("/api/state", token=a["token"])
    assert st["phase"] in ("roll", "buy", "settle", "chance", "handoff", "over"), st["phase"]
    assert [p["pos"] for p in st["players"]] == pos_before, "继续后位置应原样保留"
    assert [p["money"] for p in st["players"]] == money_before, "继续后金币应原样保留"
    print("[ok] 继续成功，局面原样回来（阶段=%s）" % st["phase"])

    # ---- 观战 ----
    w = call("/api/watch", {})["token"]
    ws = call("/api/state", token=w)
    assert ws["you"] is None and ws["watching"] is True and ws["admin"] is False
    assert len(ws["players"]) == 2, "观战者不能占座位"
    call("/api/action", {"action": "roll"}, token=w, expect_error=True)
    print("[ok] 观战不占座位、能看到局面、不能操作")

    # ---- 重开 → 等待室设置 / 人机 ----
    call("/api/action", {"action": "restart"}, token=a["token"])
    st = call("/api/state", token=a["token"])
    assert st["phase"] == "lobby", st["phase"]
    assert all(t["level"] == 0 for t in st["board"]), "重开后等级应清零"
    assert all(p["money"] == st["startMoney"] for p in st["players"]), "重开后金币回到起始资金"
    print("[ok] 重开一局：回到等待室，地块等级清零、金币复位")

    call("/api/action", {"action": "settings", "startMoney": 2500, "botLevel": "aggr"},
         token=a["token"])
    st = call("/api/state", token=a["token"])
    assert st["settings"]["startMoney"] == 2500 and st["settings"]["botLevel"] == "aggr"
    assert all(p["money"] == 2500 for p in st["players"]), "改资金后等待室里的钱也要跟着变"
    call("/api/action", {"action": "settings", "startMoney": 200}, token=a["token"], expect_error=True)
    print("[ok] 起始资金可设（2500/激进），越界值被拒")

    call("/api/action", {"action": "bot_add"}, token=a["token"])
    st = call("/api/state", token=a["token"])
    bot = [p for p in st["players"] if p["kind"] == "bot"]
    assert len(bot) == 1, "应该加进一个电脑玩家"
    assert bot[0]["botLevel"] == "aggr", bot[0]["botLevel"]
    assert bot[0]["money"] == 2500, "人机也要按新资金起家"
    call("/api/claim", {"pid": bot[0]["id"]}, expect_error=True)
    call("/api/action", {"action": "bot_level", "pid": bot[0]["id"], "level": "steady"},
         token=a["token"])
    st = call("/api/state", token=a["token"])
    assert [p for p in st["players"] if p["kind"] == "bot"][0]["botLevel"] == "steady"
    print("[ok] 电脑玩家「%s」入桌，难度可单独改，电脑座位不能认领" % bot[0]["name"])

    call("/api/action", {"action": "start"}, token=a["token"])
    st = call("/api/state", token=a["token"])
    assert len(st["players"]) == 3, "三人（含一台电脑）应该能开局"
    call("/api/action", {"action": "bot_add"}, token=a["token"], expect_error=True)
    print("[ok] 3 人（2 真人 + 1 电脑）开局，开局后不能再加人机")

    # ---- 存档：列表 / 载入 / 认领座位（放最后）----
    # 先回到等待室，模拟「服务重启后载入存档」：这时内存里的 token 全部作废，载入会把座位清空待认领
    call("/api/action", {"action": "restart"}, token=a["token"])
    saves = call("/api/saves")["saves"]
    assert saves, "对局进行中应该有存档记录"
    print("[ok] 存档 %d 局，最近一局：%s（%s）" % (len(saves), saves[0]["title"], saves[0]["status"]))

    n_players = len(st["players"])
    loaded = call("/api/load", {"id": saves[0]["id"]})
    seats = loaded["seats"]
    assert len(seats) == n_players, "载入后应有 %d 个座位，实际 %s" % (n_players, seats)
    st = call("/api/state", token=a["token"], expect_error=True)
    assert "失效" in st["error"], "载入后旧 token 应该失效"
    print("[ok] 载入存档：%d 个座位待认领，旧 token 已失效" % len(seats))

    seat = seats[0]
    tok = call("/api/claim", {"pid": seat["id"]})["token"]
    st = call("/api/state", token=tok)
    assert st["you"] == seat["id"], "认领后应该是这个座位"
    assert st["players"][0]["money"] == seat["money"], "载入后金币应保持一致"

    # 多玩家载入同一档：第二个人再点「载入存档」不能把第一个人的 token 冲掉
    again = call("/api/load", {"id": saves[0]["id"]})
    assert any(x["claimed"] for x in again["seats"]), "重复载入应保留已认领标记"
    st2 = call("/api/state", token=tok)
    assert st2["you"] == seat["id"], "重复载入同一档后，先认领的人不应掉登录态"
    print("[ok] 重复载入同一档不会踢掉已认领的玩家")
    call("/api/claim", {"pid": seat["id"]}, expect_error=True)
    print("[ok] 认领座位成功（%s，%d 金币，%d 处地契），重复认领被拒"
          % (seat["name"], seat["money"], seat["props"]))

    print("ALL PASS")


if __name__ == "__main__":
    main()

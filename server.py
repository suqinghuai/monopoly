#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
大富翁 · 局域网联机版（服务端）
只依赖 Python 标准库：文件服务 + JSON 接口 + SSE 实时广播。

棋盘为 11×11 的外圈，共 40 格。

用法:
    python server.py                # 默认 0.0.0.0:9000
    python server.py -p 9100
"""

from __future__ import annotations

import argparse
import datetime
import json
import random
import secrets
import socket
import sqlite3
import sys
import threading
import time
import urllib.error
import urllib.request
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

# PyInstaller 打包后：页面资源在解包目录 _MEIPASS，存档/配置/日志写在 exe 同目录
FROZEN = bool(getattr(sys, "frozen", False))
ROOT = Path(sys.executable).resolve().parent if FROZEN else Path(__file__).resolve().parent
WEB = Path(getattr(sys, "_MEIPASS", ROOT)) / "web"

START_MONEY = 1500
SALARY = 500            # 经过起点
BOARD_SIZE = 40
MAX_PLAYERS = 4

# 可选初始资金（房主在开始页挑，也可以自己填 600-6000）
MONEY_CHOICES = [1000, 1500, 2000, 3000]
MONEY_MIN, MONEY_MAX = 600, 6000

# 地块三级：0 初级 / 1 中级 / 2 高级
LEVELS = [
    {"name": "初级", "rent": 1.0},
    {"name": "中级", "rent": 1.7},
    {"name": "高级", "rent": 2.6},
]
UPGRADE_RATE = [0.6, 0.9]      # 升到 2 级 / 3 级，按地块原价的倍数收

# 人机三档：
#   keep    = 手上至少留多少现金才敢花
#   group   = 凑色组的让利系数（凑得越齐越敢花钱）
#   upgrade = 走到自己地上时愿意升级的概率
#   trade   = 接受别人交换的门槛（按交易规模缩放，越大越挑；本轮整体调高，只吃明显占便宜的）
#   risk    = 现金安全垫：额外按「场上最贵租金 × risk」留钱，防止一脚踩死
#   payback = 升级回本次数上限：升级费 ÷ 每次多收的租金，越小越挑（整组在手放宽 15%）
BOT_LEVELS = {
    "normal": {"name": "平衡", "keep": 160, "group": 0.3, "upgrade": 0.85, "trade": 0.35, "risk": 0.45, "payback": 3.3},
    "steady": {"name": "稳健", "keep": 420, "group": 0.7, "upgrade": 0.6, "trade": 0.5, "risk": 0.9, "payback": 3.0},
    "aggr":   {"name": "激进", "keep": 70, "group": 0.2, "upgrade": 0.95, "trade": 0.18, "risk": 0.1, "payback": 3.8},
}
TRADE_MIN_MARGIN = 120    # 再小的「便宜」也不接：差价不到这份钱就当没看见

BOT_NAMES = ["铁算盘", "阿呆", "小机灵", "财迷", "守财奴", "赌徒", "老掌柜", "账房"]
BOT_DELAY = {"roll": 1.15, "buy": 1.6, "settle": 2.0, "chance": 2.0}   # 结算弹窗：停留 2 秒再「点确定」
BOT_MOVE_LEAD = 1.1       # 骰子动画 + 落地停留
BOT_STEP_ANIM = 0.17      # 棋子每格的动画时长
# 结算窗前端的展示时序：骰子 2.6s + 每格 0.172s + 收尾 0.16s，弹窗才出现。
# 电脑必须在这之后再等 2 秒才自动点「确定」，否则弹窗还没出现，回合已经过去了。
SETTLE_ANIM_BASE = 2.6
SETTLE_STEP_ANIM = 0.172
SETTLE_ANIM_TAIL = 0.16
SETTLE_SHOW_BUFFER = 0.2   # SSE / 弹窗渲染的余量，保证「确定」时弹窗已完整显示 2 秒

# ---------------------------------------------------------------- AI 玩家（方案 A）
# 电脑有两种大脑：local（本地启发式）与 ai（走 OpenAI 兼容接口）。AI 只负责「提建议」，
# 能不能做仍由 Game 里那套规则校验裁决 —— AI 说错了也不会把规则改坏。
AI_SETTINGS = ROOT / "ai_config.json"   # 开始页里保存的接口配置
AI_FAIL_LOG = ROOT / "ai_fail.log"
AI_MEM_LIMIT = 240        # 笔记软上限（字）：写进提示词，超了要求模型自己精简
AI_MEM_HARD = 360         # 硬上限：超过就单独发一次「压缩笔记」调用，失败则截断
AI_RETRY = 3              # 返回不合法时最多试几次，仍不行就交回人机
AI_TRADE_MAX = 2          # 每个 AI 座位一局最多主动谈几笔换地（门槛高、次数少，别把交换当日常操作）

# 三档难度的人设（内置，不再依赖 config.ini）
AI_PERSONAS = {
    "normal": "普通：该省就省，看到便宜的地先占住，被人坑过会记着",
    "steady": "稳健：手上永远留足两脚最贵租金，只在回收期短的地上升级，不轻易跟人换地",
    "aggr": "激进：抢地优先，为了凑齐一整组愿意透支，谁挡路就记他一笔",
}

CHARS = [
    {"id": "fox", "name": "小狐", "glyph": "狐", "color": "#E08D5B", "ink": "#A95E2A",
     "desc": "机灵的橙色小狐"},
    {"id": "panda", "name": "小熊", "glyph": "熊", "color": "#5C8F9E", "ink": "#3A6C7B",
     "desc": "稳重的青色小熊"},
    {"id": "penguin", "name": "企鹅", "glyph": "企", "color": "#6F84C8", "ink": "#455CA2",
     "desc": "淡定的蓝衣企鹅"},
    {"id": "dino", "name": "恐龙", "glyph": "龙", "color": "#62A171", "ink": "#37764A",
     "desc": "热血的绿色恐龙"},
]

GROUP_COLORS = [
    "#C9A66B", "#6FA3C9", "#6FB79B", "#D98C7A", "#B07FB0",
    "#8B9BC4", "#A8B56A", "#C08862", "#7FB5B5", "#C46A7A",
]
GROUP_NAMES = [
    "麦金", "天蓝", "青碧", "珊瑚", "藕紫",
    "灰蓝", "橄榄", "赭石", "青绿", "玫红",
]

# 40 格：4 个角 + 30 块地产（10 个色组，每组 3 块）+ 4 张机会 + 2 格税 + 休息站
TILE_DEFS = [
    # 0-10 下边
    {"type": "go", "name": "起点", "sub": f"经过 +{SALARY}", "icon": "flag"},
    {"type": "prop", "group": 0, "name": "小桥", "price": 80, "rent": 26, "icon": "bridge"},
    {"type": "prop", "group": 0, "name": "巷口", "price": 90, "rent": 30, "icon": "home"},
    {"type": "prop", "group": 0, "name": "老屋", "price": 100, "rent": 34, "icon": "house"},
    {"type": "chance", "name": "机会", "sub": "抽一张卡", "icon": "spark"},
    {"type": "prop", "group": 1, "name": "码头", "price": 120, "rent": 40, "icon": "anchor"},
    {"type": "prop", "group": 1, "name": "渔港", "price": 130, "rent": 44, "icon": "boat"},
    {"type": "prop", "group": 1, "name": "灯塔", "price": 140, "rent": 48, "icon": "light"},
    {"type": "tax", "name": "税务局", "sub": "交 150", "amount": 150, "icon": "receipt"},
    {"type": "prop", "group": 2, "name": "茶铺", "price": 150, "rent": 50, "icon": "tea"},
    {"type": "hospital", "name": "医院", "sub": "交 120", "amount": 120, "icon": "hospital"},
    # 11-20 左边
    {"type": "prop", "group": 2, "name": "茶园", "price": 160, "rent": 54, "icon": "leaf"},
    {"type": "prop", "group": 2, "name": "竹林", "price": 170, "rent": 58, "icon": "bamboo"},
    {"type": "chance", "name": "机会", "sub": "抽一张卡", "icon": "spark"},
    {"type": "prop", "group": 3, "name": "集市", "price": 180, "rent": 60, "icon": "market"},
    {"type": "prop", "group": 3, "name": "老街", "price": 190, "rent": 64, "icon": "shop"},
    {"type": "prop", "group": 3, "name": "客栈", "price": 200, "rent": 68, "icon": "bed"},
    {"type": "prop", "group": 4, "name": "剧院", "price": 210, "rent": 70, "icon": "mask"},
    {"type": "prop", "group": 4, "name": "画廊", "price": 220, "rent": 74, "icon": "frame"},
    {"type": "prop", "group": 4, "name": "博物馆", "price": 230, "rent": 78, "icon": "museum"},
    {"type": "park", "name": "停车场", "sub": "奖金 +150", "amount": 150, "icon": "park"},
    # 21-30 上边
    {"type": "prop", "group": 5, "name": "车站", "price": 240, "rent": 80, "icon": "train"},
    {"type": "prop", "group": 5, "name": "港口", "price": 250, "rent": 85, "icon": "port"},
    {"type": "prop", "group": 5, "name": "机场", "price": 260, "rent": 90, "icon": "plane"},
    {"type": "prop", "group": 6, "name": "银行", "price": 270, "rent": 95, "icon": "bank"},
    {"type": "chance", "name": "机会", "sub": "抽一张卡", "icon": "spark"},
    {"type": "prop", "group": 6, "name": "交易所", "price": 280, "rent": 100, "icon": "chart"},
    {"type": "prop", "group": 6, "name": "金库", "price": 290, "rent": 105, "icon": "vault"},
    {"type": "tax", "name": "市政税", "sub": "交 200", "amount": 200, "icon": "tax"},
    {"type": "prop", "group": 7, "name": "山顶别墅", "price": 300, "rent": 110, "icon": "villa"},
    {"type": "chance", "name": "幸运角", "sub": "抽一张卡", "icon": "clover"},
    # 31-39 右边
    {"type": "prop", "group": 7, "name": "温泉", "price": 320, "rent": 115, "icon": "spring"},
    {"type": "prop", "group": 7, "name": "观景台", "price": 340, "rent": 120, "icon": "view"},
    {"type": "prop", "group": 8, "name": "花园", "price": 350, "rent": 125, "icon": "flower"},
    {"type": "prop", "group": 8, "name": "温室", "price": 360, "rent": 130, "icon": "plant"},
    {"type": "prop", "group": 8, "name": "果园", "price": 370, "rent": 135, "icon": "apple"},
    {"type": "prop", "group": 9, "name": "码头街", "price": 380, "rent": 140, "icon": "shop"},
    {"type": "prop", "group": 9, "name": "中央大道", "price": 400, "rent": 150, "icon": "tower"},
    {"type": "rest", "name": "休息站", "sub": "什么也不发生", "icon": "moon"},
    {"type": "prop", "group": 9, "name": "云端塔", "price": 450, "rent": 170, "icon": "cloud"},
]

def rules_payload() -> dict:
    """规则说明（数值全部从常量里取，改规则不用改前端文案）。"""
    pct = [int(round(l["rent"] * 100)) for l in LEVELS]
    money_choices = " / ".join(str(x) for x in MONEY_CHOICES)
    bot_mood = {"normal": "看性价比，留点余钱",
                "steady": "现金为王，只捡便宜",
                "aggr": "能买就买，升级最凶"}
    return {
        "title": "大富翁 · 规则说明",
        "sections": [
            {"title": "怎么赢", "items": [
                "2 到 %d 人同局（人和电脑都算），最后一位没有破产的玩家获胜。" % MAX_PLAYERS,
                "钱被扣成负数就破产出局，名下的地块全部变回无主。",
            ]},
            {"title": "一回合做什么", "items": [
                "轮到谁，先决定要不要在掷骰前发起交换（不换就直接掷骰）。",
                "掷两枚骰子，按点数逐格往前走；经过起点拿 %d 金币。" % SALARY,
                "停在无主地产上：可以买下，也可以放弃。",
                "停在别人的地上：按对方地块当前的等级交租金。",
                "正好踩在自己的地上：可以把它升级一级（刚买的这块本回合不能升）。",
                "落地后的租金、奖金、机会卡都会弹出窗口给所有人看，由落点玩家点「确定」才真正扣钱 / 加钱；"
                "电脑角色停留 2 秒后自动点确定。确认完这一回合自动结束，不需要再点「结束回合」。",
            ]},
            {"title": "买地", "items": [
                "只有停在无主地产上时才能买，价格印在格子下方。",
                "买下的地就是你的，别人再踩到要给你交租。",
                "钱不够就只能放弃，地块保持无主。",
            ]},
            {"title": "升级（三级）", "items": [
                "只有正踩在自己这块地上的那一回合才能升级 —— 走到哪里，才升哪里。",
                "刚买下的地不能马上升：要等下一次再走到它上面（不是「下回合」）才能升。",
                "每块地三级：初级（1.0 倍租金）、中级（%d%% 租金）、高级（%d%% 租金）。" % (pct[1], pct[2]),
                "升级费按地块原价算：升到中级 %d%%、升到高级 %d%%。" % (
                    int(round(UPGRADE_RATE[0] * 100)), int(round(UPGRADE_RATE[1] * 100))),
                "升级后租金立刻上涨，对手再踩到就得多付钱。",
            ]},
            {"title": "为什么升级按钮是灰的？", "items": [
                "四个条件同时满足才能升：站在这块地上、这块地是你的、不是本回合刚买的、金币够升级费。",
                "「没走到这儿」= 这块地现在不归你踩，先走到它上面。",
                "（「新买」的意思是：这块地是你刚买下的，下一次再走到它上面才能升）",
                "「钱不够」= 升级费还差钱；「已满级」= 这已经是高级，不能再升。",
                "升级只在这一回合的「地契卡」上点，点完这一回合就自动结束。",
            ]},
            {"title": "租金与固定格子", "items": [
                "租金 = 地块原租金 × 等级倍率，棋盘上每块地都标了当前租金。",
                "整组加成：同色系的 %d 块地全部落在一个人手里时，这组地租金再翻一倍；"
                "地块格子上的租金后面会标「已翻倍」，集齐的那一刻会有一次恭喜动画。"
                % len([t for t in TILE_DEFS if t.get("group") == 0]),
                "起点：经过 +%d 金币（正好停在也算）。" % SALARY,
                "税务局 −%d、市政税 −%d、医院 −%d、停车场 +%d。" % (
                    next(t["amount"] for t in TILE_DEFS if t["name"] == "税务局"),
                    next(t["amount"] for t in TILE_DEFS if t["name"] == "市政税"),
                    next(t["amount"] for t in TILE_DEFS if t["name"] == "医院"),
                    next(t["amount"] for t in TILE_DEFS if t["name"] == "停车场"),
                ),
                "机会格：抽一张卡，共 %d 张（分红、罚单、前进后退、直达起点等）。"
                "卡片会停在棋盘中央给所有人看，抽到的人点「确定」才生效；电脑停留 2 秒后自动确认。" % len(CHANCE_CARDS),
                "休息站：什么也不发生。",
            ]},
            {"title": "交换", "items": [
                "交换只能在自己回合、掷骰之前发起：先点「发起交换」，谈不谈都不影响掷骰。",
                "地块 + 现金自由搭配；双方的每一侧都可以一次选多块地打包交换。",
                "对方点「接受」才生效，拒绝就作废；同一时间只有一笔待回应的交换。",
                "人机（本地算法）只会接受或拒绝，不会主动发起交换。",
                "AI 座位有主动权：它会在落地后那一次计划里写好换地打算，到你下一回合开局（掷骰前）才真正发出去（一局最多几笔，被拒就作罢）。",
            ]},
            {"title": "电脑玩家", "items": [
                "只能在等待室加电脑，开局后只能改难度，不能加人或删人。",
                "电脑有两种：人机（本地算法，秒回）和 AI（真的调一个大模型来想）。房主在等待室点「＋人机」或「＋AI」自己挑。",
                "三档难度：%s。" % "、".join(
                    "%s（%s）" % (v["name"], bot_mood[k]) for k, v in BOT_LEVELS.items()),
                "人机会按色组凑地、按手里的现金决定买不买；升级只升它正踩着的地，且用「收几次租回本」来算划不划算。",
                "人机还看局势：现金比平均多就攥钱，明显落后就抢地，所以同一档难度也会打出不同的节奏。",
                "电脑每做一个决定都会留一句理由（例如「买下就凑齐这组了，咬牙也要拿」「升级费要收 6 次租才回本，不划算」），在记录里能直接看到它怎么想的。",
                "AI 玩家除了看规则和局势，还会带着自己写的「小本子」（记谁坑过它、盯上哪块地、下一步计划）和最近流水，所以打起来更连贯、也会记仇。",
                "AI 在落地之后才决定：踩到要买 / 要升的地时调一次模型，一次把这一回合剩下的打算排好；"
                "AI 走到只收租、交税、领钱这种不需要选的地方就不调模型，自动确认。",
                "AI 给的计划会先过一遍结构校验：不合法就打回去让它重来，三次还不行就换人机接手这一回合，对局不会卡住。",
                "AI 想的时候玩家卡片上会显示「AI 正在思考…」，最多等几秒。",
                "4 个座位全交给电脑也能开局，人只负责观战。",
            ]},
            {"title": "设置、暂停与存档", "items": [
                "开始页可选初始资金 %s（自定义范围 %d–%d）。" % (money_choices, MONEY_MIN, MONEY_MAX),
                "房主在对局中可以点「返回开始页（保留局面）」改设置，再点「继续」回到原来的局面。",
                "存档写在程序目录的 monopoly.db 里，同时只跑一局；下次打开可以认领上次的座位接着玩。",
                "观战不占座位，只读棋盘和日志，不能操作棋子。",
            ]},
        ],
    }


CHANCE_CARDS = [
    {"text": "银行分红到账", "delta": 150, "icon": "bank"},
    {"text": "朋友聚餐，你买了单", "delta": -120, "icon": "shop"},
    {"text": "路边捡到钱包", "delta": 80, "icon": "spark"},
    {"text": "市政维修摊派", "delta": -100, "icon": "receipt"},
    {"text": "顺风车，前进 3 格", "move": 3, "icon": "car"},
    {"text": "回头找东西，后退 2 格", "move": -2, "icon": "moon"},
    {"text": "彩票中奖", "delta": 200, "icon": "clover"},
    {"text": "违停罚单", "delta": -90, "icon": "tax"},
    {"text": "直达起点，补贴到账", "goto": 0, "icon": "flag"},
    {"text": "抄近路，前进 5 格", "move": 5, "icon": "car"},
]


# ---------------------------------------------------------------- 存档（SQLite）
DB_PATH = ROOT / "monopoly.db"
SCHEMA_VERSION = "1"
SAVE_SLOTS = 6          # 历史存档最多留几局，超了删最旧的那几局

SCHEMA_SQL = """
CREATE TABLE IF NOT EXISTS meta (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS games (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    title      TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    status     TEXT NOT NULL,      -- playing / over
    phase      TEXT,
    summary    TEXT,               -- 玩家与轮次摘要，列表页用
    snapshot   TEXT NOT NULL       -- 完整局面（JSON）
);
"""


def now_text() -> str:
    return datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")


def db_open() -> sqlite3.Connection:
    conn = sqlite3.connect(str(DB_PATH), timeout=8)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL")
    return conn


def db_init() -> None:
    with db_open() as conn:
        conn.executescript(SCHEMA_SQL)
        row = conn.execute("SELECT value FROM meta WHERE key = 'schema_version'").fetchone()
        if row is None:
            conn.execute("INSERT INTO meta (key, value) VALUES ('schema_version', ?)", (SCHEMA_VERSION,))


def db_new_game(title: str, summary: str, snapshot: str) -> int:
    stamp = now_text()
    with db_open() as conn:
        cur = conn.execute(
            "INSERT INTO games (title, created_at, updated_at, status, phase, summary, snapshot)"
            " VALUES (?, ?, ?, 'playing', 'lobby', ?, ?)",
            (title, stamp, stamp, summary, snapshot),
        )
        return int(cur.lastrowid)


def db_save_game(game_id: int, phase: str, status: str, summary: str, snapshot: str) -> None:
    with db_open() as conn:
        conn.execute(
            "UPDATE games SET updated_at = ?, phase = ?, status = ?, summary = ?, snapshot = ?"
            " WHERE id = ?",
            (now_text(), phase, status, summary, snapshot, game_id),
        )


def db_prune_games(keep: int = SAVE_SLOTS) -> list[dict]:
    """存档最多留 keep 局：按「最近更新」倒序，多出来的最旧几条直接删掉。

    返回被删掉的记录（id / title / updated_at），调用方拿去向玩家交代一句。
    """
    with db_open() as conn:
        rows = conn.execute(
            "SELECT id, title, updated_at FROM games ORDER BY updated_at DESC, id DESC"
        ).fetchall()
        extra = [dict(r) for r in rows[keep:]]
        if extra:
            conn.executemany("DELETE FROM games WHERE id = ?", [(r["id"],) for r in extra])
    return extra


def db_list_games() -> list[dict]:
    with db_open() as conn:
        rows = conn.execute(
            "SELECT id, title, created_at, updated_at, status, phase, summary"
            " FROM games ORDER BY updated_at DESC, id DESC"
        ).fetchall()
    out = []
    for r in rows:
        item = dict(r)
        try:
            item["summary"] = json.loads(item["summary"] or "{}")
        except json.JSONDecodeError:
            item["summary"] = {}
        out.append(item)
    return out


def db_get_game(game_id: int) -> dict | None:
    with db_open() as conn:
        row = conn.execute("SELECT * FROM games WHERE id = ?", (game_id,)).fetchone()
    return dict(row) if row else None


def next_pid(existing: list[int]) -> int:
    i = 1
    while i in existing:
        i += 1
    return i


# ---------------------------------------------------------------- 地块等级
def rent_at(tile: dict) -> int:
    """按等级算租金：初级 = 原租金，中级 1.7 倍，高级 2.6 倍。"""
    lv = int(tile.get("level", 0))
    lv = max(0, min(lv, len(LEVELS) - 1))
    return int(round(tile["rent"] * LEVELS[lv]["rent"]))


def upgrade_cost(tile: dict) -> int | None:
    """升到下一级要花多少钱；已经满级返回 None。"""
    lv = int(tile.get("level", 0))
    if lv >= len(LEVELS) - 1:
        return None
    return int(round(tile["price"] * UPGRADE_RATE[lv]))


def rent_next(tile: dict) -> int:
    """升一级后的租金（已经满级就返回当前租金）。"""
    lv = min(int(tile.get("level", 0)), len(LEVELS) - 1)
    return int(round(tile["rent"] * LEVELS[min(lv + 1, len(LEVELS) - 1)]["rent"]))


def bot_keep(level: str) -> int:
    return BOT_LEVELS.get(level, BOT_LEVELS["normal"])["keep"]


# ---------------------------------------------------------------- AI 大脑
def extract_json(text: str) -> dict | None:
    """模型爱在 JSON 外面套 ``` 或加解释，这里只把最外层的对象捞出来。"""
    if not text:
        return None
    body = text.strip()
    if body.startswith("```"):
        body = body.strip("`")
        if body.lstrip().lower().startswith("json"):
            body = body.lstrip()[4:]
    start, end = body.find("{"), body.rfind("}")
    if start < -1 or end <= start:
        return None
    try:
        obj = json.loads(body[start:end + 1])
    except json.JSONDecodeError:
        return None
    return obj if isinstance(obj, dict) else None


class AIBrain:
    """AI 玩家的大脑：一个 OpenAI 兼容的 /chat/completions 客户端，只依赖标准库。

    接口地址 / API Key / 模型在开始页弹窗里填，保存到 exe 同目录的 ai_config.json，
    保存即生效，不用重启。旧的 config.ini 只在第一次没有 ai_config.json 时读一次做迁移。
    """

    def __init__(self) -> None:
        self.base_url = ""
        self.api_key = ""
        self.model = ""
        self.timeout = 16.0
        self.temperature = 0.7
        self.max_tokens = 300
        self.persona: dict[str, dict] = {}
        self.ok = False
        self.reason = "还没配置 AI 接口"
        self.calls = 0
        self.errors = 0
        self.reload()

    # ---------------------------------------------------------- 配置
    def _load(self) -> dict:
        if not AI_SETTINGS.exists():
            return {}
        try:
            data = json.loads(AI_SETTINGS.read_text(encoding="utf-8"))
            return data if isinstance(data, dict) else {}
        except (OSError, json.JSONDecodeError):
            return {}

    def _write(self, data: dict) -> None:
        AI_SETTINGS.write_text(
            json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    def reload(self) -> bool:
        self.base_url = self.api_key = self.model = ""
        self.ok = False
        data = self._load()
        self.base_url = str(data.get("base_url") or "").strip().rstrip("/")
        self.api_key = str(data.get("api_key") or "").strip()
        self.model = str(data.get("model") or "").strip()
        if not self.base_url or not self.model:
            self.reason = "还没配置 AI 接口，点开始页的「AI 接口」填地址和模型"
            return False
        if not (self.base_url.startswith("http://") or self.base_url.startswith("https://")):
            self.reason = "接口地址要以 http:// 或 https:// 开头"
            return False
        try:
            self.timeout = max(2.0, min(60.0, float(data.get("timeout") or 16)))
            self.temperature = max(0.0, min(2.0, float(data.get("temperature") or 0.7)))
            self.max_tokens = max(80, min(4096, int(data.get("max_tokens") or 300)))
        except (TypeError, ValueError):
            self.reason = "timeout / temperature / max_tokens 不是数字"
            return False
        self.persona = {k: {"persona": v} for k, v in AI_PERSONAS.items()}
        self.ok = True
        self.reason = "%s（模型 %s）" % (self.base_url, self.model)
        return True

    def key_mask(self) -> str:
        if not self.api_key:
            return ""
        if len(self.api_key) <= 8:
            return "****"
        return self.api_key[:4] + "****" + self.api_key[-4:]

    def public(self) -> dict:
        return {
            "baseUrl": self.base_url,
            "model": self.model,
            "keyMask": self.key_mask(),
            "hasKey": bool(self.api_key),
            "ok": self.ok,
            "reason": self.reason,
            "timeout": self.timeout,
        }

    def configure(self, base_url: str, model: str, api_key: str = "",
                  timeout: float | None = None) -> dict:
        """开始页保存配置：校验格式 → 写 ai_config.json → 立即重载。"""
        base_url = (base_url or "").strip().rstrip("/")
        model = (model or "").strip()
        if not base_url or not model:
            raise ValueError("接口地址和模型都要填")
        if not (base_url.startswith("http://") or base_url.startswith("https://")):
            raise ValueError("接口地址要以 http:// 或 https:// 开头")
        data = {
            "base_url": base_url,
            "api_key": (api_key or "").strip() or self.api_key,   # 留空=沿用已保存的 Key
            "model": model,
            "timeout": float(timeout or self.timeout or 16),
            "temperature": self.temperature,
            "max_tokens": self.max_tokens,
        }
        self._write(data)
        self.reload()
        if not self.ok:
            raise ValueError(self.reason)
        return self.public()

    # ---------------------------------------------------------- 调用
    def _chat(self, messages: list[dict], model: str = "") -> str:
        body = {
            "model": model or self.model,
            "messages": messages,
            "temperature": self.temperature,
            "max_tokens": self.max_tokens,
        }
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        req = urllib.request.Request(self.base_url + "/chat/completions", data=data, method="POST")
        req.add_header("Content-Type", "application/json")
        if self.api_key:
            req.add_header("Authorization", "Bearer " + self.api_key)
        self.calls += 1
        with urllib.request.urlopen(req, timeout=self.timeout) as resp:
            payload = json.loads(resp.read().decode("utf-8", "replace"))
        try:
            return str(payload["choices"][0]["message"]["content"] or "")
        except (KeyError, IndexError, TypeError):
            raise ValueError("接口返回里没有 choices[0].message.content")

    def _plan_prompt(self, persona: str) -> str:
        return (
            "你是棋盘游戏「大富翁」里的电脑玩家。这一回合你已经掷完骰子、走到了落点，"
            "现在只调用你一次：你要把「落地之后这一回合剩下要做的事」一次性排好，程序会按计划执行，中途不再问你。\n"
            "现在的流程：如果你踩到无主地，按 buy 决定买或放弃；如果停在自己已买下的地块上，按 land 决定升级或不动；"
            "然后程序自动结束回合。\n"
            "另注：换地只能在自己回合掷骰之前发起，所以计划里的 trade 不会立刻执行 —— "
            "程序会把它留到你下一回合开局、掷骰之前替你发出去。\n"
            "规则要点：40 格环形棋盘，经过起点收 %d 金币；走到无主地块可买下，之后别人踩到要付租金；"
            "租金随等级上涨（初级 1.0 / 中级 1.7 / 高级 2.6 倍原租金）；"
            "同色系的地块如果全被一个人买齐，这组地租金再翻一倍；"
            "只有踩在自己已拥有的地块上、且不是本回合刚买下的，才能花升级费升级；现金跌破 0 就出局。\n"
            "你的性格：%s\n"
            "输出要求：只回一个 JSON 对象，不要解释、不要 markdown 代码块，字段：\n"
            '{"why":"一句话理由（对手也能看到，20 字以内）",\n'
            ' "memory":"更新后的个人笔记（%d 字以内，写「谁坑过我、我盯上哪块地、下一步打算」）",\n'
            ' "buy":{"want":true,"maxPrice":你愿出的最高价},\n'
            ' "land":{"upgrade":true,"maxCost":你愿付的最高升级费},\n'
            ' "trade":{"target":"对方名字","want":"想要对方的地名","give":"你让出的地名（可省略）",'
            '"giveCash":补给对方的现金}}\n'
            "buy.want 填 false 表示踩到无主地一律放弃；maxPrice 是上限，超过就放弃，不确定可以写 0 表示只要买得起就买。"
            "land.upgrade 填 false 表示停在自己的地上也不升级；maxCost 是升级费上限，不确定写 0 表示只要升得起就升。"
            "trade 可以整个省略；只有「换回来就能凑齐一整组」或「对方明显卖便宜了」才写，一局最多主动谈 %d 笔，"
            "而且会在你下一回合才生效。\n"
            "\n注意：计划是一次性的，后面没法改主意，所以 maxPrice / maxCost 要按现在的钱包留够余量。"
            % (SALARY, persona or "普通，理性但会记仇", AI_MEM_LIMIT, AI_TRADE_MAX)
        )

    def decide_plan(self, context: str, level: str = "normal",
                    feedback: str = "") -> tuple[dict | None, str, str]:
        """一次拿到整回合计划。返回 (计划 dict, 错误说明, 错误类型)：空串=成功，net=放弃，json=可重试。"""
        prof = self.persona.get(level, {})
        messages = [
            {"role": "system", "content": self._plan_prompt(prof.get("persona", ""))},
            {"role": "user", "content": context + (("\n\n" + feedback) if feedback else "")},
        ]
        try:
            text = self._chat(messages, prof.get("model", ""))
        except (urllib.error.URLError, urllib.error.HTTPError, socket.timeout, TimeoutError,
                ValueError, json.JSONDecodeError) as exc:
            self.errors += 1
            return None, "接口调用失败：%s" % exc, "net"
        except Exception as exc:                     # 兜底，别让一次怪错把对局卡死
            self.errors += 1
            return None, "接口调用失败：%s" % exc, "net"
        intent = extract_json(text)
        if intent is None:
            return None, "返回的不是 JSON 对象：%s" % text[:160], "json"
        return intent, "", ""

    def clamp_memory(self, text: str) -> str:
        """整回合只调一次接口，所以笔记超长就地截断，不再额外发一次压缩请求。"""
        text = (text or "").strip()
        return text[:AI_MEM_LIMIT]



ai_brain = AIBrain()


class Game:
    """全局共享的一局游戏。所有修改都在 lock 里完成并 version += 1。"""

    def __init__(self) -> None:
        self.lock = threading.Condition()
        self.version = 0
        self.game_id: int | None = None
        self.db_ok = True
        self.host_token: str | None = None
        self.watchers: set[str] = set()
        self.frozen: dict | None = None
        self.bot_timer: threading.Timer | None = None
        self.start_money = START_MONEY
        self.bot_level = "normal"
        self.reset()

    # ---------------------------------------------------------------- 基础
    def reset(self) -> None:
        self.players: list[dict] = []
        self.tokens: dict[str, int] = {}
        self.board = [dict(t, i=n, owner=None, level=0, bought_turn=-1) for n, t in enumerate(TILE_DEFS)]
        self.phase = "lobby"          # lobby / setup / roll / settle / buy / chance / over
        self.turn_no = 0              # 回合序号：用来判断「这回合刚买的地不能立刻升」
        self.current: int | None = None
        self.host: int | None = None
        self.dice = [0, 0]
        self.pending: int | None = None
        self.chance: dict | None = None      # 待确认的机会卡（玩家点「确定」后才生效）
        self.settles: list[dict] = []        # 待确认的结算事件（租金 / 工资 / 税费…），确认后才动钱
        self.settle_lead = 0.0               # 本回合结算窗出现前还要播多久动画（电脑自动确认用）
        self.land: dict | None = None        # 结算完之后要落的点（买地 / 升级 / 机会）
        self.log: list[dict] = []
        self.log_seq = 0
        self.news: dict = {"seq": 0, "type": "none"}
        self.news_frame: list[dict] = []    # 本帧累积的事件（一次状态变化可能有掷骰 + 落地 + 买地多条）
        self.news_pub: list[dict] = []      # 上一帧已经推出去的事件批
        self.news_seq = 0
        self.trades: list[dict] = []
        self.trade_seq = 0
        self.winner: int | None = None
        self.frozen = None
        self.bot_timer = None
        self.trade_timer = None
        self.bump()
        self.say("房间已创建，等人加入。")

    # ---------------------------------------------------------------- 存档
    def snapshot(self) -> dict:
        """可落库的完整局面。不包含 token（载入后重新认领座位）。"""
        return {
            "players": self.players,
            "board": self.board,
            "phase": self.phase,
            "current": self.current,
            "host": self.host,
            "dice": self.dice,
            "pending": self.pending,
            "chance": self.chance,
            "settles": self.settles,
            "land": self.land,
            "log": self.log,
            "log_seq": self.log_seq,
            "news": self.news,
            "news_seq": self.news_seq,
            "trades": self.trades,
            "trade_seq": self.trade_seq,
            "winner": self.winner,
            "version": self.version,
            "turn_no": self.turn_no,
            "start_money": self.start_money,
            "bot_level": self.bot_level,
            "frozen": self.frozen,
        }

    def summary(self) -> str:
        return json.dumps({
            "players": [{"name": p["name"], "char": p["char"], "money": p["money"],
                         "kind": p.get("kind", "human")} for p in self.players],
            "current": self.current,
            "round": self.log_seq,
            "bots": len([p for p in self.players if p.get("kind") == "bot"]),
        }, ensure_ascii=False)

    def persist(self) -> None:
        if self.game_id is None or self.phase == "lobby" or not self.db_ok:
            return
        status = "over" if self.phase == "over" else "playing"
        try:
            db_save_game(self.game_id, self.phase, status, self.summary(),
                         json.dumps(self.snapshot(), ensure_ascii=False))
        except Exception as exc:                        # 存档失败不影响继续玩
            if self.db_ok:
                print("存档写入失败，已降级为纯内存模式：%s" % exc)
            self.db_ok = False

    def restore(self, snap: dict, game_id: int) -> None:
        with self.lock:
            self.players = snap.get("players", [])
            for p in self.players:
                p.setdefault("kind", "human")
                p.setdefault("bot_level", "normal")
                p.setdefault("brain", "local")
                p.setdefault("memory", "")
                p.pop("ai_plan", None)
                p.setdefault("ai_plan_turn", None)
                p["thinking"] = False          # 存档里不该留「思考中」，载入后一律清掉
            self.board = snap.get("board", [])
            for t in self.board:
                t.setdefault("level", 0)
                t.setdefault("owner", None)
                t.setdefault("bought_turn", -1)
            self.phase = snap.get("phase", "lobby")
            self.turn_no = snap.get("turn_no", 0)
            self.current = snap.get("current")
            self.host = snap.get("host")
            self.dice = snap.get("dice") or [0, 0]
            self.pending = snap.get("pending")
            self.chance = snap.get("chance")
            self.settles = snap.get("settles") or []
            self.settle_lead = 0.0            # 载入后直接显示弹窗，不再补播移动
            self.land = snap.get("land")
            if self.phase == "action":       # 旧存档没有「结算」这个概念，落到行动阶段就直接交棒
                self.phase = "roll"
                self.pending = None
            self.log = snap.get("log", [])
            self.log_seq = snap.get("log_seq", 0)
            self.news = snap.get("news") or {"seq": 0, "type": "none"}
            self.news_seq = snap.get("news_seq", 0)
            self.news_frame = []
            self.news_pub = []
            self.trades = snap.get("trades", [])
            self.trade_seq = snap.get("trade_seq", 0)
            self.winner = snap.get("winner")
            self.start_money = snap.get("start_money", START_MONEY)
            self.bot_level = snap.get("bot_level", "normal")
            self.frozen = snap.get("frozen")
            for tr in self.trades:
                tr.setdefault("asked", False)
            self.version = snap.get("version", 0) + 1
            self.tokens = {}                             # 座位全部空着，等认领
            self.watchers = set()
            self.host_token = None
            self.game_id = game_id
            self.say("已载入存档 %d，请各自认领座位。" % game_id)
            self.version += 1
            self.lock.notify_all()
            self._schedule_bot()

    def seat_list(self) -> list[dict]:
        claimed = set(self.tokens.values())
        out = []
        for p in self.players:
            is_bot = p.get("kind") == "bot"
            out.append({
                "id": p["id"], "name": p["name"], "char": p["char"],
                "money": p["money"], "alive": p["alive"],
                "props": len(self.props_of(p["id"])),
                "claimed": True if is_bot else (p["id"] in claimed),
                "kind": p.get("kind", "human"),
                "level": p.get("bot_level", "normal"),
            })
        return out

    def claim_seat(self, pid: int) -> str:
        with self.lock:
            if self.phase in ("lobby",):
                raise ValueError("还没开局，直接加入房间就行")
            p = self.player(pid)
            if not p:
                raise ValueError("没有这个座位")
            if p.get("kind") == "bot":
                raise ValueError("这是电脑玩家，不用认领")
            if pid in self.tokens.values():
                raise ValueError("这个座位已经有人坐下了")
            token = secrets.token_hex(14)
            self.tokens[token] = pid
            if self.host_token is None:
                self.host_token = token                    # 第一个回到座位的人当房主
            self.say("%s 认领了座位" % p["name"], "join", pid)
            self.bump()
            return token

    def load_from_db(self, game_id: int) -> list[dict]:
        with self.lock:
            if self.phase not in ("lobby", "setup") and self.game_id != game_id:
                raise ValueError("现在还有一局在进行，先结束它再载入别的存档")
            # 同一局已经被载入过、而且已经有人认领了座位：不能再 restore ——
            # restore 会清空 self.tokens，后一个人点「载入存档」就会把前一个人的登录态冲掉，
            # 表现为「一个人行动完，另一个人就登录失效」。这里只把现有座位表还给他继续认领。
            if self.game_id == game_id and self.tokens:
                return self.seat_list()
            row = db_get_game(game_id)
            if not row:
                raise ValueError("这个存档不存在")
            try:
                snap = json.loads(row["snapshot"] or "{}")
            except json.JSONDecodeError:
                raise ValueError("存档内容损坏，读不出来")
            if not snap or not snap.get("players"):
                raise ValueError("这局还没开始过，没有可载入的局面")
            self.restore(snap, game_id)
            return self.seat_list()

    def bump(self) -> None:
        with self.lock:
            self.version += 1
            # 本帧的事件封成一批交给前端，按顺序播动画（不清空就永远只播最后一个）
            self.news_pub = list(self.news_frame)
            self.news_frame = []
            self.persist()
            self._schedule_bot()
            self.lock.notify_all()

    def say(self, text: str, kind: str = "sys", pid: int | None = None) -> None:
        self.log_seq += 1
        self.log.append({"id": self.log_seq, "kind": kind, "pid": pid, "text": text})
        del self.log[:-80]

    def event(self, type_: str, **kw) -> None:
        """给前端播动画用的一次性事件（带自增 seq，客户端只播一次）。"""
        self.news_seq += 1
        item = dict(kw, seq=self.news_seq, type=type_)
        self.news = item
        self.news_frame.append(item)

    def player(self, pid: int) -> dict | None:
        for p in self.players:
            if p["id"] == pid:
                return p
        return None

    def used_chars(self) -> list[str]:
        return [p["char"] for p in self.players]

    def props_of(self, pid: int) -> list[int]:
        return [t["i"] for t in self.board if t.get("owner") == pid]

    def watch(self) -> str:
        """观战：不占座位，也能看棋盘、也能当房主管设置。"""
        with self.lock:
            token = secrets.token_hex(14)
            self.watchers.add(token)
            if self.host_token is None:
                self.host_token = token
            if not self.players:
                self.say("有人进来观战。", "join")
            else:
                self.say("有人进来观战。", "sys")
            self.bump()
            return token

    def is_admin(self, token: str) -> bool:
        return token == self.host_token

    def can_view(self, token: str) -> bool:
        return token in self.tokens or token in self.watchers

    def leave(self, token: str) -> None:
        with self.lock:
            if token in self.watchers:
                self.watchers.discard(token)
            if self.phase != "lobby" or self.frozen is not None:
                # 局已开始：座位不回收，只让观战者走
                if token not in self.tokens:
                    if self.host_token == token:
                        self.host_token = None
                        for tk in self.tokens:
                            self.host_token = tk
                            break
                    self.bump()
                    return
                raise ValueError("对局已经开始，退出会丢座位；直接关掉页面就行，座位会留着")
            pid = self.tokens.pop(token, None)
            if self.host_token == token:
                self.host_token = None
                for tk, _ in self.tokens.items():
                    self.host_token = tk
                    break
            if pid is not None:
                p = self.player(pid)
                if p and self.phase == "lobby":
                    self.players.remove(p)
                    if self.host == pid:
                        self.host = self.players[0]["id"] if self.players else None
                    self.say("%s 离开了房间" % p["name"], "sys")
            self.bump()

    # ---------------------------------------------------------------- 起始资金 / 难度
    def set_settings(self, start_money=None, bot_level=None) -> None:
        with self.lock:
            if self.phase == "over":
                raise ValueError("本局已经结束，等下一局再改设置")
            if start_money is not None:
                if self.phase != "lobby" or self.frozen is not None:
                    raise ValueError("这一局已经开局，起始资金不再改动；想改请重开一局")
                try:
                    value = int(start_money)
                except (TypeError, ValueError):
                    raise ValueError("起始资金要给个数字")
                if not MONEY_MIN <= value <= MONEY_MAX:
                    raise ValueError("起始资金要在 %d 到 %d 之间" % (MONEY_MIN, MONEY_MAX))
                self.start_money = value
                for p in self.players:
                    p["money"] = value
                self.say("房主把起始资金改成 %d" % value, "sys")
            if bot_level is not None:
                if bot_level not in BOT_LEVELS:
                    raise ValueError("难度只有 平衡 / 稳健 / 激进")
                self.bot_level = bot_level
                for p in self.players:
                    if p.get("kind") == "bot":
                        p["bot_level"] = bot_level
                self.say("人机难度设为「%s」" % BOT_LEVELS[bot_level]["name"], "sys")
            self.bump()

    # ---------------------------------------------------------------- 人机
    def _bot_name(self) -> str:
        used = [p["name"] for p in self.players]
        for n in BOT_NAMES:
            if n not in used:
                return n
        return "电脑%d" % (len(self.players) + 1)

    def add_bot(self, brain: str = "local") -> int:
        with self.lock:
            if self.phase not in ("lobby", "setup"):
                raise ValueError("对局进行中不能加人机，先重开一局")
            if self.frozen is not None:
                raise ValueError("这一局已经开局，不能再加人机；想加请重开一局")
            if len(self.players) >= MAX_PLAYERS:
                raise ValueError("房间满了（最多 %d 人）" % MAX_PLAYERS)
            if brain not in ("local", "ai"):
                raise ValueError("电脑只有两种：人机 / AI")
            if brain == "ai" and not ai_brain.ok:
                raise ValueError("AI 还没配好：%s（点开始页的「AI 接口」填一下）" % ai_brain.reason)
            free = [c["id"] for c in CHARS if c["id"] not in self.used_chars()]
            if not free:
                raise ValueError("没有空余角色了")
            pid = next_pid([p["id"] for p in self.players])
            name = self._bot_name()
            self.players.append({
                "id": pid, "name": name, "char": free[0], "pos": 0,
                "money": self.start_money, "alive": True,
                "kind": "bot", "bot_level": self.bot_level,
                "brain": brain, "memory": "", "thinking": False,
            })
            tag = "AI" if brain == "ai" else "人机"
            self.say("房主加了%s玩家「%s」（%s）" % (tag, name, BOT_LEVELS[self.bot_level]["name"]), "join", pid)
            self.bump()
            return pid

    def remove_bot(self, pid: int) -> None:
        with self.lock:
            if self.phase != "lobby" or self.frozen is not None:
                raise ValueError("只有还没开局的等待室能删人机")
            p = self.player(pid)
            if not p or p.get("kind") != "bot":
                raise ValueError("这不是电脑玩家")
            self.players.remove(p)
            self.say("房主移除了电脑玩家「%s」" % p["name"], "sys")
            self.bump()

    def set_bot_level(self, pid: int, level: str) -> None:
        with self.lock:
            if level not in BOT_LEVELS:
                raise ValueError("难度只有 平衡 / 稳健 / 激进")
            p = self.player(pid)
            if not p or p.get("kind") != "bot":
                raise ValueError("这不是电脑玩家")
            p["bot_level"] = level
            self.bump()

    # ---------------------------------------------------------------- 加入
    def join(self, name: str, char: str) -> tuple[str, int]:
        with self.lock:
            if self.phase != "lobby" or self.frozen is not None:
                raise ValueError("这局已经开始了，等下一局吧")
            if len(self.players) >= MAX_PLAYERS:
                raise ValueError("房间满了（最多 %d 人）" % MAX_PLAYERS)
            name = (name or "").strip()[:8] or "玩家"
            if char not in [c["id"] for c in CHARS]:
                raise ValueError("角色不存在")
            if char in self.used_chars():
                raise ValueError("这个角色已被选走")
            pid = next_pid([p["id"] for p in self.players])
            self.players.append({
                "id": pid, "name": name, "char": char, "pos": 0,
                "money": self.start_money, "alive": True,
                "kind": "human", "bot_level": "normal",
            })
            token = secrets.token_hex(14)
            self.tokens[token] = pid
            if self.host is None:
                self.host = pid
            if self.host_token is None:
                self.host_token = token
            self.say(f"{name} 加入，选了「{self._char_name(char)}」", "join", pid)
            self.bump()
            return token, pid

    def _char_name(self, char: str) -> str:
        for c in CHARS:
            if c["id"] == char:
                return c["name"]
        return char

    def pid_of(self, token: str) -> int | None:
        with self.lock:
            return self.tokens.get(token)

    # ---------------------------------------------------------------- 开局
    def start(self) -> None:
        with self.lock:
            if self.phase != "lobby" or self.frozen is not None:
                raise ValueError("已经开始了")
            if len(self.players) < 2:
                raise ValueError("至少 2 个玩家（人或电脑）才能开始")
            self.phase = "roll"
            self.current = self.players[0]["id"]
            self.dice = [0, 0]
            self.turn_no = 1
            for t in self.board:
                t["bought_turn"] = -1
            if self.db_ok:
                try:
                    names = "、".join(p["name"] for p in self.players)
                    self.game_id = db_new_game(
                        names + " 的局", self.summary(),
                        json.dumps(self.snapshot(), ensure_ascii=False))
                    # 存档只留最近 SAVE_SLOTS 局，多出来的最旧那几局直接删掉
                    for old in db_prune_games():
                        self.say("存档最多留 %d 局，「%s」已被删掉" % (SAVE_SLOTS, old["title"]),
                                 "sys")
                except Exception as exc:
                    self.db_ok = False
                    print("存档数据库不可用，本次对局只在内存里进行：%s" % exc)
            self.say("游戏开始，从 " + self.player(self.current)["name"] + " 先手。", "turn")
            self.bump()

    def restart(self) -> None:
        with self.lock:
            # 旧的那一局就此封存，下一局另起一行记录
            if self.game_id is not None and self.db_ok:
                try:
                    db_save_game(self.game_id, self.phase, "over", self.summary(),
                                 json.dumps(self.snapshot(), ensure_ascii=False))
                except Exception as exc:
                    print("封存旧对局失败：%s" % exc)
            self.game_id = None
            players = self.players
            host = self.host
            self.players = players
            self.host = host
            self.board = [dict(t, i=n, owner=None, level=0) for n, t in enumerate(TILE_DEFS)]
            self.phase = "lobby"
            self.current = None
            self.dice = [0, 0]
            self.last_steps = 0
            self.pending = None
            self.chance = None
            self.trades = []
            self.trade_seq = 0
            self.winner = None
            self.frozen = None
            self.log = [{"id": 1, "kind": "sys", "pid": None, "text": "新的一局，等人开始。"}]
            self.log_seq = 1
            self.event("reset")
            for p in players:
                p.update(pos=0, money=self.start_money, alive=True)
            self.bump()

    # ---------------------------------------------------------------- 暂停 / 继续
    def pause(self) -> None:
        """房主把正在进行的局冻结，退回开始页调设置。"""
        with self.lock:
            if self.phase in ("lobby", "setup"):
                raise ValueError("现在没在打，不用暂停")
            snap = self.snapshot()
            snap.pop("frozen", None)
            self.frozen = snap
            self.phase = "setup"
            self.say("房主把对局暂停了，回到开始页调设置。", "sys")
            self.bump()

    def resume(self) -> None:
        with self.lock:
            if self.phase != "setup" or not self.frozen:
                raise ValueError("现在没有暂停中的对局")
            snap = self.frozen
            keep_tokens = self.tokens
            keep_host = self.host_token
            keep_watch = self.watchers
            players = self.players
            self.players = snap.get("players", players)
            self.board = snap.get("board", self.board)
            for t in self.board:
                t.setdefault("level", 0)
            self.phase = snap.get("phase", "roll")
            self.current = snap.get("current")
            self.dice = snap.get("dice") or [0, 0]
            self.pending = snap.get("pending")
            self.log = snap.get("log", self.log)
            self.log_seq = snap.get("log_seq", self.log_seq)
            self.trades = snap.get("trades", [])
            for tr in self.trades:
                tr.setdefault("asked", False)
            self.winner = snap.get("winner")
            self.frozen = None
            self.tokens = keep_tokens
            self.host_token = keep_host
            self.watchers = keep_watch
            self.say("继续上一局。", "sys")
            self.bump()

    # ---------------------------------------------------------------- 掷骰
    def roll(self, pid: int) -> None:
        with self.lock:
            self._need(pid, "roll")
            p = self.player(pid)
            self.dice = [random.randint(1, 6), random.randint(1, 6)]
            steps = sum(self.dice)
            self.last_steps = steps
            self.settle_lead = SETTLE_ANIM_BASE + SETTLE_STEP_ANIM * steps + SETTLE_ANIM_TAIL
            self.say(f"{p['name']} 掷出 {self.dice[0]} + {self.dice[1]}，前进 {steps} 格", "dice", pid)
            self.event("dice", pid=pid, dice=list(self.dice), steps=steps)
            self.settles = []
            self.land = None
            self._move(p, steps, pay_salary=True)
            self._resolve(p, depth=0)
            self._settle_start(p)
            self.bump()

    def _move(self, p: dict, steps: int, pay_salary: bool) -> None:
        old = p["pos"]
        p["pos"] = (old + steps) % BOARD_SIZE
        if pay_salary and steps > 0 and (old + steps) >= BOARD_SIZE:
            # 工资先挂进待确认队列：玩家点「确定」之后才真正进账（所有人都会看到这个弹窗）
            self.settles.append({"type": "salary", "pid": p["id"], "tile": 0, "amount": SALARY,
                                 "text": "经过起点，领取 %d" % SALARY, "icon": "flag"})

    def _resolve(self, p: dict, depth: int) -> None:
        """走到哪一格：把「要扣的钱 / 要做的选择」挂起来，不立刻改钱。"""
        tile = self.board[p["pos"]]
        kind = tile["type"]
        self.pending = None
        self.land = None

        if kind == "prop":
            owner = tile["owner"]
            if owner is None:
                self.event("land", pid=p["id"], tile=tile["i"], name=tile["name"])
                if p["money"] >= tile["price"]:
                    self.land = {"kind": "buy", "tile": tile["i"]}
                else:
                    self.say(f"{p['name']} 走到「{tile['name']}」，钱不够买（{tile['price']}）", "info", p["id"])
            elif owner == p["id"]:
                self.event("land", pid=p["id"], tile=tile["i"], name=tile["name"])
                self.land = {"kind": "own", "tile": tile["i"]}
            else:
                other = self.player(owner)
                rent = self.rent_of(tile)
                lv_name = LEVELS[int(tile.get("level", 0))]["name"]
                self.settles.append({
                    "type": "rent", "pid": p["id"], "tile": tile["i"], "amount": rent,
                    "to": owner, "name": tile["name"], "tileName": tile["name"], "icon": tile.get("icon"),
                    "text": "%s 的「%s」（%s），租金 %d" % (other["name"], tile["name"], lv_name, rent),
                })
                self.event("land", pid=p["id"], tile=tile["i"], name=tile["name"])
        elif kind == "chance":
            self.land = {"kind": "chance", "tile": tile["i"]}
        elif kind in ("tax", "hospital"):
            amount = tile["amount"]
            self.settles.append({
                "type": "tax", "pid": p["id"], "tile": tile["i"], "amount": -amount,
                "name": tile["name"], "icon": tile.get("icon"),
                "text": "在「%s」支出 %d" % (tile["name"], amount),
            })
        elif kind == "park":
            self.settles.append({
                "type": "park", "pid": p["id"], "tile": tile["i"], "amount": tile["amount"],
                "name": tile["name"], "icon": tile.get("icon"),
                "text": "停在停车场，拿奖金 %d" % tile["amount"],
            })
        elif kind == "go":
            self.settles.append({
                "type": "go", "pid": p["id"], "tile": tile["i"], "amount": 0,
                "name": tile["name"], "icon": tile.get("icon"),
                "text": "正好停在起点",
            })
        elif kind == "rest":
            self.settles.append({
                "type": "rest", "pid": p["id"], "tile": tile["i"], "amount": 0,
                "name": tile["name"], "icon": tile.get("icon"),
                "text": "停在休息站，什么也不发生",
            })

    def _settle_start(self, p: dict) -> None:
        """进入「待确认结算」：弹窗给所有人看，落点玩家点确定后才动钱。"""
        if self.settles:
            e = self.settles[0]
            self.phase = "settle"
            self.pending = None
            kind = e["type"]
            self.say("%s：%s，等确认" % (p["name"], e.get("text") or ""),
                     "pay" if kind in ("rent", "tax") else ("gain" if kind in ("salary", "park") else "info"),
                     p["id"])
            self.event("settle", pid=p["id"], kind=kind, tile=e.get("tile"),
                       amount=e.get("amount"), to=e.get("to"), text=e.get("text"),
                       name=e.get("name"), icon=e.get("icon"))
            return
        self._land_start(p)

    def settle_ok(self, pid: int) -> None:
        """落点玩家点「确定」：这时才真正扣钱 / 加钱，然后自动进入下一步或结束回合。"""
        with self.lock:
            if self.phase != "settle":
                raise ValueError("现在没有待确认的结算")
            if self.current != pid:
                raise ValueError("还没轮到你")
            p = self.player(pid)
            if not p:
                return
            if not self.settles:
                self._land_start(p)
                self.bump()
                return
            e = self.settles.pop(0)
            self.settle_lead = 0.0          # 后续结算窗不再补播上一段移动动画
            kind = e["type"]
            if kind == "salary":
                p["money"] += e["amount"]
                self.say(f"{p['name']} 经过起点，领取 {e['amount']}", "gain", p["id"])
                self.event("salary", pid=p["id"], amount=e["amount"])
            elif kind == "rent":
                amount = int(e["amount"])
                other = self.player(e.get("to"))
                p["money"] -= amount
                if other:
                    other["money"] += amount
                self.say(f"{p['name']} 踩到 {other['name'] if other else '?'} 的「{e.get('name') or ''}」，付租金 {amount}",
                         "pay", p["id"])
                self.event("rent", pid=p["id"], tile=e.get("tile"), name=e.get("name"), amount=-amount)
                self._bankrupt_check(p)
                if self.phase == "over":
                    self.bump()
                    return
            elif kind == "tax":
                amount = -int(e["amount"])
                p["money"] -= amount
                self.say(f"{p['name']} 在「{e.get('name') or ''}」支出 {amount}", "pay", p["id"])
                self.event("tax", pid=p["id"], tile=e.get("tile"), name=e.get("name"), amount=-amount)
                self._bankrupt_check(p)
                if self.phase == "over":
                    self.bump()
                    return
            elif kind == "park":
                p["money"] += int(e["amount"])
                self.say(f"{p['name']} 停在停车场，拿奖金 {e['amount']}", "gain", p["id"])
                self.event("park", pid=p["id"], tile=e.get("tile"), amount=e["amount"])
            elif kind == "go":
                self.say(f"{p['name']} 正好停在起点", "info", p["id"])
            elif kind == "rest":
                self.say(f"{p['name']} 停在休息站，什么也不发生", "info", p["id"])

            if not p["alive"]:
                self._advance_turn()
            elif self.settles:
                self._settle_start(p)
            else:
                self._land_start(p)
            self.bump()

    def _land_start(self, p: dict) -> None:
        """结算完之后，看落点要不要做选择（买地 / 升级 / 抽卡）；不需要就直接交棒。"""
        if self.phase == "over":
            return
        land = self.land
        self.land = None
        if not land:
            self._advance_turn()
            return
        tile = self.board[int(land["tile"])]
        kind = land.get("kind")
        if kind == "buy":
            self.pending = tile["i"]
            self.phase = "buy"
            self.say(f"{p['name']} 走到「{tile['name']}」，无主地，要不要买", "buy", p["id"])
            return
        if kind == "own":
            cost = upgrade_cost(tile)
            fresh = int(tile.get("bought_turn", -1)) == self.turn_no
            if cost is None:
                self.say(f"{p['name']} 回到自己的「{tile['name']}」，已经满级", "info", p["id"])
                self._advance_turn()
                return
            if fresh:
                self.say(f"{p['name']} 回到自己的「{tile['name']}」，新买的本回合不能升", "info", p["id"])
                self._advance_turn()
                return
            if p["money"] < cost:
                self.say(f"{p['name']} 回到自己的「{tile['name']}」，升级要 {cost}，钱不够", "info", p["id"])
                self._advance_turn()
                return
            self.pending = tile["i"]
            self.phase = "buy"
            self.say(f"{p['name']} 回到自己的「{tile['name']}」，可以花 {cost} 升级", "buy", p["id"])
            return
        if kind == "chance":
            card = random.choice(CHANCE_CARDS)
            self.chance = dict(card)
            self.chance["pid"] = p["id"]
            self.chance["name"] = p["name"]
            self.phase = "chance"
            self.say(f"{p['name']} 抽到「{card['text']}」，等待确认", "chance", p["id"])
            self.event("chance", pid=p["id"], text=card["text"], icon=card.get("icon", "spark"),
                       delta=card.get("delta"), move=card.get("move"), goto=card.get("goto"),
                       wait=True)
            return
        self._advance_turn()

    def chance_ok(self, pid: int) -> None:
        """玩家（或电脑）确认机会卡，卡上的动作这时才真正执行。"""
        with self.lock:
            if self.phase != "chance" or not self.chance:
                raise ValueError("现在没有待确认的机会卡")
            if self.chance.get("pid") != pid:
                raise ValueError("这张卡不是你抽到的")
            p = self.player(pid)
            card = dict(self.chance)
            self.chance = None
            self.settle_lead = 0.0          # 机会卡确认后直接进结算窗，不补播上一段掷骰移动
            self.settles = []
            self.land = None
            self.event("chance_go", pid=pid, text=card.get("text", ""), icon=card.get("icon", "spark"),
                       delta=card.get("delta"), move=card.get("move"), goto=card.get("goto"))
            self._apply_chance(p, card, depth=0)
            if self.phase == "over":
                self.bump()
                return
            if not p["alive"]:
                self._advance_turn()
            elif self.settles:
                self._settle_start(p)
            elif self.land:
                self._land_start(p)
            else:
                self._advance_turn()
            self.bump()

    def _apply_chance(self, p: dict, card: dict, depth: int) -> None:
        """执行机会卡（调用方持锁）。"""
        if card.get("goto") is not None:
            p["pos"] = card["goto"]
            self.settles.append({"type": "salary", "pid": p["id"], "tile": card["goto"], "amount": SALARY,
                                 "text": "回到起点，领取 %d" % SALARY, "icon": "flag"})
            if depth < 3:
                self._resolve(p, depth + 1)
            return
        if card.get("move") is not None:
            self._move(p, card["move"], pay_salary=True)
            if depth < 3:
                self._resolve(p, depth + 1)
            return
        if card.get("delta") is not None:
            delta = card["delta"]
            p["money"] += delta
            sign = "+" if delta > 0 else ""
            self.say(f"{p['name']} {('收入' if delta > 0 else '支出')} {sign}{delta}",
                     "gain" if delta > 0 else "pay", p["id"])
            self.event("cash", pid=p["id"], tile=p["pos"], amount=delta, text=card.get("text", ""))
            self._bankrupt_check(p)
            return

    def _bankrupt_check(self, p: dict) -> None:
        if p["money"] >= 0:
            return
        p["money"] = 0
        p["alive"] = False
        for t in self.board:
            if t["owner"] == p["id"]:
                t["owner"] = None
                t["level"] = 0
                t["bought_turn"] = -1
        self.trades = [t for t in self.trades if t["from"] != p["id"] and t["to"] != p["id"]]
        self.say(f"{p['name']} 资金链断裂，出局", "bankrupt", p["id"])
        self.event("bankrupt", pid=p["id"], text=f"{p['name']} 出局")
        alive = [x for x in self.players if x["alive"]]
        if len(alive) <= 1:
            self.phase = "over"
            self.winner = alive[0]["id"] if alive else None
            if alive:
                self.say(f"{alive[0]['name']} 成为最后的赢家", "over", alive[0]["id"])

    # ---------------------------------------------------------------- 买地 / 升级（都在落点阶段一次做完）
    def buy(self, pid: int) -> None:
        with self.lock:
            self._need(pid, "buy")
            p = self.player(pid)
            tile = self.board[self.pending]
            if tile.get("owner") is not None:
                raise ValueError("这块地已经有主人了")
            if p["money"] < tile["price"]:
                raise ValueError("钱不够")
            before_sets = self._group_set_ids(pid)
            p["money"] -= tile["price"]
            tile["owner"] = pid
            tile["bought_turn"] = self.turn_no
            self.pending = None
            self.say(f"{p['name']} 买下「{tile['name']}」，花 {tile['price']}", "buy", p["id"])
            self.event("buy", pid=pid, tile=tile["i"], name=tile["name"],
                       amount=-tile["price"], group=tile.get("group"))
            self._check_group_complete(p, tile.get("group"), before_sets)
            self._advance_turn()
            self.bump()

    def pass_buy(self, pid: int) -> None:
        with self.lock:
            self._need(pid, "buy")
            p = self.player(pid)
            tile_i = self.pending
            tile = self.board[tile_i]
            self.pending = None
            if tile.get("owner") == pid:
                self.say(f"{p['name']} 放弃升级「{tile['name']}」", "info", p["id"])
                self.event("pass", pid=pid, tile=tile_i, name=tile["name"], upgrade=True)
            else:
                self.say(f"{p['name']} 放弃购买「{tile['name']}」", "info", p["id"])
                self.event("pass", pid=pid, tile=tile_i, name=tile["name"])
            self._advance_turn()
            self.bump()

    def upgrade(self, pid: int, tile_i: int) -> None:
        with self.lock:
            self._need(pid, "buy")
            p = self.player(pid)
            if self.pending is None or int(self.pending) != int(tile_i):
                raise ValueError("现在只能处理落点这块地")
            if not (0 <= int(tile_i) < BOARD_SIZE):
                raise ValueError("没有这块地")
            tile = self.board[int(tile_i)]
            if tile.get("type") != "prop" or tile.get("owner") != pid:
                raise ValueError("只能升级自己的地块")
            if p["pos"] != tile["i"]:
                raise ValueError("只能升级自己正踩着的这块地（要走到「%s」上才行）" % tile["name"])
            if int(tile.get("bought_turn", -1)) == self.turn_no:
                raise ValueError("这块地是刚买下的，得下一次再走到它上面才能升")
            cost = upgrade_cost(tile)
            if cost is None:
                raise ValueError("这块地已经是高级了")
            if p["money"] < cost:
                raise ValueError("钱不够升级（要 %d）" % cost)
            p["money"] -= cost
            tile["level"] = int(tile.get("level", 0)) + 1
            lv_name = LEVELS[tile["level"]]["name"]
            self.pending = None
            self.say(f"{p['name']} 把「{tile['name']}」升到{lv_name}，花 {cost}，租金变成 {self.rent_of(tile)}",
                     "buy", pid)
            self.event("upgrade", pid=pid, tile=tile["i"], name=tile["name"],
                       amount=-cost, level=tile["level"], rent=self.rent_of(tile))
            self._advance_turn()
            self.bump()

    def _group_set_ids(self, pid: int) -> set:
        groups = {t.get("group") for t in self.board if t.get("type") == "prop" and t.get("group") is not None}
        return {g for g in groups if self.owns_set(pid, g)}

    def _check_group_complete(self, p: dict, group, before_sets: set) -> None:
        """买下 / 换到一块地之后，如果这一组刚好凑齐，就发一次「恭喜」事件（所有人可见）。"""
        if group is None or group in before_sets:
            return
        if not self.owns_set(p["id"], group):
            return
        name = GROUP_NAMES[group] if 0 <= int(group) < len(GROUP_NAMES) else ("第 %s 色组" % (int(group) + 1))
        self.say(f"{p['name']} 凑齐了「{name}」整组，这组租金翻倍！", "group", p["id"])
        self.event("group", pid=p["id"], group=group, name=name,
                   text=f"{p['name']} 凑齐「{name}」整组，租金翻倍")

    def end_turn(self, pid: int) -> None:
        with self.lock:
            self._need(pid, "buy")
            self._advance_turn()
            self.bump()

    def _advance_turn(self) -> None:
        alive = [x for x in self.players if x["alive"]]
        if len(alive) <= 1:
            self.phase = "over"
            self.winner = alive[0]["id"] if alive else None
            if alive:
                self.say(f"{alive[0]['name']} 成为最后的赢家", "over", alive[0]["id"])
            return
        idx = next(i for i, x in enumerate(self.players) if x["id"] == self.current)
        for step in range(1, len(self.players) + 1):
            nxt = self.players[(idx + step) % len(self.players)]
            if nxt["alive"]:
                self.current = nxt["id"]
                break
        self.phase = "roll"
        self.pending = None
        self.chance = None
        self.settles = []
        self.land = None
        self.last_steps = 0
        self.settle_lead = 0.0
        self.turn_no += 1
        for x in self.players:
            x.pop("ai_plan", None)          # 上一回合的计划作废，别被下一次存档带进新回合
            x["ai_plan_turn"] = None
        self.say("轮到 " + self.player(self.current)["name"], "turn", self.current)

    def _need(self, pid: int, phase: str) -> None:
        if self.phase != phase:
            raise ValueError("现在不能做这个操作")
        if self.current != pid:
            raise ValueError("还没轮到你")

    # ---------------------------------------------------------------- 人机决策
    def _bot_cfg(self, p: dict) -> dict:
        return BOT_LEVELS.get(p.get("bot_level", "normal"), BOT_LEVELS["normal"])

    def _schedule_bot(self) -> None:
        """轮到电脑（或电脑有交易要表态）时排一次延迟动作；任何状态变化都会重排。"""
        if self.bot_timer is not None:
            self.bot_timer.cancel()
            self.bot_timer = None
        if self.phase in ("lobby", "setup", "over"):
            return
        # 交易不跟着回合走，先处理
        for tr in self.trades:
            if tr.get("asked"):
                continue
            target = self.player(tr["to"])
            if target and target.get("kind") == "bot" and target["alive"]:
                tr["asked"] = True
                # 交易用自己的 timer：回合推进时 _schedule_bot 会 cancel 掉 bot_timer，
                # 用同一个的话「AI 提完交换就结束回合」会把刚排上的应答直接取消掉。
                self._arm_trade(tr["id"])
                return
        cur = self.player(self.current) if self.current is not None else None
        if not cur or cur.get("kind") != "bot" or not cur["alive"]:
            return
        if cur.get("thinking"):
            return                     # AI 还在算，等它自己推进，别排重复动作
        if self.phase not in ("roll", "settle", "buy", "chance"):
            return
        delay = BOT_DELAY.get(self.phase, 1.0)
        if self.phase == "settle":
            # 等前端把骰子 + 走格动画播完、结算窗真正出现，再停留 2 秒
            delay = BOT_DELAY["settle"] + SETTLE_SHOW_BUFFER + max(0.0, self.settle_lead)
        # 掷完骰子的那一帧要先把「骰子 + 逐格移动」播完，再让它做买地 / 升级的决定
        elif self.phase == "buy" and self.last_steps:
            delay = max(delay, BOT_MOVE_LEAD + BOT_STEP_ANIM * min(self.last_steps, 12))
        if self.phase == "buy" and cur.get("brain") == "ai" and ai_brain.ok:
            delay += 0.4               # AI 要多等一小会儿，前端才来得及显示「正在思考」
        self._arm(self._bot_step, delay, self.version, self.current, self.phase)

    def _arm(self, fn, delay: float, *args) -> None:
        t = threading.Timer(delay, fn, args=args)
        t.daemon = True
        self.bot_timer = t
        t.start()

    def _arm_trade(self, trade_id: int) -> None:
        """电脑回应交换申请：独立 timer，不受回合推进时对 bot_timer 的 cancel 影响。"""
        t = threading.Timer(1.0, self._bot_trade_step, args=(self.version, trade_id))
        t.daemon = True
        self.trade_timer = t
        t.start()

    def _bot_step(self, version: int, pid: int, phase: str) -> None:
        use_ai = False
        turn_no = 0
        with self.lock:
            if version != self.version or self.current != pid or self.phase != phase:
                return
            p = self.player(pid)
            if not p or p.get("kind") != "bot" or not p["alive"]:
                return
            is_ai = p.get("brain") == "ai" and ai_brain.ok
            if phase == "buy" and is_ai:
                # 落地之后才问模型：这是这一回合唯一一次接口调用
                use_ai = True
                turn_no = self.turn_no
            elif phase == "roll":
                # AI 的换地计划是上一回合落地后写好的，在自己掷骰前发出去
                if is_ai and p.get("ai_trade_next"):
                    intent = p.pop("ai_trade_next", None)
                    try:
                        self._ai_trade(p, intent, end_turn=False)
                    except ValueError as exc:
                        print("[ai] 换地计划没发出去：%s" % exc)
                        p["ai_trade_next"] = None
                self._local_step(pid, "roll")
            else:
                self._local_step(pid, phase)
        if use_ai:
            # AI 要发 HTTP，必须在锁外做，否则整局（含 SSE）都会被卡住
            self._run_ai_turn(pid, turn_no)

    def _local_step(self, pid: int, phase: str) -> None:
        """人机（本地启发式）走一步；调用方持锁。"""
        p = self.player(pid)
        if not p:
            return
        why: list[str] = []
        try:
            if phase == "roll":
                self.roll(pid)
            elif phase == "settle":
                self.settle_ok(pid)          # 电脑也走「确认」这条路，只是自动点
            elif phase == "chance":
                self.chance_ok(pid)
            elif phase == "buy":
                tile_i = self.pending
                if tile_i is None:
                    raise ValueError("现在没有待处理的落点")
                tile = self.board[tile_i]
                if tile.get("owner") == pid:
                    ok = self.bot_try_upgrade(p, why)
                    self._bot_say(p, why)
                    if not ok:
                        self.pass_buy(pid)
                else:
                    want = self.bot_wants_buy(p, tile, why)
                    self._bot_say(p, why)          # 先说话再动手，保证同一帧里「说的话」在「做的事」之前
                    if want:
                        self.buy(pid)
                    else:
                        self.pass_buy(pid)
        except ValueError as exc:
            # 决策撞上规则（钱不够之类）：保守收尾，别把局势卡死
            print("人机 %s 动作失败：%s" % (p["name"], exc))
            try:
                if self.phase == "buy":
                    self.pass_buy(pid)
                elif self.phase == "settle":
                    self.settle_ok(pid)
            except ValueError:
                pass

    def _bot_say(self, p: dict, why: list[str]) -> None:
        """电脑的一句话决策说明（人机的算法原因，AI 的 why 由 _run_ai_step 负责）。"""
        text = (why[0] if why else "").strip()
        if not text:
            return
        self.say("%s：%s" % (p["name"], text[:60]), "ai", p["id"])

    # ---------------------------------------------------------- AI 回合
    def _ai_alive(self, pid: int, turn_no: int) -> bool:
        """AI 思考期间局面可能被房主暂停 / 重开，回来时先确认这一回合还算数。"""
        return (self.phase == "buy" and self.current == pid and self.pending is not None
                and self.turn_no == turn_no and self.frozen is None)

    def _ai_turn_context(self, p: dict) -> str:
        """落地之后喂给模型的最小局面：现在只需要决定「买不买 / 升不升」，换地写进下回合。"""
        me = p
        lines = []
        alive = [x for x in self.players if x["alive"]]
        lines.append("【第 %d 回合 · 已落地】场上还有 %d 人，你刚走到落点，程序等你决定这一回合收尾。" %
                     (self.turn_no, len(alive)))
        lines.append("【你】%s，现金 %d，地契 %d 块。" % (me["name"], me["money"], len(self.props_of(me["id"]))))
        lines.append("【掷骰】%d + %d，前进 %d 格，落在「%s」。" % (
            self.dice[0], self.dice[1], sum(self.dice),
            self.board[me["pos"]]["name"]))

        tile = self.board[self.pending] if self.pending is not None else self.board[me["pos"]]
        if tile.get("owner") is None:
            lines.append("【落点】无主地产「%s」，买价 %d，原租金 %d，色组%s；买完你还剩 %d 金币。" % (
                tile["name"], tile["price"], tile["rent"],
                GROUP_NAMES[tile["group"]] if tile.get("group") is not None else "?",
                me["money"] - tile["price"]))
            lines.append("【这次的决策】buy.want 决定买不买，maxPrice 是你愿出的上限；land 这次用不上（随便填合法值即可）。")
        else:
            cost = upgrade_cost(tile)
            fresh = int(tile.get("bought_turn", -1)) == self.turn_no
            lv = LEVELS[int(tile.get("level", 0))]["name"]
            lines.append("【落点】你自己的「%s」，现在%s，租金 %d；升级费 %s，升完租金 %d%s。" % (
                tile["name"], lv, self.rent_of(tile),
                ("—（已满级）" if cost is None else str(cost)),
                self.rent_of_next(tile) if cost is not None else self.rent_of(tile),
                "；本回合刚买，不能升" if fresh else ""))
            lines.append("【这次的决策】land.upgrade 决定升不升，maxCost 是你愿付的上限；buy 这次用不上（随便填合法值即可）。")

        mine = [self.board[i] for i in self.props_of(me["id"])]
        if mine:
            bits = []
            for t in mine:
                bits.append("%s(色组%s %s 租%d)" % (
                    t["name"], GROUP_NAMES[t.get("group")] if t.get("group") is not None else "?",
                    LEVELS[int(t.get("level", 0))]["name"], self.rent_of(t)))
            lines.append("【你的地契】" + "；".join(bits))

        rivals = [x for x in alive if x["id"] != me["id"]]
        if rivals:
            rb = []
            for x in rivals:
                props = [self.board[i] for i in self.props_of(x["id"])]
                top = max([self.rent_of(t) for t in props], default=0)
                rb.append("%s 现金%d 地契%d 最贵租%d" % (x["name"], x["money"], len(props), top))
            lines.append("【对手】" + "；".join(rb))
        lines.append("【场上最贵租金】%d（踩上去要付这么多）" % self._max_rent())

        # 换地材料：下一回合开局才发，所以现在就把目标挑好
        others_line = []
        for x in rivals:
            props = [self.board[i] for i in self.props_of(x["id"])]
            if props:
                others_line.append("%s 有 %s" % (
                    x["name"],
                    "、".join("%s(色组%s 租%d)" % (t["name"], GROUP_NAMES[t.get("group")],
                                                  self.rent_of(t)) for t in props)))
        my_names = "、".join("%s(色组%s)" % (t["name"], GROUP_NAMES[t.get("group")]) for t in mine) if mine else "还没有"
        lines.append("【换地·下回合开局才发】你的地：%s" % my_names)
        if others_line:
            lines.append("【别人的地】" + "；".join(others_line))
        if me.get("ai_trades"):
            lines.append("【已主动换地】%d 笔（一局最多 %d 笔）" % (int(me["ai_trades"]), AI_TRADE_MAX))

        if me.get("memory"):
            lines.append("【你的笔记与计划】" + str(me["memory"]))
        else:
            lines.append("【你的笔记与计划】（还没有，这一回合自己写一条）")

        events = self._ai_recent(p)
        if events:
            lines.append("【最近流水】" + " / ".join(events))
        lines.append("【要你做的事】只决定落点这一件事：把 buy / land 填好（用不上的那个也填个合法值）。"
                     "trade 可选，写了会在你下回合开局、掷骰之前发出去，不会在现在执行。")
        return "\n".join(lines)

    def _ai_recent(self, p: dict) -> list[str]:
        """挑与该玩家有关的最近 10 条日志，给它「记仇」的事实依据。"""
        names = {x["id"]: x["name"] for x in self.players}
        out = []
        for row in reversed(self.log):
            if row["id"] > 0 and (row.get("pid") == p["id"] or p["name"] in row["text"]
                                  or any(n in row["text"] for n in names.values())):
                out.append(row["text"])
            if len(out) >= 10:
                break
        return list(reversed(out))

    def money_after_buy(self, p: dict, tile: dict) -> int:
        return int(p["money"]) - int(tile.get("price") or 0)

    @staticmethod
    def _plan_int(value, label):
        """计划里的数字字段：允许数字或数字字符串，空值表示「不设上限」。"""
        if value is None or value == "":
            return None
        if isinstance(value, bool):
            raise ValueError("%s 要填数字，不要填 true/false" % label)
        try:
            n = int(float(str(value).strip()))
        except (TypeError, ValueError):
            raise ValueError("%s 要填数字（%r 看不懂）" % (label, value))
        if n < 0:
            raise ValueError("%s 不能是负数" % label)
        return n

    def _ai_plan_norm(self, plan: dict) -> dict:
        """校验并规整整回合计划；结构不对就抛 ValueError（外层会重试，三次不行交回人机）。"""
        if not isinstance(plan, dict):
            raise ValueError("要回一个 JSON 对象")
        buy = plan.get("buy")
        if isinstance(buy, str):
            buy = {"want": buy.strip().lower() in ("buy", "true", "1", "买", "买下")}
        if not isinstance(buy, dict):
            raise ValueError('缺少 buy，应为 {"want":true/false,"maxPrice":数字}')
        want = buy.get("want")
        if not isinstance(want, bool):
            raise ValueError("buy.want 必须是 true 或 false")
        max_price = self._plan_int(buy.get("maxPrice"), "buy.maxPrice")

        land = plan.get("land")
        if isinstance(land, str):
            land = {"upgrade": land.strip().lower() in ("upgrade", "true", "1", "升级")}
        if not isinstance(land, dict):
            raise ValueError('缺少 land，应为 {"upgrade":true/false,"maxCost":数字}')
        up = land.get("upgrade")
        if not isinstance(up, bool):
            raise ValueError("land.upgrade 必须是 true 或 false")
        max_cost = self._plan_int(land.get("maxCost"), "land.maxCost")

        trade = plan.get("trade")
        if trade in (None, "", {}, "none", "null"):
            trade = None
        else:
            if not isinstance(trade, dict):
                raise ValueError("trade 要么省略，要么写成对象")
            target = str(trade.get("target") or "").strip()
            if not target:
                raise ValueError("trade.target 要写对方名字")
            want_tile = str(trade.get("want") or "").strip()
            give_tile = str(trade.get("give") or "").strip()
            if not want_tile and not give_tile:
                raise ValueError("trade 至少要有 want（你想要对方哪块地）")
            cash = self._plan_int(trade.get("giveCash"), "trade.giveCash") or 0
            trade = {"target": target, "want": want_tile or None,
                     "give": give_tile or None, "giveCash": cash}
        return {"buy": {"want": want, "maxPrice": max_price},
                "land": {"upgrade": up, "maxCost": max_cost},
                "trade": trade}

    def _ai_plan_valid(self, p: dict) -> bool:
        return bool(p.get("ai_plan")) and p.get("ai_plan_turn") == self.turn_no

    def _ai_plan_step(self, pid: int, phase: str = "buy") -> None:
        """按「落地后」拿到的计划做这一回合剩下的决定；调用方持锁。计划走不通就保守收尾。"""
        p = self.player(pid)
        if not p:
            return
        if not self._ai_plan_valid(p):
            self._local_step(pid, phase)
            return
        plan = p["ai_plan"]
        try:
            if phase != "buy" or self.pending is None:
                raise ValueError("现在没有要做的落点决策")
            tile = self.board[self.pending]
            if tile.get("owner") is None:
                b = plan.get("buy") or {}
                cap = b.get("maxPrice")
                ok = (bool(b.get("want")) and int(p["money"]) >= int(tile["price"])
                      and (cap is None or int(tile["price"]) <= int(cap)))
                if ok:
                    self.buy(pid)
                else:
                    self.pass_buy(pid)
                return
            if tile.get("owner") == pid:
                l = plan.get("land") or {}
                cost = upgrade_cost(tile)
                fresh = int(tile.get("bought_turn", -1)) == self.turn_no
                cap = l.get("maxCost")
                if (l.get("upgrade") and cost is not None and not fresh
                        and int(p["money"]) >= int(cost) and (cap is None or int(cost) <= int(cap))):
                    self.upgrade(pid, tile["i"])
                else:
                    self.pass_buy(pid)
                return
            raise ValueError("落点不是无主地也不是自己的地")
        except ValueError as exc:
            print("[ai] 落点决策在当前局面走不通：%s" % exc)
            try:
                if self.phase == "buy":
                    self.pass_buy(pid)
            except ValueError:
                pass

    def _tile_by_name(self, name) -> dict | None:
        key = str(name or "").strip()
        if not key:
            return None
        for t in self.board:
            if t.get("type") == "prop" and t.get("name") == key:
                return t
        return None

    def _ai_trade(self, p: dict, intent: dict, end_turn: bool = False) -> None:
        """AI 主动发起换地：名字 → 校验 → trade_offer。默认不结束回合（在自己掷骰前执行）。"""
        if int(p.get("ai_trades") or 0) >= AI_TRADE_MAX:
            raise ValueError("你这局已经主动谈过 %d 笔换地了" % AI_TRADE_MAX)
        others = [x for x in self.players if x["alive"] and x["id"] != p["id"]]
        target_name = str(intent.get("target") or "").strip()
        other = next((x for x in others if x["name"] == target_name), None)
        if other is None:
            raise ValueError("target 必须是场上其他玩家的名字，现在只有：%s"
                             % "、".join(x["name"] for x in others))

        want = self._tile_by_name(intent.get("want"))
        give = self._tile_by_name(intent.get("give"))
        if want is None and give is None:
            raise ValueError("trade 至少要有 want（你想要对方哪块地）")
        if want is not None and want.get("owner") != other["id"]:
            raise ValueError("「%s」不是 %s 的地" % (want["name"], other["name"]))
        if give is not None and give.get("owner") != p["id"]:
            raise ValueError("「%s」不是你的地" % give["name"])
        try:
            cash = max(0, int(intent.get("giveCash") or 0))
        except (TypeError, ValueError):
            cash = 0
        if cash > p["money"]:
            raise ValueError("你只有 %d 金币，给不出 %d" % (p["money"], cash))
        self.trade_offer(p["id"], {
            "target": other["id"],
            "give": [give["i"]] if give else [],
            "want": [want["i"]] if want else [],
            "giveCash": cash,
            "wantCash": 0,
        })
        p["ai_trades"] = int(p.get("ai_trades") or 0) + 1
        if end_turn:
            self._advance_turn()

    def _run_ai_turn(self, pid: int, turn_no: int) -> None:
        """落地之后一次接口：拿到这一回合剩余打算 → 存起来 → 立刻执行买 / 升，然后自动交棒。"""
        raw = ""
        reason = ""
        with self.lock:
            if not self._ai_alive(pid, turn_no):
                return
            p = self.player(pid)
            if not p:
                return
            p["thinking"] = True
            self.bump()                 # 推一帧，前端立刻显示「AI 正在思考…」

        try:
            for attempt in range(1, AI_RETRY + 1):
                with self.lock:
                    if not self._ai_alive(pid, turn_no):
                        return
                    cur = self.player(pid)
                    if not cur:
                        return
                    ctx = self._ai_turn_context(cur)
                    level = cur.get("bot_level", "normal")
                feedback = ""
                if reason:
                    feedback = ("（提醒）你上一次回的是 %s，被系统打回：%s。请重新给一份合法的落地计划。"
                                % (raw[:120], reason))
                plan, err, kind = ai_brain.decide_plan(ctx, level, feedback)
                if plan is None:
                    raw = raw or err
                    reason = err
                    if kind == "net":       # 网络不通超时，别纠缠，直接交回人机
                        break
                    continue
                raw = json.dumps(plan, ensure_ascii=False)[:300]
                with self.lock:
                    if not self._ai_alive(pid, turn_no):
                        return
                    cur = self.player(pid)
                    if not cur:
                        return
                    try:
                        norm = self._ai_plan_norm(plan)
                    except ValueError as exc:
                        reason = str(exc)
                        print("[ai] %s 第 %d 次计划被打回：%s" % (cur["name"], attempt, reason))
                        continue
                    cur["thinking"] = False
                    cur["ai_plan"] = norm
                    cur["ai_plan_turn"] = turn_no
                    # 换地声明留到下一回合开局：现在先存起来，掷骰前由 _bot_step 代发
                    cur["ai_trade_next"] = norm.get("trade") or None
                    mem = ai_brain.clamp_memory(str(plan.get("memory") or ""))
                    if mem:
                        cur["memory"] = mem
                    if plan.get("why"):
                        # 先写日志再动手：这样「说的话」和动作在同一帧里，前端不会错位
                        self.say("%s：%s" % (cur["name"], str(plan["why"])[:60]), "ai", cur["id"])
                    self._ai_plan_step(pid, "buy")   # 买 / 升 / 放弃，执行完这一回合自动结束
                    return
        finally:
            self._clear_thinking(pid)

        # 三次都不合法（或接口挂了）→ 交回人机，并把这次对话记到日志文件里
        with self.lock:
            if not self._ai_alive(pid, turn_no):
                return
            cur = self.player(pid)
            if not cur:
                return
            cur["thinking"] = False
            self.say("AI「%s」这回没给出可用的计划，已交回人机接手（%s）"
                     % (cur["name"], (reason or "接口不可用")[:80]), "ai", cur["id"])
            self._log_ai_fail(cur, "land", raw, reason)
            self._local_step(pid, "buy")

    def _clear_thinking(self, pid: int) -> None:
        with self.lock:
            p = self.player(pid)
            if p and p.get("thinking"):
                p["thinking"] = False
                self.bump()

    def _log_ai_fail(self, p: dict, phase: str, raw: str, reason: str) -> None:
        """失败样本落盘，方便以后拿真实案例调提示词。"""
        item = {
            "time": datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
            "game": self.game_id, "pid": p["id"], "name": p["name"],
            "level": p.get("bot_level"), "model": ai_brain.model,
            "phase": phase, "returned": raw, "reason": reason,
        }
        try:
            with AI_FAIL_LOG.open("a", encoding="utf-8") as fh:
                fh.write(json.dumps(item, ensure_ascii=False) + "\n")
        except OSError as exc:
            print("AI 失败样本没写进文件：%s" % exc)
        print("[ai] 失败样本：%s" % json.dumps(item, ensure_ascii=False)[:200])

    def _bot_trade_step(self, version: int, trade_id: int) -> None:
        with self.lock:
            # 不看 version：发起方可能在 trade_offer 之后立刻还有动作（AI 提完交换就结束回合），
            # 那一次 bump 会改掉 version，但「这笔交换还在等电脑回应」这件事本身没变。
            trade = next((t for t in self.trades if t["id"] == trade_id), None)
            if not trade:
                return
            p = self.player(trade["to"])
            if not p or p.get("kind") != "bot":
                return
            try:
                ok, why = self.bot_trade_eval(p, trade)
                self.trade_answer(p["id"], trade_id, ok, why)
            except ValueError:
                pass

    # -------- 估值辅助：电脑靠这几把尺子算「这块地值不值」 --------
    def _group_mates(self, pid: int, group) -> list[dict]:
        return [t for t in self.board
                if t.get("type") == "prop" and t.get("group") == group and t.get("owner") == pid]

    def _group_total(self, group) -> int:
        return len([t for t in self.board if t.get("type") == "prop" and t.get("group") == group])

    def owns_set(self, pid: int, group) -> bool:
        """整组（同色系所有地块）是不是都在一个人手里。"""
        tiles = [t for t in self.board
                 if t.get("type") == "prop" and t.get("group") == group]
        return bool(tiles) and all(t.get("owner") == pid for t in tiles)

    def _rent_with_level(self, tile: dict, level: int | None = None) -> int:
        """租金 = 原租金 × 等级倍率，整组集齐再翻一倍。"""
        lv = int(tile.get("level", 0)) if level is None else int(level)
        lv = max(0, min(lv, len(LEVELS) - 1))
        value = int(round(tile["rent"] * LEVELS[lv]["rent"]))
        owner = tile.get("owner")
        if owner is not None and self.owns_set(owner, tile.get("group")):
            value *= 2
        return value

    def rent_of(self, tile: dict) -> int:
        return self._rent_with_level(tile)

    def rent_of_next(self, tile: dict) -> int:
        lv = min(int(tile.get("level", 0)) + 1, len(LEVELS) - 1)
        return self._rent_with_level(tile, lv)

    def _max_rent(self) -> int:
        rents = [self.rent_of(t) for t in self.board if t.get("type") == "prop" and t.get("owner")]
        return max(rents) if rents else 40

    def _bot_pace(self, p: dict) -> float:
        """资金姿态：领先就攥钱、落后就抢地，让三档难度之外还有「局势感」。"""
        others = [x["money"] for x in self.players if x["alive"] and x["id"] != p["id"]]
        if not others:
            return 1.0
        avg = sum(others) / float(len(others))
        if avg <= 0:
            return 1.0
        ratio = p["money"] / avg
        if ratio >= 1.7:
            return 0.6                      # 大幅领先：留着钱等收租
        if ratio >= 1.2:
            return 0.85
        if ratio <= 0.65:
            return 1.3                      # 明显落后：宁可多占一块地
        if ratio <= 0.9:
            return 1.1
        return 1.0

    def bot_tile_score(self, p: dict, tile: dict) -> int:
        """这块地对 p 值多少：现在能收的租金 + 凑色组的潜力，不划算的组打折。"""
        if tile.get("type") != "prop":
            return 0
        score = self.rent_of(tile) * 3
        group = tile.get("group")
        mates = len(self._group_mates(p["id"], group))
        total = self._group_total(group)
        if total and mates >= total - 1:                       # 买下就垄断整组
            score += tile["price"] * 1.2
        elif mates:
            score += tile["price"] * (0.25 + 0.2 * mates)
        else:
            score = max(score, tile["price"] * 0.8)
        rivals = len([t for t in self.board
                      if t.get("type") == "prop" and t.get("group") == group
                      and t.get("owner") not in (None, p["id"])])
        if not mates and rivals >= 2:                          # 这组已经被别人占住，含金量打折
            score = int(score * 0.7)
        return int(score)

    def bot_wants_buy(self, p: dict, tile: dict, why: list | None = None) -> bool:
        cfg = self._bot_cfg(p)
        level = p.get("bot_level", "normal")
        price = tile["price"]

        def out(ok: bool, text: str) -> bool:
            if why is not None:
                why.append(text)
            return ok

        if p["money"] < price:
            return out(False, "钱不够，这块 %d 买不起" % price)

        group = tile.get("group")
        mates = len(self._group_mates(p["id"], group))
        total = self._group_total(group)

        # 留钱：凑色组越齐越敢花；能补齐整组就把钱压上；落后时放宽、领先时收紧
        keep = int(cfg["keep"] * self._bot_pace(p))
        if mates:
            keep = int(keep * (1 - cfg["group"] * 0.75))
        if total and mates >= total - 1:
            keep = 0
        reserve = keep + int(self._max_rent() * cfg["risk"])
        cash_left = p["money"] - price

        if level == "steady" and price > p["money"] * 0.55:
            return out(False, "这价几乎是我半副身家，不想全砸在一块地上")   # 稳健不把半副身家砸在一块地
        if cash_left < 0:
            return out(False, "买完现金就是负的，不能买")
        if cash_left < reserve:
            # 手头紧：只有「这一买能补齐整组」或者明显捡漏才破例
            if total and mates >= total - 1:
                return out(True, "买下就凑齐这组了，咬牙也要拿")
            if self.bot_tile_score(p, tile) >= price * 2:
                return out(True, "这块明显捡漏，破例拿一块")
            return out(False, "买完就扛不住下一脚租金，先等等")
        if total and mates >= total - 1:
            return out(True, "买下就整组在手，租金能翻着涨")
        if mates:
            return out(True, "这组我已经有 %d 块，再补一块" % mates)
        return out(True, "价格还行，先占一块")

    def bot_try_upgrade(self, p: dict, why: list | None = None) -> bool:
        """新规则下只能升自己脚下这块地，所以机会难得：按「几次收租回本」算值不值。"""
        cfg = self._bot_cfg(p)
        pos = p["pos"]
        tile = self.board[pos] if 0 <= pos < len(self.board) else None

        def out(ok: bool, text: str) -> bool:
            if why is not None:
                why.append(text)
            return ok

        if not tile or tile.get("type") != "prop" or tile.get("owner") != p["id"]:
            return out(False, "脚下不是我的地，这轮没得升")
        if int(tile.get("bought_turn", -1)) == self.turn_no:
            return out(False, "「%s」是新买的，下次走到才能升" % tile["name"])   # 这回合刚买，规则上也不允许
        cost = upgrade_cost(tile)
        if cost is None:
            return out(False, "「%s」已经满级了" % tile["name"])
        if p["money"] < cost:
            return out(False, "升「%s」要 %d，钱不够" % (tile["name"], cost))
        surplus = p["money"] - cost
        floor_cash = cfg["keep"] + int(self._max_rent() * cfg["risk"] * 0.35)
        if surplus < floor_cash:
            return out(False, "升完就扛不住租金，先攥着钱")   # 升完剩下的钱扛不住一脚租金，先攥着
        gain = self.rent_of_next(tile) - self.rent_of(tile)
        if gain <= 0:
            return out(False, "升了租金也不涨，算了")
        mates = len(self._group_mates(p["id"], tile.get("group")))
        total = self._group_total(tile.get("group"))
        monopoly = bool(total and mates >= total)               # 整组在手，升级收益最稳
        payback = cost / float(gain)                            # 多收几次租才把升级费赚回来
        limit = cfg["payback"] * (1.15 if monopoly else 1.0)
        if payback > limit:
            return out(False, "升级费要收 %d 次租才回本，不划算" % int(round(payback)))
        if not monopoly and random.random() > cfg["upgrade"]:
            return out(False, "手头还不宽裕，这轮先不升")
        before, after = self.rent_of(tile), self.rent_of_next(tile)
        try:
            self.upgrade(p["id"], tile["i"])
            return out(True, "升「%s」只要收 %d 次租就回本，租金 %d → %d"
                       % (tile["name"], int(round(payback)), before, after))
        except ValueError:
            return out(False, "想升「%s」但规则不让" % tile["name"])

    def _trade_safety(self, p: dict) -> tuple[int, float]:
        """(现金安全垫, 缺钱程度 0..1)：缺钱越紧，越不肯把现金换成地、越想要现金。"""
        cfg = self._bot_cfg(p)
        reserve = int(cfg["keep"] * self._bot_pace(p)) + int(self._max_rent() * cfg["risk"])
        need_money = max(1, reserve * 2)
        tight = 1.0 - min(1.0, p["money"] / float(need_money))
        return reserve, max(0.0, tight)

    def _trade_tile_value(self, p: dict, tile: dict, role: str, progress: float,
                          notes: list, other_id: int | None = None,
                          costs: list | None = None) -> int:
        """一块地在这次交换里的价值（role=in 我拿到 / out 我让出），按色组与回合进度修正。"""
        if not tile or tile.get("type") != "prop":
            return 0
        base = self.bot_tile_score(p, tile)
        group = tile.get("group")
        total = self._group_total(group)
        mates = len(self._group_mates(p["id"], group))
        if role == "in":
            if total and mates >= total - 1:
                # 换回来就能整组垄断：不只是这块自己租金翻倍，同组我手上的地也一起翻倍
                base = int(base * (1.15 + 0.35 * progress))
                mates_rent = sum(self.rent_of(t) for t in self._group_mates(p["id"], group))
                base += int((mates_rent + self.rent_of(tile)) * (1.0 + 0.8 * progress))
                notes.append("换回来就凑齐这组、整组租金翻倍")
            elif mates == 0:
                rivals = len([t for t in self.board if t.get("type") == "prop"
                              and t.get("group") == group and t.get("owner") not in (None, p["id"])])
                if rivals >= max(2, total - 1):
                    # 这组已经被对手占死，我拿到手里也收不到多少租
                    base = int(base * 0.45)
                    notes.append("这组已被对手占住、拿在手里不值钱")
            else:
                base = int(base * (1.0 + 0.1 * mates))
            return max(0, base)
        # role == "out"：让出去的那块，代价还要看会不会喂饱对手
        their_mates = 0
        if other_id is not None:
            their_mates = len(self._group_mates(other_id, group))
        if total and their_mates >= total - 1:
            # 一让出去对手就整组垄断、租金翻倍：越到后期越不能送（宁可要更高的补偿）
            base = int(base * (2.6 - 0.3 * progress))
            (costs if costs is not None else notes).append("这块让出去对方就凑齐整组，代价太大")
        elif their_mates == 0:
            base = int(base * 0.75)          # 对方拿着也组不起来，让出的实际损失小一些
        return max(0, base)

    def bot_trade_eval(self, p: dict, trade: dict) -> tuple[bool, str]:
        """评估一笔交换，返回 (是否接受, 一句理由)。

        除「地值 + 现金差」外，还按三件事修正：
        - 手头紧不紧（缺钱时现金更值钱、把现金换出去更肉疼）；
        - 回合进度（前期抢地、后期租金与现金更硬，送对手整组的代价也更大）；
        - 色组归属（能凑齐我这一组就加价，已被对手占死的组打折，喂对手整组重罚）。
        """
        cfg = self._bot_cfg(p)
        want_cash = int(trade.get("wantCash") or 0)
        if want_cash > p["money"]:
            return False, "我拿不出 %d 现金" % want_cash

        progress = min(1.0, self.turn_no / 40.0)     # 第 40 回合后按「后期」算满
        reserve, tight = self._trade_safety(p)
        notes: list[str] = []          # 这笔交换的好处
        costs: list[str] = []          # 这笔交换的代价
        gain = 0
        volume = 1

        in_ids = self._tile_ids(trade.get("give"))          # 我拿到的
        out_ids = self._tile_ids(trade.get("want"))         # 我给出的
        raw_in = sum(self.bot_tile_score(p, self.board[i]) for i in in_ids)
        raw_out = sum(self.bot_tile_score(p, self.board[i]) for i in out_ids)
        value_in = sum(self._trade_tile_value(p, self.board[i], "in", progress, notes) for i in in_ids)
        value_out = sum(self._trade_tile_value(p, self.board[i], "out", progress, costs,
                                               other_id=int(trade.get("from") or 0)) for i in out_ids)
        gain += value_in - value_out
        # 门槛按「两边里较便宜那组」的规模算：打包换，不能用两边地价相加的体量去要求
        if raw_in and raw_out:
            volume += min(raw_in, raw_out)
        else:
            volume += max(raw_in, raw_out)

        cash_in = int(trade.get("giveCash") or 0)
        # 手头紧时进来的现金更值钱；要掏出去的现金更肉疼（还要留够安全垫）
        gain += int(cash_in * (1.0 + 0.6 * tight))
        gain -= int(want_cash * (1.0 + 0.9 * tight))
        if cash_in and tight > 0.4:
            notes.append("手头有点紧，进账的现金也算价值")

        # 掏完现金后还够不够扛下一次大租金：不够就只认「凑齐整组」这种大便宜
        if want_cash and p["money"] - want_cash < reserve:
            if value_in < value_out:
                return False, "换完现金只剩 %d，扛不住下一次大租金" % (p["money"] - want_cash)
            if not notes or "凑齐" not in " ".join(notes):
                return False, "要动的现金太多，换完手里太空"

        need = int(cfg["trade"] * volume)
        need = max(need, TRADE_MIN_MARGIN)
        # 越到后期、手头越紧，门槛越硬：不是明显占便宜的一律不点头
        need = int(need * (1.0 + 0.25 * progress + 0.2 * tight))

        if gain > need and gain > 0:
            poison = [c for c in costs if "凑齐整组" in c]
            if poison and cash_in < value_out * 1.2:
                # 能赚一点也不送对手垄断：除非现金补偿高到能抵掉这组翻倍的租金
                return False, "有点赚，但%s，现金补得不够，不换" % poison[0]
            why = notes[0] if notes else "这块地合我的算盘"
            return True, "值得换：%s（净赚约 %d）" % (why, gain - need)
        if gain > 0:
            if costs:
                return False, "有点便宜但%s，不划算" % costs[0]
            return False, "看着有点便宜但不够明显（+%d，门槛 %d）" % (gain, need)
        if costs:
            return False, "这笔我吃亏（%+d）：%s" % (gain, costs[0])
        return False, "这笔我吃亏（%+d）" % gain

    def bot_wants_trade(self, p: dict, trade: dict) -> bool:
        """兼容旧调用：只取结论。"""
        return self.bot_trade_eval(p, trade)[0]

    # ---------------------------------------------------------------- 交换
    @staticmethod
    def _tile_ids(value) -> list[int]:
        """兼容单选 / 多选：None、单个数字、数组都收，统一成去重后的下标列表。"""
        if value is None or value == "":
            return []
        if isinstance(value, (list, tuple)):
            raw = list(value)
        else:
            raw = [value]
        out = []
        for x in raw:
            if x is None or x == "":
                continue
            try:
                i = int(x)
            except (TypeError, ValueError):
                raise ValueError("地块参数不对")
            if not (0 <= i < BOARD_SIZE):
                raise ValueError("没有这块地")
            if i not in out:
                out.append(i)
        return sorted(out)

    def _trade_names(self, ids: list[int]) -> str:
        return "、".join(self.board[i]["name"] for i in ids)

    def trade_offer(self, pid: int, payload: dict) -> None:
        with self.lock:
            if self.phase in ("lobby", "over", "setup"):
                raise ValueError("现在不能交换")
            if self.current != pid or self.phase != "roll":
                raise ValueError("只能在自己回合、掷骰之前发起交换")
            me = self.player(pid)
            if not me or not me["alive"]:
                raise ValueError("你已出局")
            try:
                target = int(payload.get("target", 0))
                give = self._tile_ids(payload.get("give"))
                want = self._tile_ids(payload.get("want"))
                give_cash = max(0, int(payload.get("giveCash", 0) or 0))
                want_cash = max(0, int(payload.get("wantCash", 0) or 0))
            except (TypeError, ValueError):
                raise ValueError("交换参数不对")

            other = self.player(target)
            if not other or not other["alive"] or other["id"] == pid:
                raise ValueError("对方不可交易")
            for i in give:
                if self.board[i].get("type") != "prop" or self.board[i].get("owner") != pid:
                    raise ValueError("「%s」不是你的地" % self.board[i]["name"])
            for i in want:
                if self.board[i].get("type") != "prop" or self.board[i].get("owner") != target:
                    raise ValueError("「%s」不是对方的" % self.board[i]["name"])
            if not give and not want and give_cash == 0 and want_cash == 0:
                raise ValueError("交换内容为空")
            if give_cash > me["money"]:
                raise ValueError("你拿不出这么多现金")
            if want_cash > other["money"]:
                raise ValueError("对方拿不出这么多现金")
            if any(t["from"] == pid for t in self.trades):
                raise ValueError("你已经有一笔交换在等回应了")

            self.trade_seq += 1
            self.trades.append({
                "id": self.trade_seq, "from": pid, "to": target,
                "give": give, "want": want,
                "giveCash": give_cash, "wantCash": want_cash,
                "asked": False,
            })
            self.say(f"{me['name']} 向 {other['name']} 发起交换", "trade", pid)
            self.event("trade_offer", pid=pid,
                       text=f"{me['name']} 向 {other['name']} 发起交换",
                       trade={"from": me["name"], "to": other["name"],
                              "give": self._trade_names(give),
                              "want": self._trade_names(want),
                              "giveCash": give_cash, "wantCash": want_cash})
            self.bump()

    def trade_answer(self, pid: int, trade_id: int, accept: bool, reason: str = "") -> None:
        with self.lock:
            trade = next((t for t in self.trades if t["id"] == trade_id), None)
            if not trade or trade["to"] != pid:
                raise ValueError("没有这个交换申请")
            me = self.player(pid)
            other = self.player(trade["from"])
            give = self._tile_ids(trade.get("give"))
            want = self._tile_ids(trade.get("want"))
            if accept:
                if other["money"] < trade["giveCash"] or me["money"] < trade["wantCash"]:
                    self.trades.remove(trade)
                    raise ValueError("现金不足，交换失效")
                before = {me["id"]: self._group_set_ids(me["id"]),
                          other["id"]: self._group_set_ids(other["id"])}
                other["money"] -= trade["giveCash"]
                me["money"] += trade["giveCash"]
                me["money"] -= trade["wantCash"]
                other["money"] += trade["wantCash"]
                for i in give:
                    self.board[i]["owner"] = pid
                    self.board[i]["bought_turn"] = self.turn_no
                for i in want:
                    self.board[i]["owner"] = trade["from"]
                    self.board[i]["bought_turn"] = self.turn_no
                self.say(f"{me['name']} 接受了交换" + (f"：{reason}" if reason else ""), "trade", pid)
                self.event(
                    "trade", pid=pid,
                    text=f"{me['name']} 与 {other['name']} 成交",
                    trade={
                        "from": other["name"], "to": me["name"],
                        "give": self._trade_names(give),
                        "want": self._trade_names(want),
                        "giveCash": trade["giveCash"], "wantCash": trade["wantCash"],
                    })
                # 打包交换可能一次凑齐好几组：逐组比对新旧，发恭喜
                for player in (me, other):
                    groups = {self.board[i].get("group") for i in self.props_of(player["id"])}
                    for g in groups:
                        self._check_group_complete(player, g, before.get(player["id"], set()))
            else:
                self.say(f"{me['name']} 拒绝了交换" + (f"：{reason}" if reason else ""), "trade", pid)
            self.trades.remove(trade)
            self.bump()

    # ---------------------------------------------------------------- 视图
    def view(self, pid: int | None, token: str = "") -> dict:
        with self.lock:
            me = self.player(pid) if pid is not None else None
            players = [{
                "id": p["id"], "name": p["name"], "char": p["char"],
                "pos": p["pos"], "money": p["money"], "alive": p["alive"],
                "props": self.props_of(p["id"]),
                "kind": p.get("kind", "human"),
                "botLevel": p.get("bot_level", "normal"),
                "brain": p.get("brain", "local"),
                "thinking": bool(p.get("thinking")),
                "note": str(p.get("memory") or "")[:80],
            } for p in self.players]
            board = []
            my_pos = me["pos"] if me else None
            for t in self.board:
                item = dict(t)
                if t.get("type") == "prop":
                    item["rentNow"] = self.rent_of(t)
                    item["upCost"] = upgrade_cost(t)
                    item["levelName"] = LEVELS[int(t.get("level", 0))]["name"]
                    # 地契卡要展示「三档租金 / 两次升级价」，服务端一次算好，前端不做钱的计算
                    item["rents"] = [self._rent_with_level(t, lv) for lv in range(len(LEVELS))]
                    item["ups"] = [int(round(t["price"] * UPGRADE_RATE[lv]))
                                   if lv < len(UPGRADE_RATE) and int(t.get("level", 0)) <= lv else None
                                   for lv in range(len(LEVELS))]
                    item["fresh"] = int(t.get("bought_turn", -1)) == self.turn_no
                    item["set"] = bool(t.get("owner") is not None and self.owns_set(t.get("owner"), t.get("group")))
                    item["doubled"] = item["set"]
                    item["canUp"] = bool(
                        me and item["upCost"] is not None
                        and t.get("owner") == me["id"] and my_pos == t["i"] and not item["fresh"]
                        and self.phase == "buy" and me["money"] >= item["upCost"]
                    )
                    item["upWhy"] = ""
                    if me and t.get("owner") == me["id"]:
                        # 给前端一句「为什么升不了」，省得玩家自己猜
                        if item["upCost"] is None:
                            item["upWhy"] = "已满级"
                        elif item["fresh"]:
                            item["upWhy"] = "新买·要再走一次才能升"
                        elif my_pos != t["i"]:
                            item["upWhy"] = "没走到这儿"
                        elif me["money"] < item["upCost"]:
                            item["upWhy"] = "钱不够"
                board.append(item)
            return {
                "version": self.version,
                "phase": self.phase,
                "current": self.current,
                "host": self.host,
                "you": pid,
                "dice": self.dice,
                "pending": self.pending,
                "chance": self.chance,
                "settles": self.settles,
                "settle": self.settles[0] if self.settles else None,
                "land": self.land,
                "round": self.turn_no,
                "winner": self.winner,
                "players": players,
                "board": board,
                "chars": CHARS,
                "groups": GROUP_COLORS,
                "log": self.log[-40:],
                "news": self.news,
                "newsList": self.news_pub,
                "trades": [t for t in self.trades if pid in (t["from"], t["to"])],
                "startMoney": self.start_money,
                "salary": SALARY,
                "boardSize": BOARD_SIZE,
                "admin": bool(token) and token == self.host_token,
                "watching": bool(token) and token in self.watchers,
                "paused": self.frozen is not None,
                "settings": {
                    "startMoney": self.start_money,
                    "botLevel": self.bot_level,
                    "moneyChoices": MONEY_CHOICES,
                    "moneyMin": MONEY_MIN,
                    "moneyMax": MONEY_MAX,
                    "botLevels": {k: v["name"] for k, v in BOT_LEVELS.items()},
                    "maxPlayers": MAX_PLAYERS,
                    "levels": [x["name"] for x in LEVELS],
                    "ai": {"ok": ai_brain.ok, "reason": ai_brain.reason, "model": ai_brain.model,
                           "timeout": ai_brain.timeout, "retry": AI_RETRY},
                },
            }

    # ---------------------------------------------------------------- 入口
    ADMIN_ACTIONS = {
        "start", "restart", "pause", "resume",
        "settings", "bot_add", "bot_remove", "bot_level",
    }

    def act(self, token: str, action: str, payload: dict) -> None:
        pid = self.pid_of(token)
        if pid is None and token not in self.watchers:
            raise ValueError("登录状态失效，请重新加入")
        if action in self.ADMIN_ACTIONS:
            if not self.is_admin(token):
                raise ValueError("只有房主能做这个操作")
            if action == "start":
                self.start()
            elif action == "restart":
                self.restart()
            elif action == "pause":
                self.pause()
            elif action == "resume":
                self.resume()
            elif action == "settings":
                self.set_settings(payload.get("startMoney"), payload.get("botLevel"))
            elif action == "bot_add":
                self.add_bot(str(payload.get("brain") or "local"))
            elif action == "bot_remove":
                self.remove_bot(int(payload.get("pid", 0)))
            elif action == "bot_level":
                self.set_bot_level(int(payload.get("pid", 0)), str(payload.get("level", "")))
            return
        if pid is None:
            raise ValueError("观战者不能操作棋盘")
        if action == "roll":
            self.roll(pid)
        elif action == "settle_ok":
            self.settle_ok(pid)
        elif action == "chance_ok":
            self.chance_ok(pid)
        elif action == "buy":
            self.buy(pid)
        elif action == "pass":
            self.pass_buy(pid)
        elif action == "upgrade":
            self.upgrade(pid, int(payload.get("tile", -1)))
        elif action == "end_turn":
            self.end_turn(pid)
        elif action == "trade_offer":
            self.trade_offer(pid, payload)
        elif action == "trade_answer":
            self.trade_answer(pid, int(payload.get("id", 0)), bool(payload.get("accept")))
        else:
            raise ValueError("未知操作")


game = Game()


class Handler(BaseHTTPRequestHandler):
    server_version = "MonopolyLAN/2.0"

    def log_message(self, fmt: str, *args) -> None:
        sys.stderr.write("%s  %s\n" % (self.log_date_time_string(), fmt % args))

    # -------------------------------------------------------------- 输出
    def _json(self, data: dict, code: int = 200) -> None:
        body = json.dumps(data, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _html(self, path: Path) -> None:
        if not path.exists():
            self._json({"error": "页面文件缺失"}, 500)
            return
        body = path.read_bytes()
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _error(self, message: str, code: int = 400) -> None:
        self._json({"error": message}, code)

    # -------------------------------------------------------------- GET
    def do_GET(self) -> None:
        parsed = urlparse(self.path)
        qs = parse_qs(parsed.query)
        token = (qs.get("token") or [""])[0]

        if parsed.path in ("/", "/index.html"):
            self._html(WEB / "index.html")
            return

        if parsed.path == "/api/lobby":
            with game.lock:
                self._json({
                    "phase": game.phase,
                    "paused": game.frozen is not None,
                    "chars": CHARS,
                    "players": [{"name": p["name"], "char": p["char"],
                                 "kind": p.get("kind", "human")} for p in game.players],
                    "taken": game.used_chars(),
                    "settings": {
                        "startMoney": game.start_money,
                        "botLevel": game.bot_level,
                        "moneyChoices": MONEY_CHOICES,
                        "moneyMin": MONEY_MIN,
                        "moneyMax": MONEY_MAX,
                        "botLevels": {k: v["name"] for k, v in BOT_LEVELS.items()},
                        "maxPlayers": MAX_PLAYERS,
                    },
                })
            return

        if parsed.path == "/api/rules":
            self._json(rules_payload())
            return

        if parsed.path == "/api/ai_config":
            self._json(ai_brain.public())
            return

        if parsed.path == "/api/saves":
            try:
                self._json({"saves": db_list_games(), "current": game.game_id})
            except Exception as exc:
                self._error("读不到存档：%s" % exc, 500)
            return

        if parsed.path == "/api/seats":
            self._json({"phase": game.phase, "seats": game.seat_list(),
                        "admin": game.is_admin(token)})
            return

        if parsed.path == "/api/state":
            if not game.can_view(token):
                self._error("登录状态失效", 401)
                return
            self._json(game.view(game.pid_of(token), token))
            return

        if parsed.path == "/api/events":
            self._events(token)
            return

        self._error("没有这个地址", 404)

    def _events(self, token: str) -> None:
        if not game.can_view(token):
            self._error("登录状态失效", 401)
            return
        pid = game.pid_of(token)

        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Connection", "keep-alive")
        self.send_header("X-Accel-Buffering", "no")
        self.end_headers()

        last = -1
        try:
            while True:
                with game.lock:
                    changed = game.lock.wait_for(
                        lambda: game.version != last, timeout=15)
                    if changed:
                        last = game.version
                        payload = json.dumps(game.view(pid, token), ensure_ascii=False)
                    else:
                        payload = None
                if payload is None:
                    self.wfile.write(b": ping\n\n")
                else:
                    self.wfile.write(("data: " + payload + "\n\n").encode("utf-8"))
                self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError, OSError):
            return

    # -------------------------------------------------------------- POST
    def do_POST(self) -> None:
        parsed = urlparse(self.path)
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length) if length else b"{}"
        try:
            payload = json.loads(raw.decode("utf-8") or "{}")
        except json.JSONDecodeError:
            self._error("请求格式不对")
            return
        token = (parse_qs(parsed.query).get("token") or [""])[0]

        if parsed.path == "/api/ai_config":
            try:
                cfg = ai_brain.configure(
                    str(payload.get("baseUrl") or ""),
                    str(payload.get("model") or ""),
                    str(payload.get("apiKey") or ""),
                    payload.get("timeout"),
                )
            except (TypeError, ValueError) as exc:
                self._error(str(exc), 409)
                return
            with game.lock:
                game.version += 1
                game.lock.notify_all()
            self._json({"ok": True, "ai": cfg})
            return

        if parsed.path == "/api/watch":
            new_token = game.watch()
            self._json({"token": new_token, "watching": True})
            return

        if parsed.path == "/api/join":
            try:
                new_token, pid = game.join(payload.get("name", ""), payload.get("char", ""))
            except ValueError as exc:
                self._error(str(exc), 409)
                return
            self._json({"token": new_token, "pid": pid})
            return

        if parsed.path == "/api/load":
            try:
                seats = game.load_from_db(int(payload.get("id", 0)))
            except (TypeError, ValueError) as exc:
                self._error(str(exc), 409)
                return
            self._json({"ok": True, "seats": seats, "phase": game.phase})
            return

        if parsed.path == "/api/claim":
            try:
                pid = int(payload.get("pid", 0))
                new_token = game.claim_seat(pid)
            except (TypeError, ValueError) as exc:
                self._error(str(exc), 409)
                return
            self._json({"token": new_token, "pid": pid})
            return

        if parsed.path == "/api/leave":
            game.leave(token)
            self._json({"ok": True})
            return

        if parsed.path == "/api/action":
            try:
                game.act(token, str(payload.get("action", "")), payload)
            except ValueError as exc:
                self._error(str(exc), 409)
                return
            self._json({"ok": True})
            return

        self._error("没有这个地址", 404)


def local_ip() -> str:
    """取本机局域网 IP，取不到就返回空字符串。"""
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.settimeout(0.6)
            s.connect(("8.8.8.8", 80))
            return s.getsockname()[0]
    except OSError:
        pass
    try:
        return socket.gethostbyname_ex(socket.gethostname())[2][0]
    except OSError:
        return ""


def main() -> int:
    parser = argparse.ArgumentParser(
        description="大富翁局域网服务端",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    parser.add_argument("-p", "--port", type=int, default=9000, help="监听端口")
    parser.add_argument("--host", default="0.0.0.0", help="监听地址")
    parser.add_argument("--no-browser", action="store_true", help="启动后不自动打开浏览器")
    args = parser.parse_args()

    if not (WEB / "index.html").exists():
        print(f"缺少页面文件：{WEB / 'index.html'}")
        return 1

    try:
        db_init()
        games = len(db_list_games())
        print(f"  存档数据库 : {DB_PATH.name}（已有 {games} 局历史存档）")
    except Exception as exc:
        print(f"  存档数据库不可用，将以纯内存模式运行：{exc}")

    httpd = ThreadingHTTPServer((args.host, args.port), Handler)
    httpd.daemon_threads = True

    ip = local_ip()
    print("=" * 52)
    print("  大富翁 · 局域网联机")
    print(f"  本机打开 : http://127.0.0.1:{args.port}/")
    if ip:
        print(f"  手机/其它电脑 : http://{ip}:{args.port}/")
    else:
        print(f"  手机/其它电脑 : http://<本机局域网IP>:{args.port}/")
    print("  同一 WiFi 下打开地址即可，2 到 4 人同局")
    print("  按 Ctrl+C 停止")
    print("=" * 52, flush=True)

    if FROZEN and not args.no_browser:
        threading.Timer(1.0, lambda: webbrowser.open(
            f"http://127.0.0.1:{args.port}/")).start()

    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\n已停止。")
    finally:
        httpd.server_close()
    return 0


if __name__ == "__main__":
    sys.exit(main())

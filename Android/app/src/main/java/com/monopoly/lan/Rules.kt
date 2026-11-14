package com.monopoly.lan

import org.json.JSONArray
import org.json.JSONObject

/**
 * 与 server.py 一一对应的规则常量。
 *
 * 一句话提醒：这里的数字改了，网页那边的文案会跟着变（rules 接口把这些数值一起下发），
 * 但机器人策略里写死的乘系数（Bots.kt）是照着这套价位调的，改价要顺手复核一遍。
 */
object Rules {
    const val START_MONEY = 1500
    const val SALARY = 500
    const val BOARD_SIZE = 40
    const val MAX_PLAYERS = 4

    val MONEY_CHOICES = intArrayOf(1000, 1500, 2000, 3000)
    const val MONEY_MIN = 600
    const val MONEY_MAX = 6000

    /** 地块三级：0 初级 / 1 中级 / 2 高级 */
    val LEVELS = arrayOf(
        Level("初级", 1.0),
        Level("中级", 1.7),
        Level("高级", 2.6),
    )

    /** 升到 2 级 / 3 级，按地块原价的倍数收 */
    val UPGRADE_RATE = doubleArrayOf(0.6, 0.9)

    class Level(val name: String, val rent: Double)

    // 人机三档：keep 留钱 / group 凑组让利 / upgrade 升级意愿 / trade 接换地门槛 / risk 租金安全垫 / payback 回本次数上限
    class BotLevel(
        val name: String,
        val keep: Int,
        val group: Double,
        val upgrade: Double,
        val trade: Double,
        val risk: Double,
        val payback: Double,
    )

    val BOT_LEVELS: Map<String, BotLevel> = linkedMapOf(
        "normal" to BotLevel("平衡", 160, 0.3, 0.85, 0.35, 0.45, 3.3),
        "steady" to BotLevel("稳健", 420, 0.7, 0.6, 0.5, 0.9, 3.0),
        "aggr" to BotLevel("激进", 70, 0.2, 0.95, 0.18, 0.1, 3.8),
    )

    const val TRADE_MIN_MARGIN = 120

    val BOT_NAMES = arrayOf("铁算盘", "阿呆", "小机灵", "财迷", "守财奴", "赌徒", "老掌柜", "账房")

    /** 结算 / 机会卡 / 买地升级：弹窗出现后停留 2 秒再「点确定」 */
    const val BOT_DELAY_SEC = 2.0
    const val SETTLE_SHOW_BUFFER = 0.2
    const val POPUP_FALLBACK_EXTRA = 2.5
    const val HANDOFF_FALLBACK = 0.5
    const val HANDOFF_HOLD = 2.0
    const val BOT_MOVE_LEAD = 1.1
    const val BOT_STEP_ANIM = 0.17
    const val SETTLE_ANIM_BASE = 2.6
    const val SETTLE_STEP_ANIM = 0.172
    const val SETTLE_ANIM_TAIL = 0.16

    const val AI_MEM_LIMIT = 240
    const val AI_RETRY = 3
    const val AI_TRADE_MAX = 2

    val AI_PERSONAS = linkedMapOf(
        "normal" to "普通：该省就省，看到便宜的地先占住，被人坑过会记着",
        "steady" to "稳健：手上永远留足两脚最贵租金，只在回收期短的地上升级，不轻易跟人换地",
        "aggr" to "激进：抢地优先，为了凑齐一整组愿意透支，谁挡路就记他一笔",
    )

    class Char(val id: String, val name: String, val glyph: String, val color: String, val ink: String, val desc: String)

    val CHARS = arrayOf(
        Char("fox", "小狐", "狐", "#E08D5B", "#A95E2A", "机灵的橙色小狐"),
        Char("panda", "小熊", "熊", "#5C8F9E", "#3A6C7B", "稳重的青色小熊"),
        Char("penguin", "企鹅", "企", "#6F84C8", "#455CA2", "淡定的蓝衣企鹅"),
        Char("dino", "恐龙", "龙", "#62A171", "#37764A", "热血的绿色恐龙"),
    )

    val GROUP_COLORS = arrayOf(
        "#C9A66B", "#6FA3C9", "#6FB79B", "#D98C7A", "#B07FB0",
        "#8B9BC4", "#A8B56A", "#C08862", "#7FB5B5", "#C46A7A",
    )

    val GROUP_NAMES = arrayOf(
        "麦金", "天蓝", "青碧", "珊瑚", "藕紫",
        "灰蓝", "橄榄", "赭石", "青绿", "玫红",
    )

    /** 40 格：4 个角 + 30 块地产（10 个色组，每组 3 块）+ 4 张机会 + 2 格税 + 休息站 */
    class TileDef(
        val type: String,
        val name: String,
        val sub: String? = null,
        val icon: String? = null,
        val group: Int? = null,
        val price: Int = 0,
        val rent: Int = 0,
        val amount: Int = 0,
    )

    val TILE_DEFS: List<TileDef> = listOf(
        // 0-10 下边
        TileDef("go", "起点", "经过 +$SALARY", "flag"),
        TileDef("prop", "小桥", group = 0, price = 80, rent = 26, icon = "bridge"),
        TileDef("prop", "巷口", group = 0, price = 90, rent = 30, icon = "home"),
        TileDef("prop", "老屋", group = 0, price = 100, rent = 34, icon = "house"),
        TileDef("chance", "机会", "抽一张卡", "spark"),
        TileDef("prop", "码头", group = 1, price = 120, rent = 40, icon = "anchor"),
        TileDef("prop", "渔港", group = 1, price = 130, rent = 44, icon = "boat"),
        TileDef("prop", "灯塔", group = 1, price = 140, rent = 48, icon = "light"),
        TileDef("tax", "税务局", "交 150", "receipt", amount = 150),
        TileDef("prop", "茶铺", group = 2, price = 150, rent = 50, icon = "tea"),
        TileDef("hospital", "医院", "交 120", "hospital", amount = 120),
        // 11-20 左边
        TileDef("prop", "茶园", group = 2, price = 160, rent = 54, icon = "leaf"),
        TileDef("prop", "竹林", group = 2, price = 170, rent = 58, icon = "bamboo"),
        TileDef("chance", "机会", "抽一张卡", "spark"),
        TileDef("prop", "集市", group = 3, price = 180, rent = 60, icon = "market"),
        TileDef("prop", "老街", group = 3, price = 190, rent = 64, icon = "shop"),
        TileDef("prop", "客栈", group = 3, price = 200, rent = 68, icon = "bed"),
        TileDef("prop", "剧院", group = 4, price = 210, rent = 70, icon = "mask"),
        TileDef("prop", "画廊", group = 4, price = 220, rent = 74, icon = "frame"),
        TileDef("prop", "博物馆", group = 4, price = 230, rent = 78, icon = "museum"),
        TileDef("park", "停车场", "奖金 +150", "park", amount = 150),
        // 21-30 上边
        TileDef("prop", "车站", group = 5, price = 240, rent = 80, icon = "train"),
        TileDef("prop", "港口", group = 5, price = 250, rent = 85, icon = "port"),
        TileDef("prop", "机场", group = 5, price = 260, rent = 90, icon = "plane"),
        TileDef("prop", "银行", group = 6, price = 270, rent = 95, icon = "bank"),
        TileDef("chance", "机会", "抽一张卡", "spark"),
        TileDef("prop", "交易所", group = 6, price = 280, rent = 100, icon = "chart"),
        TileDef("prop", "金库", group = 6, price = 290, rent = 105, icon = "vault"),
        TileDef("tax", "市政税", "交 200", "tax", amount = 200),
        TileDef("prop", "山顶别墅", group = 7, price = 300, rent = 110, icon = "villa"),
        TileDef("chance", "幸运角", "抽一张卡", "clover"),
        // 31-39 右边
        TileDef("prop", "温泉", group = 7, price = 320, rent = 115, icon = "spring"),
        TileDef("prop", "观景台", group = 7, price = 340, rent = 120, icon = "view"),
        TileDef("prop", "花园", group = 8, price = 350, rent = 125, icon = "flower"),
        TileDef("prop", "温室", group = 8, price = 360, rent = 130, icon = "plant"),
        TileDef("prop", "果园", group = 8, price = 370, rent = 135, icon = "apple"),
        TileDef("prop", "码头街", group = 9, price = 380, rent = 140, icon = "shop"),
        TileDef("prop", "中央大道", group = 9, price = 400, rent = 150, icon = "tower"),
        TileDef("rest", "休息站", "什么也不发生", "moon"),
        TileDef("prop", "云端塔", group = 9, price = 450, rent = 170, icon = "cloud"),
    )

    class ChanceCard(val text: String, val delta: Int?, val move: Int?, val goto: Int?, val icon: String)

    val CHANCE_CARDS = listOf(
        ChanceCard("银行分红到账", 150, null, null, "bank"),
        ChanceCard("朋友聚餐，你买了单", -120, null, null, "shop"),
        ChanceCard("路边捡到钱包", 80, null, null, "spark"),
        ChanceCard("市政维修摊派", -100, null, null, "receipt"),
        ChanceCard("顺风车，前进 3 格", null, 3, null, "car"),
        ChanceCard("回头找东西，后退 2 格", null, -2, null, "moon"),
        ChanceCard("彩票中奖", 200, null, null, "clover"),
        ChanceCard("违停罚单", -90, null, null, "tax"),
        ChanceCard("直达起点，补贴到账", null, null, 0, "flag"),
        ChanceCard("抄近路，前进 5 格", null, 5, null, "car"),
    )

    fun botKeep(level: String): Int = (BOT_LEVELS[level] ?: BOT_LEVELS["normal"]!!).keep

    /**
     * 规则说明（数值全部从常量里取，改规则不用改前端文案）。
     * 直接照 server.py 的 rules_payload() 抄，前端只做渲染。
     */
    fun rulesPayload(): JSONObject {
        val pct = LEVELS.map { Math.round(it.rent * 100).toInt() }
        val out = JSONObject()
        out.put("title", "大富翁 · 规则说明")
        val sections = JSONArray()

        sections.put(section("怎么赢", listOf(
            "2 到 $MAX_PLAYERS 人同局（人和电脑都算），最后一位没有破产的玩家获胜。",
            "钱被扣成负数就破产出局，名下的地块全部变回无主。",
        )))

        sections.put(section("一回合做什么", listOf(
            "轮到谁，先决定要不要在掷骰前发起交换（不换就直接掷骰）。",
            "掷两枚骰子，按点数逐格往前走；经过起点拿 $SALARY 金币。",
            "停在无主地产上：可以买下，也可以放弃。",
            "停在别人的地上：按对方地块当前的等级交租金。",
            "正好踩在自己的地上：可以把它升级一级（刚买的这块本回合不能升）。",
            "落地后的租金、奖金、机会卡都会弹出窗口给所有人看，由落点玩家点「确定」才真正扣钱 / 加钱；" +
                "电脑角色停留 2 秒后自动点确定。确认完这一回合自动结束，不需要再点「结束回合」。",
        )))

        sections.put(section("买地", listOf(
            "只有停在无主地产上时才能买，价格印在格子下方。",
            "买下的地就是你的，别人再踩到要给你交租。",
            "钱不够就只能放弃，地块保持无主。",
        )))

        sections.put(section("升级（三级）", listOf(
            "只有正踩在自己这块地上的那一回合才能升级 —— 走到哪里，才升哪里。",
            "刚买下的地不能马上升：要等下一次再走到它上面（不是「下回合」）才能升。",
            "每块地三级：初级（1.0 倍租金）、中级（${pct[1]}% 租金）、高级（${pct[2]}% 租金）。",
            "升级费按地块原价算：升到中级 ${Math.round(UPGRADE_RATE[0] * 100).toInt()}%、升到高级 ${Math.round(UPGRADE_RATE[1] * 100).toInt()}%。",
            "升级后租金立刻上涨，对手再踩到就得多付钱。",
        )))

        sections.put(section("为什么升级按钮是灰的？", listOf(
            "四个条件同时满足才能升：站在这块地上、这块地是你的、不是本回合刚买的、金币够升级费。",
            "「没走到这儿」= 这块地现在不归你踩，先走到它上面。",
            "（「新买」的意思是：这块地是你刚买下的，下一次再走到它上面才能升）",
            "「钱不够」= 升级费还差钱；「已满级」= 这已经是高级，不能再升。",
            "升级只在这一回合的「地契卡」上点，点完这一回合就自动结束。",
        )))

        val group0 = TILE_DEFS.count { it.group == 0 }
        val taxAmount = TILE_DEFS.first { it.name == "税务局" }.amount
        val cityTax = TILE_DEFS.first { it.name == "市政税" }.amount
        val hospital = TILE_DEFS.first { it.name == "医院" }.amount
        val park = TILE_DEFS.first { it.name == "停车场" }.amount
        sections.put(section("租金与固定格子", listOf(
            "租金 = 地块原租金 × 等级倍率，棋盘上每块地都标了当前租金。",
            "整组加成：同色系的 $group0 块地全部落在一个人手里时，这组地租金再翻一倍；" +
                "地块格子上的租金后面会标「已翻倍」，集齐的那一刻会有一次恭喜动画。",
            "起点：经过 +$SALARY 金币（正好停在也算）。",
            "税务局 −$taxAmount、市政税 −$cityTax、医院 −$hospital、停车场 +$park。",
            "机会格：抽一张卡，共 ${CHANCE_CARDS.size} 张（分红、罚单、前进后退、直达起点等）。" +
                "卡片会停在棋盘中央给所有人看，抽到的人点「确定」才生效；电脑停留 2 秒后自动确认。",
            "休息站：什么也不发生。",
        )))

        sections.put(section("交换", listOf(
            "交换只能在自己回合、掷骰之前发起：先点「发起交换」，谈不谈都不影响掷骰。",
            "地块 + 现金自由搭配；双方的每一侧都可以一次选多块地打包交换。",
            "对方点「接受」才生效，拒绝就作废；同一时间只有一笔待回应的交换。",
            "人机（本地算法）只会接受或拒绝，不会主动发起交换。",
            "AI 座位有主动权：它会在落地后那一次计划里写好换地打算，到你下一回合开局（掷骰前）才真正发出去（一局最多几笔，被拒就作罢）。",
        )))

        val moods = listOf("normal" to "看性价比，留点余钱", "steady" to "现金为王，只捡便宜", "aggr" to "能买就买，升级最凶")
        sections.put(section("电脑玩家", listOf(
            "只能在等待室加电脑，开局后只能改难度，不能加人或删人。",
            "电脑有两种：人机（本地算法，秒回）和 AI（真的调一个大模型来想）。房主在等待室点「＋人机」或「＋AI」自己挑。",
            "三档难度：" + moods.joinToString("、") { (k, mood) ->
                "${BOT_LEVELS[k]?.name}（$mood）"
            } + "。",
            "人机会按色组凑地、按手里的现金决定买不买；升级只升它正踩着的地，且用「收几次租回本」来算划不划算。",
            "人机还看局势：现金比平均多就攥钱，明显落后就抢地，所以同一档难度也会打出不同的节奏。",
            "电脑每做一个决定都会留一句理由（例如「买下就凑齐这组了，咬牙也要拿」「升级费要收 6 次租才回本，不划算」），在记录里能直接看到它怎么想的。",
            "AI 玩家除了看规则和局势，还会带着自己写的「小本子」（记谁坑过它、盯上哪块地、下一步计划）和最近流水，所以打起来更连贯、也会记仇。",
            "AI 在落地之后才决定：踩到要买 / 要升的地时调一次模型，一次把这一回合剩下的打算排好；" +
                "AI 走到只收租、交税、领钱这种不需要选的地方就不调模型，自动确认。",
            "AI 给的计划会先过一遍结构校验：不合法就打回去让它重来，三次还不行就换人机接手这一回合，对局不会卡住。",
            "AI 想的时候玩家卡片上会显示「AI 正在思考…」，最多等几秒。",
            "4 个座位全交给电脑也能开局，人只负责观战。",
        )))

        sections.put(section("设置、暂停与存档", listOf(
            "开始页可选初始资金 ${MONEY_CHOICES.joinToString(" / ")}（自定义范围 $MONEY_MIN–$MONEY_MAX）。",
            "房主在对局中可以点「返回开始页（保留局面）」改设置，再点「继续」回到原来的局面。",
            "存档写在手机应用私有目录里，同时只跑一局；下次打开可以认领上次的座位接着玩。",
            "观战不占座位，只读棋盘和日志，不能操作棋子。",
        )))

        out.put("sections", sections)
        return out
    }

    private fun section(title: String, items: List<String>): JSONObject {
        val o = JSONObject()
        o.put("title", title)
        val arr = JSONArray()
        for (i in items) arr.put(i)
        o.put("items", arr)
        return o
    }
}

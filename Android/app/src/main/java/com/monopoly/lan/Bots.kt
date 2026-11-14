package com.monopoly.lan

import com.monopoly.lan.Game.Player
import com.monopoly.lan.Game.Tile
import com.monopoly.lan.Game.Trade
import kotlin.random.Random

/**
 * 人机（本地启发式）的决策算法，对应 server.py 里那一组 bot_* / _trade_* 方法。
 *
 * 这些函数都假设调用方已经持有 Game.lock（和原版一样，只有 localStep 会在锁里调它们）。
 */

    /** 资金姿态：领先就攥钱、落后就抢地，让三档难度之外还有「局势感」。 */
    fun Game.botPace(p: Player): Double {
        val others = players.filter { it.alive && it.id != p.id }.map { it.money }
        if (others.isEmpty()) return 1.0
        val avg = others.sum().toDouble() / others.size
        if (avg <= 0) return 1.0
        val ratio = p.money / avg
        if (ratio >= 1.7) return 0.6          // 大幅领先：留着钱等收租
        if (ratio >= 1.2) return 0.85
        if (ratio <= 0.65) return 1.3         // 明显落后：宁可多占一块地
        if (ratio <= 0.9) return 1.1
        return 1.0
    }

    /** 这块地对 p 值多少：现在能收的租金 + 凑色组的潜力，不划算的组打折。 */
    fun Game.botTileScore(p: Player, tile: Tile): Int {
        if (tile.type != "prop") return 0
        var score = rentOf(tile) * 3
        val group = tile.group
        val mates = groupMates(p.id, group).size
        val total = groupTotal(group)
        if (total > 0 && mates >= total - 1) {           // 买下就垄断整组
            score += Math.round(tile.price * 1.2).toInt()
        } else if (mates > 0) {
            score += Math.round(tile.price * (0.25 + 0.2 * mates)).toInt()
        } else {
            score = maxOf(score, Math.round(tile.price * 0.8).toInt())
        }
        val rivals = board.count {
            it.type == "prop" && it.group == group && it.owner != null && it.owner != p.id
        }
        if (mates == 0 && rivals >= 2) {                  // 这组已经被别人占住，含金量打折
            score = (score * 0.7).toInt()
        }
        return score
    }

    fun Game.botWantsBuy(p: Player, tile: Tile, why: MutableList<String>? = null): Boolean {
        val cfg = botCfg(p)
        val level = p.botLevel
        val price = tile.price

        fun out(ok: Boolean, text: String): Boolean {
            why?.add(text)
            return ok
        }

        if (p.money < price) return out(false, "钱不够，这块 $price 买不起")

        val group = tile.group
        val mates = groupMates(p.id, group).size
        val total = groupTotal(group)

        // 留钱：凑色组越齐越敢花；能补齐整组就把钱压上；落后时放宽、领先时收紧
        var keep = Math.round(cfg.keep * botPace(p)).toInt()
        if (mates > 0) keep = Math.round(keep * (1 - cfg.group * 0.75)).toInt()
        if (total > 0 && mates >= total - 1) keep = 0
        val reserve = keep + Math.round(maxRent() * cfg.risk).toInt()
        val cashLeft = p.money - price

        if (level == "steady" && price > p.money * 0.55) {
            return out(false, "这价几乎是我半副身家，不想全砸在一块地上")   // 稳健不把半副身家砸在一块地
        }
        if (cashLeft < 0) return out(false, "买完现金就是负的，不能买")
        if (cashLeft < reserve) {
            // 手头紧：只有「这一买能补齐整组」或者明显捡漏才破例
            if (total > 0 && mates >= total - 1) return out(true, "买下就凑齐这组了，咬牙也要拿")
            if (botTileScore(p, tile) >= price * 2) return out(true, "这块明显捡漏，破例拿一块")
            return out(false, "买完就扛不住下一脚租金，先等等")
        }
        if (total > 0 && mates >= total - 1) return out(true, "买下就整组在手，租金能翻着涨")
        if (mates > 0) return out(true, "这组我已经有 $mates 块，再补一块")
        return out(true, "价格还行，先占一块")
    }

    /** 新规则下只能升自己脚下这块地，所以机会难得：按「几次收租回本」算值不值。 */
    fun Game.botTryUpgrade(p: Player, why: MutableList<String>? = null): Boolean {
        val cfg = botCfg(p)
        val pos = p.pos
        val tile = board.getOrNull(pos)

        fun out(ok: Boolean, text: String): Boolean {
            why?.add(text)
            return ok
        }

        if (tile == null || tile.type != "prop" || tile.owner != p.id) return out(false, "脚下不是我的地，这轮没得升")
        if (tile.boughtTurn == turnNo) return out(false, "「${tile.name}」是新买的，下次走到才能升")
        val cost = upgradeCost(tile) ?: return out(false, "「${tile.name}」已经满级了")
        if (p.money < cost) return out(false, "升「${tile.name}」要 $cost，钱不够")
        val surplus = p.money - cost
        val floorCash = cfg.keep + Math.round(maxRent() * cfg.risk * 0.35).toInt()
        if (surplus < floorCash) return out(false, "升完就扛不住租金，先攥着钱")   // 升完剩下的钱扛不住一脚租金
        val gain = rentOfNext(tile) - rentOf(tile)
        if (gain <= 0) return out(false, "升了租金也不涨，算了")
        val mates = groupMates(p.id, tile.group).size
        val total = groupTotal(tile.group)
        val monopoly = total > 0 && mates >= total          // 整组在手，升级收益最稳
        val payback = cost.toDouble() / gain                // 多收几次租才把升级费赚回来
        val limit = cfg.payback * (if (monopoly) 1.15 else 1.0)
        if (payback > limit) return out(false, "升级费要收 ${Math.round(payback)} 次租才回本，不划算")
        if (!monopoly && Random.nextDouble() > cfg.upgrade) return out(false, "手头还不宽裕，这轮先不升")
        val before = rentOf(tile)
        val after = rentOfNext(tile)
        return try {
            upgrade(p.id, tile.i)
            out(true, "升「${tile.name}」只要收 ${Math.round(payback)} 次租就回本，租金 $before → $after")
        } catch (e: IllegalStateException) {
            out(false, "想升「${tile.name}」但规则不让")
        }
    }

    /** (现金安全垫, 缺钱程度 0..1)：缺钱越紧，越不肯把现金换成地、越想要现金。 */
    fun Game.tradeSafety(p: Player): Pair<Int, Double> {
        val cfg = botCfg(p)
        val reserve = Math.round(cfg.keep * botPace(p)).toInt() + Math.round(maxRent() * cfg.risk).toInt()
        val needMoney = maxOf(1, reserve * 2)
        val tight = 1.0 - minOf(1.0, p.money.toDouble() / needMoney)
        return reserve to maxOf(0.0, tight)
    }

    /** 一块地在这次交换里的价值（role=in 我拿到 / out 我让出），按色组与回合进度修正。 */
    fun Game.tradeTileValue(
        p: Player,
        tile: Tile,
        role: String,
        progress: Double,
        notes: MutableList<String>,
        otherId: Int? = null,
        costs: MutableList<String>? = null,
    ): Int {
        if (tile.type != "prop") return 0
        var base = botTileScore(p, tile)
        val group = tile.group
        val total = groupTotal(group)
        val mates = groupMates(p.id, group).size
        if (role == "in") {
            if (total > 0 && mates >= total - 1) {
                // 换回来就能整组垄断：不只是这块自己租金翻倍，同组我手上的地也一起翻倍
                base = (base * (1.15 + 0.35 * progress)).toInt()
                val matesRent = groupMates(p.id, group).sumOf { rentOf(it) }
                base += ((matesRent + rentOf(tile)) * (1.0 + 0.8 * progress)).toInt()
                notes.add("换回来就凑齐这组、整组租金翻倍")
            } else if (mates == 0) {
                val rivals = board.count {
                    it.type == "prop" && it.group == group && it.owner != null && it.owner != p.id
                }
                if (rivals >= maxOf(2, total - 1)) {
                    // 这组已经被对手占死，我拿到手里也收不到多少租
                    base = (base * 0.45).toInt()
                    notes.add("这组已被对手占住、拿在手里不值钱")
                }
            } else {
                base = (base * (1.0 + 0.1 * mates)).toInt()
            }
            return maxOf(0, base)
        }
        // role == "out"：让出去的那块，代价还要看会不会喂饱对手
        var theirMates = 0
        if (otherId != null) theirMates = groupMates(otherId, group).size
        if (total > 0 && theirMates >= total - 1) {
            // 一让出去对手就整组垄断、租金翻倍：越到后期越不能送（宁可要更高的补偿）
            base = (base * (2.6 - 0.3 * progress)).toInt()
            (costs ?: notes).add("这块让出去对方就凑齐整组，代价太大")
        } else if (theirMates == 0) {
            base = (base * 0.75).toInt()          // 对方拿着也组不起来，让出的实际损失小一些
        }
        return maxOf(0, base)
    }

    /**
     * 评估一笔交换，返回 (是否接受, 一句理由)。
     *
     * 除「地值 + 现金差」外，还按三件事修正：
     * - 手头紧不紧（缺钱时现金更值钱、把现金换出去更肉疼）；
     * - 回合进度（前期抢地、后期租金与现金更硬，送对手整组的代价也更大）；
     * - 色组归属（能凑齐我这一组就加价，已被对手占死的组打折，喂对手整组重罚）。
     */
    fun Game.botTradeEval(p: Player, trade: Trade): Pair<Boolean, String> {
        val cfg = botCfg(p)
        val wantCash = trade.wantCash
        if (wantCash > p.money) return false to "我拿不出 $wantCash 现金"

        val progress = minOf(1.0, turnNo / 40.0)     // 第 40 回合后按「后期」算满
        val (reserve, tight) = tradeSafety(p)
        val notes = ArrayList<String>()          // 这笔交换的好处
        val costs = ArrayList<String>()          // 这笔交换的代价
        var gain = 0
        var volume = 1

        val inIds = trade.give          // 我拿到的
        val outIds = trade.want         // 我给出的
        val rawIn = inIds.sumOf { botTileScore(p, board[it]) }
        val rawOut = outIds.sumOf { botTileScore(p, board[it]) }
        val valueIn = inIds.sumOf { tradeTileValue(p, board[it], "in", progress, notes) }
        val valueOut = outIds.sumOf {
            tradeTileValue(p, board[it], "out", progress, costs, trade.from, costs)
        }
        gain += valueIn - valueOut
        // 门槛按「两边里较便宜那组」的规模算：打包换，不能用两边地价相加的体量去要求
        if (rawIn > 0 && rawOut > 0) volume += minOf(rawIn, rawOut) else volume += maxOf(rawIn, rawOut)

        val cashIn = trade.giveCash
        // 手头紧时进来的现金更值钱；要掏出去的现金更肉疼（还要留够安全垫）
        gain += (cashIn * (1.0 + 0.6 * tight)).toInt()
        gain -= (wantCash * (1.0 + 0.9 * tight)).toInt()
        if (cashIn > 0 && tight > 0.4) notes.add("手头有点紧，进账的现金也算价值")

        // 掏完现金后还够不够扛下一次大租金：不够就只认「凑齐整组」这种大便宜
        if (wantCash > 0 && p.money - wantCash < reserve) {
            if (valueIn < valueOut) return false to "换完现金只剩 ${p.money - wantCash}，扛不住下一次大租金"
            if (notes.isEmpty() || !notes.joinToString(" ").contains("凑齐")) {
                return false to "要动的现金太多，换完手里太空"
            }
        }

        var need = Math.round(cfg.trade * volume).toInt()
        need = maxOf(need, Rules.TRADE_MIN_MARGIN)
        // 越到后期、手头越紧，门槛越硬：不是明显占便宜的一律不点头
        need = Math.round(need * (1.0 + 0.25 * progress + 0.2 * tight)).toInt()

        if (gain > need && gain > 0) {
            val poison = costs.filter { it.contains("凑齐整组") }
            if (poison.isNotEmpty() && cashIn < valueOut * 1.2) {
                // 能赚一点也不送对手垄断：除非现金补偿高到能抵掉这组翻倍的租金
                return false to "有点赚，但${poison[0]}，现金补得不够，不换"
            }
            val why = notes.firstOrNull() ?: "这块地合我的算盘"
            return true to "值得换：$why（净赚约 ${gain - need}）"
        }
        if (gain > 0) {
            if (costs.isNotEmpty()) return false to "有点便宜但${costs[0]}，不划算"
            return false to "看着有点便宜但不够明显（+$gain，门槛 $need）"
        }
        if (costs.isNotEmpty()) return false to "这笔我吃亏（$gain）：${costs[0]}"
        return false to "这笔我吃亏（$gain）"
    }

    /** 兼容旧调用：只取结论。 */
    fun Game.botWantsTrade(p: Player, trade: Trade): Boolean = botTradeEval(p, trade).first

package com.monopoly.lan

import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.random.Random

/**
 * 全局共享的一局游戏（对应 server.py 的 Game 类）。
 *
 * 约定和原版一致：所有状态修改都在 lock 里完成，每次改动 version += 1 并唤醒 SSE 线程；
 * 弹窗类流程（结算 / 机会卡 / 地契卡 / 回合交接）都是「先挂起，等前端确认再动钱」。
 */
class Game(private val brain: AiBrain) {

    val lock = ReentrantLock()
    val cond = lock.newCondition()

    var version: Int = 0
    var gameId: Long? = null
    var dbOk: Boolean = true
    var hostToken: String? = null
    val watchers = LinkedHashSet<String>()
    var frozenSnap: JSONObject? = null
    var botTimer: ScheduledFuture<*>? = null
    var tradeTimer: ScheduledFuture<*>? = null
    var startMoney: Int = Rules.START_MONEY
    var botLevel: String = "normal"

    val players = ArrayList<Player>()
    val tokens = LinkedHashMap<String, Int>()
    val board = ArrayList<Tile>()
    var phase: String = "lobby"          // lobby / setup / roll / settle / buy / chance / handoff / over
    var turnNo: Int = 0
    var current: Int? = null
    var host: Int? = null
    var dice: IntArray = intArrayOf(0, 0)
    var pending: Int? = null
    var chance: PendingChance? = null
    val settles = ArrayList<Settle>()
    var settleLead: Double = 0.0
    var settleViewed: Boolean = false
    var chanceViewed: Boolean = false
    var buyViewed: Boolean = false
    var handoffViewed: Boolean = false
    var handoffAck: Boolean = false
    var skipRollDelay: Boolean = false
    var land: LandAction? = null
    val log = ArrayList<LogEntry>()
    var logSeq: Int = 0
    var news: News = News(JSONObject().put("seq", 0).put("type", "none"))
    var newsFrame = ArrayList<News>()
    var newsPub: List<News> = emptyList()
    var newsSeq: Int = 0
    val trades = ArrayList<Trade>()
    var tradeSeq: Int = 0
    var winner: Int? = null
    var lastSteps: Int = 0

    private val rnd = Random.Default
    private val secure = SecureRandom()

    // ---------------------------------------------------------------- 数据模型

    class Player(
        val id: Int,
        var name: String,
        var char: String,
        var pos: Int = 0,
        var money: Int,
        var alive: Boolean = true,
        var kind: String = "human",        // human / bot
        var botLevel: String = "normal",
        var brain: String = "local",       // local / ai
        var memory: String = "",
        var thinking: Boolean = false,
        var aiPlan: JSONObject? = null,
        var aiPlanTurn: Int? = null,
        var aiTradeNext: JSONObject? = null,
        var aiTrades: Int = 0,
    )

    class Tile(val i: Int, val def: Rules.TileDef) {
        var owner: Int? = null
        var level: Int = 0
        var boughtTurn: Int = -1
        val type get() = def.type
        val name get() = def.name
        val group get() = def.group
        val price get() = def.price
        val rent get() = def.rent
        val amount get() = def.amount
        val icon get() = def.icon
    }

    class Settle(
        val type: String,
        val pid: Int,
        val tile: Int?,
        val amount: Int,
        val to: Int? = null,
        val name: String? = null,
        val tileName: String? = null,
        val icon: String? = null,
        val text: String? = null,
    )

    class PendingChance(
        val text: String,
        val delta: Int?,
        val move: Int?,
        val goto: Int?,
        val icon: String,
        var pid: Int,
        var name: String,
    )

    class LandAction(val kind: String, val tile: Int)

    class LogEntry(val id: Int, val kind: String, val pid: Int?, val text: String)

    class News(val o: JSONObject) {
        val seq: Int get() = o.optInt("seq")
        val type: String get() = o.optString("type")
    }

    class Trade(
        val id: Int,
        val from: Int,
        val to: Int,
        val give: List<Int>,
        val want: List<Int>,
        val giveCash: Int,
        val wantCash: Int,
        var asked: Boolean = false,
    )

    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "monopoly-timer").also { it.isDaemon = true }
        }

    init {
        reset()
    }

    // ---------------------------------------------------------------- 基础

    fun reset() {
        players.clear()
        tokens.clear()
        board.clear()
        for ((n, def) in Rules.TILE_DEFS.withIndex()) board.add(Tile(n, def))
        phase = "lobby"
        turnNo = 0
        current = null
        host = null
        dice = intArrayOf(0, 0)
        pending = null
        chance = null
        settles.clear()
        settleLead = 0.0
        settleViewed = false
        chanceViewed = false
        buyViewed = false
        handoffViewed = false
        handoffAck = false
        skipRollDelay = false
        land = null
        log.clear()
        logSeq = 0
        news = News(JSONObject().put("seq", 0).put("type", "none"))
        newsFrame.clear()
        newsPub = emptyList()
        newsSeq = 0
        trades.clear()
        tradeSeq = 0
        winner = null
        frozenSnap = null
        botTimer?.cancel(false)
        botTimer = null
        tradeTimer?.cancel(false)
        tradeTimer = null
        bump()
        say("房间已创建，等人加入。")
    }

    fun bump() {
        lock.lock()
        try {
            version += 1
            newsPub = newsFrame.toList()
            newsFrame.clear()
            persist()
            scheduleBot()
            cond.signalAll()
        } finally {
            lock.unlock()
        }
    }

    fun say(text: String, kind: String = "sys", pid: Int? = null) {
        logSeq += 1
        log.add(LogEntry(logSeq, kind, pid, text))
        while (log.size > 80) log.removeAt(0)
    }

    /** 玩家在对局记录里发一条消息（观战者也能发）。 */
    fun chat(token: String?, textIn: String) {
        val line = textIn.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
        if (line.isEmpty()) throw IllegalArgumentException("先说点什么再发")
        val text = if (line.length > 80) line.take(80) + "…" else line
        lock.lock()
        try {
            if (!canView(token)) throw IllegalStateException("登录状态失效，重进一下房间")
            val pid = if (token == null) null else tokens[token]
            val name = if (pid != null) player(pid)?.name ?: "观战者" else "观战者"
            say("$name：$text", "chat", pid)
            bump()
        } finally {
            lock.unlock()
        }
    }

    /** 给前端播动画用的一次性事件（带自增 seq，客户端只播一次）。 */
    fun event(type: String, build: JSONObject.() -> Unit = {}) {
        newsSeq += 1
        val item = JSONObject().put("seq", newsSeq).put("type", type)
        item.build()
        news = News(item)
        newsFrame.add(news)
    }

    fun player(pid: Int?): Player? {
        if (pid == null) return null
        for (p in players) if (p.id == pid) return p
        return null
    }

    fun usedChars(): List<String> = players.map { it.char }

    fun propsOf(pid: Int?): List<Int> =
        board.filter { it.owner != null && it.owner == pid }.map { it.i }

    /** 观战：不占座位，也能看棋盘、也能当房主管设置。 */
    fun watch(): String {
        lock.lock()
        try {
            val token = newToken()
            watchers.add(token)
            if (hostToken == null) hostToken = token
            say("有人进来观战。", if (players.isEmpty()) "join" else "sys")
            bump()
            return token
        } finally {
            lock.unlock()
        }
    }

    fun isAdmin(token: String?): Boolean = token != null && token == hostToken

    fun canView(token: String?): Boolean =
        token != null && (tokens.containsKey(token) || watchers.contains(token))

    fun leave(token: String?) {
        if (token == null) return
        lock.lock()
        try {
            watchers.remove(token)
            if (phase != "lobby" || frozenSnap != null) {
                // 局已开始：座位不回收，只让观战者走
                if (!tokens.containsKey(token)) {
                    if (hostToken == token) hostToken = tokens.keys.firstOrNull()
                    bump()
                    return
                }
                throw IllegalStateException("对局已经开始，退出会丢座位；直接关掉页面就行，座位会留着")
            }
            val pid = tokens.remove(token)
            if (hostToken == token) hostToken = tokens.keys.firstOrNull()
            if (pid != null) {
                val p = player(pid)
                if (p != null && phase == "lobby") {
                    players.remove(p)
                    if (host == pid) host = players.firstOrNull()?.id
                    say("${p.name} 离开了房间", "sys")
                }
            }
            bump()
        } finally {
            lock.unlock()
        }
    }

    private fun newToken(): String {
        val bytes = ByteArray(14)
        secure.nextBytes(bytes)
        val sb = StringBuilder()
        for (b in bytes) sb.append(String.format("%02x", b))
        return sb.toString()
    }

    // ---------------------------------------------------------------- 起始资金 / 难度

    fun setSettings(startMoneyIn: Int?, botLevelIn: String?) {
        lock.lock()
        try {
            if (phase == "over") throw IllegalStateException("本局已经结束，等下一局再改设置")
            if (startMoneyIn != null) {
                if (phase != "lobby" || frozenSnap != null) {
                    throw IllegalStateException("这一局已经开局，起始资金不再改动；想改请重开一局")
                }
                if (startMoneyIn < Rules.MONEY_MIN || startMoneyIn > Rules.MONEY_MAX) {
                    throw IllegalStateException("起始资金要在 ${Rules.MONEY_MIN} 到 ${Rules.MONEY_MAX} 之间")
                }
                startMoney = startMoneyIn
                for (p in players) p.money = startMoneyIn
                say("房主把起始资金改成 $startMoneyIn", "sys")
            }
            if (botLevelIn != null) {
                if (!Rules.BOT_LEVELS.containsKey(botLevelIn)) {
                    throw IllegalStateException("难度只有 平衡 / 稳健 / 激进")
                }
                botLevel = botLevelIn
                for (p in players) if (p.kind == "bot") p.botLevel = botLevelIn
                say("人机难度设为「${Rules.BOT_LEVELS[botLevelIn]!!.name}」", "sys")
            }
            bump()
        } finally {
            lock.unlock()
        }
    }

    // ---------------------------------------------------------------- 人机

    private fun botName(): String {
        val used = players.map { it.name }
        for (n in Rules.BOT_NAMES) if (!used.contains(n)) return n
        return "电脑${players.size + 1}"
    }

    fun addBot(brainKind: String = "local"): Int {
        lock.lock()
        try {
            if (phase != "lobby" && phase != "setup") throw IllegalStateException("对局进行中不能加人机，先重开一局")
            if (frozenSnap != null) throw IllegalStateException("这一局已经开局，不能再加人机；想加请重开一局")
            if (players.size >= Rules.MAX_PLAYERS) throw IllegalStateException("房间满了（最多 ${Rules.MAX_PLAYERS} 人）")
            if (brainKind != "local" && brainKind != "ai") throw IllegalStateException("电脑只有两种：人机 / AI")
            if (brainKind == "ai" && !brain.ok) {
                throw IllegalStateException("AI 还没配好：${brain.reason}（点开始页的「AI 接口」填一下）")
            }
            val free = Rules.CHARS.map { it.id }.filter { !usedChars().contains(it) }
            if (free.isEmpty()) throw IllegalStateException("没有空余角色了")
            val pid = nextPid(players.map { it.id })
            val name = botName()
            players.add(Player(pid, name, free[0], 0, startMoney, true, "bot", botLevel, brainKind))
            val tag = if (brainKind == "ai") "AI" else "人机"
            say("房主加了${tag}玩家「$name」（${Rules.BOT_LEVELS[botLevel]!!.name}）", "join", pid)
            bump()
            return pid
        } finally {
            lock.unlock()
        }
    }

    fun removeBot(pid: Int) {
        lock.lock()
        try {
            if (phase != "lobby" || frozenSnap != null) throw IllegalStateException("只有还没开局的等待室能删人机")
            val p = player(pid)
            if (p == null || p.kind != "bot") throw IllegalStateException("这不是电脑玩家")
            players.remove(p)
            say("房主移除了电脑玩家「${p.name}」", "sys")
            bump()
        } finally {
            lock.unlock()
        }
    }

    fun setBotLevel(pid: Int, level: String) {
        lock.lock()
        try {
            if (!Rules.BOT_LEVELS.containsKey(level)) throw IllegalStateException("难度只有 平衡 / 稳健 / 激进")
            val p = player(pid)
            if (p == null || p.kind != "bot") throw IllegalStateException("这不是电脑玩家")
            p.botLevel = level
            bump()
        } finally {
            lock.unlock()
        }
    }

    // ---------------------------------------------------------------- 加入

    fun join(nameIn: String, char: String): Pair<String, Int> {
        lock.lock()
        try {
            if (phase != "lobby" || frozenSnap != null) throw IllegalStateException("这局已经开始了，等下一局吧")
            if (players.size >= Rules.MAX_PLAYERS) throw IllegalStateException("房间满了（最多 ${Rules.MAX_PLAYERS} 人）")
            val name = nameIn.trim().take(8).ifEmpty { "玩家" }
            if (Rules.CHARS.none { it.id == char }) throw IllegalStateException("角色不存在")
            if (usedChars().contains(char)) throw IllegalStateException("这个角色已被选走")
            val pid = nextPid(players.map { it.id })
            players.add(Player(pid, name, char, 0, startMoney, true, "human", "normal", "local"))
            val token = newToken()
            tokens[token] = pid
            if (host == null) host = pid
            if (hostToken == null) hostToken = token
            say("$name 加入，选了「${charName(char)}」", "join", pid)
            bump()
            return token to pid
        } finally {
            lock.unlock()
        }
    }

    fun charName(char: String): String = Rules.CHARS.firstOrNull { it.id == char }?.name ?: char

    fun pidOf(token: String?): Int? {
        if (token == null) return null
        return tokens[token]
    }

    fun nextPid(existing: List<Int>): Int {
        var i = 1
        while (existing.contains(i)) i++
        return i
    }

    // ---------------------------------------------------------------- 开局

    fun start() {
        lock.lock()
        try {
            if (phase != "lobby" || frozenSnap != null) throw IllegalStateException("已经开始了")
            if (players.size < 2) throw IllegalStateException("至少 2 个玩家（人或电脑）才能开始")
            phase = "roll"
            current = players[0].id
            dice = intArrayOf(0, 0)
            turnNo = 1
            for (t in board) t.boughtTurn = -1
            if (dbOk) {
                try {
                    val names = players.joinToString("、") { it.name }
                    gameId = Store.newGame(
                        "$names 的局",
                        summary(),
                        snapshot().toString(),
                    )
                    for (old in Store.pruneGames()) {
                        say("存档最多留 ${Store.SAVE_SLOTS} 局，「${old.optString("title")}」已被删掉", "sys")
                    }
                } catch (e: Exception) {
                    dbOk = false
                }
            }
            say("游戏开始，从 " + (player(current)?.name ?: "?") + " 先手。", "turn")
            bump()
        } finally {
            lock.unlock()
        }
    }

    fun restart() {
        lock.lock()
        try {
            // 旧的那一局就此封存，下一局另起一行记录
            val gid = gameId
            if (gid != null && dbOk) {
                try {
                    Store.saveGame(gid, phase, "over", summary(), snapshot().toString())
                } catch (e: Exception) {
                    // 封存失败不影响重开
                }
            }
            gameId = null
            board.clear()
            for ((n, def) in Rules.TILE_DEFS.withIndex()) board.add(Tile(n, def))
            phase = "lobby"
            current = null
            dice = intArrayOf(0, 0)
            lastSteps = 0
            pending = null
            chance = null
            settles.clear()
            land = null
            trades.clear()
            tradeSeq = 0
            winner = null
            frozenSnap = null
            log.clear()
            log.add(LogEntry(1, "sys", null, "新的一局，等人开始。"))
            logSeq = 1
            event("reset")
            for (p in players) {
                p.pos = 0
                p.money = startMoney
                p.alive = true
                p.aiPlan = null
                p.aiPlanTurn = null
                p.aiTradeNext = null
                p.thinking = false
            }
            bump()
        } finally {
            lock.unlock()
        }
    }

    /**
     * 房主结束房间：除了自己，其他人全部清出去，回到等待大厅，可以直接开新房间。
     * 和 restart 的区别：restart 是把牌桌重开、人还坐着；closeRoom 是把房间关掉重开，
     * 其他人手上的登录态作废，得重新加入（也可以换个角色）。
     */
    fun closeRoom() {
        lock.lock()
        try {
            val gid = gameId
            if (gid != null && dbOk) {
                try {
                    Store.saveGame(gid, phase, "over", summary(), snapshot().toString())
                } catch (e: Exception) {
                    // 封存失败不影响关房
                }
            }
            gameId = null
            val keepToken = hostToken
            val keepPid = if (keepToken != null) tokens[keepToken] else null
            val keepPlayer = if (keepPid != null) player(keepPid) else null
            players.clear()
            if (keepPlayer != null) players.add(keepPlayer)
            tokens.clear()
            if (keepToken != null && keepPid != null) tokens[keepToken] = keepPid
            watchers.clear()
            // 房主当时是在观战：把观战身份留着，否则他自己也会被踢出去
            if (keepToken != null && keepPid == null) watchers.add(keepToken)
            host = if (keepPlayer != null) keepPid else null
            board.clear()
            for ((n, def) in Rules.TILE_DEFS.withIndex()) board.add(Tile(n, def))
            phase = "lobby"
            turnNo = 0
            current = null
            dice = intArrayOf(0, 0)
            lastSteps = 0
            pending = null
            chance = null
            settles.clear()
            settleLead = 0.0
            settleViewed = false
            chanceViewed = false
            buyViewed = false
            handoffViewed = false
            handoffAck = false
            skipRollDelay = false
            land = null
            trades.clear()
            tradeSeq = 0
            winner = null
            frozenSnap = null
            log.clear()
            log.add(LogEntry(1, "sys", null, "房主结束了房间，其他人需要重新加入。"))
            logSeq = 1
            event("reset")
            for (p in players) {
                p.pos = 0
                p.money = startMoney
                p.alive = true
                p.aiPlan = null
                p.aiPlanTurn = null
                p.aiTradeNext = null
                p.thinking = false
            }
            bump()
        } finally {
            lock.unlock()
        }
    }

    /** 房主把正在进行的局冻结，退回开始页调设置。 */
    fun pause() {
        lock.lock()
        try {
            if (phase == "lobby" || phase == "setup") throw IllegalStateException("现在没在打，不用暂停")
            val snap = snapshot()
            snap.remove("frozen")
            frozenSnap = snap
            phase = "setup"
            say("房主把对局暂停了，回到开始页调设置。", "sys")
            bump()
        } finally {
            lock.unlock()
        }
    }

    fun resume() {
        lock.lock()
        try {
            val snap = frozenSnap ?: throw IllegalStateException("现在没有暂停中的对局")
            if (phase != "setup") throw IllegalStateException("现在没有暂停中的对局")
            val keepTokens = LinkedHashMap(tokens)
            val keepHost = hostToken
            val keepWatch = LinkedHashSet(watchers)
            restoreFields(snap)
            frozenSnap = null
            tokens.clear()
            tokens.putAll(keepTokens)
            hostToken = keepHost
            watchers.clear()
            watchers.addAll(keepWatch)
            say("继续上一局。", "sys")
            bump()
        } finally {
            lock.unlock()
        }
    }

    // ---------------------------------------------------------------- 掷骰

    fun roll(pid: Int) {
        need(pid, "roll")
        val p = player(pid) ?: throw IllegalStateException("没有这个玩家")
        dice = intArrayOf(rnd.nextInt(1, 7), rnd.nextInt(1, 7))
        val steps = dice[0] + dice[1]
        lastSteps = steps
        settleLead = Rules.SETTLE_ANIM_BASE + Rules.SETTLE_STEP_ANIM * steps + Rules.SETTLE_ANIM_TAIL
        say("${p.name} 掷出 ${dice[0]} + ${dice[1]}，前进 $steps 格", "dice", pid)
        event("dice") {
            put("pid", pid)
            put("dice", JSONArray().put(dice[0]).put(dice[1]))
            put("steps", steps)
        }
        settles.clear()
        land = null
        move(p, steps, true)
        resolve(p, 0)
        settleStart(p)
        bump()
    }

    fun move(p: Player, steps: Int, paySalary: Boolean) {
        val old = p.pos
        p.pos = Math.floorMod(old + steps, Rules.BOARD_SIZE)
        if (paySalary && steps > 0 && (old + steps) >= Rules.BOARD_SIZE) {
            // 工资先挂进待确认队列：玩家点「确定」之后才真正进账（所有人都会看到这个弹窗）
            settles.add(
                Settle(
                    "salary", p.id, 0, Rules.SALARY,
                    text = "经过起点，领取 ${Rules.SALARY}", icon = "flag",
                )
            )
        }
    }

    /** 走到哪一格：把「要扣的钱 / 要做的选择」挂起来，不立刻改钱。 */
    fun resolve(p: Player, depth: Int) {
        val tile = board[p.pos]
        val kind = tile.type
        pending = null
        land = null

        when (kind) {
            "prop" -> {
                val owner = tile.owner
                if (owner == null) {
                    event("land") { put("pid", p.id); put("tile", tile.i); put("name", tile.name) }
                    if (p.money >= tile.price) {
                        land = LandAction("buy", tile.i)
                    } else {
                        say("${p.name} 走到「${tile.name}」，钱不够买（${tile.price}）", "info", p.id)
                    }
                } else if (owner == p.id) {
                    event("land") { put("pid", p.id); put("tile", tile.i); put("name", tile.name) }
                    land = LandAction("own", tile.i)
                } else {
                    val other = player(owner)
                    val rent = rentOf(tile)
                    val lvName = Rules.LEVELS[tile.level.coerceIn(0, Rules.LEVELS.size - 1)].name
                    settles.add(
                        Settle(
                            "rent", p.id, tile.i, rent,
                            to = owner, name = tile.name, tileName = tile.name,
                            icon = tile.icon,
                            text = "${other?.name ?: "?"} 的「${tile.name}」（$lvName），租金 $rent",
                        )
                    )
                    event("land") { put("pid", p.id); put("tile", tile.i); put("name", tile.name) }
                }
            }
            "chance" -> land = LandAction("chance", tile.i)
            "tax", "hospital" -> {
                settles.add(
                    Settle(
                        "tax", p.id, tile.i, -tile.amount,
                        name = tile.name, icon = tile.icon,
                        text = "在「${tile.name}」支出 ${tile.amount}",
                    )
                )
            }
            "park" -> settles.add(
                Settle(
                    "park", p.id, tile.i, tile.amount,
                    name = tile.name, icon = tile.icon,
                    text = "停在停车场，拿奖金 ${tile.amount}",
                )
            )
            "go" -> settles.add(
                Settle("go", p.id, tile.i, 0, name = tile.name, icon = tile.icon, text = "正好停在起点")
            )
            "rest" -> settles.add(
                Settle("rest", p.id, tile.i, 0, name = tile.name, icon = tile.icon, text = "停在休息站，什么也不发生")
            )
        }
    }

    /** 进入「待确认结算」：弹窗给所有人看，落点玩家点确定后才动钱。 */
    fun settleStart(p: Player) {
        if (settles.isNotEmpty()) {
            val e = settles[0]
            phase = "settle"
            pending = null
            settleViewed = false
            val kind = e.type
            say(
                "${p.name}：${e.text ?: ""}，等确认",
                when (kind) {
                    "rent", "tax" -> "pay"
                    "salary", "park" -> "gain"
                    else -> "info"
                },
                p.id,
            )
            val ev = e
            event("settle") {
                put("pid", p.id)
                put("kind", kind)
                put("tile", (ev.tile ?: JSONObject.NULL) as Any)
                put("amount", ev.amount)
                put("to", (ev.to ?: JSONObject.NULL) as Any)
                put("text", (ev.text ?: JSONObject.NULL) as Any)
                put("name", (ev.name ?: JSONObject.NULL) as Any)
                put("icon", (ev.icon ?: JSONObject.NULL) as Any)
            }
            return
        }
        landStart(p)
    }

    /** 任一客户端把结算窗真正画到屏幕上后回报；电脑从这一刻起再等 2 秒。 */
    fun settleSeen(token: String?) {
        lock.lock()
        try {
            if (phase != "settle" || settles.isEmpty() || settleViewed) return
            settleViewed = true
            armBotPopupConfirm("settle")
            cond.signalAll()
        } finally {
            lock.unlock()
        }
    }

    /** 同上，机会卡已经出现在屏幕上。 */
    fun chanceSeen(token: String?) {
        lock.lock()
        try {
            if (phase != "chance" || chance == null || chanceViewed) return
            chanceViewed = true
            armBotPopupConfirm("chance")
            cond.signalAll()
        } finally {
            lock.unlock()
        }
    }

    /** 地契卡（买地 / 原地升级）已经出现在屏幕上。 */
    fun buySeen(token: String?) {
        lock.lock()
        try {
            if (phase != "buy" || pending == null || buyViewed) return
            buyViewed = true
            armBotPopupConfirm("buy")
            cond.signalAll()
        } finally {
            lock.unlock()
        }
    }

    /** 下一家的页面已经把上一回合的弹窗 / 动画清空。 */
    fun handoffSeen(token: String?) {
        lock.lock()
        try {
            if (phase != "handoff" || handoffViewed) return
            handoffViewed = true
            handoffAck = true
            botTimer?.cancel(false)
            botTimer = null
            // 页面已经清空旧弹窗，从这里开始固定等 2 秒再轮到下一家掷骰 / 操作
            val v = version
            val cur = current
            arm(Rules.HANDOFF_HOLD) { finishHandoff(v, cur) }
        } finally {
            lock.unlock()
        }
    }

    /** 交接完成：进入下一家的掷骰阶段。 */
    fun finishHandoff(v: Int, cur: Int?) {
        lock.lock()
        try {
            if (phase != "handoff" || v != version || cur != current) return
            phase = "roll"
            skipRollDelay = false
            bump()
        } finally {
            lock.unlock()
        }
    }

    /** 弹窗已显示：把兜底计时换成精确的「再等 2 秒」。调用方持锁。 */
    fun armBotPopupConfirm(phaseName: String) {
        val cur = player(current)
        if (cur == null || cur.kind != "bot" || !cur.alive) return
        botTimer?.cancel(false)
        botTimer = null
        val v = version
        val pid = current ?: return
        val delay = Rules.BOT_DELAY_SEC + Rules.SETTLE_SHOW_BUFFER
        arm(delay) { botStep(v, pid, phaseName) }
    }

    /** 落点玩家点「确定」：这时才真正扣钱 / 加钱，然后自动进入下一步或结束回合。 */
    fun settleOk(pid: Int) {
        if (phase != "settle") throw IllegalStateException("现在没有待确认的结算")
        if (current != pid) throw IllegalStateException("还没轮到你")
        val p = player(pid) ?: return
        if (settles.isEmpty()) {
            landStart(p)
            bump()
            return
        }
        val e = settles.removeAt(0)
        settleLead = 0.0        // 后续结算窗不再补播上一段移动动画
        when (e.type) {
            "salary" -> {
                p.money += e.amount
                say("${p.name} 经过起点，领取 ${e.amount}", "gain", p.id)
                event("salary") { put("pid", p.id); put("amount", e.amount) }
            }
            "rent" -> {
                val amount = e.amount
                val other = player(e.to)
                p.money -= amount
                if (other != null) other.money += amount
                say(
                    "${p.name} 踩到 ${other?.name ?: "?"} 的「${e.name ?: ""}」，付租金 $amount",
                    "pay", p.id,
                )
                event("rent") {
                    put("pid", p.id)
                    put("tile", (e.tile ?: JSONObject.NULL) as Any)
                    put("name", (e.name ?: JSONObject.NULL) as Any)
                    put("amount", -amount)
                }
                bankruptCheck(p)
                if (phase == "over") {
                    bump()
                    return
                }
            }
            "tax" -> {
                val amount = -e.amount
                p.money -= amount
                say("${p.name} 在「${e.name ?: ""}」支出 $amount", "pay", p.id)
                event("tax") {
                    put("pid", p.id)
                    put("tile", (e.tile ?: JSONObject.NULL) as Any)
                    put("name", (e.name ?: JSONObject.NULL) as Any)
                    put("amount", -amount)
                }
                bankruptCheck(p)
                if (phase == "over") {
                    bump()
                    return
                }
            }
            "park" -> {
                p.money += e.amount
                say("${p.name} 停在停车场，拿奖金 ${e.amount}", "gain", p.id)
                event("park") {
                    put("pid", p.id)
                    put("tile", (e.tile ?: JSONObject.NULL) as Any)
                    put("amount", e.amount)
                }
            }
            "go" -> say("${p.name} 正好停在起点", "info", p.id)
            "rest" -> say("${p.name} 停在休息站，什么也不发生", "info", p.id)
        }

        if (!p.alive) {
            advanceTurn()
        } else if (settles.isNotEmpty()) {
            settleStart(p)
        } else {
            landStart(p)
        }
        bump()
    }

    /** 结算完之后，看落点要不要做选择（买地 / 升级 / 抽卡）；不需要就直接交棒。 */
    fun landStart(p: Player) {
        if (phase == "over") return
        val action = land
        land = null
        if (action == null) {
            advanceTurn()
            return
        }
        val tile = board[action.tile]
        when (action.kind) {
            "buy" -> {
                pending = tile.i
                phase = "buy"
                buyViewed = false
                say("${p.name} 走到「${tile.name}」，无主地，要不要买", "buy", p.id)
                return
            }
            "own" -> {
                val cost = upgradeCost(tile)
                val fresh = tile.boughtTurn == turnNo
                if (cost == null) {
                    say("${p.name} 回到自己的「${tile.name}」，已经满级", "info", p.id)
                    advanceTurn()
                    return
                }
                if (fresh) {
                    say("${p.name} 回到自己的「${tile.name}」，新买的本回合不能升", "info", p.id)
                    advanceTurn()
                    return
                }
                if (p.money < cost) {
                    say("${p.name} 回到自己的「${tile.name}」，升级要 $cost，钱不够", "info", p.id)
                    advanceTurn()
                    return
                }
                pending = tile.i
                phase = "buy"
                buyViewed = false
                say("${p.name} 回到自己的「${tile.name}」，可以花 $cost 升级", "buy", p.id)
                return
            }
            "chance" -> {
                val card = Rules.CHANCE_CARDS[rnd.nextInt(Rules.CHANCE_CARDS.size)]
                chance = PendingChance(
                    card.text, card.delta, card.move, card.goto, card.icon, p.id, p.name
                )
                phase = "chance"
                chanceViewed = false
                say("${p.name} 抽到「${card.text}」，等待确认", "chance", p.id)
                event("chance") {
                    put("pid", p.id)
                    put("text", card.text)
                    put("icon", card.icon)
                    put("delta", card.delta ?: JSONObject.NULL)
                    put("move", card.move ?: JSONObject.NULL)
                    put("goto", card.goto ?: JSONObject.NULL)
                    put("wait", true)
                }
                return
            }
        }
        advanceTurn()
    }

    /** 玩家（或电脑）确认机会卡，卡上的动作这时才真正执行。 */
    fun chanceOk(pid: Int) {
        if (phase != "chance" || chance == null) throw IllegalStateException("现在没有待确认的机会卡")
        if (chance!!.pid != pid) throw IllegalStateException("这张卡不是你抽到的")
        val p = player(pid) ?: return
        val card = chance!!
        chance = null
        settleLead = 0.0        // 机会卡确认后直接进结算窗，不补播上一段掷骰移动
        settles.clear()
        land = null
        event("chance_go") {
            put("pid", pid)
            put("text", card.text)
            put("icon", card.icon)
            put("delta", card.delta ?: JSONObject.NULL)
            put("move", card.move ?: JSONObject.NULL)
            put("goto", card.goto ?: JSONObject.NULL)
        }
        applyChance(p, card, 0)
        if (phase == "over") {
            bump()
            return
        }
        if (!p.alive) {
            advanceTurn()
        } else if (settles.isNotEmpty()) {
            settleStart(p)
        } else if (land != null) {
            landStart(p)
        } else {
            advanceTurn()
        }
        bump()
    }

    /** 执行机会卡（调用方持锁）。 */
    fun applyChance(p: Player, card: PendingChance, depth: Int) {
        if (card.goto != null) {
            p.pos = card.goto
            settles.add(
                Settle(
                    "salary", p.id, card.goto, Rules.SALARY,
                    text = "回到起点，领取 ${Rules.SALARY}", icon = "flag",
                )
            )
            if (depth < 3) resolve(p, depth + 1)
            return
        }
        if (card.move != null) {
            move(p, card.move, true)
            if (depth < 3) resolve(p, depth + 1)
            return
        }
        if (card.delta != null) {
            val delta = card.delta
            p.money += delta
            val sign = if (delta > 0) "+" else ""
            say("${p.name} ${if (delta > 0) "收入" else "支出"} $sign$delta", if (delta > 0) "gain" else "pay", p.id)
            event("cash") {
                put("pid", p.id)
                put("tile", p.pos)
                put("amount", delta)
                put("text", card.text)
            }
            bankruptCheck(p)
        }
    }

    fun bankruptCheck(p: Player) {
        if (p.money >= 0) return
        p.money = 0
        p.alive = false
        for (t in board) {
            if (t.owner == p.id) {
                t.owner = null
                t.level = 0
                t.boughtTurn = -1
            }
        }
        trades.removeAll { it.from == p.id || it.to == p.id }
        say("${p.name} 资金链断裂，出局", "bankrupt", p.id)
        event("bankrupt") { put("pid", p.id); put("text", "${p.name} 出局") }
        val alive = players.filter { it.alive }
        if (alive.size <= 1) {
            phase = "over"
            winner = alive.firstOrNull()?.id
            if (alive.isNotEmpty()) {
                say("${alive[0].name} 成为最后的赢家", "over", alive[0].id)
            }
        }
    }

    // ---------------------------------------------------------------- 买地 / 升级（都在落点阶段一次做完）

    fun buy(pid: Int) {
        need(pid, "buy")
        val p = player(pid)!!
        val tile = board[pending ?: throw IllegalStateException("现在没有待处理的落点")]
        if (tile.owner != null) throw IllegalStateException("这块地已经有主人了")
        if (p.money < tile.price) throw IllegalStateException("钱不够")
        val beforeSets = groupSetIds(pid)
        p.money -= tile.price
        tile.owner = pid
        tile.boughtTurn = turnNo
        pending = null
        say("${p.name} 买下「${tile.name}」，花 ${tile.price}", "buy", p.id)
        event("buy") {
            put("pid", pid)
            put("tile", tile.i)
            put("name", tile.name)
            put("amount", -tile.price)
            put("group", tile.group ?: JSONObject.NULL)
        }
        checkGroupComplete(p, tile.group, beforeSets)
        advanceTurn()
        bump()
    }

    fun passBuy(pid: Int) {
        need(pid, "buy")
        val p = player(pid)!!
        val tileI = pending ?: throw IllegalStateException("现在没有待处理的落点")
        val tile = board[tileI]
        pending = null
        if (tile.owner == pid) {
            say("${p.name} 放弃升级「${tile.name}」", "info", p.id)
            event("pass") { put("pid", pid); put("tile", tileI); put("name", tile.name); put("upgrade", true) }
        } else {
            say("${p.name} 放弃购买「${tile.name}」", "info", p.id)
            event("pass") { put("pid", pid); put("tile", tileI); put("name", tile.name) }
        }
        advanceTurn()
        bump()
    }

    fun upgrade(pid: Int, tileI: Int) {
        need(pid, "buy")
        val p = player(pid)!!
        val pend = pending ?: throw IllegalStateException("现在只能处理落点这块地")
        if (pend != tileI) throw IllegalStateException("现在只能处理落点这块地")
        if (tileI < 0 || tileI >= Rules.BOARD_SIZE) throw IllegalStateException("没有这块地")
        val tile = board[tileI]
        if (tile.type != "prop" || tile.owner != pid) throw IllegalStateException("只能升级自己的地块")
        if (p.pos != tile.i) throw IllegalStateException("只能升级自己正踩着的这块地（要走到「${tile.name}」上才行）")
        if (tile.boughtTurn == turnNo) throw IllegalStateException("这块地是刚买下的，得下一次再走到它上面才能升")
        val cost = upgradeCost(tile) ?: throw IllegalStateException("这块地已经是高级了")
        if (p.money < cost) throw IllegalStateException("钱不够升级（要 $cost）")
        p.money -= cost
        tile.level += 1
        val lvName = Rules.LEVELS[tile.level].name
        pending = null
        say("${p.name} 把「${tile.name}」升到$lvName，花 $cost，租金变成 ${rentOf(tile)}", "buy", pid)
        event("upgrade") {
            put("pid", pid)
            put("tile", tile.i)
            put("name", tile.name)
            put("amount", -cost)
            put("level", tile.level)
            put("rent", rentOf(tile))
        }
        advanceTurn()
        bump()
    }

    fun groupSetIds(pid: Int): Set<Int> {
        val groups = board.filter { it.type == "prop" && it.group != null }.mapNotNull { it.group }.toSet()
        return groups.filter { ownsSet(pid, it) }.toSet()
    }

    /** 买下 / 换到一块地之后，如果这一组刚好凑齐，就发一次「恭喜」事件（所有人可见）。 */
    fun checkGroupComplete(p: Player, group: Int?, beforeSets: Set<Int>) {
        if (group == null || beforeSets.contains(group)) return
        if (!ownsSet(p.id, group)) return
        val name = if (group in Rules.GROUP_NAMES.indices) Rules.GROUP_NAMES[group] else "第 ${group + 1} 色组"
        say("${p.name} 凑齐了「$name」整组，这组租金翻倍！", "group", p.id)
        event("group") {
            put("pid", p.id)
            put("group", group)
            put("name", name)
            put("text", "${p.name} 凑齐「$name」整组，租金翻倍")
        }
    }

    fun endTurn(pid: Int) {
        need(pid, "buy")
        advanceTurn()
        bump()
    }

    fun advanceTurn() {
        val alive = players.filter { it.alive }
        if (alive.size <= 1) {
            phase = "over"
            winner = alive.firstOrNull()?.id
            if (alive.isNotEmpty()) say("${alive[0].name} 成为最后的赢家", "over", alive[0].id)
            return
        }
        val idx = players.indexOfFirst { it.id == current }
        if (idx < 0) return
        for (step in 1..players.size) {
            val nxt = players[(idx + step) % players.size]
            if (nxt.alive) {
                current = nxt.id
                break
            }
        }
        phase = "handoff"
        pending = null
        chance = null
        settles.clear()
        land = null
        lastSteps = 0
        settleLead = 0.0
        settleViewed = false
        chanceViewed = false
        buyViewed = false
        handoffViewed = false
        handoffAck = false
        skipRollDelay = false
        turnNo += 1
        for (x in players) {
            x.aiPlan = null          // 上一回合的计划作废，别被下一次存档带进新回合
            x.aiPlanTurn = null
        }
        say("轮到 " + (player(current)?.name ?: "?"), "turn", current)
    }

    fun need(pid: Int, expectPhase: String) {
        if (phase != expectPhase) throw IllegalStateException("现在不能做这个操作")
        if (current != pid) throw IllegalStateException("还没轮到你")
    }

    // ---------------------------------------------------------------- 租金 / 色组

    fun rentAt(tile: Tile): Int {
        val lv = tile.level.coerceIn(0, Rules.LEVELS.size - 1)
        return Math.round(tile.rent * Rules.LEVELS[lv].rent).toInt()
    }

    fun upgradeCost(tile: Tile): Int? {
        val lv = tile.level
        if (lv >= Rules.LEVELS.size - 1) return null
        return Math.round(tile.price * Rules.UPGRADE_RATE[lv]).toInt()
    }

    /** 租金 = 原租金 × 等级倍率，整组集齐再翻一倍。 */
    fun rentWithLevel(tile: Tile, level: Int? = null): Int {
        val lv = (level ?: tile.level).coerceIn(0, Rules.LEVELS.size - 1)
        var value = Math.round(tile.rent * Rules.LEVELS[lv].rent).toInt()
        val owner = tile.owner
        if (owner != null && ownsSet(owner, tile.group)) value *= 2
        return value
    }

    fun rentOf(tile: Tile): Int = rentWithLevel(tile)

    fun rentOfNext(tile: Tile): Int {
        val lv = minOf(tile.level + 1, Rules.LEVELS.size - 1)
        return rentWithLevel(tile, lv)
    }

    fun maxRent(): Int {
        val rents = board.filter { it.type == "prop" && it.owner != null }.map { rentOf(it) }
        return rents.maxOrNull() ?: 40
    }

    fun groupMates(pid: Int, group: Int?): List<Tile> =
        board.filter { it.type == "prop" && it.group == group && it.owner == pid }

    fun groupTotal(group: Int?): Int =
        board.count { it.type == "prop" && it.group == group }

    /** 整组（同色系所有地块）是不是都在一个人手里。 */
    fun ownsSet(pid: Int?, group: Int?): Boolean {
        if (pid == null || group == null) return false
        val tiles = board.filter { it.type == "prop" && it.group == group }
        return tiles.isNotEmpty() && tiles.all { it.owner == pid }
    }

    // ---------------------------------------------------------------- 人机调度

    fun botCfg(p: Player): Rules.BotLevel =
        Rules.BOT_LEVELS[p.botLevel] ?: Rules.BOT_LEVELS["normal"]!!

    /** 轮到电脑（或电脑有交易要表态）时排一次延迟动作；任何状态变化都会重排。 */
    fun scheduleBot() {
        botTimer?.cancel(false)
        botTimer = null
        if (phase == "lobby" || phase == "setup" || phase == "over") return
        if (phase == "handoff") {
            // 先等前端把上一回合的弹窗 / 动画清空；没有页面回报时用短兜底
            val v = version
            val cur = current
            arm(Rules.HANDOFF_FALLBACK) { finishHandoff(v, cur) }
            return
        }
        // 交易不跟着回合走，先处理
        for (tr in trades) {
            if (tr.asked) continue
            val target = player(tr.to)
            if (target != null && target.kind == "bot" && target.alive) {
                tr.asked = true
                // 交易用自己的 timer：回合推进时 scheduleBot 会 cancel 掉 botTimer，
                // 用同一个的话「AI 提完交换就结束回合」会把刚排上的应答直接取消掉。
                armTrade(tr.id)
                return
            }
        }
        val cur = player(current) ?: return
        if (cur.kind != "bot" || !cur.alive) return
        if (cur.thinking) return            // AI 还在算，等它自己推进，别排重复动作
        if (phase != "roll" && phase != "settle" && phase != "buy" && phase != "chance") return
        var delay = when (phase) {
            "settle" -> {
                // 等前端把骰子 + 走格动画播完、结算窗真正出现，再停留 2 秒
                var d = Rules.BOT_DELAY_SEC + Rules.SETTLE_SHOW_BUFFER + maxOf(0.0, settleLead)
                if (!settleViewed) d += Rules.POPUP_FALLBACK_EXTRA
                d
            }
            "chance" -> {
                var d = Rules.BOT_DELAY_SEC + Rules.SETTLE_SHOW_BUFFER + maxOf(0.0, settleLead)
                if (!chanceViewed) d += Rules.POPUP_FALLBACK_EXTRA
                d
            }
            "buy" -> {
                // 地契卡要等骰子 + 走格动画播完才出现；出现后电脑再停留 2 秒做决定
                val animLead = if (lastSteps != 0) Rules.BOT_MOVE_LEAD + Rules.BOT_STEP_ANIM * minOf(lastSteps, 12) else 0.0
                var d = Rules.BOT_DELAY_SEC + Rules.SETTLE_SHOW_BUFFER + animLead
                if (!buyViewed) d += Rules.POPUP_FALLBACK_EXTRA
                d
            }
            else -> Rules.BOT_DELAY_SEC
        }
        if (phase == "buy" && cur.brain == "ai" && brain.ok) {
            delay += 0.4               // AI 要多等一小会儿，前端才来得及显示「正在思考」
        }
        val v = version
        val pid = current!!
        val ph = phase
        arm(delay) { botStep(v, pid, ph) }
    }

    /** 排一个定时动作（会替换掉上一个 botTimer，和原版 _arm 一样）。 */
    fun arm(delaySec: Double, action: () -> Unit) {
        botTimer?.cancel(false)
        botTimer = scheduler.schedule(
            { action() },
            (delaySec * 1000).toLong().coerceAtLeast(0L),
            TimeUnit.MILLISECONDS,
        )
    }

    /** 电脑回应交换申请：独立 timer，不受回合推进时对 botTimer 的 cancel 影响。 */
    fun armTrade(tradeId: Int) {
        tradeTimer?.cancel(false)
        val v = version
        tradeTimer = scheduler.schedule(
            { botTradeStep(v, tradeId) },
            1000L,
            TimeUnit.MILLISECONDS,
        )
    }

    fun botStep(versionAtArm: Int, pid: Int, phaseAtArm: String) {
        var useAi = false
        var turnForAi = 0
        lock.lock()
        try {
            if (versionAtArm != version || current != pid || phase != phaseAtArm) return
            val p = player(pid) ?: return
            if (p.kind != "bot" || !p.alive) return
            val isAi = p.brain == "ai" && brain.ok
            if (phaseAtArm == "buy" && isAi) {
                // 落地之后才问模型：这是这一回合唯一一次接口调用
                useAi = true
                turnForAi = turnNo
            } else if (phaseAtArm == "roll") {
                // AI 的换地计划是上一回合落地后写好的，在自己掷骰前发出去
                if (isAi) {
                    val intent = p.aiTradeNext
                    if (intent != null) {
                        p.aiTradeNext = null
                        try {
                            aiTrade(p, intent, false)
                        } catch (e: Exception) {
                            p.aiTradeNext = null
                        }
                    }
                }
                localStep(pid, "roll")
            } else {
                localStep(pid, phaseAtArm)
            }
        } finally {
            lock.unlock()
        }
        if (useAi) {
            // AI 要发 HTTP，必须在锁外做，否则整局（含 SSE）都会被卡住
            runAiTurn(pid, turnForAi)
        }
    }

    /** 人机（本地启发式）走一步；调用方持锁。 */
    fun localStep(pid: Int, phaseName: String) {
        val p = player(pid) ?: return
        val why = ArrayList<String>()
        try {
            when (phaseName) {
                "roll" -> roll(pid)
                "settle" -> settleOk(pid)          // 电脑也走「确认」这条路，只是自动点
                "chance" -> chanceOk(pid)
                "buy" -> {
                    val tileI = pending ?: throw IllegalStateException("现在没有待处理的落点")
                    val tile = board[tileI]
                    if (tile.owner == pid) {
                        val ok = botTryUpgrade(p, why)
                        botSay(p, why)
                        if (!ok) passBuy(pid)
                    } else {
                        val want = botWantsBuy(p, tile, why)
                        botSay(p, why)          // 先说话再动手，保证同一帧里「说的话」在「做的事」之前
                        if (want) buy(pid) else passBuy(pid)
                    }
                }
            }
        } catch (e: IllegalStateException) {
            // 决策撞上规则（钱不够之类）：保守收尾，别把局势卡死
            try {
                if (phase == "buy") passBuy(pid)
                else if (phase == "settle") settleOk(pid)
            } catch (ignored: Exception) {
            }
        }
    }

    /** 电脑的一句话决策说明（人机的算法原因，AI 的 why 由 runAiTurn 负责）。 */
    fun botSay(p: Player, why: List<String>) {
        val text = (why.firstOrNull() ?: "").trim()
        if (text.isEmpty()) return
        say("${p.name}：${text.take(60)}", "ai", p.id)
    }

    // ---------------------------------------------------------------- AI 回合

    /** AI 思考期间局面可能被房主暂停 / 重开，回来时先确认这一回合还算数。 */
    private fun aiAlive(pid: Int, turn: Int): Boolean =
        phase == "buy" && current == pid && pending != null && turnNo == turn && frozenSnap == null

    /** 落地之后喂给模型的最小局面：现在只需要决定「买不买 / 升不升」，换地写进下回合。 */
    fun aiTurnContext(me: Player): String {
        val lines = ArrayList<String>()
        val alive = players.filter { it.alive }
        lines.add(
            "【第 $turnNo 回合 · 已落地】场上还有 ${alive.size} 人，你刚走到落点，程序等你决定这一回合收尾。"
        )
        lines.add("【你】${me.name}，现金 ${me.money}，地契 ${propsOf(me.id).size} 块。")
        lines.add(
            "【掷骰】${dice[0]} + ${dice[1]}，前进 ${dice[0] + dice[1]} 格，落在「${board[me.pos].name}」。"
        )

        val tile = board[pending ?: me.pos]
        if (tile.owner == null) {
            lines.add(
                "【落点】无主地产「${tile.name}」，买价 ${tile.price}，原租金 ${tile.rent}，色组" +
                    "${tile.group?.let { Rules.GROUP_NAMES.getOrElse(it) { "?" } } ?: "?"}；买完你还剩 ${me.money - tile.price} 金币。"
            )
            lines.add("【这次的决策】buy.want 决定买不买，maxPrice 是你愿出的上限；land 这次用不上（随便填合法值即可）。")
        } else {
            val cost = upgradeCost(tile)
            val fresh = tile.boughtTurn == turnNo
            val lv = Rules.LEVELS[tile.level].name
            lines.add(
                "【落点】你自己的「${tile.name}」，现在$lv，租金 ${rentOf(tile)}；升级费 " +
                    "${if (cost == null) "—（已满级）" else cost.toString()}，升完租金 " +
                    "${if (cost == null) rentOf(tile) else rentOfNext(tile)}" +
                    "${if (fresh) "；本回合刚买，不能升" else ""}。"
            )
            lines.add("【这次的决策】land.upgrade 决定升不升，maxCost 是你愿付的上限；buy 这次用不上（随便填合法值即可）。")
        }

        val mine = propsOf(me.id).map { board[it] }
        if (mine.isNotEmpty()) {
            val bits = mine.map {
                "${it.name}(色组${it.group?.let { g -> Rules.GROUP_NAMES.getOrElse(g) { "?" } } ?: "?"} " +
                    "${Rules.LEVELS[it.level].name} 租${rentOf(it)})"
            }
            lines.add("【你的地契】" + bits.joinToString("；"))
        }

        val rivals = alive.filter { it.id != me.id }
        if (rivals.isNotEmpty()) {
            val rb = rivals.map { x ->
                val props = propsOf(x.id).map { board[it] }
                val top = props.map { rentOf(it) }.maxOrNull() ?: 0
                "${x.name} 现金${x.money} 地契${props.size} 最贵租$top"
            }
            lines.add("【对手】" + rb.joinToString("；"))
        }
        lines.add("【场上最贵租金】${maxRent()}（踩上去要付这么多）")

        // 换地材料：下一回合开局才发，所以现在就把目标挑好
        val othersLine = ArrayList<String>()
        for (x in rivals) {
            val props = propsOf(x.id).map { board[it] }
            if (props.isNotEmpty()) {
                othersLine.add(
                    "${x.name} 有 " + props.joinToString("、") {
                        "${it.name}(色组${it.group?.let { g -> Rules.GROUP_NAMES.getOrElse(g) { "?" } } ?: "?"} 租${rentOf(it)})"
                    }
                )
            }
        }
        val myNames = if (mine.isEmpty()) "还没有" else mine.joinToString("、") {
            "${it.name}(色组${it.group?.let { g -> Rules.GROUP_NAMES.getOrElse(g) { "?" } } ?: "?"})"
        }
        lines.add("【换地·下回合开局才发】你的地：$myNames")
        if (othersLine.isNotEmpty()) lines.add("【别人的地】" + othersLine.joinToString("；"))
        if (me.aiTrades > 0) lines.add("【已主动换地】${me.aiTrades} 笔（一局最多 ${Rules.AI_TRADE_MAX} 笔）")

        if (me.memory.isNotEmpty()) lines.add("【你的笔记与计划】${me.memory}")
        else lines.add("【你的笔记与计划】（还没有，这一回合自己写一条）")

        val events = aiRecent(me)
        if (events.isNotEmpty()) lines.add("【最近流水】" + events.joinToString(" / "))
        lines.add(
            "【要你做的事】只决定落点这一件事：把 buy / land 填好（用不上的那个也填个合法值）。" +
                "trade 可选，写了会在你下回合开局、掷骰之前发出去，不会在现在执行。"
        )
        return lines.joinToString("\n")
    }

    /** 挑与该玩家有关的最近 10 条日志，给它「记仇」的事实依据。 */
    fun aiRecent(p: Player): List<String> {
        val names = players.map { it.name }
        val out = ArrayList<String>()
        for (row in log.reversed()) {
            if (row.id > 0 && (row.pid == p.id || row.text.contains(p.name) ||
                    names.any { row.text.contains(it) })
            ) {
                out.add(row.text)
            }
            if (out.size >= 10) break
        }
        return out.reversed()
    }

    fun moneyAfterBuy(p: Player, tile: Tile): Int = p.money - tile.price

    /** 计划里的数字字段：允许数字或数字字符串，空值表示「不设上限」。 */
    private fun planInt(value: Any?, label: String): Int? {
        if (value == null || value == JSONObject.NULL || value == "") return null
        if (value is Boolean) throw IllegalArgumentException("$label 要填数字，不要填 true/false")
        val n = when (value) {
            is Number -> value.toDouble()
            is String -> value.trim().toDoubleOrNull()
                ?: throw IllegalArgumentException("$label 要填数字（$value 看不懂）")
            else -> throw IllegalArgumentException("$label 要填数字（$value 看不懂）")
        }
        val i = n.toInt()
        if (i < 0) throw IllegalArgumentException("$label 不能是负数")
        return i
    }

    /** 校验并规整整回合计划；结构不对就抛异常（外层会重试，三次不行交回人机）。 */
    fun aiPlanNorm(plan: JSONObject): JSONObject {
        var buy: Any? = plan.opt("buy")
        if (buy is String) {
            buy = JSONObject().put(
                "want",
                listOf("buy", "true", "1", "买", "买下").contains(buy.trim().lowercase()),
            )
        }
        if (buy !is JSONObject) throw IllegalArgumentException("缺少 buy，应为 {\"want\":true/false,\"maxPrice\":数字}")
        val wantVal = buy.opt("want")
        if (wantVal !is Boolean) throw IllegalArgumentException("buy.want 必须是 true 或 false")
        val maxPrice = planInt(buy.opt("maxPrice"), "buy.maxPrice")

        var landV: Any? = plan.opt("land")
        if (landV is String) {
            landV = JSONObject().put(
                "upgrade",
                listOf("upgrade", "true", "1", "升级").contains(landV.trim().lowercase()),
            )
        }
        if (landV !is JSONObject) throw IllegalArgumentException("缺少 land，应为 {\"upgrade\":true/false,\"maxCost\":数字}")
        val upVal = landV.opt("upgrade")
        if (upVal !is Boolean) throw IllegalArgumentException("land.upgrade 必须是 true 或 false")
        val maxCost = planInt(landV.opt("maxCost"), "land.maxCost")

        var trade: JSONObject? = null
        val tradeRaw = plan.opt("trade")
        if (!(tradeRaw == null || tradeRaw == JSONObject.NULL || tradeRaw == "" ||
                tradeRaw == "none" || tradeRaw == "null" ||
                (tradeRaw is JSONObject && tradeRaw.length() == 0))
        ) {
            if (tradeRaw !is JSONObject) throw IllegalArgumentException("trade 要么省略，要么写成对象")
            val target = Json.optString(tradeRaw, "target").trim()
            if (target.isEmpty()) throw IllegalArgumentException("trade.target 要写对方名字")
            val wantTile = Json.optString(tradeRaw, "want").trim()
            val giveTile = Json.optString(tradeRaw, "give").trim()
            if (wantTile.isEmpty() && giveTile.isEmpty()) {
                throw IllegalArgumentException("trade 至少要有 want（你想要对方哪块地）")
            }
            val cash = planInt(tradeRaw.opt("giveCash"), "trade.giveCash") ?: 0
            trade = JSONObject()
                .put("target", target)
                .put("want", wantTile.ifEmpty { JSONObject.NULL })
                .put("give", giveTile.ifEmpty { JSONObject.NULL })
                .put("giveCash", cash)
        }
        return JSONObject()
            .put("buy", JSONObject().put("want", wantVal).put("maxPrice", maxPrice ?: JSONObject.NULL))
            .put("land", JSONObject().put("upgrade", upVal).put("maxCost", maxCost ?: JSONObject.NULL))
            .put("trade", trade ?: JSONObject.NULL)
    }

    fun aiPlanValid(p: Player): Boolean = p.aiPlan != null && p.aiPlanTurn == turnNo

    /** 按「落地后」拿到的计划做这一回合剩下的决定；调用方持锁。计划走不通就保守收尾。 */
    fun aiPlanStep(pid: Int, phaseName: String = "buy") {
        val p = player(pid) ?: return
        val plan = p.aiPlan
        if (plan == null || !aiPlanValid(p)) {
            localStep(pid, phaseName)
            return
        }
        try {
            if (phaseName != "buy" || pending == null) throw IllegalStateException("现在没有要做的落点决策")
            val tile = board[pending!!]
            if (tile.owner == null) {
                val b = plan.optJSONObject("buy") ?: JSONObject()
                val cap = Json.optIntOrNull(b, "maxPrice")
                val ok = (b.optBoolean("want", false) && p.money >= tile.price &&
                    (cap == null || tile.price <= cap))
                if (ok) buy(pid) else passBuy(pid)
                return
            }
            if (tile.owner == pid) {
                val l = plan.optJSONObject("land") ?: JSONObject()
                val cost = upgradeCost(tile)
                val fresh = tile.boughtTurn == turnNo
                val cap = Json.optIntOrNull(l, "maxCost")
                if (l.optBoolean("upgrade", false) && cost != null && !fresh &&
                    p.money >= cost && (cap == null || cost <= cap)
                ) {
                    upgrade(pid, tile.i)
                } else {
                    passBuy(pid)
                }
                return
            }
            throw IllegalStateException("落点不是无主地也不是自己的地")
        } catch (e: Exception) {
            try {
                if (phase == "buy") passBuy(pid)
            } catch (ignored: Exception) {
            }
        }
    }

    fun tileByName(name: String?): Tile? {
        val key = (name ?: "").trim()
        if (key.isEmpty()) return null
        return board.firstOrNull { it.type == "prop" && it.name == key }
    }

    /** AI 主动发起换地：名字 → 校验 → tradeOffer。默认不结束回合（在自己掷骰前执行）。 */
    fun aiTrade(p: Player, intent: JSONObject, endTurn: Boolean = false) {
        if (p.aiTrades >= Rules.AI_TRADE_MAX) {
            throw IllegalStateException("你这局已经主动谈过 ${Rules.AI_TRADE_MAX} 笔换地了")
        }
        val others = players.filter { it.alive && it.id != p.id }
        val targetName = Json.optString(intent, "target").trim()
        val other = others.firstOrNull { it.name == targetName }
            ?: throw IllegalStateException(
                "target 必须是场上其他玩家的名字，现在只有：" + others.joinToString("、") { it.name }
            )

        val want = tileByName(if (intent.isNull("want")) null else intent.optString("want"))
        val give = tileByName(if (intent.isNull("give")) null else intent.optString("give"))
        if (want == null && give == null) throw IllegalStateException("trade 至少要有 want（你想要对方哪块地）")
        if (want != null && want.owner != other.id) throw IllegalStateException("「${want.name}」不是${other.name}的地")
        if (give != null && give.owner != p.id) throw IllegalStateException("「${give.name}」不是你的地")
        var cash = 0
        try {
            cash = maxOf(0, Json.optIntOrNull(intent, "giveCash") ?: 0)
        } catch (e: Exception) {
            cash = 0
        }
        if (cash > p.money) throw IllegalStateException("你只有 ${p.money} 金币，给不出 $cash")
        tradeOffer(
            p.id,
            JSONObject()
                .put("target", other.id)
                .put("give", Json.arrInts(if (give != null) listOf(give.i) else emptyList()))
                .put("want", Json.arrInts(if (want != null) listOf(want.i) else emptyList()))
                .put("giveCash", cash)
                .put("wantCash", 0),
        )
        p.aiTrades += 1
        if (endTurn) advanceTurn()
    }

    /** 落地之后一次接口：拿到这一回合剩余打算 → 存起来 → 立刻执行买 / 升，然后自动交棒。 */
    fun runAiTurn(pid: Int, turn: Int) {
        var raw = ""
        var reason = ""
        lock.lock()
        try {
            if (!aiAlive(pid, turn)) return
            val p = player(pid) ?: return
            p.thinking = true
            bump()                 // 推一帧，前端立刻显示「AI 正在思考…」
        } finally {
            lock.unlock()
        }

        var finished = false
        try {
            for (attempt in 1..Rules.AI_RETRY) {
                var ctx = ""
                var level = "normal"
                lock.lock()
                try {
                    if (!aiAlive(pid, turn)) return
                    val cur = player(pid) ?: return
                    ctx = aiTurnContext(cur)
                    level = cur.botLevel
                } finally {
                    lock.unlock()
                }
                val feedback = if (reason.isEmpty()) "" else
                    "（提醒）你上一次回的是 ${raw.take(120)}，被系统打回：$reason。请重新给一份合法的落地计划。"
                val result = brain.decidePlan(ctx, level, feedback)
                val plan = result.plan
                if (plan == null) {
                    if (raw.isEmpty()) raw = result.error
                    reason = result.error
                    if (result.kind == "net") break   // 网络不通超时，别纠缠，直接交回人机
                    continue
                }
                raw = plan.toString().take(300)
                lock.lock()
                try {
                    if (!aiAlive(pid, turn)) return
                    val cur = player(pid) ?: return
                    val norm = try {
                        aiPlanNorm(plan)
                    } catch (e: IllegalArgumentException) {
                        reason = e.message ?: "计划不合法"
                        null
                    }
                    if (norm == null) {
                        // 继续下一次尝试
                    } else {
                        cur.thinking = false
                        cur.aiPlan = norm
                        cur.aiPlanTurn = turn
                        // 换地声明留到下一回合开局：现在先存起来，掷骰前由 botStep 代发
                        cur.aiTradeNext = if (norm.isNull("trade")) null else norm.optJSONObject("trade")
                        val mem = brain.clampMemory(plan.optString("memory"))
                        if (mem.isNotEmpty()) cur.memory = mem
                        val why = plan.optString("why")
                        if (why.isNotEmpty()) {
                            // 先写日志再动手：这样「说的话」和动作在同一帧里，前端不会错位
                            say("${cur.name}：${why.take(60)}", "ai", cur.id)
                        }
                        aiPlanStep(pid, "buy")   // 买 / 升 / 放弃，执行完这一回合自动结束
                        finished = true
                        return
                    }
                } finally {
                    lock.unlock()
                }
            }
        } finally {
            if (!finished) clearThinking(pid)
        }

        // 三次都不合法（或接口挂了）→ 交回人机，并把这次对话记到日志
        lock.lock()
        try {
            if (!aiAlive(pid, turn)) return
            val cur = player(pid) ?: return
            cur.thinking = false
            say(
                "AI「${cur.name}」这回没给出可用的计划，已交回人机接手（${(reason.ifEmpty { "接口不可用" }).take(80)}）",
                "ai", cur.id,
            )
            logAiFail(cur, "land", raw, reason)
            localStep(pid, "buy")
        } finally {
            lock.unlock()
        }
    }

    fun clearThinking(pid: Int) {
        lock.lock()
        try {
            val p = player(pid)
            if (p != null && p.thinking) {
                p.thinking = false
                bump()
            }
        } finally {
            lock.unlock()
        }
    }

    /** 失败样本落盘，方便以后拿真实案例调提示词。 */
    fun logAiFail(p: Player, phaseName: String, raw: String, reason: String) {
        val item = JSONObject().apply {
            put("time", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            put("game", gameId ?: JSONObject.NULL)
            put("pid", p.id)
            put("name", p.name)
            put("level", p.botLevel)
            put("model", brain.model)
            put("phase", phaseName)
            put("returned", raw)
            put("reason", reason)
        }
        try {
            Store.Log.append(item.toString() + "\n")
        } catch (e: Exception) {
            // 写不进日志不影响对局
        }
    }

    fun botTradeStep(versionAtArm: Int, tradeId: Int) {
        lock.lock()
        try {
            // 不看 version：发起方可能在 tradeOffer 之后立刻还有动作（AI 提完交换就结束回合），
            // 那一次 bump 会改掉 version，但「这笔交换还在等电脑回应」这件事本身没变。
            val trade = trades.firstOrNull { it.id == tradeId } ?: return
            val p = player(trade.to) ?: return
            if (p.kind != "bot") return
            try {
                val (ok, why) = botTradeEval(p, trade)
                tradeAnswer(p.id, tradeId, ok, why)
            } catch (e: IllegalStateException) {
                // 交换已经失效：忽略
            }
        } finally {
            lock.unlock()
        }
    }

    // ---------------------------------------------------------------- 交换

    fun tradeNames(ids: List<Int>): String = ids.joinToString("、") { board[it].name }

    fun tradeOffer(pid: Int, payload: JSONObject) {
        lock.lock()
        try {
            if (phase == "lobby" || phase == "over" || phase == "setup") throw IllegalStateException("现在不能交换")
            if (current != pid || phase != "roll") throw IllegalStateException("只能在自己回合、掷骰之前发起交换")
            val me = player(pid)
            if (me == null || !me.alive) throw IllegalStateException("你已出局")
            val target = payload.optInt("target", 0)
            val give = try {
                Json.readTileIds(if (payload.has("give")) payload.opt("give") else null)
            } catch (e: IllegalArgumentException) {
                throw IllegalStateException("交换参数不对")
            }
            val want = try {
                Json.readTileIds(if (payload.has("want")) payload.opt("want") else null)
            } catch (e: IllegalArgumentException) {
                throw IllegalStateException("交换参数不对")
            }
            val giveCash = maxOf(0, Json.optIntOrNull(payload, "giveCash") ?: 0)
            val wantCash = maxOf(0, Json.optIntOrNull(payload, "wantCash") ?: 0)

            val other = player(target)
            if (other == null || !other.alive || other.id == pid) throw IllegalStateException("对方不可交易")
            for (i in give) {
                if (board[i].type != "prop" || board[i].owner != pid) {
                    throw IllegalStateException("「${board[i].name}」不是你的地")
                }
            }
            for (i in want) {
                if (board[i].type != "prop" || board[i].owner != target) {
                    throw IllegalStateException("「${board[i].name}」不是对方的")
                }
            }
            if (give.isEmpty() && want.isEmpty() && giveCash == 0 && wantCash == 0) {
                throw IllegalStateException("交换内容为空")
            }
            if (giveCash > me.money) throw IllegalStateException("你拿不出这么多现金")
            if (wantCash > other.money) throw IllegalStateException("对方拿不出这么多现金")
            if (trades.any { it.from == pid }) throw IllegalStateException("你已经有一笔交换在等回应了")

            tradeSeq += 1
            trades.add(Trade(tradeSeq, pid, target, give, want, giveCash, wantCash))
            say("${me.name} 向 ${other.name} 发起交换", "trade", pid)
            event("trade_offer") {
                put("pid", pid)
                put("text", "${me.name} 向 ${other.name} 发起交换")
                put(
                    "trade", JSONObject()
                        .put("from", me.name)
                        .put("to", other.name)
                        .put("give", tradeNames(give))
                        .put("want", tradeNames(want))
                        .put("giveCash", giveCash)
                        .put("wantCash", wantCash)
                )
            }
            bump()
        } finally {
            lock.unlock()
        }
    }

    fun tradeAnswer(pid: Int, tradeId: Int, accept: Boolean, reason: String = "") {
        lock.lock()
        try {
            val trade = trades.firstOrNull { it.id == tradeId } ?: throw IllegalStateException("没有这个交换申请")
            if (trade.to != pid) throw IllegalStateException("没有这个交换申请")
            val me = player(pid)
            val other = player(trade.from)
            if (me == null || other == null) {
                trades.remove(trade)
                throw IllegalStateException("交换双方已经不在了")
            }
            if (accept) {
                if (other.money < trade.giveCash || me.money < trade.wantCash) {
                    trades.remove(trade)
                    throw IllegalStateException("现金不足，交换失效")
                }
                val before = mapOf(
                    me.id to groupSetIds(me.id),
                    other.id to groupSetIds(other.id),
                )
                other.money -= trade.giveCash
                me.money += trade.giveCash
                me.money -= trade.wantCash
                other.money += trade.wantCash
                for (i in trade.give) {
                    board[i].owner = pid
                    board[i].boughtTurn = turnNo
                }
                for (i in trade.want) {
                    board[i].owner = trade.from
                    board[i].boughtTurn = turnNo
                }
                say("${me.name} 接受了交换" + (if (reason.isNotEmpty()) "：$reason" else ""), "trade", pid)
                event("trade") {
                    put("pid", pid)
                    put("text", "${me.name} 与 ${other.name} 成交")
                    put(
                        "trade", JSONObject()
                            .put("from", other.name)
                            .put("to", me.name)
                            .put("give", tradeNames(trade.give))
                            .put("want", tradeNames(trade.want))
                            .put("giveCash", trade.giveCash)
                            .put("wantCash", trade.wantCash)
                    )
                }
                // 打包交换可能一次凑齐好几组：逐组比对新旧，发恭喜
                for (player in listOf(me, other)) {
                    val groups = propsOf(player.id).mapNotNull { board[it].group }.toSet()
                    for (g in groups) checkGroupComplete(player, g, before[player.id] ?: emptySet())
                }
            } else {
                say("${me.name} 拒绝了交换" + (if (reason.isNotEmpty()) "：$reason" else ""), "trade", pid)
            }
            trades.remove(trade)
            bump()
        } finally {
            lock.unlock()
        }
    }

    // ---------------------------------------------------------------- 座位 / 存档

    fun seatList(): JSONArray {
        val claimed = tokens.values.toSet()
        val out = JSONArray()
        for (p in players) {
            val isBot = p.kind == "bot"
            out.put(
                JSONObject()
                    .put("id", p.id)
                    .put("name", p.name)
                    .put("char", p.char)
                    .put("money", p.money)
                    .put("alive", p.alive)
                    .put("props", propsOf(p.id).size)
                    .put("claimed", if (isBot) true else claimed.contains(p.id))
                    .put("kind", p.kind)
                    .put("level", p.botLevel)
            )
        }
        return out
    }

    fun claimSeat(pid: Int): String {
        lock.lock()
        try {
            if (phase == "lobby") throw IllegalStateException("还没开局，直接加入房间就行")
            val p = player(pid) ?: throw IllegalStateException("没有这个座位")
            if (p.kind == "bot") throw IllegalStateException("这是电脑玩家，不用认领")
            if (tokens.values.contains(pid)) throw IllegalStateException("这个座位已经有人坐下了")
            val token = newToken()
            tokens[token] = pid
            if (hostToken == null) hostToken = token     // 第一个回到座位的人当房主
            say("${p.name} 认领了座位", "join", pid)
            bump()
            return token
        } finally {
            lock.unlock()
        }
    }

    fun loadFromDb(gameIdIn: Long): JSONArray {
        lock.lock()
        try {
            if (phase != "lobby" && phase != "setup" && gameId != gameIdIn) {
                throw IllegalStateException("现在还有一局在进行，先结束它再载入别的存档")
            }
            // 同一局已经被载入过、而且已经有人认领了座位：不能再 restore
            if (gameId == gameIdIn && tokens.isNotEmpty()) return seatList()
            val row = Store.getGame(gameIdIn) ?: throw IllegalStateException("这个存档不存在")
            val snapRaw = row.optString("snapshot", "")
            val snap = try {
                JSONObject(snapRaw.ifEmpty { "{}" })
            } catch (e: Exception) {
                throw IllegalStateException("存档内容损坏，读不出来")
            }
            if (snap.length() == 0 || !snap.has("players") || snap.optJSONArray("players")?.length() == 0) {
                throw IllegalStateException("这局还没开始过，没有可载入的局面")
            }
            restore(snap, gameIdIn)
            return seatList()
        } finally {
            lock.unlock()
        }
    }

    // ---------------------------------------------------------------- 存档序列化

    fun snapshot(): JSONObject {
        val playersArr = JSONArray()
        for (p in players) {
            playersArr.put(
                JSONObject()
                    .put("id", p.id)
                    .put("name", p.name)
                    .put("char", p.char)
                    .put("pos", p.pos)
                    .put("money", p.money)
                    .put("alive", p.alive)
                    .put("kind", p.kind)
                    .put("bot_level", p.botLevel)
                    .put("brain", p.brain)
                    .put("memory", p.memory)
                    .put("thinking", p.thinking)
                    .put("ai_plan", p.aiPlan ?: JSONObject.NULL)
                    .put("ai_plan_turn", p.aiPlanTurn ?: JSONObject.NULL)
                    .put("ai_trade_next", p.aiTradeNext ?: JSONObject.NULL)
                    .put("ai_trades", p.aiTrades)
            )
        }
        return JSONObject()
            .put("players", playersArr)
            .put("board", boardJson())
            .put("phase", phase)
            .put("current", current ?: JSONObject.NULL)
            .put("host", host ?: JSONObject.NULL)
            .put("dice", JSONArray().put(dice[0]).put(dice[1]))
            .put("pending", pending ?: JSONObject.NULL)
            .put("chance", chance?.let { chanceJson(it) } ?: JSONObject.NULL)
            .put("settles", settlesJson())
            .put("land", land?.let { JSONObject().put("kind", it.kind).put("tile", it.tile) } ?: JSONObject.NULL)
            .put("log", logJson(log))
            .put("log_seq", logSeq)
            .put("news", news.o)
            .put("news_seq", newsSeq)
            .put("trades", tradesJson(trades))
            .put("trade_seq", tradeSeq)
            .put("winner", winner ?: JSONObject.NULL)
            .put("version", version)
            .put("turn_no", turnNo)
            .put("start_money", startMoney)
            .put("bot_level", botLevel)
            .put("frozen", frozenSnap ?: JSONObject.NULL)
    }

    fun summary(): String {
        val arr = JSONArray()
        for (p in players) {
            arr.put(
                JSONObject().put("name", p.name).put("char", p.char)
                    .put("money", p.money).put("kind", p.kind)
            )
        }
        return JSONObject()
            .put("players", arr)
            .put("current", current ?: JSONObject.NULL)
            .put("round", logSeq)
            .put("bots", players.count { it.kind == "bot" })
            .toString()
    }

    fun persist() {
        val gid = gameId ?: return
        if (phase == "lobby") return
        if (!dbOk) return
        val status = if (phase == "over") "over" else "playing"
        try {
            Store.saveGame(gid, phase, status, summary(), snapshot().toString())
        } catch (e: Exception) {
            // 存档失败不影响继续玩
            dbOk = false
        }
    }

    /** resume 用：只把局面字段搬回来（登录态由调用方自己保留）。 */
    fun restoreFields(snap: JSONObject) {
        players.clear()
        val parr = snap.optJSONArray("players") ?: JSONArray()
        for (i in 0 until parr.length()) players.add(playerFromJson(parr.getJSONObject(i)))
        boardFromJson(snap.optJSONArray("board"))
        phase = snap.optString("phase", "roll")
        current = if (snap.isNull("current")) null else snap.optInt("current")
        val d = snap.optJSONArray("dice")
        dice = if (d != null && d.length() >= 2) intArrayOf(d.optInt(0), d.optInt(1)) else intArrayOf(0, 0)
        pending = if (snap.isNull("pending")) null else snap.optInt("pending")
        log.clear()
        val larr = snap.optJSONArray("log") ?: JSONArray()
        for (i in 0 until larr.length()) {
            val o = larr.getJSONObject(i)
            log.add(
                LogEntry(
                    o.optInt("id"), o.optString("kind", "sys"),
                    if (o.isNull("pid")) null else o.optInt("pid"),
                    o.optString("text"),
                )
            )
        }
        logSeq = snap.optInt("log_seq", logSeq)
        trades.clear()
        val tarr = snap.optJSONArray("trades") ?: JSONArray()
        for (i in 0 until tarr.length()) trades.add(tradeFromJson(tarr.getJSONObject(i)))
        winner = if (snap.isNull("winner")) null else snap.optInt("winner")
    }

    fun restore(snap: JSONObject, gameIdIn: Long) {
        lock.lock()
        try {
            players.clear()
            val parr = snap.optJSONArray("players") ?: JSONArray()
            for (i in 0 until parr.length()) {
                val p = playerFromJson(parr.getJSONObject(i))
                p.aiPlan = null
                p.thinking = false      // 存档里不该留「思考中」，载入后一律清掉
                players.add(p)
            }
            boardFromJson(snap.optJSONArray("board"))
            phase = snap.optString("phase", "lobby")
            turnNo = snap.optInt("turn_no", 0)
            current = if (snap.isNull("current")) null else snap.optInt("current")
            host = if (snap.isNull("host")) null else snap.optInt("host")
            val d = snap.optJSONArray("dice")
            dice = if (d != null && d.length() >= 2) intArrayOf(d.optInt(0), d.optInt(1)) else intArrayOf(0, 0)
            pending = if (snap.isNull("pending")) null else snap.optInt("pending")
            val ch = snap.optJSONObject("chance")
            chance = if (ch == null) null else PendingChance(
                ch.optString("text"),
                Json.optIntOrNull(ch, "delta"),
                Json.optIntOrNull(ch, "move"),
                Json.optIntOrNull(ch, "goto"),
                ch.optString("icon", "spark"),
                ch.optInt("pid"),
                ch.optString("name"),
            )
            settles.clear()
            val sarr = snap.optJSONArray("settles") ?: JSONArray()
            for (i in 0 until sarr.length()) {
                val o = sarr.getJSONObject(i)
                settles.add(
                    Settle(
                        o.optString("type"), o.optInt("pid"),
                        Json.optIntOrNull(o, "tile"), o.optInt("amount"),
                        Json.optIntOrNull(o, "to"),
                        if (o.isNull("name")) null else o.optString("name"),
                        if (o.isNull("tileName")) null else o.optString("tileName"),
                        if (o.isNull("icon")) null else o.optString("icon"),
                        if (o.isNull("text")) null else o.optString("text"),
                    )
                )
            }
            settleLead = 0.0            // 载入后直接显示弹窗，不再补播移动
            settleViewed = false
            chanceViewed = false
            buyViewed = false
            handoffViewed = false
            handoffAck = false
            skipRollDelay = false
            val landO = snap.optJSONObject("land")
            land = if (landO == null) null else LandAction(landO.optString("kind"), landO.optInt("tile"))
            if (phase == "action") {      // 旧存档没有「结算」这个概念，落到行动阶段就直接交棒
                phase = "roll"
                pending = null
            }
            log.clear()
            val larr = snap.optJSONArray("log") ?: JSONArray()
            for (i in 0 until larr.length()) {
                val o = larr.getJSONObject(i)
                log.add(
                    LogEntry(
                        o.optInt("id"), o.optString("kind", "sys"),
                        if (o.isNull("pid")) null else o.optInt("pid"),
                        o.optString("text"),
                    )
                )
            }
            logSeq = snap.optInt("log_seq", 0)
            news = News(snap.optJSONObject("news") ?: JSONObject().put("seq", 0).put("type", "none"))
            newsSeq = snap.optInt("news_seq", 0)
            newsFrame.clear()
            newsPub = emptyList()
            trades.clear()
            val tarr = snap.optJSONArray("trades") ?: JSONArray()
            for (i in 0 until tarr.length()) trades.add(tradeFromJson(tarr.getJSONObject(i)))
            tradeSeq = snap.optInt("trade_seq", 0)
            winner = if (snap.isNull("winner")) null else snap.optInt("winner")
            startMoney = snap.optInt("start_money", Rules.START_MONEY)
            botLevel = snap.optString("bot_level", "normal")
            frozenSnap = snap.optJSONObject("frozen")
            version = snap.optInt("version", 0) + 1
            tokens.clear()                             // 座位全部空着，等认领
            watchers.clear()
            hostToken = null
            gameId = gameIdIn
            say("已载入存档 $gameIdIn，请各自认领座位。")
            version += 1
            cond.signalAll()
            scheduleBot()
        } finally {
            lock.unlock()
        }
    }

    private fun playerFromJson(o: JSONObject): Player = Player(
        id = o.optInt("id"),
        name = o.optString("name"),
        char = o.optString("char"),
        pos = o.optInt("pos", 0),
        money = o.optInt("money", 0),
        alive = o.optBoolean("alive", true),
        kind = o.optString("kind", "human"),
        botLevel = o.optString("bot_level", "normal"),
        brain = o.optString("brain", "local"),
        memory = o.optString("memory", ""),
        thinking = o.optBoolean("thinking", false),
        aiPlan = o.optJSONObject("ai_plan"),
        aiPlanTurn = Json.optIntOrNull(o, "ai_plan_turn"),
        aiTradeNext = o.optJSONObject("ai_trade_next"),
        aiTrades = o.optInt("ai_trades", 0),
    )

    private fun boardFromJson(arr: JSONArray?) {
        board.clear()
        if (arr == null) {
            for ((n, def) in Rules.TILE_DEFS.withIndex()) board.add(Tile(n, def))
            return
        }
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val index = o.optInt("i", i)
            val def = Rules.TILE_DEFS.getOrNull(index) ?: Rules.TILE_DEFS[i]
            val t = Tile(index, def)
            t.owner = Json.optIntOrNull(o, "owner")
            t.level = o.optInt("level", 0)
            t.boughtTurn = o.optInt("bought_turn", -1)
            board.add(t)
        }
    }

    private fun chanceJson(c: PendingChance): JSONObject = JSONObject()
        .put("text", c.text)
        .put("delta", c.delta ?: JSONObject.NULL)
        .put("move", c.move ?: JSONObject.NULL)
        .put("goto", c.goto ?: JSONObject.NULL)
        .put("icon", c.icon)
        .put("pid", c.pid)
        .put("name", c.name)

    private fun settleJson(s: Settle): JSONObject {
        val o = JSONObject()
            .put("type", s.type)
            .put("pid", s.pid)
            .put("tile", s.tile ?: JSONObject.NULL)
            .put("amount", s.amount)
        if (s.to != null) o.put("to", s.to)
        if (s.name != null) o.put("name", s.name)
        if (s.tileName != null) o.put("tileName", s.tileName)
        if (s.icon != null) o.put("icon", s.icon)
        if (s.text != null) o.put("text", s.text)
        return o
    }

    private fun settlesJson(): JSONArray {
        val a = JSONArray()
        for (s in settles) a.put(settleJson(s))
        return a
    }

    private fun logJson(list: List<LogEntry>): JSONArray {
        val a = JSONArray()
        for (e in list) {
            a.put(
                JSONObject().put("id", e.id).put("kind", e.kind)
                    .put("pid", e.pid ?: JSONObject.NULL).put("text", e.text)
            )
        }
        return a
    }

    private fun tradeJson(t: Trade): JSONObject = JSONObject()
        .put("id", t.id)
        .put("from", t.from)
        .put("to", t.to)
        .put("give", Json.arrInts(t.give))
        .put("want", Json.arrInts(t.want))
        .put("giveCash", t.giveCash)
        .put("wantCash", t.wantCash)
        .put("asked", t.asked)

    private fun tradesJson(list: List<Trade>): JSONArray {
        val a = JSONArray()
        for (t in list) a.put(tradeJson(t))
        return a
    }

    private fun tradeFromJson(o: JSONObject): Trade = Trade(
        id = o.optInt("id"),
        from = o.optInt("from"),
        to = o.optInt("to"),
        give = Json.readTileIds(if (o.has("give")) o.opt("give") else null),
        want = Json.readTileIds(if (o.has("want")) o.opt("want") else null),
        giveCash = o.optInt("giveCash", 0),
        wantCash = o.optInt("wantCash", 0),
        asked = o.optBoolean("asked", false),
    )

    /** 只给存档用：完整棋盘字段（含 computed 值不需要，载入时按规则重算）。 */
    private fun boardJson(): JSONArray {
        val a = JSONArray()
        for (t in board) {
            val o = JSONObject()
                .put("i", t.i)
                .put("type", t.type)
                .put("name", t.name)
            if (t.def.sub != null) o.put("sub", t.def.sub)
            if (t.def.icon != null) o.put("icon", t.def.icon)
            if (t.def.group != null) o.put("group", t.def.group)
            if (t.type == "prop") o.put("price", t.price).put("rent", t.rent)
            if (t.def.amount != 0) o.put("amount", t.def.amount)
            o.put("owner", t.owner ?: JSONObject.NULL)
            o.put("level", t.level)
            o.put("bought_turn", t.boughtTurn)
            a.put(o)
        }
        return a
    }

    // ---------------------------------------------------------------- 视图

    fun view(pid: Int?, token: String): JSONObject {
        lock.lock()
        try {
            val me = player(pid)
            val playersArr = JSONArray()
            for (p in players) {
                playersArr.put(
                    JSONObject()
                        .put("id", p.id)
                        .put("name", p.name)
                        .put("char", p.char)
                        .put("pos", p.pos)
                        .put("money", p.money)
                        .put("alive", p.alive)
                        .put("props", Json.arrInts(propsOf(p.id)))
                        .put("kind", p.kind)
                        .put("botLevel", p.botLevel)
                        .put("brain", p.brain)
                        .put("thinking", p.thinking)
                        .put("note", p.memory.take(80))
                )
            }

            val boardArr = JSONArray()
            val myPos = me?.pos
            for (t in board) {
                val o = JSONObject()
                    .put("i", t.i)
                    .put("type", t.type)
                    .put("name", t.name)
                if (t.def.sub != null) o.put("sub", t.def.sub)
                if (t.def.icon != null) o.put("icon", t.def.icon)
                if (t.def.group != null) o.put("group", t.def.group)
                if (t.type == "prop") o.put("price", t.price).put("rent", t.rent)
                if (t.def.amount != 0) o.put("amount", t.def.amount)
                o.put("owner", t.owner ?: JSONObject.NULL)
                o.put("level", t.level)
                o.put("bought_turn", t.boughtTurn)
                if (t.type == "prop") {
                    val upCost = upgradeCost(t)
                    o.put("rentNow", rentOf(t))
                    o.put("upCost", upCost ?: JSONObject.NULL)
                    o.put("levelName", Rules.LEVELS[t.level.coerceIn(0, Rules.LEVELS.size - 1)].name)
                    // 地契卡要展示「三档租金 / 两次升级价」，服务端一次算好，前端不做钱的计算
                    val rents = JSONArray()
                    for (lv in Rules.LEVELS.indices) rents.put(rentWithLevel(t, lv))
                    o.put("rents", rents)
                    val ups = JSONArray()
                    for (lv in Rules.LEVELS.indices) {
                        ups.put(
                            if (lv < Rules.UPGRADE_RATE.size && t.level <= lv)
                                Math.round(t.price * Rules.UPGRADE_RATE[lv]).toInt()
                            else JSONObject.NULL
                        )
                    }
                    o.put("ups", ups)
                    o.put("fresh", t.boughtTurn == turnNo)
                    val isSet = t.owner != null && ownsSet(t.owner, t.group)
                    o.put("set", isSet)
                    o.put("doubled", isSet)
                    o.put(
                        "canUp", (me != null && upCost != null && t.owner == me.id &&
                            myPos == t.i && t.boughtTurn != turnNo &&
                            phase == "buy" && me.money >= upCost)
                    )
                    var upWhy = ""
                    if (me != null && t.owner == me.id) {
                        // 给前端一句「为什么升不了」，省得玩家自己猜
                        if (upCost == null) upWhy = "已满级"
                        else if (t.boughtTurn == turnNo) upWhy = "新买·要再走一次才能升"
                        else if (myPos != t.i) upWhy = "没走到这儿"
                        else if (me.money < upCost) upWhy = "钱不够"
                    }
                    o.put("upWhy", upWhy)
                }
                boardArr.put(o)
            }

            val chars = JSONArray()
            for (c in Rules.CHARS) {
                chars.put(
                    JSONObject().put("id", c.id).put("name", c.name).put("glyph", c.glyph)
                        .put("color", c.color).put("ink", c.ink).put("desc", c.desc)
                )
            }
            val groups = JSONArray()
            for (g in Rules.GROUP_COLORS) groups.put(g)

            val myTrades = JSONArray()
            for (t in trades) {
                if (pid != null && (t.from == pid || t.to == pid)) myTrades.put(tradeJson(t))
            }

            val settings = JSONObject()
                .put("startMoney", startMoney)
                .put("botLevel", botLevel)
                .put("moneyChoices", Json.arrInts(Rules.MONEY_CHOICES.toList()))
                .put("moneyMin", Rules.MONEY_MIN)
                .put("moneyMax", Rules.MONEY_MAX)
                .put("botLevels", JSONObject().apply {
                    for ((k, v) in Rules.BOT_LEVELS) put(k, v.name)
                })
                .put("maxPlayers", Rules.MAX_PLAYERS)
                .put("levels", JSONArray().apply { for (l in Rules.LEVELS) put(l.name) })
                .put(
                    "ai", JSONObject()
                        .put("ok", brain.ok).put("reason", brain.reason)
                        .put("model", brain.model).put("timeout", brain.timeoutSec)
                        .put("retry", Rules.AI_RETRY)
                )

            val newsList = JSONArray()
            for (n in newsPub) newsList.put(n.o)

            return JSONObject()
                .put("version", version)
                .put("phase", phase)
                .put("current", current ?: JSONObject.NULL)
                .put("host", host ?: JSONObject.NULL)
                .put("you", pid ?: JSONObject.NULL)
                .put("dice", JSONArray().put(dice[0]).put(dice[1]))
                .put("pending", pending ?: JSONObject.NULL)
                .put("chance", chance?.let { chanceJson(it) } ?: JSONObject.NULL)
                .put("settles", settlesJson())
                .put("settle", if (settles.isNotEmpty()) settleJson(settles[0]) else JSONObject.NULL)
                .put("land", land?.let { JSONObject().put("kind", it.kind).put("tile", it.tile) } ?: JSONObject.NULL)
                .put("round", turnNo)
                .put("winner", winner ?: JSONObject.NULL)
                .put("players", playersArr)
                .put("board", boardArr)
                .put("chars", chars)
                .put("groups", groups)
                .put("log", logJson(log.takeLast(40)))
                .put("news", news.o)
                .put("newsList", newsList)
                .put("trades", myTrades)
                .put("startMoney", startMoney)
                .put("salary", Rules.SALARY)
                .put("boardSize", Rules.BOARD_SIZE)
                .put("admin", token.isNotEmpty() && token == hostToken)
                .put("watching", token.isNotEmpty() && watchers.contains(token))
                .put("paused", frozenSnap != null)
                .put("settings", settings)
        } finally {
            lock.unlock()
        }
    }

    // ---------------------------------------------------------------- 入口

    fun act(token: String, action: String, payload: JSONObject) {
        lock.lock()
        try {
            actLocked(token, action, payload)
        } finally {
            lock.unlock()
        }
    }

    private fun actLocked(token: String, action: String, payload: JSONObject) {
        val pid = pidOf(token)
        if (pid == null && !watchers.contains(token)) throw IllegalStateException("登录状态失效，请重新加入")
        if (ADMIN_ACTIONS.contains(action)) {
            if (!isAdmin(token)) throw IllegalStateException("只有房主能做这个操作")
            when (action) {
                "start" -> start()
                "restart" -> restart()
                "close_room" -> closeRoom()
                "pause" -> pause()
                "resume" -> resume()
                "settings" -> setSettings(
                    Json.optIntOrNull(payload, "startMoney"),
                    if (payload.isNull("botLevel")) null else payload.optString("botLevel"),
                )
                "bot_add" -> addBot(payload.optString("brain", "local").ifEmpty { "local" })
                "bot_remove" -> removeBot(payload.optInt("pid", 0))
                "bot_level" -> setBotLevel(payload.optInt("pid", 0), payload.optString("level", ""))
            }
            return
        }
        when (action) {
            "settle_seen" -> {
                settleSeen(token)
                return
            }
            "chance_seen" -> {
                chanceSeen(token)
                return
            }
            "buy_seen" -> {
                buySeen(token)
                return
            }
            "handoff_seen" -> {
                handoffSeen(token)
                return
            }
        }
        if (pid == null) throw IllegalStateException("观战者不能操作棋盘")
        when (action) {
            "roll" -> roll(pid)
            "settle_ok" -> settleOk(pid)
            "chance_ok" -> chanceOk(pid)
            "buy" -> buy(pid)
            "pass" -> passBuy(pid)
            "upgrade" -> upgrade(pid, payload.optInt("tile", -1))
            "end_turn" -> endTurn(pid)
            "trade_offer" -> tradeOffer(pid, payload)
            "trade_answer" -> tradeAnswer(pid, payload.optInt("id", 0), payload.optBoolean("accept", false))
            else -> throw IllegalStateException("未知操作")
        }
    }

    companion object {
        val ADMIN_ACTIONS = setOf(
            "start", "restart", "close_room", "pause", "resume",
            "settings", "bot_add", "bot_remove", "bot_level",
        )
    }
}

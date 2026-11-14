# 大富翁 · 联机（局域网 / 互联网 二合一）

## 1. 软件介绍

一个电脑上能开局的网页版大富翁：电脑上跑一个 Python 小服务，同一 WiFi 下的电脑、平板、手机打开网页地址就能进同一局，
2 到 4 人，进场先选角色（小狐 / 小熊 / 企鹅 / 恐龙），每人有自己的头像和头像色。
不需要联网、不需要第三方包，一个脚本起服务，一个 HTML 就是全部界面；对局历史会自动存进同级目录的 `monopoly.db`（Python 自带 SQLite），
下次打开可以开新局，也可以从存档里接着打。

**同一份程序、两种模式**：启动时先选

- **局域网模式**：不碰公网，同一 WiFi 下的设备打开 `http://<本机IP>:9000/` 就能进；
- **互联网模式**：再起一条 serveo 固定隧道，地址固定为 `https://suqinghuai.serveousercontent.com`，外地朋友也能进
  （serveo 建不起来时自动退回 Cloudflare Quick Tunnel，地址每次随机）。

**不想装 Python**：直接双击 `dist\大富翁.exe`，它会先让你选模式，然后起服务 + 按需建隧道，
打印地址（互联网模式同时复制到剪贴板）并自动打开浏览器。
存档 `monopoly.db`、AI 配置 `ai_config.json`、失败日志 `ai_fail.log`、隧道日志写在 **exe 同目录**；
控制台窗口就是服务进程，关掉即停止。

局域网版与联网版共用同一份 `src\server.py` 和同一套页面源码，区别只在实时通道（SSE / WebSocket）与是否走公网隧道。

能力：

- 11×11 外圈棋盘，共 40 格：30 块地产（10 个色组）、4 张机会、2 格税、起点 / 医院 / 停车场 / 幸运角 4 个角、休息站；
  可买地块按色组着色，起点 / 机会 / 税 / 医院 / 停车场 / 休息站各有自己的底色，一眼能分清能不能买
- 一回合的完整流程：**需要换地就先点「发起交换」→ 掷骰 → 走格 → 落地执行（买地 / 升级 / 付租 / 抽卡，手动点「确定」）→ 自动交给下一位**；
  不再有单独的「结束回合」按钮
- **所有玩家都看得到的结算确认窗**：经过起点领 500、交租金、交税、医院、停车场奖金、机会卡，都会先弹一张窗（图标 + 一句话 + 金额），
  点「确定」之后钱才真的动；电脑停 2 秒自动确认，走到不需要做选择的地方也不调 AI 接口
- 机会卡会停在棋盘中央给所有人看，抽到的人点「确定」才执行（走棋或增减金币），电脑 2 秒后自动确认
- 骰子行右侧显示「第 N 回合」；买卖、收租、交税、交换的金币变化都有飘字动画
- 轮到谁，页面四周就亮起谁颜色的**环境光**（像换了个阵营领域的光照，中心仍是白底，不是蒙一层色布；换人约 1.6 秒交叉淡变）
- 地块三级（初级 / 中级 / 高级）：踩回自己的地可以花钱升级，租金跟着涨（1.7 倍 / 2.6 倍），满级后不再收升级费
- **地契卡**：走到无主地块 / 自己的地块，棋盘正中会弹一张地契卡（色组色带、售价或当前等级与租金、三档租金表 + 两次升级价），
  所有玩家都能看到；落点玩家直接在地契卡上点「买下」或「升级 / 放弃」，别人看到的是「等 XX 决定…」
  （新买的地块显示「下次走到才能升」，钱不够时按钮变灰并写清差多少）
- 等待室由房主设置：初始资金（1000 / 1500 / 2000 / 3000 或手输 600–6000）、加几台电脑、电脑难度（平衡 / 稳健 / 激进）
- **对局聊天**：日志框底部有一条输入栏，发出去的一句话直接进对局记录（绿底 `kind=chat`），在场所有人都能在日志里看到；
  棋盘每帧重绘而输入栏只建一次，打字打到一半不会被刷掉（局域网版和联网版共用同一份页面，两边都有）
- **电脑玩家**（服务端托管），可以在等待室一台台加进来，难度可单独调；电脑会自己掷骰、买地、升级、交税，
  每做一个决定都会在记录里留一句理由（「买下就凑齐这组了，咬牙也要拿」「升级费要收 6 次租才回本」），
  人机只回答你的交换申请（接受 / 拒绝）、不会主动找你换地；4 台电脑可以自己开一局，真人全程观战
- **AI 座位**：电脑有两种——「人机」用本地算法秒回，「AI」走 OpenAI 兼容接口真调大模型
  （在**开始页点「AI 接口」**填地址 / 密钥 / 模型，支持 OpenAI、DeepSeek、火山方舟、本地 ollama）；
  AI 会带上自己写的笔记和最近流水，所以打久了会连着上一回合的主意、也会记仇；**一回合只调一次接口，而且是在掷骰、走到落点之后才调**：
  它看到的是「已经站在哪块地上、现金多少、这块地买价 / 升级费 / 租金多少」，只排这一回合剩下的动作（买 / 升），
  走完落点不需要做选择（付租、交税、领钱）就不调接口；计划里想换的地排到**下一回合开局、掷骰之前**才发出；
  计划结构先过校验，不合法打回重来最多 3 次，仍不行就交回人机接手并把模型原话记进 `ai_fail.log`；
  它思考时座位和面板上都会显示「正在思考…」，等超过 `timeout` 秒自动降级；
  AI 还有交换主动权：觉得划算时会在计划里排一笔换地（一局最多 2 笔，被拒就作罢），人机不会
- **观战**：入场页点「只观战」不占座位，能看完整棋盘和日志，房主点观战也能管设置、开始、暂停
- 房主随时可以「返回开始页（保留局面）」：局面冻结、座位和登录凭证都留着，改完设置点「继续游戏」接着打；
  这个按钮和「规则说明」并成一排，固定在右侧面板最下面
- 玩家之间的地产交换**双方都能多选**（可以一次打包几块地，仍可贴现金差），对方接受 / 拒绝，电脑也会表态（AI 还会自己发起）；
  交换只能在自己回合、**掷骰之前**发起
- 资产不够付租金即破产出局，最后活着的人获胜，房主可以「再来一局」
- 房主还可以「结束房间」：把房间里其他人全部请出去（他们的登录态作废、需要重新加入，也可以换角色），
  自己留在大厅，等于原地开一个新房间；和「再来一局」的区别是「再来一局」人还坐着、只重开牌桌
- **集齐同色整组**：某一色的 3 块地都归同一个人时，全屏放一条「凑齐「麦金」整组，租金翻倍」横幅，
  这三块地的租金立刻 ×2，地图上的租金后面会标「（已翻倍）」
- 棋盘中心一个正方形日志框，系统消息与各角色记录自动分色，**最新的在最上面、旧的往下沉**；
  日志按动画节奏落账——掷骰、走格、翻牌、买地这几段动画播完，这一帧的几条记录才一起出现在框里，
  不会出现「棋子还在路上，记录已经说买下了」
- 金币旁边始终带地契数量（我的卡片、中央玩家条、右侧玩家列表三处都显示「金币 + N 契」）
- 骰子滚动、棋子逐格跳动、抽卡翻牌、买地弹卡、换地成交五类动画（抽卡和成交都是整张卡从背面翻过来）
- **掷骰时骰子放大到屏幕正中**：两颗 84×84 的大骰子居中翻滚约 0.54 秒、**定格停 2 秒**再淡出，棋盘上的小骰子同步转，
  顺序仍是「骰子 → 棋子起步」，不会抢拍
- 经过起点 +500 金币（正好停在起点也算），棋盘角上的说明和底部提示都跟着这个数走
- **掉线能回来**：登录凭证同时写进地址栏（`?token=`）和 localStorage，手机息屏、页面被关、切到别的 App 再回来，
  重新打开同一个链接就还在原来的座位上；息屏唤醒 / 从后台切回来时会自动补一次状态，SSE 断了会自己重连
- 手机端适配：窄屏下棋盘整块缩到屏幕宽度（11×11 全部可见，不用左右拖），格子文字自动精简，
  右面板改到底下顺序排；在 375 / 390 宽度实测没有横向滚动
- 本地存档：每走一步自动把「当前局面」写进 `monopoly.db`；重开服务、换电脑都能在家门口看到「新游戏 / 载入存档」两个入口，
  载入后各自认领原来的座位继续打；**历史存档最多保留 6 局**，再开新局会自动删掉最旧的那一局

适用场景：家庭 / 宿舍 / 小团队的局域网娱乐局，也可以单机开几个浏览器窗口自己试规则。

## 2. 使用说明（面向用户）

**前置条件**：电脑上装有 Python 3.9 以上（标准库够用，不需要 pip 装任何东西）；用 exe 则什么都不用装。

**启动**

```
双击 dist\大富翁.exe           # 先选 1=局域网 / 2=互联网
python launcher.py            # 源码方式，同样先选模式（或 双击 启动.bat）
python launcher.py --mode net # 跳过菜单：直接互联网模式
```

选完模式后窗口里会打印地址，例如局域网模式：

```
本机打开 : http://127.0.0.1:9000/
同一 WiFi 下的手机/电脑 : http://192.168.3.104:9000/
```

互联网模式会多打印一行固定地址 `https://suqinghuai.serveousercontent.com`（并复制到剪贴板）。
serveo 偶尔会拒绝固定子名（上一次会话还没释放），等一分钟再开即可；`dist\cloudflared.exe` 存在时会自动退用它。

**开局步骤**

1. 电脑浏览器打开「本机打开」那条；手机连同一个 WiFi，照着「手机/其它电脑」那条输入。
2. 每人选一个角色、填名字，点「进入房间」。只想看不想玩就点「只观战」。
3. 到齐（至少 2 人，**电脑也算人数**）后由房主在「开局设置」里定好初始资金、选电脑难度，
   想凑座位就点「＋ 人机」（秒回，本地算法）或「＋ AI」（真调大模型，见下一条），再点「开始游戏」。
4. 轮到你时，**要换地就先点「发起交换」**（只能掷骰前发起），然后点「掷骰子」；走到哪里就按落点办事：
   无主地可以「买下」或「放弃」，自己的地可以「升级」或「放弃」；经过起点 / 付租 / 交税 / 医院 / 停车场会先弹确认窗，
   点「确定」钱才动。全部办完会自动交给下一位，不需要再点「结束回合」。
5. 踩回**自己**的地时才能升级：走到这块地上面，右侧地契行出现「升级 ￥X」，从初级升到中级、再到高级，租金立刻上涨（名字后面出现 ★ / ★★）。
   两条限制：**只能升你正踩着的那一块**（别的自有地会显示「没走到这儿」）；**刚买下的地不能马上升**（显示「新买」）——
   注意这不是「下回合」就行，而是**下一次再走到它上面**才能升。
   另外：**同一色系的 3 块地全被你买齐时，这组地租金翻一倍**（格子上的租金数字会直接变成翻倍后的值）。
6. 想换地就点「发起交换」：两边的地契都能多选（勾几块就打包几块），可以再贴一笔现金差，
   对方面板上会出现「接受 / 拒绝」；对方是电脑时会自己决定。
7. 拿不准规则随时点「规则说明」：入场页、等待室、对局右侧都有这个按钮，观战者也能看。

**对局中回开始页**

房主觉得人机太少、想改难度（或想看看这局打到现在什么样），点右侧「返回开始页（保留局面）」：
局面原地冻结、谁也没出局、座位和登录凭证都在，页面变成「开始页·局面已暂停」——
这时只能调人机难度（资金已锁定），改完点「继续游戏」就接着打。想让这一局作废就点「重开一局」。

**AI 座位（真·大模型来想棋）**

电脑有两档：**人机**是服务端自带的启发式算法，秒回、不花钱，适合当练手对手；
**AI** 走 OpenAI 兼容接口，会用自然语言跟你「说话」（日志里以浅蓝小方块标出），也会自己记账。

1. 想用 AI，直接在**开始页**点「AI 接口」：填接口地址、模型、API Key，点保存，**立即生效、不用重启服务**。
   地址填到 `/v1` 这一层即可，服务端会请求 `<地址>/chat/completions`；OpenAI、DeepSeek、火山方舟、本地 ollama 都一样。
2. 配置只保存在本机同目录的 **`ai_config.json`**（不会下发到浏览器，也不会写进存档）；Key 留空表示沿用已保存的那一个。
   配置只存在本机，不会下发到浏览器；v1.0.0 起已不再读取旧的 `config.ini`。
3. 配好后等待室的「＋ AI」按钮可点，按钮下面会显示当前模型；没配好时会说明差什么（例如「还没配置 AI 接口」）。
4. AI 有三档人设（平衡 / 稳健 / 激进），内置在服务端，不再需要写 `[ai.normal]` 之类的配置。
5. AI 思考时，它的座位上会写「正在思考…」，右侧也会提示「X 正在思考…（最多等 N 秒）」——
   N 就是配置里的 `timeout`（默认 16 秒）。等太久的话它会自己放弃，改由人机接手，对局不会卡住。

**AI 是怎么「想」的**（这些都是自动的，不用管）：

- **一回合只调一次接口**：轮它出手时，服务端把它能看到的东西整理成一段话（第几回合、它的现金和地契、
  每块地的等级和租金、对手的钱和地、场上最贵租金、无主地块的价格、以及**它自己写在上一回合的笔记**），
  模型一次就把这一回合的「买 / 升 / 换地」全排成计划，程序按计划一步步执行（掷骰、买或放弃、升级或结束），
  中途不再请求模型 —— 所以一回合只花一次调用，也不会出现「想到一半改主意」的割裂感。
- 笔记由它自己维护（「谁坑过我、我盯上哪块地、下步打算」），下一回合原样回喂，所以打久了会连着上一回合的主意，
  也会记仇；笔记超出 240 字会就地截断。
- 它给出的计划**先过一遍结构校验**：格式不对就打回去重来，最多 3 次；3 次都不合法，或接口超时 / 报错，
  这一回合就交回人机执行，同时把模型原话记进 `ai_fail.log`（JSON，一行一条），方便以后调提示词。
- 计划里的每一步在执行时还会再过一次规则：钱不够买、地已易主、升级费超预算这类情况，程序会自动改成放弃。

**接着上次的局打（存档）**

1. 每次开局后，服务端会把当前局面自动写进项目根目录的 `monopoly.db`（exe 版写在 exe 同目录），一步一存，关掉服务端也不会丢。
2. 下次打开网页，入场页顶部有两个页签：「新游戏」和「载入存档 N」。点后者能看到历史局列表
   （标题、谁在局里、各自金币、更新时间、进行中 / 已结束）。
3. 点「载入这局」，页面会进入「认领座位」：每行是你原来的角色、金币和地契数。
   各自点自己那行的「坐这里」，就能拿到新的登录凭证继续打；被别人点走的座位会变灰。
4. 同一时间只允许一局在进行。当前已经有一局在跑时，再载入别的存档会被拒绝，先把当前这局打完或让房主重开。

**排错**

| 现象 | 原因 / 处理 |
| --- | --- |
| 手机打不开 | 手机和电脑不在同一 WiFi；或电脑防火墙拦了 Python，允许一下入站即可 |
| 端口被占用 | `python src\server.py -p 9010` 换个端口，所有人用新地址 |
| 名字/角色选了没反应 | 一个角色只能一个人用，换一个角色再试 |
| 页面停在「等待开始」 | 房主还没点「开始游戏」，或房间里只有 1 个人 |
| 中途有人关页面 | 座位会留在局里：重新打开页面会自动回到自己的座位；换设备就去「载入存档 → 认领座位」 |
| 载入存档被拒 | 现在还有一局在进行，先打完或让房主「再来一局」，再载入别的存档 |
| 座位点了提示「已经有人坐下」 | 这个座位被别的浏览器认领了；同一个人请用原来那台设备（它的凭证存在浏览器里） |
| 存档列表为空 | 还没有开局过；开局后才会生成记录 |
| 「＋ 人机」/「＋ AI」是灰的 | 房间满 4 人，或已经开局 / 暂停中；电脑只能开局前加，删除也是 |
| 「＋ AI」是灰的，提示「AI 还没配好」 | 开始页点「AI 接口」，把接口地址和模型填全（多数服务还要 API Key）；Key 留空会沿用旧的，换服务时记得重新填 |
| AI 座位显示「正在思考…」很久 | 模型慢或超时设得太长；等它到点会自动交回人机。急的话把接口配置里的超时调小 |
| 「继续游戏」点了没反应 | 只有房主（含观战的房主）能继续；别人只会看到「等房主继续」 |
| 观战者看不到自己的按钮 | 观战没有自己的棋子：面板会显示「观战中」，钱和契都是 —，操作按钮全禁用 |

## 3. 使用说明（面向开发者）

**环境**：Python 3.9+，运行时只用标准库（`http.server` / `threading` / `json` / `secrets` / `sqlite3` / `struct`）。
包管理统一用 uv，虚拟环境固定在 `./.venv`：

```
uv sync                     # 建 ./.venv 并按 uv.lock 装 dev 工具链（ruff / black / pyinstaller）
uv run python src\launcher.py --mode lan
uv run ruff check src       # 规范要求：ruff 已开 T20（禁止裸 print）
uv run black --check src
```

**一处源码，两种模式**：`src/launcher.py` 起服务（`src/server.py` 的 `Handler`）后按模式决定要不要建隧道；
页面只有 `src/www/index.html` 一份，实时通道在页面里自己协商——先试 `/ws`（公网隧道不被缓冲），
连不上自动退回 `/api/events`（SSE，安卓端内置服务只有这条）。所以改页面不用再同步第二份文件。

**日志**：服务端和启动器的控制台输出统一走 `logging`（logger 名 `monopoly`，`%(message)s` 格式），
业务代码里不要再写裸 `print`——ruff 的 `T20` 会直接报错。

**打包电脑版**：

```
powershell -ExecutionPolicy Bypass -File src\tools\打包exe.ps1   # -> dist\大富翁.exe（约 10 MB）
```

**打包安卓版**：`Android\build.ps1`，构建时把 `src\www\index.html` 同步进 `assets\www\`（页面不另存第二份）。

**跑接口自测**（服务端要先起着）：

```
python tests/selftest.py 9000
```

自测覆盖：加入房间、重复角色被拒、非房主不能开局、掷骰、落地结算确认、买地 / 升级、交换生效、非法操作被拒，
地块三级（升级后租金上涨、非本人 / 非操作阶段被拒），暂停回开始页（局面冻结、掷骰 / 改资金 / 加人机被拒）与继续还原，
观战不占座位，重开一局（等级清零、金币复位），等待室设置（起始资金与难度、越界被拒），电脑入桌与难度单独改，
以及存档链路（`/api/saves` 有记录 → `/api/load` 载入后旧 token 失效 → `/api/claim` 认领座位 → 金币与地契一致、重复认领被拒）。
自测会把当前这一局「载入」一遍，所以要在干净状态下跑：`monopoly.db` 里已有进行中的局、或房间里已经有人时，
先 `python src\launcher.py --mode lan` 重启一次并删掉 `monopoly.db*` 再测。

AI 座位不接真模型也能调试：`tests/mock_openai.py` 是个假 OpenAI 端点
（`python tests/mock_openai.py 9109 normal`，模式 `normal` / `badjson`（前 3 次回非 JSON）/
`slow`（第一次睡 12 秒）/ `slowall`（每次都睡 3 秒），请求原文追加到同目录 `mock_requests.jsonl`）。
在开始页「AI 接口」把地址指向 `http://127.0.0.1:9109/v1`、模型随便填，即可只花本地算力跑通 AI 决策与降级链路。

**改规则**：棋盘、角色、色组、机会卡都在 `server.py` 顶部的 `TILE_DEFS` / `CHARS` / `GROUP_COLORS` / `CHANCE_CARDS`，
改完重启服务端即可；前端按服务端下发的 `board` / `chars` / `groups` 渲染，不需要同步改 HTML。
只有格子图标需要在前端 `ICON` 字典里准备同名图标（`TILE_DEFS` 的 `icon` 字段对应 key，缺了会退化成空白）。
新增格子类型时，记得在前端 `TINTS` 里补一条底色，否则那块会退回成中性底。

调平衡看这几个常量（都在 `START_MONEY` 附近）：`START_MONEY` / `MONEY_CHOICES` / `MONEY_MIN` / `MONEY_MAX`（起始资金与范围）、
`LEVELS`（三级租金倍率 1.0 / 1.7 / 2.6）与 `UPGRADE_RATE`（升级费占原价的 0.6 / 0.9）、
`BOT_LEVELS`（三档难度各自的 `keep` 现金阈值、同色系加成 `group`、升级概率 `upgrade`、受理交换阈值 `trade`、
安全垫 `risk`、升级回本次数上限 `payback`）、
`BOT_NAMES`（电脑名字池）、`BOT_DELAY`（电脑每步的思考时长）。改完重启即可，前端只显示服务端下发的数值。

**电脑玩家（方案 A）**：电脑是服务端里的一个 `kind = "bot"` 玩家，没有 token、不连浏览器，靠 `threading.Timer`
在 `bump()` 之后排一拍，走的是和真人完全同一套 `roll / buy / upgrade / end_turn`。它只回答交换（`_bot_trade_step`），
从不在自己回合里发起交换；决策撞上规则（钱不够等）会 `catch ValueError` 后保守收尾，不会卡住对局。
**它会说话**：`bot_wants_buy()` / `bot_try_upgrade()` 都带一个可选的 `why` 列表，每个分支把「为什么这么决定」的中文理由
append 进去，`_local_step()` 先算理由 → `_bot_say()` 取第一条 `say(..., kind="ai")` → 再真正执行动作，
所以「它说的话」和「它做的事」落在同一帧、话在动作之前（前端按 `kind="ai"` 统一渲染成电脑发言）。
升级成功分支必须先算 `before, after = rent_at(tile), rent_next(tile)` 再调 `upgrade()`，否则 `tile` 是引用，
打完日志会变成「Y → Y」。
只在等待室能加 / 删，开局后只能改难度 —— 这条限制在 `join()` / `add_bot()` 里按 `frozen` 与 `phase` 判定。
节奏见 `BOT_DELAY`（掷骰 1.15s / 买地决策 2.0s / 结束后 1.5s），掷完骰子那一拍还会按步数等
`1.1 + 0.17 × 步数` 秒，保证「骰子 + 逐格移动」在电脑下一次决定之前播完；嫌慢就把这两个数字调小。

**AI 座位（大模型版，`brain = "ai"`）**：和上面是同一类座位（`kind = "bot"`），区别只在「怎么想」。
`AIBrain`（`server.py` 里 `bot_keep()` 之后）负责读 `ai_config.json`、拼 system prompt、POST `<base_url>/chat/completions`。
**一回合只发一次请求，且必须等棋子落地之后**：模型看到的是「已经站在哪块地上」，只排这一回合**剩下**的「买 / 升」，
走完不需要做选择（付租、交税、领钱）就不调接口；计划里的 `trade` 存起来，**下一回合开局、掷骰之前**才发出去。
`decide_plan()` 返回的计划形状：

```json
{"why": "一句话", "memory": "笔记",
 "buy": {"want": true, "maxPrice": 300}, "land": {"upgrade": true, "maxCost": 200},
 "trade": {"target": "小雨", "want": "码头", "give": "小桥", "giveCash": 100}}
```

`_ai_plan_norm()` 先做结构校验（`want` / `upgrade` 必须是布尔、价格必须是非负数字、`trade` 至少要有 `want`），
不过关就重试；三次都不过关或网络失败，就写 `ai_fail.log`、提示「已交回人机接手」并交回本地算法。
流程在 `_run_ai_turn()`：

1. 锁内 `thinking = True` + `bump()`（把「正在思考」这一帧发给前端，并且**避免 `_schedule_bot` 在思考期间重复排拍**）；
   触发时机是 `phase == "buy"`（已掷骰、已落地、已确认完结算），不是回合开头；
2. 循环最多 `AI_RETRY = 3` 次：锁内取上下文（`_ai_turn_context()`）→ 锁外调模型（**HTTP 一定在锁外**，否则整个对局被一次网络请求卡住）；
3. 成功后把规范化计划存进 `p["ai_plan"] / p["ai_plan_turn"] / p["ai_trade_next"]`，先 `say(why, kind="ai")`，再 `_ai_plan_step(pid, "buy")`；
4. `buy.want / maxPrice` 决定买或放弃，`land.upgrade / maxCost` 决定升级或 `pass_buy()`；**这些步骤不再请求模型**；
   `trade` 不在本回合执行，下一回合 `_bot_step()` 在 `phase == "roll"` 时先 `_ai_trade(..., end_turn=False)` 再掷骰；
5. 计划某步在当前局面走不通（钱不够、地已易主等）就 `pass_buy()` 保守收尾，不卡局；
6. `finally` 里 `_clear_thinking()`，保证异常也不会把座位永远卡在「思考中」；`_advance_turn()` 会清掉所有人的旧计划。

`_ai_turn_context()` 只给**它自己该知道的**东西（不含别人的 token、也不含别人的笔记）：回合数、现金、骰子、落点、这块地的买价 / 租金 / 色组 / 买后余额
或自有地的等级 / 升级费 / 升后租金 / 新买标记、自己的地契、对手与最贵租金、下回合才发的换地材料、`memory`、最近流水、最近 10 条与它相关的流水。
`_ai_alive(pid, turn_no)` 在每次锁内提交前再确认一遍局面没变（暂停 / 重开都算），**故意不看 `version`**：
思考帧自己就会 `version += 1`。

笔记长度：`AI_MEM_LIMIT = 240`（提示词里要求的上限），超出直接截到 240（不再发第二次压缩请求，保证一回合只调一次接口）。
失败记录 `AI_FAIL_LOG = ROOT / "ai_fail.log"`，一行一条 JSON：
`time / game / pid / name / level / model / phase / returned / reason` —— 计划失败时 `phase = "plan"`，
`returned` 是模型原话（截断 300 字），调提示词时直接看这个就知道它为什么会给出非法计划。

**人机怎么算账**（`bot_wants_buy` / `bot_try_upgrade` / `bot_wants_trade`）：

- 买地：先算这块地在色组里还有几块（`_group_mates`），凑得越齐越敢花；留钱 = `keep × 资金姿态`
  + 「场上最贵租金 × risk」的安全垫；能补齐整组时直接不设安全垫。
- 升级：只升脚下这块（规则要求），按**回收期**算值不值 —— `payback = 升级费 ÷ 每次多收的租金`，
  ≤ 3.3 / 3.0 / 3.8 才动手（整组在手放宽 15%）；升完剩下的钱还得够顶一脚最贵租金。
- 资金姿态 `_bot_pace()`：现金是同场平均的 1.7 倍以上就收紧（少买少升、攒钱收租），
  0.65 倍以下就放开（抢地、敢升）。
- 交换：`bot_tile_score` 给「租金 × 3 + 凑组潜力」估值，双向相减再加现金差，超过 `trade` 门槛才答应。

**AI 主动发起交换**：计划里的 `trade` 先存在 `p["ai_trade_next"]`，下一回合开局（`phase == "roll"`）才交给 `_ai_trade()`：
`_tile_by_name()` 按名字找 `type == "prop"` 的地块，校验归属（`want` 必须属对方、`give` 必须属自己）、现金够不够、
`p["ai_trades"] >= AI_TRADE_MAX`（默认 2 笔）就拒；通过后 `trade_offer()` + `ai_trades += 1`（`end_turn=False`，随后照常掷骰）。
`_ai_turn_context()` 因此一直带着「【换地】你的地」「【别人的地】」两块，并注明「下回合开局才发」，模型在落地后就能安排这笔交易。

**交易应答的 timer 要独立**：`_bot_trade_step()` 挂在 `self.trade_timer` 上，不能共用 `bot_timer` ——
`_schedule_bot()` 开头会 `cancel()` 掉 `bot_timer`，而 AI 提完交换立刻 `end_turn()` 就会触发一次重排，
共用的话刚排上的应答被取消、`asked` 又已经置 True → 这笔交换永远没人应答。同理它也**不看 `version`**
（发起方的 `end_turn` 会改 `version`，但「这笔交换还在等电脑表态」这件事没变），只查 trade 是否还在 `self.trades` 里。

**存档**：表结构、快照字段、认领座位的规则见下面第 5 节；想清空全部历史，停掉服务端后删掉 `monopoly.db`
（连带的 `monopoly.db-wal` / `monopoly.db-shm` 一起删），下次启动会自动建新库。
`SAVE_SLOTS = 6`：`db_new_game()` 之后紧跟一次 `db_prune_games()`，按 `updated_at DESC, id DESC` 排序，
把第 7 局之外的旧记录直接 `DELETE`，并在日志里留一句「存档最多留 6 局，『××』已被删掉」。
想多留几局就把这个常量调大（代价是列表更长、库更大）。

**退出房间会真的腾出座位**：`/api/leave` 靠 query 上的 `token` 找到座位，前端必须带上
（`api('/api/leave?token=' + encodeURIComponent(S.token), {})`）。早先漏了 token，服务端拿到的空 token 找不到人，
玩家一直留在 `players` 里，再用同一个角色进来就会撞「这个角色已被选走」—— 这就是那个 bug 的根因。

**提交规范**：改动记录在 `doc/开发文档.md`；临时调试文件不入库，可复用的测试脚本放在 `tests/`。

## 4. 项目结构

```
monopoly/
├─ src/                     业务源码（唯一源码根）
│  ├─ server.py             服务端（游戏逻辑 + HTTP 接口 + SSE / WebSocket 两条实时通道 + SQLite 存档）
│  ├─ launcher.py           统一启动器（选局域网 / 互联网），exe 和 启动.bat 都走它
│  ├─ www/index.html        唯一前端（入场 / 载入存档 / 认领座位 / 等待室 / 棋盘 / 面板），内联 CSS 与 JS
│  └─ tools/打包exe.ps1      PyInstaller onefile -> dist/大富翁.exe
├─ 启动.bat                 源码方式启动（需要 Python），指向 src/launcher.py
├─ pyproject.toml           项目声明：requires-python >= 3.9，运行时零第三方依赖，dev 组 ruff / black / pyinstaller
├─ uv.lock                  依赖锁定（uv lock 生成）
├─ doc/
│  ├─ 开发文档.md           每次迭代的开发记录
│  └─ 版本日志.md           由用户编辑
├─ tests/
│  ├─ selftest.py           接口与流程自测（python tests/selftest.py 9000）
│  ├─ channel_probe.py      实时通道自测：同一地址上 /ws 与 /api/events 都验一遍
│  ├─ test_settle_timing.py 结算 / 交接节奏的单元测试
│  └─ mock_openai.py        假的 OpenAI 兼容端点，用于 AI 全链路调试
├─ Android/                 安卓版构建工程（页面由 syncWeb 从 ../src/www 同步）
├─ assets/                  图标（app_icon.png / app.ico）
├─ dist/                    本地产物（不入库）：大富翁.exe / 大富翁.spec / serveo_name.txt / cloudflared.exe
├─ README.md                本文件
├─ 开发规范.md              项目约定
└─ .gitignore               忽略存档、配置密钥、日志、.venv 与打包产物
```

运行时才会生成、且**不入库**的文件：`monopoly.db`（存档）、`ai_config.json`（开始页保存的 AI 接口配置）、
`ai_fail.log`（AI 非法返回记录）、`server_out.log` / `server_err.log`。

## 5. 技术原理

**整体架构**：单进程多线程。`ThreadingHTTPServer` 负责请求，一个全局 `Game` 对象持有全部对局状态，
所有读写都在 `Game.lock`（`threading.Condition`）里完成，改一次状态 `version += 1` 并 `notify_all()`。

**接口**

| 地址 | 作用 |
| --- | --- |
| `GET /?token=…` | 返回 `www/index.html`（无 token 也返回同一个页面，由前端决定显示入场还是棋盘） |
| `GET /api/lobby` | 房间概况：玩家列表、角色占用、能不能开局 |
| `POST /api/join` | 选角色 + 名字入场，返回 `token`（`secrets.token_hex(14)`） |
| `POST /api/watch` | 只观战：返回 `token`，不占座位；`view` 里 `watching = true`、`you = null` |
| `GET /api/state?token=…` | 当前玩家的完整视图：棋盘、玩家、自己的地契、交换申请、最近 40 条日志、最新事件 |
| `POST /api/action?token=…` | 所有操作：玩家侧 `roll` / `settle_ok` / `chance_ok` / `buy` / `pass` / `upgrade` / `trade_offer` / `trade_answer`；房主侧 `start` / `restart` / `close_room` / `pause` / `resume` / `settings` / `bot_add` / `bot_remove` / `bot_level` |
| `POST /api/leave?token=…` | 退出房间（局中只有观战者能退，玩家退座会被拒，以免丢座位） |
| `GET/POST /api/ai_config` | 开始页读取 / 保存 AI 接口配置（地址、模型、Key），保存即生效 |
| `GET /api/events?token=…` | SSE 长连接，`version` 变化就推一帧新状态（局域网 / 安卓端内置服务走这条） |
| `GET /ws?token=…` | WebSocket，同样的推帧逻辑。页面优先用它，因为公网隧道会缓冲 `text/event-stream`；连不上就自动退回 SSE |
| `GET /api/saves` | 历史存档列表（`id / title / created_at / updated_at / status / phase / summary`）+ 当前正在用的 `game_id` |
| `POST /api/load` | 载入指定存档，返回 `seats`（座位表：`id / name / char / money / alive / props / claimed`） |
| `GET /api/seats` | 当前局面的座位认领状态，认领页轮询用 |
| `POST /api/claim` | 认领一个空座位，返回新的 `token` |

**服务端关键设计**

- `TILE_DEFS` 是 40 项格子定义（`type` / `name` / `group` / `price` / `rent` / `icon`），`Game.reset()` 时复制成带 `i`、`owner` 的运行棋盘。
- 日志从纯字符串改成结构化字典 `{"id","kind","pid","text"}`，`say(text, kind, pid)` 统一入口；`pid` 让前端知道该用哪个角色的颜色。
- 事件通道 `event(type, **kw)` 维护 `self.news`（最后一条）+ `self.news_frame`（本帧累积）；
  `bump()` 把这一帧的事件封成 `news_pub` 冷却后下发成 `view.newsList`（数组，按 `seq` 升序）。
  一次状态变化里常有「掷骰 + 领工资 + 落地 + 抽卡」好几条，前端逐条播，`seq` 保证刷新 / 重连不重播。
- 机会卡是字典列表，支持 `delta`（进出账）/ `move`（相对走格）/ `goto`（直接跳格），结算后递归 `_resolve`（深度上限 3，防止连环卡死循环）。
- **权限收口在 token 上**：`self.host_token` 是唯一房主凭据，`is_admin(token)` 一处判定；
  `act()` 用 `ADMIN_ACTIONS = {start, restart, close_room, pause, resume, settings, bot_add, bot_remove, bot_level}`
  统一在入口校验，所以「4 台电脑自己打、真人只观战」时房主照样能开局 / 暂停。载入存档后 `host_token = None`，
  第一个 `claim` 的人自动成为房主。
- **暂停 = 快照移出 + 相位拨回**：`pause()` 把 `snapshot()`（`pop("frozen")` 去嵌套）存进 `self.frozen`、`phase = "setup"`，
  tokens / watchers / host_token 全保留，所以回来不用重新认领；`resume()` 把 `frozen` 灌回但保留这三样。
  `"setup"` 仍会落库，所以「暂停中重启服务」会直接回到暂停状态的开始页。
- **地块三级**：`LEVELS`（倍率 1.0 / 1.7 / 2.6）与 `UPGRADE_RATE`（0.6 / 0.9）驱动 `rent_at(tile)` 与 `upgrade_cost(tile)`；
  `upgrade(pid, tile_i)` 只在 `phase == "buy"`、`current == pid`、`pending` 就是这块地时允许，且必须是自己满级前的地，破产清地时等级归零。
- **电脑玩家**：`kind = "bot"` 的座位不占 token；`bump()` → `_schedule_bot()` 用 `threading.Timer` 排一拍，
  `_bot_step(version, pid, phase)` 先比对三元组，不一致就退出（新状态已重排新 timer），
  所以暂停 / 继续 / 重开 / 真人抢先操作都不会有两个 timer 同时推进同一台电脑。
  决策函数 `bot_wants_buy` / `bot_try_upgrade` / `bot_wants_trade` 按 `BOT_LEVELS` 的阈值算，撞规则时保守收尾。
- **观战**：`self.watchers` 是 token 集合，不占座位；`view(None, token)` 天然可用（`you = null`、`trades` 过滤为空、
  `admin` 由 token 比对），前端据此渲染只读面板。
- **AI 座位**：`brain = "ai"` 的座位和电脑共用 `kind = "bot"` 的排拍与全部规则入口，只是决策换成了
  「锁内取上下文 → 锁外一次 HTTP → 锁内校验提交」；`view()` 因此多下发 `players[].brain / thinking / note`
  和 `settings.ai`（`ok / reason / model / timeout / retry`），前端靠这几个字段决定「＋ AI」能不能点、
  谁在思考、以及按钮下面的说明文字。日志多了 `kind = "ai"` 这一种，前端用浅蓝小方块区分「它说的话」。

**存档层（SQLite）**

- 库文件固定为 `ROOT / "monopoly.db"`，`main()` 里先 `db_init()` 建表再打印已有存档数；建不出来就打印警告、
  `Game.db_ok = False`，整局退回纯内存模式，不会因为存档挂掉而开不了局。
- 两张表：
  - `meta(key, value)`：存 `schema_version`，为以后迁移留钩子；
  - `games(id, title, created_at, updated_at, status, phase, summary, snapshot)`：
    `status` 是 `playing / over`，`summary` 是列表页用的轻量摘要（玩家名 + 金币 + 当前行动者），`snapshot` 是完整局面 JSON。
- 存的是「当前局面」而不是操作流水：`Game.snapshot()` 把 `players / board / phase / current / host / dice / pending / log /
  news / trades / winner / version` 原样导出，`Game.restore()` 再整份灌回去，所以载入后的棋盘、地契、日志、待办买地阶段完全一致。
- 落库时机：所有改状态的入口最后都会调 `bump()`，`bump()` 里顺手 `persist()`。`phase == "lobby"`、还没开局（`game_id is None`）
  或已降级时跳过，避免把空房间写进库。写失败只提示一次，然后置 `db_ok = False`。
- 一局一条记录：`start()` 时 `db_new_game()` 插入新行（标题取「阿明、小雨 的局」），之后每一步都是 `UPDATE` 同一行；
  `restart()` 先把旧行按 `status = "over"` 封存，再 `game_id = None`，下一局重新插一行。所以「结束的局」是可回看的存档。
- 认领座位：`snapshot` 里**不含 token**（token 只是运行期的登录凭证，重启就失效），载入时把 `tokens` 清空，
  由 `POST /api/claim` 重新发一个 `secrets.token_hex(14)`，并要求这个座位当前没有 token（`pid in tokens.values()` 即已占）。
  这样既不会把别人的会话凭据写进数据库，也保证一个座位只有一个人能操作。
- 同一时间只允许一局在进行：`load_from_db()` 里判断「内存里 `phase != "lobby"` 且要载入的不是当前这局」就拒绝（HTTP 409）。

**前端关键设计**

- 棋盘是 CSS Grid 11×11，`cellOf(i)` 把 0–39 映射到外圈格子；中心 `.center` 用 `grid-area: 2 / 2 / 11 / 11` 占住内圈。
- 棋子不放在格子里，而是绝对定位覆盖在棋盘上（`.pieces` 层）：`measureTiles()` 记录每格中心并算出棋子尺寸 `--tk`，
  `refreshTokens()` 按落点分组错开摆放，`runMove()` 用 172ms 的 `setInterval` 一格格挪，带弹跳 class。
- 动画全部靠「上一帧状态 vs 这一帧状态」的差量 + 服务端 `news` 事件驱动：位置差 `(new-old+40)%40`，≤13 判前进，否则按后退绕圈走。
- **动画不抢帧**：`applyState()` 只把状态入队（`frameQueue`），`pumpFrames()` 串行播，
  `applyFrame()` 返回「这一帧要占多久」（骰子 0.9s + 每格 0.172s），播完才上下一帧 —— 人机连续推进时棋子照样逐格走、卡片不会被下一张盖掉。
  一帧里若有多条事件（`newsList`），按 `seq` 顺序播，骰子后面的事件统一延后 0.9s 再演。
  房间 / 暂停视图不受队列影响，即时切换。
- 骰子、头像、格子图标都是内联 SVG（`AVATAR` / `ICON` / `PIPS`），零外部资源。
- 日志框用 `height:100% + aspect-ratio:1/1 + max-width:100%` 保证是正方形，`lineColor()` 按 `pid → 角色 ink`、
  机会卡琥珀、终局薄荷、系统灰返回文字色。
- **日志倒序 + 不主动滚**：`S.logShown.slice(-40).reverse()`，最新的在最上面、旧的往下沉。整块 `#logList` 每帧重绘，
  所以滚动位置只能在 `appendChild` 之后设（`keepLogScroll()`）：默认 `scrollTop = 0` 停在顶部，
  新的一来就顶在最上面、旧的被挤出框外；只有玩家自己往下滚去看历史时才记住 `S.logTop`，
  并按 `新 scrollHeight − 旧 scrollHeight` 补偿，避免每帧把人拽回顶部。
- **日志与动画对齐**：`applyFrame()` 不直接把 `st.log` 画出来，而是 `collectLogs(st)` 把 `id > S.logId` 的新条目
  塞进 `S.logQueue`；`pumpFrames()` 的计时器到点、这一帧的骰子 / 走格 / 卡片动画都播完了，才 `flushLogs()` 落进
  `S.logShown` 并 `paintLogs(新增条数)`局部重绘（只有新条目带 `logIn` 动画）。`dur` 里把
  `CARD_HOLD = {chance:2800, buy:2300, upgrade:2300, trade:2300, bankrupt:2000}` 也算进去，
  所以卡片还盖着棋盘时日志不会提前冒出来。
- 抽卡 / 换地成交的「翻牌」：`pushPop(html, hold, cls)` 多了第三个参数，机会卡和成交卡传 `'flip'`，
  CSS 走 `@keyframes cardFlip`（`rotateY(-168deg) → 9deg → 0`），比普通弹卡更有翻开感。
- 手机端：`@media (max-width:899px)` 把 `#game` 改成单列（棋盘在上、面板在下）、`.board` 去掉了 `min-width:440px`
  和 `overflow-x:auto`，改成 `width:100%; height:min(100%, calc(100vh - 40px)); min-width:0`，
  格子 / 字号 / 骰子 / 日志字号一起缩；`@media (max-width:599px)` 再压一档，非角格只留颜色条 + 名字（藏图标），
  375 / 390 宽度实测 `scrollWidth == clientWidth`，不用左右拖。
- 掉线重连：`setToken()` 除了写 localStorage 还 `history.replaceState('?token=…')`（`rememberTokenInUrl()`），
  `clearToken()` 把 query 清掉；`visibilitychange`（回前台）/ `pageshow`（从缓存恢复）/ `online` 时调 `wakeUp()`，
  先看 `S.es.readyState` 补一条 SSE，再 `refreshState()` 拉一次最新局面。
- 右侧面板：地契 / 交换 / 玩家三块包进 `.panel-scroll`（`flex:1 + overflow-y:auto`），
  底部 `.admin-row.bottom-row` 是两列 grid（「返回开始页」+「规则说明」），`margin-top:auto` 钉在面板最下面；
  非房主只藏 `#btnPause`，整行不藏，因为「规则说明」人人都能点。
- **机会卡是「待确认」状态**：`_resolve()` 摸到机会格只把卡挂进 `game.chance` 并把 `phase` 设成 `"chance"`，
  真正的效果（走格 / 收罚金 / 回起点 +500）由 `chance_ok()` 在「确定」时才执行；卡片走 `showChanceHold()`
  常驻在棋盘中央（`.popcard.hold`，翻牌 + 上下悬停），抽到的人点按钮、其他人看「等 XX 确定」；
  电脑由 `BOT_DELAY["chance"] = 2.0` 排一个 2 秒后的自动确认。`chance` 与 `round` 都随 `view()` 下发，也进存档。
- **动画顺序**：`applyFrame()` 算出这一帧的走格总时长 `walkDur`，把 `landAt = DICE_MS + walkDur` 交给
  `handleNews()`；骰子事件立刻播，`rent/tax/park/buy/upgrade/chance/cash` 全部排到棋子落格之后再触发。
- **所有增减金币都有飘字**：`cash(i, v)` 包了 `floatMoney()`，买地 `-price`、升级 `-cost`、
  成交按两头 `＋/－`、机会卡 `cash` 事件、`chance_go` 的 `+500 / delta` 都接上。
- **发起交换有醒目横幅**：服务端 `trade_offer()` 发 `event("trade_offer", trade={...})`（广播给所有人），
  前端 `showTradeOfferCard()` 用 `.popcard.offer`（从上滑入 + 上下悬停 + 淡蓝外发光）显示「A 想和 B 换地」。
- **回合数**：骰子行右侧 `.c-round` 读 `st.round`（即服务端 `turn_no`），显示「第 N 回合」。
- 手机端日志框取消 `aspect-ratio` 改为铺满中央区，玩家条 `nowrap + overflow-x:auto` 只占一行（4 人也单行）。
- 入场页有三个视图：`#joinView`（内部再用 `#paneNew` / `#paneSave` 两个页签切「新游戏 / 载入存档」）、
  `#roomView`、新增的 `#claimView`（认领座位），`showView()` 统一切换。
- 载入流程：`POST /api/load` 返回座位表 → `renderSeats()` 画认领页并每 1.8s `GET /api/seats` 刷新 →
  点「坐这里」`POST /api/claim` 拿 token → `setToken()` 存 localStorage 并接上 SSE，跟正常开局完全同一条路径。
- 非可买地块的底色在 `TINTS` 里按 `type` 定义（`go` 薄荷绿 / `chance` 暖黄 / `tax` 浅砖红 / `hospital` 浅蓝 /
  `park` 青绿 / `rest` 浅紫），渲染时写进 `tile.style.background` 与 `--ico`，图标也跟着换成对应的深色。
- 地契数量直接用 `view()` 已经下发的 `player.props`（格子索引数组）长度，不改协议：我的卡片 `#meDeedNum`、
  中央 `chip-deed`、右侧玩家列表 `pdeed` 三处都渲染「金币 + N 契」。
- 等待室设置卡 `#setCard`：`#setMoney`（手输）+ `#setMoneyChips`（1000/1500/2000/3000 快捷 chip）+ `#setBotLevel`（难度下拉）+
  `#btnAddBot`（＋添加电脑）；右上角 `#setWho` 说明「房主可改 / 由房主设置 / 已开局：资金锁定」，非房主看到的是只读态。
  主按钮 `#btnStart` 文案按状态切「开始游戏 / 继续游戏 / 等房主开始 / 等房主继续」。
- `phase` 为 `setup`（暂停态）时会渲染成等待室：标题「开始页·局面已暂停」、资金锁灰、「退出房间」隐藏、电脑只能改难度。
- 等级在前端只做展示：格子名后跟 ★（中级）/ ★★（高级）；右侧地契行 `lvtag` + 「升级 ￥X」按钮（`data-up`，只在
  `phase == "buy" && pending === t.i && myTurn` 可点，满级显示「已满级」）；钱和租金全部用服务端下发的 `rentNow` / `upCost`，前端不算钱。
- **地契卡**：服务端 `view()` 给每块 `type == "prop"` 的地再补两个只读字段 —— `rents`（三档有效租金，含垄断翻倍）
  和 `ups`（两次升级价，已经升过的档返回 `null`），前端因此不用自己算钱。`deedTarget(st)` 只认一个场景：
  `phase == "buy" && pending != null`，`mode` 由 `tile.owner === st.current` 区分买地 / 升级。
  `deedCardHTML()` 拼「色带 + 图标 + 名称 + 售价 / 当前等级与租金 + 三档租金表（当前档高亮）+ 业主行 + 说明 + 按钮」，
  按钮按「是不是自己的回合」切两种形态：自己回合是「买下 / 放弃」或「升级 / 放弃升级」，别人是
  「等 XX 决定…」；升不了级时按钮文案依次落到「已满级」/「新买 · 下次走到才能升」/「金币不够 ¥X」。
  `applyDeedCard()` 用 `mode|i|owner|level|fresh|current|you|phase` 做 key，避免每帧重建；`applyFrame()` 末尾
  等这一帧动画播完（`dur + 30ms`）才挂出来，房间 / 暂停 / 认领视图传 `null` 收起。
  点击走 `#deedCard` 上的一次事件委托，读 `data-deed` 分派 `buy` / `pass` / `upgrade{tile}`，
  服务端照旧再校验一遍，所以别人点不动、也不会因为前端绕过规则。
- **掷骰居中放大**：`showDiceFocus()` 往 `<body>` 追加一层 `.dice-focus`（`position:fixed; inset:0; z-index:62;
  pointer-events:none`，在事件弹卡 55 / toast 60 之上），里面 `.df-card .die` 是 84×84 的两颗大骰子（手机 58×58）。
  `rollDiceAnimation()` 改成 9 拍 × 60ms，随机点数同时刷到棋盘小骰子和浮层大骰子；定格后加 `landed`，
  **停 2 秒**再 `hideDiceFocus()` 淡出。翻滚 540 + 停留 2000 + 淡出 190 ≈ 2.73s，所以 `DICE_MS` 一起抬到 **2600**，
  棋子要等骰子消失才起步。
- 观战分支：`st.you == null` 时头像清空、名字「观战中」、金币与契显示 —、操作按钮全禁用，提示「你在观战」。
- 电脑在真人名单里带 `pbot` 徽章「电脑·稳健」，等待室里是「电脑·难度」小字 + 「移除」按钮（`paused` 时隐藏）。
- **回合氛围光**：`<body>` 后固定一层 `#turnTint`（`position:fixed; inset:0; z-index:6; pointer-events:none`），
  里面是两个 `.tt-layer`；每层不是纯色块，而是「上 / 下 / 左 / 右四束径向光 + 一圈内阴影」，
  用 `mix-blend-mode:soft-light` 只调明暗与色相 —— 中心留白保持白底，四周像被当前玩家的灯照亮；
  `--tc` 存角色的 RGB 三元组，`applyTurnTint(st)` 读 `st.current` → `charOf(...).color` → `hexToRgb`，
  换人时把新颜色写到**另一层**再 `.on`，旧层同时 `.off`，靠 CSS `opacity 1.6s` 交叉淡变，
  所以既没有整页蒙版，也没有渐变背景无法插值导致的跳色。`phase == "over"`、当前玩家出局、
  切到房间 / 认领 / 入场视图时两层一起 `.off`。
- **交换门槛（第十一轮）**：`BOT_LEVELS[level]["trade"]` 从 0.0 / 0.18 / -0.1 提到 **0.35 / 0.5 / 0.18**，
  再加 `TRADE_MIN_MARGIN = 120`；平价或小幅让利一律拒。AI 侧 `AI_TRADE_MAX = 2`（一局最多主动提 2 笔），
  系统提示词里明说「只有凑齐整组、或对方明显卖便宜才提，大多数回合不必排 trade」。
- **交换评估三个维度（第十二轮）**：`bot_trade_eval()` 在「地值 + 现金差」之外再算三件事 ——
  ①**缺钱程度** `_trade_safety()`（现金低于两倍安全垫时，进账现金按 1.6 倍计价、掏出现金按 1.9 倍扣，
  掏完低于安全垫且不是凑整组就拒）；②**回合进度** `turn_no / 40`（越到后期门槛越硬、送对手整组的代价越大，
  自己凑整组的复利也越大）；③**色组归属**（换回来能垄断就加上「同组所有地租金翻倍」的收益，
  已被对手占死的组打折，让出后对手直接凑齐整组则按 2.3–2.6 倍重罚，除非对方现金补到 1.2 倍以上）。
  结论连同理由一起写进日志（「铁算盘 拒绝了交换：…」）。

**边界限制**

- 单桌：同一进程只有一局在进行（这是产品决定，不是缺失）。
- 电脑只在等待室加、局中不可加不可删（难度可以随时调）；人机不会主动发起交换、也不会「合谋」；AI 座位会主动提交换，但一局最多 2 笔（`AI_TRADE_MAX`）。
- 4 台电脑的纯 AI 局需要真人以观战身份点「开始」，没有全自动开桌。
- 暂停中的局在存档列表里仍标 `playing`，没有单独的「已暂停」标签。
- 存档只存「当前局面」快照，不存操作流水，所以不能回放某一步，也不能回溯到更早的局面。
- 历史存档最多 6 局（`SAVE_SLOTS`），开新局时自动删掉最旧的一局；被删的局没法找回。
- 存档不做身份延续：token 不落库，载入后必须各自认领座位；座位被别的浏览器先点走就坐不上了，只能由那人退出后重启服务端再认领。
- 交换只支持「各 1 块地 + 现金差」。
- 破产只判定「付不起就出局」，没有抵押 / 分期还债。
- 服务端存档写库在 `bump()` 里同步完成，是单机小局的量级；不做批量 / 异步刷盘。

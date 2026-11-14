package com.monopoly.lan

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * 主界面：创建一个局域网房间（同一 WiFi 的人能进来），或者加入别人已经开好的房间。
 * 进房间可以直接用应用内的 WebView 打开，也可以交给外部浏览器。
 */
class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var urlText: TextView
    private lateinit var hintText: TextView
    private lateinit var openRow: LinearLayout
    private lateinit var stopBtn: Button
    private lateinit var joinInput: EditText
    private lateinit var scanBox: LinearLayout

    private val pool = Executors.newSingleThreadExecutor()

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refresh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        setContentView(buildUi())
        registerState()
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(stateReceiver)
        } catch (e: Exception) {
            // 没注册上就算了
        }
        pool.shutdownNow()
        super.onDestroy()
    }

    private fun registerState() {
        val filter = IntentFilter(ServerService.ACTION_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(stateReceiver, filter)
        }
    }

    // ---------------------------------------------------------------- 界面
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
            setBackgroundColor(Color.parseColor("#F4F8F6"))
        }

        root.addView(TextView(this).apply {
            text = "大富翁"
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#22735F"))
        })
        root.addView(TextView(this).apply {
            text = "手机开一个局域网房间，同一个 WiFi 的人打开链接就能进来一起玩"
            textSize = 13f
            setTextColor(Color.parseColor("#7B8C85"))
            setPadding(0, dp(4), 0, dp(16))
        })

        statusText = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#3E544C"))
            setBackgroundColor(Color.parseColor("#FFFFFF"))
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        root.addView(statusText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) })

        val lanBtn = Button(this).apply {
            text = "创建局域网房间"
            textSize = 15f
            setTextColor(Color.WHITE)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#22735F"))
                cornerRadius = dp(12).toFloat()
            }
            setOnClickListener { startServer() }
        }
        root.addView(lanBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(48)
        ).apply { bottomMargin = dp(12) })

        urlText = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#1B5E4C"))
            setTextIsSelectable(true)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setBackgroundColor(Color.parseColor("#E7F3ED"))
            visibility = View.GONE
        }
        root.addView(urlText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(6) })

        hintText = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#8A7350"))
            setPadding(dp(4), dp(2), dp(4), dp(8))
            visibility = View.GONE
        }
        root.addView(hintText)

        openRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
        }
        openRow.addView(Button(this).apply {
            text = "应用内打开"
            textSize = 13f
            setOnClickListener { openInApp(ServerService.localUrl()) }
        }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(6) })
        openRow.addView(Button(this).apply {
            text = "浏览器打开"
            textSize = 13f
            setOnClickListener { openInBrowser(ServerService.localUrl()) }
        }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(6) })
        openRow.addView(Button(this).apply {
            text = "复制地址"
            textSize = 13f
            setOnClickListener { copyUrl() }
        }, LinearLayout.LayoutParams(0, dp(44), 1f))
        root.addView(openRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8) })

        stopBtn = Button(this).apply {
            text = "停止服务"
            textSize = 13f
            setTextColor(Color.parseColor("#A03A3A"))
            visibility = View.GONE
            setOnClickListener {
                ServerService.stop(this@MainActivity)
                handlerPost(300) { refresh() }
            }
        }
        root.addView(stopBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(44)
        ).apply { bottomMargin = dp(20) })

        root.addView(TextView(this).apply {
            text = "加入别人的房间"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#3E544C"))
            setPadding(0, dp(8), 0, dp(8))
        })

        joinInput = EditText(this).apply {
            hint = "对方地址，例如 192.168.1.5:9000"
            textSize = 13f
            setSingleLine(true)
        }
        root.addView(joinInput, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8) })

        val joinRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        joinRow.addView(Button(this).apply {
            text = "应用内打开"
            textSize = 13f
            setOnClickListener {
                val raw = joinInput.text.toString().trim()
                if (raw.isEmpty()) {
                    toast("先填地址")
                    return@setOnClickListener
                }
                openInApp(normalizeUrl(raw))
            }
        }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(6) })
        joinRow.addView(Button(this).apply {
            text = "扫描局域网"
            textSize = 13f
            setOnClickListener { scanLan() }
        }, LinearLayout.LayoutParams(0, dp(44), 1f))
        root.addView(joinRow)

        scanBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        root.addView(scanBox)

        val scroll = ScrollView(this)
        scroll.addView(root)
        scroll.setBackgroundColor(Color.parseColor("#F4F8F6"))
        return scroll
    }

    private fun handlerPost(delayMs: Long, action: () -> Unit) {
        android.os.Handler(mainLooper).postDelayed(action, delayMs)
    }

    // ---------------------------------------------------------------- 行为
    private fun startServer() {
        ServerService.start(this)
        toast("正在开局域网房间…")
        handlerPost(900) { refresh() }
        handlerPost(2400) { refresh() }
    }

    private fun refresh() {
        if (ServerService.running) {
            statusText.text = "服务运行中 · 局域网房间（同一个 WiFi 的人能扫到这个房间）"
            val url = ServerService.url()
            if (url.isEmpty()) {
                urlText.text = "同网段地址：没取到 Wi-Fi 地址，先确认手机连着 WiFi"
            } else {
                urlText.text = "同网段地址：$url"
            }
            urlText.visibility = View.VISIBLE

            val others = if (ServerService.url().isEmpty()) ""
            else "把上面地址发给同一个 WiFi 的人；他们也可以点「扫描局域网」自动找到这个房间。"
            hintText.text = others
            hintText.visibility = if (others.isEmpty()) View.GONE else View.VISIBLE
            openRow.visibility = View.VISIBLE
            stopBtn.visibility = View.VISIBLE
        } else {
            statusText.text = if (ServerService.lastError.isEmpty())
                "服务没在跑：点上面的按钮开一个房间"
            else "起不来：${ServerService.lastError}"
            urlText.visibility = View.GONE
            hintText.visibility = View.GONE
            openRow.visibility = View.GONE
            stopBtn.visibility = View.GONE
        }
    }

    private fun copyUrl() {
        val url = ServerService.url().ifEmpty { ServerService.localUrl() }
        if (url.isEmpty()) {
            toast("还没有地址可复制")
            return
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("大富翁地址", url))
        toast("已复制 $url")
    }

    private fun normalizeUrl(raw: String): String {
        var s = raw.trim()
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
        if (!s.substringAfter("//").contains(":")) s = s.trimEnd('/') + ":" + ServerHub.DEFAULT_PORT + "/"
        return s
    }

    private fun openInApp(url: String) {
        if (url.isEmpty()) {
            toast("服务还没起来")
            return
        }
        startActivity(Intent(this, WebClientActivity::class.java).putExtra("url", url))
    }

    private fun openInBrowser(url: String) {
        if (url.isEmpty()) {
            toast("服务还没起来")
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            toast("没有可用的浏览器")
        }
    }

    private fun scanLan() {
        toast("正在扫同一个 WiFi 下的房间…")
        scanBox.removeAllViews()
        pool.execute {
            val rooms = NetInfo.scan(4000)
            handlerPost(0) {
                if (rooms.isEmpty()) {
                    scanBox.addView(TextView(this).apply {
                        text = "没扫到房间。要么对方没开「局域网房间」，要么不在同一个 WiFi。可以手输地址。"
                        textSize = 12f
                        setTextColor(Color.parseColor("#8A9993"))
                    })
                    return@handlerPost
                }
                for (room in rooms) {
                    val b = Button(this).apply {
                        text = "${room.name} · ${room.host}:${room.port}"
                        textSize = 13f
                        setOnClickListener {
                            joinInput.setText("${room.host}:${room.port}")
                            openInApp("http://${room.host}:${room.port}/")
                        }
                    }
                    scanBox.addView(b, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(44)
                    ).apply { bottomMargin = dp(4) })
                }
            }
        }
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }
}

package com.monopoly.lan

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

/** 应用内直接打开网页：就是一块全屏 WebView，别的地方不用管。 */
class WebClientActivity : Activity() {

    private var web: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra("url") ?: ""

        val view = WebView(this)
        view.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        view.webViewClient = WebViewClient()
        view.webChromeClient = WebChromeClient()
        view.setBackgroundColor(0xFFF4F8F6.toInt())
        web = view

        val root = android.widget.FrameLayout(this)
        root.addView(view)
        setContentView(root)

        if (url.isNotEmpty()) view.loadUrl(url)
    }

    override fun onBackPressed() {
        val w = web
        if (w != null && w.canGoBack()) {
            w.goBack()
            return
        }
        super.onBackPressed()
    }

    override fun onDestroy() {
        web?.let {
            it.parent?.let { p -> (p as ViewGroup).removeView(it) }
            it.destroy()
        }
        web = null
        super.onDestroy()
    }

    /** 让界面上的返回按钮也能用（虽然目前没做工具栏）。 */
    fun goBackOrFinish() {
        val w = web
        if (w != null && w.canGoBack()) w.goBack() else finish()
    }
}

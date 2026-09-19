package com.pharmacy.kiosk

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 設定全螢幕，隱藏系統狀態列 (Kiosk 模式)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        
        webView = WebView(this)
        setContentView(webView)

        // WebView 設定
        val webSettings: WebSettings = webView.settings
        webSettings.javaScriptEnabled = true
        webSettings.domStorageEnabled = true
        webSettings.allowFileAccess = true
        webSettings.allowContentAccess = true

        // 綁定 JavaScript Interface
        webView.addJavascriptInterface(WebAppInterface(), "AndroidCardReader")

        // 載入本地 HTML
        webView.webViewClient = WebViewClient()
        webView.loadUrl("file:///android_asset/index.html")
    }

    // 防跳出：停用實體返回鍵 (Kiosk 鎖定)
    override fun onBackPressed() {
        // Do nothing to lock Kiosk mode
    }

    // 與網頁通訊的 JavaScript 介面
    inner class WebAppInterface {
        @JavascriptInterface
        fun startReadCard() {
            // 這裡可串接 USB 健保卡讀卡機原生日誌或 SDK
            // 模擬讀卡成功回傳 JSON 給網頁：
            runOnUiThread {
                val sampleJson = """{"idNum":"A123456789","name":"陳大明","birth":"0700101"}"""
                webView.evaluateJavascript("if(window.onAndroidCardReadSuccess){ onAndroidCardReadSuccess($sampleJson); }", null)
            }
        }
    }
}


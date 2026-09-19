package com.pharmacy.kiosk

import android.annotation.SuppressLint
import android.os.Bundle
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
        
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        
        webView = WebView(this)
        setContentView(webView)

        val webSettings: WebSettings = webView.settings
        webSettings.javaScriptEnabled = true
        webSettings.domStorageEnabled = true
        webSettings.allowFileAccess = true
        webSettings.allowContentAccess = true

        webView.addJavascriptInterface(WebAppInterface(), "AndroidCardReader")
        webView.webViewClient = WebViewClient()
        webView.loadUrl("file:///android_asset/index.html")
    }

    override fun onBackPressed() {
        // Lock Kiosk mode
    }

    inner class WebAppInterface {
        @JavascriptInterface
        fun startReadCard() {
            runOnUiThread {
                val sampleJson = """{"idNum":"A123456789","name":"陳大明","birth":"0700101"}"""
                webView.evaluateJavascript("if(window.onAndroidCardReadSuccess){ onAndroidCardReadSuccess($sampleJson); }", null)
            }
        }
    }
}

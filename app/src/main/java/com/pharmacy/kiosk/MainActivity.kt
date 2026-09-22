package com.pharmacy.kiosk

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.nio.charset.Charset
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var usbManager: UsbManager
    private val ACTION_USB_PERMISSION = "com.pharmacy.kiosk.USB_PERMISSION"

    // 監聽 USB 插入與權限同意
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    synchronized(this) {
                        val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                            device?.let { readHealthCard(it) }
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    device?.let { checkUsbDevice(it) }
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        
        webView = WebView(this)
        setContentView(webView)

        val webSettings: WebSettings = webView.settings
        webSettings.javaScriptEnabled = true
        webSettings.domStorageEnabled = true
        webView.webViewClient = WebViewClient()
        
        // 建立給 JavaScript 呼叫的介面 (如果網頁有手動讀卡按鈕)
        webView.addJavascriptInterface(WebAppInterface(), "AndroidCardReader")
        webView.loadUrl("file:///android_asset/index.html")

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        registerReceiver(usbReceiver, filter)

        // 啟動時掃描是否已經插著讀卡機
        usbManager.deviceList.values.forEach { checkUsbDevice(it) }
    }

    private fun checkUsbDevice(device: UsbDevice) {
        // 確認是否為晶片讀卡機 (Class 11 = Smart Card)
        for (i in 0 until device.interfaceCount) {
            if (device.getInterface(i).interfaceClass == 11) {
                if (usbManager.hasPermission(device)) {
                    readHealthCard(device)
                } else {
                    val flags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                    val pi = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), flags)
                    usbManager.requestPermission(device, pi)
                }
                break
            }
        }
    }

    // 核心實作：透過 CCID 協定讀取健保卡
    private fun readHealthCard(device: UsbDevice) {
        thread {
            try {
                val usbInterface = device.getInterface(0)
                val connection = usbManager.openDevice(device) ?: return@thread
                connection.claimInterface(usbInterface, true)

                var epIn: android.hardware.usb.UsbEndpoint? = null
                var epOut: android.hardware.usb.UsbEndpoint? = null
                for (i in 0 until usbInterface.endpointCount) {
                    val ep = usbInterface.getEndpoint(i)
                    if (ep.direction == UsbConstants.USB_DIR_IN) epIn = ep
                    if (ep.direction == UsbConstants.USB_DIR_OUT) epOut = ep
                }

                if (epIn != null && epOut != null) {
                    val buffer = ByteArray(256)
                    
                    // 1. IccPowerOn (啟動卡片)
                    val pwrCmd = byteArrayOf(0x62, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
                    connection.bulkTransfer(epOut, pwrCmd, pwrCmd.size, 2000)
                    connection.bulkTransfer(epIn, buffer, buffer.size, 2000) 

                    // 2. 選擇健保卡 Profile APDU
                    val selectApdu = byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, 0x10, 0xD1.toByte(), 0x58, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x11, 0x00)
                    val selectCmd = wrapCCID(selectApdu, 1)
                    connection.bulkTransfer(epOut, selectCmd, selectCmd.size, 2000)
                    connection.bulkTransfer(epIn, buffer, buffer.size, 2000)

                    // 3. 讀取健保卡公開資料 APDU
                    val readApdu = byteArrayOf(0x00, 0xCA.toByte(), 0x11, 0x00, 0x02)
                    val readCmd = wrapCCID(readApdu, 2)
                    connection.bulkTransfer(epOut, readCmd, readCmd.size, 2000)
                    val bytesRead = connection.bulkTransfer(epIn, buffer, buffer.size, 2000)

                    // 4. 解析回傳資料 (CCID Header佔10 bytes，後面才是資料)
                    if (bytesRead > 50) {
                        val nameBytes = buffer.copyOfRange(10 + 12, 10 + 32) // 姓名區塊 20 bytes
                        val name = String(nameBytes, Charset.forName("Big5")).replace("\u0000", "").trim()
                        val idNum = String(buffer, 10 + 32, 10).trim()       // 身分證 10 bytes
                        val birth = String(buffer, 10 + 42, 7).trim()        // 生日 7 bytes (民國)

                        val jsonResult = JSONObject().apply {
                            put("name", name)
                            put("idNum", idNum)
                            put("birth", birth)
                            put("status", "success")
                        }.toString()

                        // 拋回給網頁端
                        runOnUiThread {
                            webView.evaluateJavascript("if(typeof onAndroidCardReadSuccess === 'function') { onAndroidCardReadSuccess($jsonResult); }", null)
                        }
                    }
                }
                connection.releaseInterface(usbInterface)
                connection.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // CCID APDU 封裝計算
    private fun wrapCCID(apdu: ByteArray, seq: Byte): ByteArray {
        val cmd = ByteArray(10 + apdu.size)
        cmd[0] = 0x6F // PC_to_RDR_XfrBlock
        cmd[1] = (apdu.size and 0xFF).toByte()
        cmd[2] = ((apdu.size shr 8) and 0xFF).toByte()
        cmd[6] = seq
        System.arraycopy(apdu, 0, cmd, 10, apdu.size)
        return cmd
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbReceiver)
    }

    inner class WebAppInterface {
        @JavascriptInterface
        fun startReadCard() {
            // 保留給網頁按鈕手動觸發的接口，可搭配 checkUsbDevice 實作
        }
    }
}

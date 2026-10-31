package app.qingchuan

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.qingchuan.core.Engine
import app.qingchuan.core.Prefs
import app.qingchuan.service.TransferService
import app.qingchuan.ui.AppRoot

class MainActivity : ComponentActivity() {

    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as QcApp
        prefs = app.prefs
        Engine.init(this, prefs)
        // 后台接收开启时交给前台服务托管；否则只在应用可见期间运行。
        if (prefs.background.value) {
            TransferService.start(this)
        } else {
            Engine.start()
        }
        setContent { AppRoot(this, prefs) }
    }

    override fun onStart() {
        super.onStart()
        if (!prefs.background.value) Engine.start()
    }

    override fun onStop() {
        super.onStop()
        if (!prefs.background.value) Engine.stop()
    }
}

package app.qingchuan

import android.app.Application
import app.qingchuan.core.BuildInfo
import app.qingchuan.core.ImageStore
import app.qingchuan.core.Prefs

class QcApp : Application() {

    lateinit var prefs: Prefs
        private set

    override fun onCreate() {
        super.onCreate()
        BuildInfo.VERSION_NAME = BuildConfig.VERSION_NAME
        prefs = Prefs(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // 图片缓存是可再生的，内存吃紧时先丢它。
        if (level >= TRIM_MEMORY_RUNNING_LOW) ImageStore.clearMemory()
    }
}

package app.tappy

import android.app.Application

class TappyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        TappyStore.init(this)
    }
}

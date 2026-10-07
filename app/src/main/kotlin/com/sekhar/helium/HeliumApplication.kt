package com.sekhar.helium

import android.app.Application
import com.sekhar.helium.di.AppContainer
import com.sekhar.helium.di.DefaultAppContainer

/**
 * Process entry point.
 *
 * The container is created here rather than with a DI framework so the module
 * graph stays explicit and inspectable: every dependency of the app is one line
 * in [DefaultAppContainer], and tests can substitute fakes by implementing
 * [AppContainer].
 */
class HeliumApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = DefaultAppContainer(this)
    }
}

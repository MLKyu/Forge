package com.mingeek.forge

import android.app.Application
import com.mingeek.forge.di.ForgeContainer

class ForgeApplication : Application() {

    lateinit var container: ForgeContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = ForgeContainer(this)
    }
}

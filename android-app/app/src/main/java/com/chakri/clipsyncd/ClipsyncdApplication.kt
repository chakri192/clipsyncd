package com.chakri.clipsyncd

import android.app.Application
import com.google.android.material.color.DynamicColors

class ClipsyncdApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}

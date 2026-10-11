package com.metasousa.app

import android.app.Application
import android.util.Log
import com.meta.wearable.dat.core.Wearables

class MetaSousaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Wearables.initialize(this).onFailure { error, _ ->
            Log.e("MetaSousa", "No se pudo iniciar el kit de Meta: ${error.description}")
        }
    }
}

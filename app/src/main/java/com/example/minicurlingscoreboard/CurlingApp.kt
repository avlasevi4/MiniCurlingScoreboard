package com.example.minicurlingscoreboard

import android.app.Application
import com.example.minicurlingscoreboard.data.AppDatabase

class CurlingApp : Application() {
    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }
}

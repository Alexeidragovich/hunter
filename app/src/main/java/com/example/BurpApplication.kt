package com.example

import android.app.Application
import androidx.room.Room
import com.example.data.AppDatabase

class BurpApplication : Application() {
    companion object {
        lateinit var database: AppDatabase
            private set
    }

    override fun onCreate() {
        super.onCreate()
        database = Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java,
            "burp_suite_mobile_db"
        )
        .fallbackToDestructiveMigration()
        .build()
    }
}

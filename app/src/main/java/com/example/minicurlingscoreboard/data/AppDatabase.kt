package com.example.minicurlingscoreboard.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [GameResult::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun gameResultDao(): GameResultDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        /** v2 added per-player stone colors, the base end count, and the per-end score history. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE game_results ADD COLUMN player1ColorName TEXT NOT NULL DEFAULT 'RED'")
                db.execSQL("ALTER TABLE game_results ADD COLUMN player2ColorName TEXT NOT NULL DEFAULT 'BLUE'")
                db.execSQL("ALTER TABLE game_results ADD COLUMN baseEnds INTEGER NOT NULL DEFAULT 10")
                db.execSQL("ALTER TABLE game_results ADD COLUMN endsData TEXT NOT NULL DEFAULT ''")
            }
        }

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "curling.db"
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
    }
}

package com.btcsignal.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [SignalEntity::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun signalDao(): SignalDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        /** Adds the price-path snapshot column (see PricePathSnapshot.kt / HistoryScreen)
         *  without wiping existing signal history. Nullable with no default needed beyond
         *  SQLite's implicit NULL, since old rows simply have no snapshot to show. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE signals ADD COLUMN pricePathJson TEXT")
            }
        }

        /** Adds originStrategyId/originStrategyName (see Signal.kt / SignalEntity.kt) so
         *  a Reversal-Zone (Checkpoint C) row can be attributed back to the primary
         *  strategy that armed its scan — needed for "Reversal-Zone Strategy Usage"
         *  (§24). Existing rows (every primary row, and every Reversal-Zone row fired
         *  before this migration) get NULL for both columns, which the Performance/
         *  Backtest screens already treat as "unattributed" rather than crashing or
         *  guessing an origin. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE signals ADD COLUMN originStrategyId TEXT")
                db.execSQL("ALTER TABLE signals ADD COLUMN originStrategyName TEXT")
            }
        }

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "btc_signal.db"
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { instance = it }
        }
    }
}

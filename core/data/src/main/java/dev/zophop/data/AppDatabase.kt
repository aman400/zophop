package dev.zophop.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_4_5 =
    object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            val cursorPrec = db.query("PRAGMA table_info(`preconditions`)")
            val existingPrecColumns = mutableSetOf<String>()
            cursorPrec.use {
                val nameIndex = it.getColumnIndex("name")
                while (it.moveToNext()) {
                    if (nameIndex != -1) {
                        existingPrecColumns.add(it.getString(nameIndex))
                    }
                }
            }
            if ("minBatteryPercent" !in existingPrecColumns) {
                db.execSQL("ALTER TABLE `preconditions` ADD COLUMN `minBatteryPercent` INTEGER NOT NULL DEFAULT 15")
            }
            if ("requireCharging" !in existingPrecColumns) {
                db.execSQL("ALTER TABLE `preconditions` ADD COLUMN `requireCharging` INTEGER NOT NULL DEFAULT 0")
            }
            if ("retryStrategy" !in existingPrecColumns) {
                db.execSQL("ALTER TABLE `preconditions` ADD COLUMN `retryStrategy` TEXT NOT NULL DEFAULT 'FIXED'")
            }
            if ("queueTimeoutMinutes" !in existingPrecColumns) {
                db.execSQL("ALTER TABLE `preconditions` ADD COLUMN `queueTimeoutMinutes` INTEGER NOT NULL DEFAULT 10")
            }
            if ("icon" !in existingPrecColumns) {
                db.execSQL("ALTER TABLE `preconditions` ADD COLUMN `icon` TEXT NOT NULL DEFAULT ''")
            }

            val cursorWf = db.query("PRAGMA table_info(`workflows`)")
            val existingWfColumns = mutableSetOf<String>()
            cursorWf.use {
                val nameIndex = it.getColumnIndex("name")
                while (it.moveToNext()) {
                    if (nameIndex != -1) {
                        existingWfColumns.add(it.getString(nameIndex))
                    }
                }
            }
            if ("icon" !in existingWfColumns) {
                db.execSQL("ALTER TABLE `workflows` ADD COLUMN `icon` TEXT NOT NULL DEFAULT ''")
            }
            if ("createdAt" !in existingWfColumns) {
                db.execSQL("ALTER TABLE `workflows` ADD COLUMN `createdAt` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE `workflows` SET `createdAt` = `updatedAt` WHERE `createdAt` = 0 AND `updatedAt` > 0")
            }
        }
    }

val ALL_MIGRATIONS = arrayOf(MIGRATION_4_5)

@Database(
    entities = [
        MessageEntity::class,
        WorkflowEntity::class,
        WorkflowExecutionLogEntity::class,
        CredentialEntity::class,
        PreconditionEntity::class,
    ],
    version = 5,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao

    abstract fun workflowDao(): WorkflowDao

    abstract fun credentialDao(): CredentialDao

    abstract fun preconditionDao(): PreconditionDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                val instance =
                    Room.databaseBuilder(
                        context.applicationContext,
                        AppDatabase::class.java,
                        "zophop_db",
                    ).addMigrations(MIGRATION_4_5)
                        .fallbackToDestructiveMigrationFrom(dropAllTables = true, 1, 2, 3)
                        .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                        .build()
                INSTANCE = instance
                instance
            }
    }
}

package dev.zophop.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.zophop.domain.model.generateShortWorkflowId

private val UUID_REGEX =
    Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

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

            shortenUuidWorkflowAndExecutionIds(db)
        }
    }

internal fun shortenUuidWorkflowAndExecutionIds(db: SupportSQLiteDatabase) {
    shortenUuidWorkflowIds(db)
    shortenUuidExecutionIds(db)
}

private fun nextUniqueShortId(usedIds: MutableSet<String>): String {
    var newId = generateShortWorkflowId()
    while (!usedIds.add(newId)) {
        newId = generateShortWorkflowId()
    }
    return newId
}

private fun querySingleColumn(
    db: SupportSQLiteDatabase,
    sql: String,
    column: String,
): List<String> {
    val results = mutableListOf<String>()
    db.query(sql).use { cursor ->
        val idx = cursor.getColumnIndex(column)
        while (cursor.moveToNext()) {
            if (idx != -1) results.add(cursor.getString(idx))
        }
    }
    return results
}

private fun shortenUuidWorkflowIds(db: SupportSQLiteDatabase) {
    val existingWfIds = querySingleColumn(db, "SELECT `id` FROM `workflows`", "id")
    val usedWfIds = existingWfIds.toMutableSet()
    val wfIdMap = mutableMapOf<String, String>()
    existingWfIds.filter { UUID_REGEX.matches(it) }.forEach { oldId ->
        wfIdMap[oldId] = nextUniqueShortId(usedWfIds)
    }
    wfIdMap.forEach { (oldId, newId) ->
        db.execSQL("UPDATE `workflows` SET `id` = ? WHERE `id` = ?", arrayOf<Any>(newId, oldId))
        db.execSQL("UPDATE `workflow_execution_logs` SET `workflowId` = ? WHERE `workflowId` = ?", arrayOf<Any>(newId, oldId))
    }

    val logWorkflowIds = querySingleColumn(db, "SELECT DISTINCT `workflowId` FROM `workflow_execution_logs`", "workflowId")
    logWorkflowIds.filter { UUID_REGEX.matches(it) }.forEach { oldWfId ->
        val mappedId = wfIdMap.getOrPut(oldWfId) { nextUniqueShortId(usedWfIds) }
        db.execSQL(
            "UPDATE `workflow_execution_logs` SET `workflowId` = ? WHERE `workflowId` = ?",
            arrayOf<Any>(mappedId, oldWfId),
        )
    }
}

private fun shortenUuidExecutionIds(db: SupportSQLiteDatabase) {
    val logRows = mutableListOf<Pair<String, String>>()
    db.query("SELECT `id`, `logs` FROM `workflow_execution_logs`").use { cursor ->
        val idIdx = cursor.getColumnIndex("id")
        val logsIdx = cursor.getColumnIndex("logs")
        while (cursor.moveToNext()) {
            if (idIdx != -1) {
                logRows.add(cursor.getString(idIdx) to (if (logsIdx != -1) cursor.getString(logsIdx) ?: "" else ""))
            }
        }
    }

    val usedExecIds = logRows.map { it.first }.toMutableSet()
    val execIdMap = mutableMapOf<String, String>()
    logRows.filter { UUID_REGEX.matches(it.first) }.forEach { (oldExecId, _) ->
        execIdMap[oldExecId] = nextUniqueShortId(usedExecIds)
    }
    execIdMap.forEach { (oldExecId, newExecId) ->
        db.execSQL("UPDATE `workflow_execution_logs` SET `id` = ? WHERE `id` = ?", arrayOf<Any>(newExecId, oldExecId))
    }
    if (execIdMap.isEmpty()) return

    logRows.forEach { (oldExecId, logs) ->
        var updatedLogs = logs
        execIdMap.forEach { (oldRef, newRef) ->
            if (updatedLogs.contains(oldRef)) updatedLogs = updatedLogs.replace(oldRef, newRef)
        }
        if (updatedLogs != logs) {
            db.execSQL(
                "UPDATE `workflow_execution_logs` SET `logs` = ? WHERE `id` = ?",
                arrayOf<Any>(updatedLogs, execIdMap[oldExecId] ?: oldExecId),
            )
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
                val builder =
                    Room.databaseBuilder(
                        context.applicationContext,
                        AppDatabase::class.java,
                        "zophop_db",
                    )
                ALL_MIGRATIONS.forEach { builder.addMigrations(it) }
                val instance =
                    builder
                        .addCallback(
                            object : Callback() {
                                override fun onOpen(db: SupportSQLiteDatabase) {
                                    super.onOpen(db)
                                    if (!db.isReadOnly) {
                                        db.beginTransaction()
                                        try {
                                            shortenUuidWorkflowAndExecutionIds(db)
                                            db.setTransactionSuccessful()
                                        } finally {
                                            db.endTransaction()
                                        }
                                    }
                                }
                            },
                        )
                        .fallbackToDestructiveMigrationFrom(dropAllTables = true, 1, 2, 3)
                        .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                        .build()
                INSTANCE = instance
                instance
            }
    }
}

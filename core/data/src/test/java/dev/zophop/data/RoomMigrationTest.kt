package dev.zophop.data

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

class RoomMigrationTest {
    @Test
    fun testMigrationConstantsAndVersion() {
        assertNotNull(MIGRATION_4_5)

        assertEquals(1, ALL_MIGRATIONS.size)

        assertEquals(4, MIGRATION_4_5.startVersion)
        assertEquals(5, MIGRATION_4_5.endVersion)
    }

    @Test
    fun testMigration4to5ExecutesAltersWhenColumnsMissing() {
        val executedSql = mutableListOf<String>()

        val fakeDbHandler =
            InvocationHandler { _, method, args ->
                when (method.name) {
                    "execSQL" -> {
                        executedSql.add(args[0] as String)
                        null
                    }
                    "query" -> {
                        Proxy.newProxyInstance(
                            android.database.Cursor::class.java.classLoader,
                            arrayOf(android.database.Cursor::class.java),
                        ) { _, cMethod, _ ->
                            when (cMethod.name) {
                                "getColumnIndex" -> 0
                                "moveToNext" -> false
                                "close" -> null
                                else -> null
                            }
                        }
                    }
                    else -> null
                }
            }

        val fakeDb =
            Proxy.newProxyInstance(
                SupportSQLiteDatabase::class.java.classLoader,
                arrayOf(SupportSQLiteDatabase::class.java),
                fakeDbHandler,
            ) as SupportSQLiteDatabase

        MIGRATION_4_5.migrate(fakeDb)

        assertEquals(8, executedSql.size)
        assertTrue(executedSql.any { it.contains("ALTER TABLE `preconditions` ADD COLUMN `minBatteryPercent`") })
        assertTrue(executedSql.any { it.contains("ALTER TABLE `preconditions` ADD COLUMN `requireCharging`") })
        assertTrue(executedSql.any { it.contains("ALTER TABLE `preconditions` ADD COLUMN `retryStrategy`") })
        assertTrue(executedSql.any { it.contains("ALTER TABLE `preconditions` ADD COLUMN `queueTimeoutMinutes`") })
        assertTrue(executedSql.any { it.contains("ALTER TABLE `preconditions` ADD COLUMN `icon`") })
        assertTrue(executedSql.any { it.contains("ALTER TABLE `workflows` ADD COLUMN `icon`") })
        assertTrue(executedSql.any { it.contains("ALTER TABLE `workflows` ADD COLUMN `createdAt`") })
        assertTrue(executedSql.any { it.contains("UPDATE `workflows` SET `createdAt` = `updatedAt`") })
    }

    @Test
    fun testMigration4to5SkipsAltersWhenColumnsAlreadyPresent() {
        val executedSql = mutableListOf<String>()
        val columns =
            listOf(
                "id",
                "name",
                "minBatteryPercent",
                "requireCharging",
                "retryStrategy",
                "queueTimeoutMinutes",
                "icon",
                "createdAt",
            )
        var cursorIndex: Int

        val fakeDbHandler =
            InvocationHandler { _, method, args ->
                when (method.name) {
                    "execSQL" -> {
                        executedSql.add(args[0] as String)
                        null
                    }
                    "query" -> {
                        cursorIndex = -1
                        Proxy.newProxyInstance(
                            android.database.Cursor::class.java.classLoader,
                            arrayOf(android.database.Cursor::class.java),
                        ) { _, cMethod, _ ->
                            when (cMethod.name) {
                                "getColumnIndex" -> 0
                                "moveToNext" -> {
                                    cursorIndex++
                                    cursorIndex < columns.size
                                }
                                "getString" -> columns[cursorIndex]
                                "close" -> null
                                else -> null
                            }
                        }
                    }
                    else -> null
                }
            }

        val fakeDb =
            Proxy.newProxyInstance(
                SupportSQLiteDatabase::class.java.classLoader,
                arrayOf(SupportSQLiteDatabase::class.java),
                fakeDbHandler,
            ) as SupportSQLiteDatabase

        MIGRATION_4_5.migrate(fakeDb)

        // All columns were already present, so 0 alters should be executed!
        assertEquals(0, executedSql.size)
    }

    @Test
    fun testMigration4to5ShortensUuidWorkflowAndExecutionIds() {
        val uuidWf = "550e8400-e29b-41d4-a716-446655440000"
        val uuidExec1 = "123e4567-e89b-12d3-a456-426614174000"
        val uuidExec2 = "987e6543-e21b-12d3-a456-426614174111"

        val workflows = mutableListOf(uuidWf, "default_manual_workflow")

        data class LogRow(
            var id: String,
            var workflowId: String,
            var logs: String,
        )

        val logRows =
            mutableListOf(
                LogRow(uuidExec1, uuidWf, """[{"retriedInExecutionId":"$uuidExec2"}]"""),
                LogRow(uuidExec2, "default_manual_workflow", "[]"),
            )
        val tableColumns =
            listOf(
                "id",
                "name",
                "minBatteryPercent",
                "requireCharging",
                "retryStrategy",
                "queueTimeoutMinutes",
                "icon",
                "createdAt",
            )

        fun createCursor(columns: List<String>, data: List<List<String>>): android.database.Cursor {
            var idx = -1
            return Proxy.newProxyInstance(
                android.database.Cursor::class.java.classLoader,
                arrayOf(android.database.Cursor::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "getColumnIndex" -> columns.indexOf(args[0] as String)
                    "moveToNext" -> {
                        idx++
                        idx < data.size
                    }
                    "getString" -> data[idx][args[0] as Int]
                    "close" -> null
                    else -> null
                }
            } as android.database.Cursor
        }

        val fakeDbHandler =
            InvocationHandler { _, method, args ->
                when (method.name) {
                    "query" -> {
                        val sql = args[0] as String
                        when {
                            sql.startsWith("PRAGMA table_info") ->
                                createCursor(listOf("name"), tableColumns.map { listOf(it) })
                            sql.contains("FROM `workflows`") ->
                                createCursor(listOf("id"), workflows.map { listOf(it) })
                            sql.contains("DISTINCT `workflowId`") ->
                                createCursor(listOf("workflowId"), logRows.map { listOf(it.workflowId) }.distinct())
                            sql.contains("FROM `workflow_execution_logs`") ->
                                createCursor(listOf("id", "logs"), logRows.map { listOf(it.id, it.logs) })
                            else -> createCursor(emptyList(), emptyList())
                        }
                    }
                    "execSQL" -> {
                        val sql = args[0] as String
                        val bindArgs = args.getOrNull(1) as? Array<*>
                        if (bindArgs != null) {
                            when {
                                sql.startsWith("UPDATE `workflows` SET `id`") -> {
                                    val newId = bindArgs[0] as String
                                    val oldId = bindArgs[1] as String
                                    val pos = workflows.indexOf(oldId)
                                    if (pos >= 0) workflows[pos] = newId
                                }
                                sql.startsWith("UPDATE `workflow_execution_logs` SET `workflowId`") -> {
                                    val newId = bindArgs[0] as String
                                    val oldId = bindArgs[1] as String
                                    logRows.filter { it.workflowId == oldId }.forEach { it.workflowId = newId }
                                }
                                sql.startsWith("UPDATE `workflow_execution_logs` SET `id`") -> {
                                    val newId = bindArgs[0] as String
                                    val oldId = bindArgs[1] as String
                                    logRows.filter { it.id == oldId }.forEach { it.id = newId }
                                }
                                sql.startsWith("UPDATE `workflow_execution_logs` SET `logs`") -> {
                                    val updatedLogs = bindArgs[0] as String
                                    val id = bindArgs[1] as String
                                    logRows.filter { it.id == id }.forEach { it.logs = updatedLogs }
                                }
                            }
                        }
                        null
                    }
                    else -> null
                }
            }

        val fakeDb =
            Proxy.newProxyInstance(
                SupportSQLiteDatabase::class.java.classLoader,
                arrayOf(SupportSQLiteDatabase::class.java),
                fakeDbHandler,
            ) as SupportSQLiteDatabase

        MIGRATION_4_5.migrate(fakeDb)

        val shortIdRegex = Regex("^[A-Z0-9]{8}$")
        assertTrue(shortIdRegex.matches(workflows[0]))
        assertEquals("default_manual_workflow", workflows[1])

        assertTrue(shortIdRegex.matches(logRows[0].id))
        assertEquals(workflows[0], logRows[0].workflowId)
        assertTrue(shortIdRegex.matches(logRows[1].id))
        assertEquals("default_manual_workflow", logRows[1].workflowId)
        assertTrue(logRows[0].logs.contains(logRows[1].id))
        assertTrue(!logRows[0].logs.contains(uuidExec2))
    }
}

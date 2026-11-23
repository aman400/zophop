package dev.zophop.plugin.notification

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.zophop.domain.model.NodeType
import dev.zophop.domain.model.WorkflowNode
import dev.zophop.plugin.api.ExecutionContext
import dev.zophop.plugin.api.NodeExecutionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE)
class NotificationNodePluginServiceTest {
    @Test
    fun testNotificationPluginManifestStructure() {
        val serviceController = Robolectric.buildService(NotificationNodePluginService::class.java).create()
        val service = serviceController.get()
        val binder = service.onBind(null) as dev.zophop.plugin.api.INodePluginService
        val manifests = binder.getManifests()

        assertEquals(1, manifests.size)
        val notifTrigger = manifests.first()

        assertEquals("dev.zophop.plugin.notification.NOTIFICATION_TRIGGER", notifTrigger.typeId)
        assertTrue(notifTrigger.requiredPermissions.contains("NOTIFICATION_LISTENER"))
        assertEquals(5, notifTrigger.configSchema.size)
    }

    @Test
    fun testNotificationExecutorCreation() {
        val executor =
            NotificationPluginModule.createExecutor(
                "dev.zophop.plugin.notification.NOTIFICATION_TRIGGER",
                okhttp3.OkHttpClient(),
                null,
            )
        assertNotNull(executor)
        assertTrue(executor is NotificationTriggerExecutor)
    }

    @Test
    fun testNotificationTriggerExecutorExecution() =
        kotlinx.coroutines.test.runTest {
            val executor =
                NotificationPluginModule.createExecutor(
                    "NOTIFICATION_TRIGGER",
                    okhttp3.OkHttpClient(),
                    null,
                ) as NotificationTriggerExecutor
            val node =
                WorkflowNode(
                    "n1",
                    "Notif Trigger",
                    NodeType.NOTIFICATION_TRIGGER,
                    true,
                    """{"packageFilter":"com.example.bankapp","textRegex":"OTP"}""",
                )
            val context =
                ExecutionContext(mapOf("package_name" to "com.example.bankapp", "title" to "Bank Alert", "text" to "Your OTP is 1234"))

            val result = executor.execute(node, context, null)
            assertTrue(result is NodeExecutionResult.Success)
        }

    @Test
    fun testNotificationTriggerIgnoredOnNonMatchingPackage() =
        kotlinx.coroutines.test.runTest {
            val executor =
                NotificationPluginModule.createExecutor(
                    "NOTIFICATION_TRIGGER",
                    okhttp3.OkHttpClient(),
                    null,
                ) as NotificationTriggerExecutor
            val node =
                WorkflowNode(
                    "n1",
                    "Notif Trigger",
                    NodeType.NOTIFICATION_TRIGGER,
                    true,
                    """{"packageFilter":"com.example.bankapp","textRegex":"OTP"}""",
                )
            val context = ExecutionContext(mapOf("package_name" to "com.other.app", "title" to "Alert", "text" to "Your OTP is 1234"))

            val result = executor.execute(node, context, null)
            assertTrue(result is NodeExecutionResult.Ignored)
        }
}

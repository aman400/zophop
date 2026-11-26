package dev.zophop.domain.engine

import dev.zophop.domain.engine.plugin.BuiltInPluginRegistry
import dev.zophop.domain.model.NodeType
import org.junit.Assert.assertEquals
import org.junit.Test

class AllNodeExecutorsDetailedTest {
    @Test
    fun testNodeExecutorFactoryCreatesCorrectExecutors() {
        BuiltInPluginRegistry.registerModule(dev.zophop.plugin.sms.SmsPluginModule)
        BuiltInPluginRegistry.registerModule(dev.zophop.plugin.notification.NotificationPluginModule)
        BuiltInPluginRegistry.registerModule(dev.zophop.plugin.ntfy.NtfyPluginModule)
        BuiltInPluginRegistry.registerModule(dev.zophop.plugin.gotify.GotifyPluginModule)
        BuiltInPluginRegistry.registerModule(dev.zophop.plugin.call.CallPluginModule)
        BuiltInPluginRegistry.registerModule(dev.zophop.plugin.gps.GpsPluginModule)

        val factory = NodeExecutorFactory()

        assertEquals("SmsTriggerExecutor", factory.getExecutor("dev.zophop.plugin.sms.SMS_TRIGGER")::class.java.simpleName)
        assertEquals(
            "NotificationTriggerExecutor",
            factory.getExecutor("dev.zophop.plugin.notification.NOTIFICATION_TRIGGER")::class.java.simpleName,
        )
        assertEquals("ManualTriggerExecutor", factory.getExecutor(NodeType.MANUAL_TRIGGER)::class.java.simpleName)
        assertEquals("RegexParserExecutor", factory.getExecutor(NodeType.REGEX_PARSER)::class.java.simpleName)
        assertEquals("JsonParserExecutor", factory.getExecutor(NodeType.JSON_PARSER)::class.java.simpleName)
        assertEquals("TemplateFormatterExecutor", factory.getExecutor(NodeType.TEMPLATE_FORMATTER)::class.java.simpleName)
        assertEquals("ConditionExecutor", factory.getExecutor(NodeType.CONDITION)::class.java.simpleName)
        assertEquals("HttpActionExecutor", factory.getExecutor(NodeType.HTTP_ACTION)::class.java.simpleName)
        assertEquals("NtfyActionExecutor", factory.getExecutor(NodeType.NTFY_ACTION)::class.java.simpleName)
        assertEquals("GotifyActionExecutor", factory.getExecutor(NodeType.GOTIFY_ACTION)::class.java.simpleName)
        assertEquals("SendNotificationExecutor", factory.getExecutor(NodeType.SEND_NOTIFICATION_ACTION)::class.java.simpleName)
        assertEquals("SendSmsExecutor", factory.getExecutor("dev.zophop.plugin.sms.SEND_SMS_ACTION")::class.java.simpleName)
    }
}

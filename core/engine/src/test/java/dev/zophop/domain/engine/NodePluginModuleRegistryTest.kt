package dev.zophop.domain.engine

import dev.zophop.domain.engine.plugin.BuiltInPluginRegistry
import dev.zophop.domain.engine.plugin.CoreEngineNodesModule
import dev.zophop.domain.engine.plugin.UnifiedPluginRegistry
import dev.zophop.plugin.api.ConfigFieldSpec
import dev.zophop.plugin.api.NodeCategory
import dev.zophop.plugin.api.NodePluginManifest
import dev.zophop.plugin.api.NodePluginModule
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NodePluginModuleRegistryTest {
    object CustomTestModule : NodePluginModule {
        override val moduleId: String = "custom-test-module"

        override fun getManifests(): List<NodePluginManifest> =
            listOf(
                NodePluginManifest(
                    typeId = "com.test.CUSTOM_TRIGGER",
                    displayName = "Custom Test Trigger",
                    description = "Custom test trigger node",
                    category = NodeCategory.TRIGGER,
                ),
            )
    }

    class ClassBasedTestModule : NodePluginModule {
        override val moduleId: String = "class-based-test-module"

        override fun getManifests(): List<NodePluginManifest> =
            listOf(
                NodePluginManifest(
                    typeId = "com.test.CLASS_BASED_ACTION",
                    displayName = "Class Based Action",
                    description = "Class-based module test",
                    category = NodeCategory.ACTION,
                ),
            )
    }

    @Test
    fun testCoreEngineNodesModuleManifestDiscovery() {
        val manifests = CoreEngineNodesModule.getManifests()
        assertTrue(manifests.isNotEmpty())
        assertTrue(manifests.any { it.typeId == "MANUAL_TRIGGER" })
        assertTrue(manifests.any { it.typeId == "HTTP_ACTION" })
    }

    @Test
    fun testBuiltInPluginRegistryModuleRegistration() {
        BuiltInPluginRegistry.registerModule(CustomTestModule)

        val allBuiltIn = BuiltInPluginRegistry.getBuiltInManifests()
        assertTrue(allBuiltIn.any { it.typeId == "com.test.CUSTOM_TRIGGER" })

        val fetched = BuiltInPluginRegistry.getBuiltInManifestByTypeId("com.test.CUSTOM_TRIGGER")
        assertNotNull(fetched)
        assertEquals("Custom Test Trigger", fetched?.displayName)

        BuiltInPluginRegistry.unregisterModule(CustomTestModule.moduleId)
    }

    @Test
    fun testClassBasedModuleResolutionAndRegistration() {
        val module = ClassBasedTestModule()
        BuiltInPluginRegistry.registerModule(module)

        val allBuiltIn = BuiltInPluginRegistry.getBuiltInManifests()
        assertTrue(allBuiltIn.any { it.typeId == "com.test.CLASS_BASED_ACTION" })

        val fetched = BuiltInPluginRegistry.getBuiltInManifestByTypeId("com.test.CLASS_BASED_ACTION")
        assertNotNull(fetched)
        assertEquals("Class Based Action", fetched?.displayName)

        BuiltInPluginRegistry.unregisterModule(module.moduleId)
    }

    @Test
    fun testUnifiedPluginRegistryResolvesBuiltInManifests() =
        runTest {
            val unifiedRegistry = UnifiedPluginRegistry(null)
            val manifests = unifiedRegistry.getAllManifests()

            assertTrue(manifests.isNotEmpty())
            assertTrue(manifests.any { it.typeId == "MANUAL_TRIGGER" })
        }

    @Test
    fun testNtfyAndGotifyManifestResolutionByShortNameAndFqn() =
        runTest {
            val unifiedRegistry = UnifiedPluginRegistry(null)

            val ntfyManifestByFqn = unifiedRegistry.getManifestByTypeId("dev.zophop.plugin.ntfy.NTFY_ACTION")
            assertNotNull(ntfyManifestByFqn)
            assertEquals("ntfy Push Action", ntfyManifestByFqn?.displayName)

            val ntfyManifestByShort = unifiedRegistry.getManifestByTypeId("NTFY_ACTION")
            assertNotNull(ntfyManifestByShort)
            assertEquals("ntfy Push Action", ntfyManifestByShort?.displayName)

            val gotifyManifestByFqn = unifiedRegistry.getManifestByTypeId("dev.zophop.plugin.gotify.GOTIFY_ACTION")
            assertNotNull(gotifyManifestByFqn)
            assertEquals("Gotify Push Action", gotifyManifestByFqn?.displayName)

            val gotifyManifestByShort = unifiedRegistry.getManifestByTypeId("GOTIFY_ACTION")
            assertNotNull(gotifyManifestByShort)
            assertEquals("Gotify Push Action", gotifyManifestByShort?.displayName)
        }

    @Test
    fun testConditionNodeValidationDefaultsOperatorToEquals() {
        // Variable provided, operator omitted -> should default to EQUALS and pass validation
        val errorWithMissingOperator = CoreEngineNodesModule.validateConfig("CONDITION", """{"variable":"status","value":"active"}""")
        org.junit.Assert.assertNull(errorWithMissingOperator)

        // Variable provided, empty operator -> should default to EQUALS and pass validation
        val errorWithEmptyOperator =
            CoreEngineNodesModule.validateConfig(
                "CONDITION",
                """{"variable":"status","operator":"","value":"active"}""",
            )
        org.junit.Assert.assertNull(errorWithEmptyOperator)

        // Blank variable without fallback -> should default to "status"
        val errorWithEmptyConfig = CoreEngineNodesModule.validateConfig("CONDITION", "{}")
        org.junit.Assert.assertNull(errorWithEmptyConfig)
    }

    @Test
    fun testAllNodeTypesDefaultConfigValidation() {
        // Empty configs for all built-in types should validate cleanly with defaults
        val types =
            listOf(
                "CONDITION",
                "CRON_TRIGGER",
                "TEMPLATE_FORMATTER",
                "JSON_PARSER",
                "REGEX_PARSER",
                "PARSE_NOTIFICATION",
                "SEND_NOTIFICATION_ACTION",
            )
        types.forEach { type ->
            val error = CoreEngineNodesModule.validateConfig(type, "{}")
            org.junit.Assert.assertNull("Expected type $type with empty config to validate with defaults, but got: $error", error)
        }
    }

    @Test
    fun testJsonParserManifestTargetTypeIsDropdown() {
        val manifest = BuiltInPluginRegistry.getBuiltInManifestByTypeId(CoreEngineNodesModule.JSON_PARSER)
        assertNotNull(manifest)
        val targetTypeField = manifest!!.configSchema.find { it.key == "targetType" }
        assertNotNull(targetTypeField)
        assertTrue(targetTypeField is ConfigFieldSpec.DropdownField)

        val dropdown = targetTypeField as ConfigFieldSpec.DropdownField
        assertEquals("Target Type", dropdown.label)
        val optionKeys = dropdown.options.map { it.first }
        assertTrue(optionKeys.contains("AUTO"))
        assertTrue(optionKeys.contains("STRING"))
        assertTrue(optionKeys.contains("NUMBER"))
        assertTrue(optionKeys.contains("DOUBLE"))
        assertTrue(optionKeys.contains("BOOLEAN"))
        assertTrue(optionKeys.contains("JSON_STRING"))
        assertTrue(optionKeys.contains("COUNT"))
        assertTrue(optionKeys.contains("ARRAY"))
        assertEquals("AUTO", dropdown.options.first().first)
    }
}

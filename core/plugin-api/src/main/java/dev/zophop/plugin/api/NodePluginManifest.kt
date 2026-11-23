package dev.zophop.plugin.api

import android.os.Parcelable
import kotlinx.parcelize.Parceler
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.TypeParceler
import org.json.JSONException
import org.json.JSONObject
import java.util.regex.PatternSyntaxException

@Parcelize
enum class NodeCategory : Parcelable {
    TRIGGER,
    EXTRACTION,
    LOGIC,
    ACTION,
}

object StringPairParceler : Parceler<Pair<String, String>> {
    override fun create(parcel: android.os.Parcel): Pair<String, String> {
        val first = parcel.readString().orEmpty()
        val second = parcel.readString().orEmpty()
        return Pair(first, second)
    }

    override fun Pair<String, String>.write(parcel: android.os.Parcel, flags: Int) {
        parcel.writeString(first)
        parcel.writeString(second)
    }
}

@Parcelize
@TypeParceler<Pair<String, String>, StringPairParceler>
sealed class ConfigFieldSpec(
    open val key: String,
    open val label: String,
    open val tooltip: String?,
    open val isTestField: Boolean = false,
) : Parcelable {
    @Parcelize
    data class TextField(
        override val key: String,
        override val label: String,
        override val tooltip: String? = null,
        val placeholder: String? = null,
        val singleLine: Boolean = true,
        val minLines: Int = 1,
        override val isTestField: Boolean = false,
    ) : ConfigFieldSpec(key, label, tooltip, isTestField)

    @Parcelize
    @TypeParceler<Pair<String, String>, StringPairParceler>
    data class DropdownField(
        override val key: String,
        override val label: String,
        override val tooltip: String? = null,
        val options: List<Pair<String, String>>,
        override val isTestField: Boolean = false,
    ) : ConfigFieldSpec(key, label, tooltip, isTestField)

    @Parcelize
    data class CodeTemplateField(
        override val key: String,
        override val label: String,
        override val tooltip: String? = null,
        val minLines: Int = 3,
        val singleLine: Boolean = false,
        val maxLength: Int? = null,
        override val isTestField: Boolean = false,
    ) : ConfigFieldSpec(key, label, tooltip, isTestField)
}

@Parcelize
data class NodePluginManifest(
    val typeId: String,
    val displayName: String,
    val description: String,
    val category: NodeCategory,
    val iconResName: String = "ic_node_plugin",
    val apiVersion: Int = CURRENT_API_VERSION,
    val requiredPermissions: List<String> = emptyList(),
    val requiredCredentialType: String? = null,
    val credentialSpec: CredentialSpec? = null,
    val setupActivity: String? = null,
    val triggerTestPayload: String? = null,
    val configSchema: List<ConfigFieldSpec> = emptyList(),
    val supportedTriggerTypes: List<String> = emptyList(),
    val outputVariables: List<String> = emptyList(),
    val supportsCredentials: Boolean = false,
    val defaultConfigJson: String? = null,
) : Parcelable {
    fun isCredentialSupported(): Boolean =
        supportsCredentials || requiredCredentialType != null || credentialSpec != null

    fun isTriggerNode(): Boolean = (category == NodeCategory.TRIGGER)

    fun isRegularNode(): Boolean = (category != NodeCategory.TRIGGER)

    fun getProducedVariables(configJson: String? = null): List<String> {
        val vars = linkedSetOf<String>()
        vars.addAll(outputVariables)
        if (!configJson.isNullOrBlank()) {
            parseConfigJsonVariables(configJson, vars)
        }
        return vars.toList()
    }

    private fun parseConfigJsonVariables(configJson: String, vars: MutableSet<String>) {
        try {
            val json = JSONObject(configJson)
            val dynamicKey = json.optString("outputKey", "").trim()
            if (dynamicKey.isNotEmpty()) {
                vars.add(dynamicKey)
            }
            json.optJSONObject("mappings")?.keys()?.forEach { key ->
                if (key.isNotBlank()) vars.add(key)
            }
            extractMappingsKeys(json, vars)
            extractRegexNamedGroups(json, vars)
        } catch (_: JSONException) {
        } catch (_: PatternSyntaxException) {
        }
    }

    private fun extractRegexNamedGroups(json: JSONObject, vars: MutableSet<String>) {
        val regexStr = json.optString("regex", "")
        if (regexStr.isBlank()) return
        val namedGroupRegex = Regex("""\(\?<([a-zA-Z][a-zA-Z0-9]*)>""")
        namedGroupRegex.findAll(regexStr).forEach {
            val name = it.groupValues[1]
            if (name.isNotBlank()) vars.add(name)
        }
    }

    private fun extractMappingsKeys(json: JSONObject, vars: MutableSet<String>) {
        val mappingsArr = json.optJSONArray("mappings") ?: json.optJSONArray("extractions") ?: return
        for (i in 0 until mappingsArr.length()) {
            val item = mappingsArr.optJSONObject(i)
            val key = item?.optString("outputKey", item.optString("key", ""))?.trim()
            if (!key.isNullOrEmpty()) vars.add(key)
        }
    }

    fun isSupportedByTrigger(triggerType: String?): Boolean {
        if (supportedTriggerTypes.isEmpty()) return true
        if (triggerType.isNullOrBlank()) return false
        val cleanTrigger = triggerType.trim()
        val triggerSimpleName = cleanTrigger.substringAfterLast('.').uppercase()
        return supportedTriggerTypes.any { bound ->
            val cleanBound = bound.trim()
            val boundSimpleName = cleanBound.substringAfterLast('.').uppercase()
            cleanTrigger.equals(cleanBound, ignoreCase = true) ||
                cleanTrigger.endsWith(cleanBound, ignoreCase = true) ||
                cleanBound.endsWith(cleanTrigger, ignoreCase = true) ||
                boundSimpleName == triggerSimpleName
        }
    }

    companion object {
        const val CURRENT_API_VERSION = 1
    }
}

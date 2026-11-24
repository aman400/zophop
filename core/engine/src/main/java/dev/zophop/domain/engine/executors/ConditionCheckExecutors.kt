package dev.zophop.domain.engine.executors

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import dev.zophop.domain.model.WorkflowNode
import dev.zophop.domain.model.optString
import dev.zophop.domain.model.parseJsonObjectSafe
import dev.zophop.plugin.api.ExecutionContext
import dev.zophop.plugin.api.NodeExecutionResult
import dev.zophop.plugin.api.NodeExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.net.NetworkInterface

private val VPN_IFACE_PREFIXES = listOf("tun", "ppp", "pflow", "wg", "wireguard", "tailscale")

private fun parseNodeConfig(configJson: String): JsonObject = parseJsonObjectSafe(configJson)

// -----------------------------------------------------------------------------
// VPN Check
// -----------------------------------------------------------------------------

private data class VpnState(
    val isConnected: Boolean,
    val ifaceName: String,
    val vpnType: String
)

/**
 * Condition check executor for VPN connection status and interface metrics.
 */
class VpnCheckExecutor : NodeExecutor {
    @SuppressLint("MissingPermission")
    override suspend fun execute(
        node: WorkflowNode,
        context: ExecutionContext,
        appContext: Context?,
    ): NodeExecutionResult {
        val config = parseNodeConfig(node.configJson)
        val requireConnected = config.optString("requireConnected", "false").equals("true", ignoreCase = true)
        val requiredInterface = config.optString("requiredInterface", "").trim()

        val state = if (appContext != null) detectVpnState(appContext) else VpnState(false, "none", "NONE")

        context.set("vpn_connected", state.isConnected.toString())
        context.set("vpn_interface", state.ifaceName)
        context.set("vpn_type", state.vpnType)

        val failure = validateVpn(requireConnected, state.isConnected, requiredInterface, state.ifaceName)
        return if (failure != null) {
            NodeExecutionResult.Failed(failure)
        } else {
            NodeExecutionResult.Success(
                logMessage = "VPN Check: connected=${state.isConnected}, interface=${state.ifaceName}, type=${state.vpnType}",
            )
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun detectVpnState(context: Context): VpnState = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        val isVpnTransport = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true

        val interfaces = withContext(Dispatchers.IO) {
            NetworkInterface.getNetworkInterfaces()
        }?.toList() ?: emptyList()

        val activeVpnIface = interfaces.firstOrNull { iface ->
            val name = iface.name.lowercase()
            VPN_IFACE_PREFIXES.any { name.startsWith(it) } && iface.isUp && !iface.isLoopback
        }

        if (isVpnTransport || activeVpnIface != null) {
            val ifaceName = activeVpnIface?.name ?: "tun0"
            val vpnType = resolveVpnType(ifaceName)
            VpnState(true, ifaceName, vpnType)
        } else {
            VpnState(false, "none", "NONE")
        }
    } catch (_: SecurityException) {
        VpnState(false, "none", "NONE")
    }

    private fun resolveVpnType(ifaceName: String): String = when {
        ifaceName.contains("tailscale", ignoreCase = true) -> "TAILSCALE"
        ifaceName.contains("wg", ignoreCase = true) || ifaceName.contains("wireguard", ignoreCase = true) -> "WIREGUARD"
        ifaceName.startsWith("tun", ignoreCase = true) -> "GENERIC_TUN"
        else -> "GENERIC_VPN"
    }

    private fun validateVpn(
        requireConnected: Boolean,
        isConnected: Boolean,
        requiredInterface: String,
        ifaceName: String,
    ): String? = when {
        requireConnected && !isConnected -> "VPN check failed: Device is not connected to a VPN"
        requiredInterface.isNotEmpty() && !ifaceName.contains(requiredInterface, ignoreCase = true) ->
            "VPN check failed: Interface '$ifaceName' does not match required '$requiredInterface'"
        else -> null
    }
}

// -----------------------------------------------------------------------------
// GPS / Location Check
// -----------------------------------------------------------------------------

private data class GpsState(
    val isEnabled: Boolean,
    val lat: String,
    val lon: String,
    val acc: String,
    val mapsUrl: String,
    val provider: String,
    val hasPerm: Boolean,
)

/**
 * Condition check executor for GPS / Location status and coordinates.
 */
class GpsCheckExecutor : NodeExecutor {
    @SuppressLint("MissingPermission")
    override suspend fun execute(
        node: WorkflowNode,
        context: ExecutionContext,
        appContext: Context?,
    ): NodeExecutionResult {
        val config = parseNodeConfig(node.configJson)
        val requireGpsEnabled = config.optString("requireGpsEnabled", "false").equals("true", ignoreCase = true)
        val fetchCoordinates = config.optString("fetchCoordinates", "true").equals("true", ignoreCase = true)

        val state = if (appContext != null) {
            detectGpsState(appContext, fetchCoordinates)
        } else {
            GpsState(false, "0.0", "0.0", "0.0", "", "none", false)
        }

        context.set("gps_enabled", state.isEnabled.toString())
        context.set("latitude", state.lat)
        context.set("longitude", state.lon)
        context.set("accuracy", state.acc)
        context.set("maps_url", state.mapsUrl)
        context.set("location_provider", state.provider)

        val failure = validateGps(requireGpsEnabled, appContext != null, state.hasPerm, state.isEnabled)
        return if (failure != null) {
            NodeExecutionResult.Failed(failure)
        } else {
            NodeExecutionResult.Success(
                logMessage = "GPS Check: enabled=${state.isEnabled}, lat=${state.lat}, lon=${state.lon}, provider=${state.provider}",
            )
        }
    }

    private fun detectGpsState(context: Context, fetchCoordinates: Boolean): GpsState {
        val hasPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED

        return try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            val isEnabled = lm?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true ||
                lm?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true

            if (lm != null && hasPerm && fetchCoordinates) {
                val loc = findBestLocation(lm)
                if (loc != null) {
                    val lat = loc.latitude.toString()
                    val lon = loc.longitude.toString()
                    return GpsState(
                        isEnabled = isEnabled,
                        lat = lat,
                        lon = lon,
                        acc = loc.accuracy.toString(),
                        mapsUrl = "https://maps.google.com/?q=$lat,$lon",
                        provider = loc.provider ?: "unknown",
                        hasPerm = hasPerm,
                    )
                }
            }
            GpsState(isEnabled, "0.0", "0.0", "0.0", "", "none", hasPerm)
        } catch (_: SecurityException) {
            GpsState(false, "0.0", "0.0", "0.0", "", "none", hasPerm)
        }
    }

    @SuppressLint("MissingPermission")
    private fun findBestLocation(lm: LocationManager): Location? =
        lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            ?: lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)

    private fun validateGps(
        requireGpsEnabled: Boolean,
        hasAppContext: Boolean,
        hasPerm: Boolean,
        isEnabled: Boolean,
    ): String? = when {
        !requireGpsEnabled -> null
        hasAppContext && !hasPerm ->
            "GPS check failed: Location permissions (ACCESS_FINE_LOCATION or ACCESS_COARSE_LOCATION) not granted"
        !isEnabled -> "GPS check failed: Device GPS / Location services are disabled"
        else -> null
    }
}

// -----------------------------------------------------------------------------
// Internet Check
// -----------------------------------------------------------------------------

private data class InternetState(
    val isConnected: Boolean,
    val networkType: String,
    val isMetered: Boolean
)

/**
 * Condition check executor for active Internet connectivity and network transport.
 */
class InternetCheckExecutor : NodeExecutor {
    @SuppressLint("MissingPermission")
    override suspend fun execute(
        node: WorkflowNode,
        context: ExecutionContext,
        appContext: Context?,
    ): NodeExecutionResult {
        val config = parseNodeConfig(node.configJson)
        val requireInternet = config.optString("requireInternet", config.optString("requireConnected", "true"))
            .equals("true", ignoreCase = true)
        val requiredNetworkType = config.optString("requiredNetworkType", "ANY")

        val state = if (appContext != null) {
            detectInternetState(appContext)
        } else {
            InternetState(true, "NONE", false)
        }

        context.set("internet_connected", state.isConnected.toString())
        context.set("network_type", state.networkType)
        context.set("is_metered", state.isMetered.toString())

        val failure = validateInternet(requireInternet, state.isConnected, requiredNetworkType, state.networkType)
        return if (failure != null) {
            NodeExecutionResult.Failed(failure)
        } else {
            NodeExecutionResult.Success(
                logMessage = "Internet Check: connected=${state.isConnected}, type=${state.networkType}, metered=${state.isMetered}",
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun detectInternetState(context: Context): InternetState = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        val isConnected = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val networkType = resolveNetworkType(caps)
        val isMetered = cm?.isActiveNetworkMetered == true
        InternetState(isConnected, networkType, isMetered)
    } catch (_: SecurityException) {
        InternetState(false, "NONE", false)
    }

    private fun resolveNetworkType(caps: NetworkCapabilities?): String = when {
        caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "WIFI"
        caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "CELLULAR"
        caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "ETHERNET"
        caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true -> "VPN"
        else -> "NONE"
    }

    private fun validateInternet(
        requireInternet: Boolean,
        isConnected: Boolean,
        requiredType: String,
        currentType: String,
    ): String? = when {
        requireInternet && !isConnected -> "Internet check failed: Device has no active Internet connection"
        requiredType.equals("WIFI", ignoreCase = true) && currentType != "WIFI" ->
            "Internet check failed: Wi-Fi connection required (currently on $currentType)"
        requiredType.equals("CELLULAR", ignoreCase = true) && currentType != "CELLULAR" ->
            "Internet check failed: Cellular connection required (currently on $currentType)"
        else -> null
    }
}

// -----------------------------------------------------------------------------
// Bluetooth Check
// -----------------------------------------------------------------------------

private data class BluetoothState(
    val isEnabled: Boolean,
    val isConnected: Boolean,
    val deviceName: String,
    val deviceAddress: String,
    val bondedCount: Int,
    val hasPerm: Boolean,
)

/**
 * Condition check executor for Bluetooth power state and connected devices.
 */
class BluetoothCheckExecutor : NodeExecutor {
    @SuppressLint("MissingPermission")
    override suspend fun execute(
        node: WorkflowNode,
        context: ExecutionContext,
        appContext: Context?,
    ): NodeExecutionResult {
        val config = parseNodeConfig(node.configJson)
        val requireBtEnabled = config.optString("requireBluetoothEnabled", "false").equals("true", ignoreCase = true)
        val requireConnected = config.optString("requireConnected", "false").equals("true", ignoreCase = true)
        val targetDeviceName = config.optString("targetDeviceName", "").trim()

        val state = if (appContext != null) {
            detectBluetoothState(appContext, targetDeviceName)
        } else {
            BluetoothState(false, false, "none", "none", 0, false)
        }

        context.set("bluetooth_enabled", state.isEnabled.toString())
        context.set("bluetooth_connected", state.isConnected.toString())
        context.set("connected_device_name", state.deviceName)
        context.set("connected_device_address", state.deviceAddress)
        context.set("bonded_device_count", state.bondedCount.toString())

        val failure = validateBluetooth(requireBtEnabled, requireConnected, targetDeviceName, state, appContext != null)
        return if (failure != null) {
            NodeExecutionResult.Failed(failure)
        } else {
            NodeExecutionResult.Success(
                logMessage = "Bluetooth Check: enabled=${state.isEnabled}, connected=${state.isConnected}, device=${state.deviceName}",
            )
        }
    }

    private fun detectBluetoothState(context: Context, targetDeviceName: String): BluetoothState {
        val hasPerm = checkBluetoothPermission(context)
        return try {
            val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = bm?.adapter
            val isEnabled = adapter?.isEnabled == true
            val canInspect = hasPerm && isEnabled
            if (bm != null && adapter != null && canInspect) {
                inspectConnectedDevices(bm, adapter, targetDeviceName, hasPerm)
            } else {
                BluetoothState(isEnabled, false, "none", "none", 0, hasPerm)
            }
        } catch (_: SecurityException) {
            BluetoothState(false, false, "none", "none", 0, hasPerm)
        }
    }

    private fun checkBluetoothPermission(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) ==
                PackageManager.PERMISSION_GRANTED
        }

    @SuppressLint("MissingPermission")
    private fun inspectConnectedDevices(
        bm: BluetoothManager,
        adapter: BluetoothAdapter,
        targetDeviceName: String,
        hasPerm: Boolean,
    ): BluetoothState {
        val bondedDevices = adapter.bondedDevices ?: emptySet()
        val gattConnected = bm.getConnectedDevices(BluetoothProfile.GATT)
        if (gattConnected.isNotEmpty()) {
            val dev = gattConnected.first()
            return BluetoothState(true, true, dev.name ?: "Unknown Device", dev.address ?: "none", bondedDevices.size, hasPerm)
        }

        val a2dpState = adapter.getProfileConnectionState(BluetoothProfile.A2DP)
        val headsetState = adapter.getProfileConnectionState(BluetoothProfile.HEADSET)
        val isProfileConnected = a2dpState == BluetoothProfile.STATE_CONNECTED || headsetState == BluetoothProfile.STATE_CONNECTED

        return if (isProfileConnected) {
            val chosen = findMatchingOrFirstDevice(bondedDevices, targetDeviceName)
            BluetoothState(
                true,
                true,
                chosen?.name ?: "Connected Bluetooth Device",
                chosen?.address ?: "none",
                bondedDevices.size,
                hasPerm,
            )
        } else {
            BluetoothState(true, false, "none", "none", bondedDevices.size, hasPerm)
        }
    }

    @SuppressLint("MissingPermission")
    private fun findMatchingOrFirstDevice(bondedDevices: Set<BluetoothDevice>, targetName: String): BluetoothDevice? =
        if (targetName.isNotEmpty()) {
            bondedDevices.firstOrNull { it.name?.contains(targetName, ignoreCase = true) == true } ?: bondedDevices.firstOrNull()
        } else {
            bondedDevices.firstOrNull()
        }

    private fun validateBluetooth(
        requireBtEnabled: Boolean,
        requireConnected: Boolean,
        targetDeviceName: String,
        state: BluetoothState,
        hasAppContext: Boolean,
    ): String? = when {
        requireBtEnabled && hasAppContext && !state.hasPerm ->
            "Bluetooth check failed: BLUETOOTH_CONNECT permission not granted"
        requireBtEnabled && !state.isEnabled ->
            "Bluetooth check failed: Bluetooth is turned off on device"
        requireConnected && hasAppContext && !state.hasPerm ->
            "Bluetooth check failed: BLUETOOTH_CONNECT permission not granted"
        requireConnected && !state.isConnected ->
            "Bluetooth check failed: No Bluetooth device is connected"
        targetDeviceName.isNotEmpty() && !state.deviceName.contains(targetDeviceName, ignoreCase = true) ->
            "Bluetooth check failed: Connected device '${state.deviceName}' does not match target '$targetDeviceName'"
        else -> null
    }
}

// -----------------------------------------------------------------------------
// SIM Check
// -----------------------------------------------------------------------------

private data class SimState(
    val isReady: Boolean,
    val carrier: String,
    val simCount: Int,
    val isRoaming: Boolean
)

/**
 * Condition check executor for active SIM card status and carrier details.
 */
class SimCheckExecutor : NodeExecutor {
    @SuppressLint("MissingPermission")
    override suspend fun execute(
        node: WorkflowNode,
        context: ExecutionContext,
        appContext: Context?,
    ): NodeExecutionResult {
        val config = parseNodeConfig(node.configJson)
        val requireSimReady = config.optString("requireSimReady", "true").equals("true", ignoreCase = true)
        val requiredCarrier = config.optString("requiredCarrier", "").trim()

        val state = if (appContext != null) detectSimState(appContext) else SimState(true, "Unknown", 1, false)

        context.set("sim_ready", state.isReady.toString())
        context.set("carrier_name", state.carrier)
        context.set("sim_count", state.simCount.toString())
        context.set("is_roaming", state.isRoaming.toString())

        val failure = validateSim(requireSimReady, state.isReady, requiredCarrier, state.carrier)
        return if (failure != null) {
            NodeExecutionResult.Failed(failure)
        } else {
            NodeExecutionResult.Success(
                logMessage = "SIM Check: ready=${state.isReady}, carrier=${state.carrier}, simCount=${state.simCount}",
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun detectSimState(context: Context): SimState {
        val hasPhonePerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_STATE,
        ) == PackageManager.PERMISSION_GRANTED

        return try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            if (tm == null) {
                return SimState(false, "Unknown", 0, false)
            }
            val isReady = tm.simState == TelephonyManager.SIM_STATE_READY
            val opName = tm.simOperatorName.ifBlank { tm.networkOperatorName.ifBlank { "" } }
            val carrier = opName.ifBlank { "Unknown" }
            val isRoaming = tm.isNetworkRoaming

            val simCount = if (hasPhonePerm) {
                val sm = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
                sm?.activeSubscriptionInfoCount ?: if (isReady) 1 else 0
            } else if (isReady) {
                1
            } else {
                0
            }
            SimState(isReady, carrier, simCount, isRoaming)
        } catch (_: SecurityException) {
            SimState(false, "Unknown", 0, false)
        }
    }

    private fun validateSim(
        requireSimReady: Boolean,
        isReady: Boolean,
        requiredCarrier: String,
        carrier: String,
    ): String? = when {
        requireSimReady && !isReady -> "SIM check failed: SIM card is not present, locked, or not ready"
        requiredCarrier.isNotEmpty() && !carrier.contains(requiredCarrier, ignoreCase = true) ->
            "SIM check failed: Active carrier '$carrier' does not match required '$requiredCarrier'"
        else -> null
    }
}

// -----------------------------------------------------------------------------
// Battery Check
// -----------------------------------------------------------------------------

private data class BatteryState(
    val percent: Int,
    val isCharging: Boolean,
    val statusStr: String
)

/**
 * Condition check executor for battery percentage and charging state.
 */
class BatteryCheckExecutor : NodeExecutor {
    override suspend fun execute(
        node: WorkflowNode,
        context: ExecutionContext,
        appContext: Context?,
    ): NodeExecutionResult {
        val config = parseNodeConfig(node.configJson)
        val minPercent = config.optString("minBatteryPercent", "15").toIntOrNull() ?: 15
        val requireCharging = config.optString("requireCharging", "false").equals("true", ignoreCase = true)

        val state = if (appContext != null) detectBatteryState(appContext) else BatteryState(100, false, "NOT_CHARGING")

        context.set("battery_percent", state.percent.toString())
        context.set("is_charging", state.isCharging.toString())
        context.set("charging_status", state.statusStr)

        val failure = validateBattery(state.percent, minPercent, requireCharging, state.isCharging)
        return if (failure != null) {
            NodeExecutionResult.Failed(failure)
        } else {
            NodeExecutionResult.Success(
                logMessage = "Battery Check: percent=${state.percent}%, charging=${state.isCharging}, status=${state.statusStr}",
            )
        }
    }

    private fun detectBatteryState(context: Context): BatteryState {
        val batteryIntent = try {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (_: SecurityException) {
            null
        }

        if (batteryIntent == null) {
            return BatteryState(100, false, "NOT_CHARGING")
        }

        val level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)

        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val statusStr = resolveBatteryStatus(status)
        val percent = if (level >= 0 && scale > 0) (level * 100) / scale else 100

        return BatteryState(percent, isCharging, statusStr)
    }

    private fun resolveBatteryStatus(status: Int): String = when (status) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "CHARGING"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "DISCHARGING"
        BatteryManager.BATTERY_STATUS_FULL -> "FULL"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "NOT_CHARGING"
        else -> "UNKNOWN"
    }

    private fun validateBattery(
        percent: Int,
        minPercent: Int,
        requireCharging: Boolean,
        isCharging: Boolean,
    ): String? = when {
        percent < minPercent ->
            "Battery check failed: Current battery is $percent% (requires at least $minPercent%)"
        requireCharging && !isCharging ->
            "Battery check failed: Device is not connected to a charger (charging required)"
        else -> null
    }
}

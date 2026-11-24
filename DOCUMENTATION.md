# 🚀 ZopHop — Complete User & Architecture Manual

ZopHop is an open-source, high-performance Android application for capturing incoming OTP SMS messages and app push notifications, evaluating complex preconditions, executing visual workflow automation pipelines, and securely forwarding data to **ntfy**, **Gotify**, and custom **HTTP Webhook** endpoints.

Built for self-hosters, home lab enthusiasts, and privacy-conscious users, ZopHop provides encrypted credential vaulting with biometric protection, node-level health probing, and resilient background queueing.

---

## 📋 Table of Contents
1. [Overview & Flow Architecture](#1-overview--flow-architecture)
2. [Visual Drag & Drop Workflow Automation Engine](#2-visual-drag--drop-workflow-automation-engine)
3. [Multi-Target Delivery Nodes & Health Checks](#3-target-delivery-nodes--health-checks)
4. [Encrypted Vault & Biometric Security](#4-encrypted-vault--biometric-security)
5. [Precondition Check System](#5-precondition-check-system)
6. [First-Time App Setup & Installation](#6-first-time-app-setup--installation)
7. [Configuring ntfy, Gotify & Custom Webhooks](#7-configuring-ntfy-gotify--custom-webhooks)
8. [Filters, Parsing & Template Variables](#8-filters-parsing--template-variables)
9. [Status Notifications, Quick Settings Tile & Live Stepper](#9-status-notifications-quick-settings-tile--live-stepper)
10. [Testing with ADB Emulator](#10-testing-with-adb-emulator)
11. [Troubleshooting & OEM Battery Settings](#11-troubleshooting--oem-battery-settings)
12. [Security, Privacy & Data Hardening](#12-security-privacy--data-hardening)

---

## 1. Overview & Flow Architecture

```
                                  ┌────────────────────────┐
                                  │   Incoming Event Source│
                                  └───────────┬────────────┘
                                              │
                       ┌──────────────────────┴──────────────────────┐
                       ▼                                             ▼
             ┌──────────────────┐                           ┌──────────────────┐
             │   SmsReceiver    │                           │ZopHopNotification│
             │  (SMS PDU / OTP) │                           │ Listener Service │
             └─────────┬────────┘                           └────────┬─────────┘
                       │                                             │
                       └──────────────────────┬──────────────────────┘
                                              │
                                              ▼
                                 ┌─────────────────────────┐
                                 │  Precondition Engine    │
                                 │ (VPN, Internet, Health) │
                                 └────────────┬────────────┘
                                              │ (Pass)
                                              ▼
                                 ┌─────────────────────────┐
                                 │ Workflow Execution      │
                                 │ Engine & Node Stepper   │
                                 └────────────┬────────────┘
                                              │
        ┌─────────────────────────────────────┼─────────────────────────────────────┐
        ▼                                     ▼                                     ▼
┌───────────────┐                     ┌───────────────┐                     ┌───────────────┐
│  ntfy Target  │                     │ Gotify Target │                     │ Custom HTTP   │
│  (Push Auth)  │                     │  (App Token)  │                     │ Webhook API   │
└───────────────┘                     └───────────────┘                     └───────────────┘
```

When an event arrives on your device:

1. **Event Capture**:
   - **SMS Receiver (`SmsReceiver`)**: Captures SMS PDUs, sender address, timestamp, and message body.
   - **Notification Listener (`ZopHopNotificationListenerService`)**: Intercepts notifications from selected apps (e.g., WhatsApp, Telegram, Banking apps).
2. **Precondition Evaluation (`PreconditionEvaluator`)**: Verifies required environmental state (active VPN interface, internet capability, carrier SIM status, or HTTP health endpoint probes).
3. **Workflow Engine (`WorkflowEngine`)**: Processes the event through active visual workflows consisting of Triggers, Parsers, Condition Evaluators, and Action Nodes.
4. **Encrypted Vault (`CredentialRepository`)**: Injects hardware-encrypted Bearer tokens, API keys, or Basic Auth headers into outgoing HTTP actions.
5. **Resilient Forwarding Queue (`ForwardingManager` & `DeliveryWorker`)**: Transmits payloads immediately or queues them with automatic exponential backoff retries if network or node pre-checks fail.

---

## 2. Visual Drag & Drop Workflow Automation Engine

ZopHop features a visual graph-based workflow engine that lets you build sophisticated automation pipelines without writing code.

### Node Categories & Supported Types

#### ⚡ Triggers
- **SMS Trigger**: Activates on incoming text messages matching optional sender allowlists (`*BANK*`) and keyword regex.
- **App Notification Trigger**: Intercepts notifications from target apps by package name filter (e.g. `com.whatsapp`, `com.telegram.messenger`) and body regex.
- **Manual Action Trigger**: Runs on-demand from the app interface for quick testing.

#### 🧩 Parsers & Formatters
- **Regex Parser**: Extracts codes or text using regular expressions (e.g. `\b\d{4,8}\b`) and assigns output to custom context keys like `otp`.
- **JSON Parser**: Parses structured JSON payloads using path expressions (e.g. `data.user.id`, `items[0].code`) with automatic target type conversion (`STRING`, `NUMBER`, `BOOLEAN`, `JSON_STRING`).
- **Template Formatter**: Constructs dynamic string payloads using variable interpolation (e.g. `Alert from ${sender}: Code ${otp}`).

#### ⚖️ Logic & Branching
- **Condition Evaluator**: Evaluates rules against variables using operators:
  - `EQUALS`, `NOT_EQUALS` (case-insensitive string match)
  - `CONTAINS`, `NOT_CONTAINS` (substring match)
  - `REGEX_MATCH` (regular expression evaluation)
  - `NOT_EMPTY`, `IS_EMPTY` (presence checks)
  - `GREATER_THAN`, `LESS_THAN` (numeric comparison)
  - Branching: Routes execution along `main`, `true`, or `false` connections.

#### 📤 Actions
- **ntfy Push Action**: Dispatches notifications to ntfy server topics with customizable priority, tags, and title.
- **Gotify Push Action**: Delivers push alerts to Gotify servers with priority levels (1–10).
- **HTTP Webhook Action**: Executes custom HTTP requests (`GET`, `POST`, `PUT`, `DELETE`, `PATCH`) with custom headers, JSON body templates, and linked vault credentials.
- **Send System Notification**: Displays a local system notification shade entry on the Android device.
- **Send Outgoing SMS**: Transmits an outgoing SMS text message to a specified recipient.

### Per-Node Failure Policies
Every node in a workflow can be configured with a failure strategy:
- `RETRY_WITH_BACKOFF`: Automatically retries node execution up to $N$ times with interval delays.
- `CONTINUE_ON_FAILURE`: Ignores node errors and proceeds to subsequent workflow steps.
- `STOP_WORKFLOW`: Immediately halts workflow execution and logs the error.

---

## 3. Target Delivery Nodes & Health Checks

ZopHop supports multiple concurrent delivery targets configured under **Settings → Delivery Target Nodes**.

### Node Pre-Check Options
- **Require Internet Connection**: Verifies active internet capability (`NET_CAPABILITY_INTERNET`).
- **Require Active VPN**: Ensures an active VPN transport (`TRANSPORT_VPN`) or virtual interface (`tun0`, `wireguard`, `tailscale`) is active before transmitting payloads.
- **Require Healthcheck Ping**: Performs a pre-flight probe request to a health endpoint before delivering the main payload.

### Healthcheck Configuration
- **Healthcheck URL**: Custom endpoint (e.g. `https://my-server.com/healthz`).
- **HTTP Method**: `GET`, `HEAD`, or `POST`.
- **Expected Status Code**: Expected status integer (default `200` or `204`).
- **Custom Headers**: Headers required by reverse proxies (e.g. `X-Health-Key: secret123`).

---

## 4. Encrypted Vault & Biometric Security

ZopHop includes a secure credential vault (`CredentialRepository`) for storing sensitive tokens and authentication credentials.

### Credential Types
- **Bearer Token**: API keys or OAuth access tokens (inserted as `Authorization: Bearer <token>`).
- **Basic Auth**: Username and password credentials (inserted as `Authorization: Basic <base64>`).
- **Custom Header Set**: Key-value header pairs.
- **ntfy / Gotify Credentials**: Server URL and authentication keys.

> [!IMPORTANT]
> **Hardware Encryption & Biometric Protection**  
> All vault credentials are encrypted at rest using AES-256 GCM via `EncryptedSharedPreferences` backed by the Android KeyStore (`MasterKey`). Viewing secret credentials or toggling biometric security requires Biometric Authentication (fingerprint/face) or Device Lock PIN/Pattern.

---

## 5. Precondition Check System

Preconditions evaluate environmental state before executing workflow nodes or triggers:

| Precondition Type | Description | Configurable Fields |
| :--- | :--- | :--- |
| **Health Check** | Probes an HTTP endpoint for status code response. | Target URL, HTTP Method, Expected Status, Headers. |
| **Internet Check** | Verifies active cellular or Wi-Fi internet connectivity. | Timeout, retry count. |
| **VPN Check** | Confirms Tailscale, WireGuard, or OpenVPN connection. | Network transport verification. |
| **SIM Check** | Validates active SIM card status and carrier name. | Required carrier name, slot index. |
| **GPS Check** | Verifies hardware GPS location services are enabled. | Location mode check. |
| **Chained Check** | Runs a sub-workflow pipeline before permitting execution. | Sub-pipeline graph JSON. |

---

## 6. First-Time App Setup & Installation

### Step 1: Install Signed APK
Download and install `ZopHop-v3.0.0.apk` (or latest release build) on Android 8.0 (API 26) or higher.

> [!NOTE]
> **Bypassing Play Protect on Sideloaded Builds**  
> Because ZopHop requests sensitive SMS permissions (`RECEIVE_SMS`) and is self-published, Google Play Protect may display a prompt. Tap **"More details"** $\rightarrow$ **"Install anyway"**.

### Step 2: Grant Permissions
Navigate to **Settings & Permissions** in ZopHop:
1. **SMS Permission (`RECEIVE_SMS`)**: Tap **Grant** to allow incoming SMS monitoring.
2. **Notification Listener (`BIND_NOTIFICATION_LISTENER_SERVICE`)**: Enable for app push notification forwarding.
3. **Post Notifications (`POST_NOTIFICATIONS`)**: Tap **Grant** (Android 13+) for status notifications.
4. **Ignore Battery Optimization**: Tap **Ignore** to prevent background sleep.

### Step 3: Configure Target Node & Activate
1. Under **Delivery Target Nodes**, tap **+ Add Node**.
2. Enter your server details and save.
3. Toggle the top app bar switch to **ACTIVE** (🟢 status notification will appear).

---

## 7. Configuring ntfy, Gotify & Custom Webhooks

### Option A: ntfy Setup

#### 1. Docker Compose Deployment
```yaml
version: '3.8'

services:
  ntfy:
    image: binwiederhier/ntfy
    container_name: ntfy
    command:
      - serve
    volumes:
      - ./ntfy:/var/lib/ntfy
    environment:
      NTFY_BASE_URL: https://ntfy.example.com
      NTFY_CACHE_FILE: /var/lib/ntfy/cache.db
      NTFY_AUTH_FILE: /var/lib/ntfy/auth.db
      NTFY_AUTH_DEFAULT_ACCESS: deny-all
    ports:
      - "8080:80"
    restart: unless-stopped
```

#### 2. Topic ACL Permissions Setup
```bash
# Create user accounts
ntfy user add publisher
ntfy user add receiver

# Grant write-only permission to publisher (ZopHop)
ntfy access publisher "otp-*" write-only

# Grant read-only permission to receiver
ntfy access receiver "otp-*" read-only

# Generate Access Token for ZopHop
ntfy token add publisher
```

### Option B: Gotify Setup

#### 1. Docker Compose Deployment
```yaml
version: '3.8'

services:
  gotify:
    image: gotify/server:latest
    container_name: gotify
    restart: unless-stopped
    environment:
      - GOTIFY_SERVER_PORT=80
    volumes:
      - ./gotify_data:/app/data
    ports:
      - "8081:80"
```

#### 2. Generate Application Token
In Gotify Web UI $\rightarrow$ **Apps** $\rightarrow$ **Create Application** `ZopHop` $\rightarrow$ Copy generated token `X-Gotify-Key`.

---

## 8. Filters, Parsing & Template Variables

### Default Template Placeholders
- `${sender}` / `{{sender}}`: Message sender address or app name.
- `${body}` / `{{body}}`: Complete message body.
- `${code}` / `${otp}` / `{{otp}}`: Extracted verification code.
- `${timestamp}` / `{{timestamp}}`: Arrival epoch timestamp.

### Tested Indian Bank SMS Patterns

| Bank / Service | Sender Header | Sample SMS Text | Allowlist Pattern | Extraction Regex | Extracted Code |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **HDFC Bank** | `VM-HDFCBK` | `482910 is your OTP for HDFC Bank card transaction of Rs 1,250.00 at Amazon.` | `*HDFCBK*` | `\b\d{6}\b` | `482910` |
| **ICICI Bank** | `AX-ICICIB` | `OTP for transaction of Rs 850.00 at Swiggy is 918234.` | `*ICICIB*` | `\b\d{6}\b` | `918234` |
| **SBI** | `AD-SBINBK` | `771029 is the OTP for your SBI netbanking login.` | `*SBINBK*` | `\b\d{6}\b` | `771029` |
| **Axis Bank** | `VK-AXISBK` | `Use OTP 603912 to authorize Rs 3,400.00 on Axis Bank Credit Card.` | `*AXISBK*` | `\b\d{6}\b` | `603912` |
| **Google** | `Google` | `G-884920 is your Google verification code.` | `Google` | `G-(\d{6})` | `884920` |

---

## 9. Status Notifications, Quick Settings Tile & Live Stepper

### Live Execution Stepper Modal
When running test workflows or active triggers, ZopHop displays a real-time Execution Stepper modal showing:
- 🟡 `PENDING`: Node waiting in queue.
- 🔵 `RUNNING`: Node currently executing.
- 🟢 `SUCCESS`: Step completed with log messages.
- 🔴 `FAILED`: Step failed with detailed error reason.

### Quick Settings Tile
Add the **ZopHop Forwarder** Quick Settings tile to turn forwarding ON or OFF directly from the Android status shade.

---

## 10. Testing with ADB Emulator

Simulate incoming SMS messages on the Android Studio Emulator:

```bash
adb emu sms send VM-HDFCBK "Your OTP for transaction of Rs 500 is 482910."
adb emu sms send Google "G-884920 is your Google verification code."
```

---

## 11. Troubleshooting & OEM Battery Settings

To prevent OEM background task managers from killing ZopHop in the background:

### 📱 Xiaomi / POCO (MIUI & HyperOS)
1. **Autostart**: Open **Security App** $\rightarrow$ **Permissions** $\rightarrow$ **Autostart** $\rightarrow$ Enable **ZopHop**.
2. **Battery Saver**: Open **Settings** $\rightarrow$ **Apps** $\rightarrow$ **Manage Apps** $\rightarrow$ **ZopHop** $\rightarrow$ **Battery Saver** $\rightarrow$ Select **No Restrictions**.
3. **Lock App**: Lock ZopHop in the Recent Apps screen 🔒.

### 📱 Samsung (One UI)
1. Open **Settings** $\rightarrow$ **Battery and Device Care** $\rightarrow$ **Background Usage Limits** $\rightarrow$ Add ZopHop to **Never Sleeping Apps**.

### 📱 OnePlus / Oppo / Realme (OxygenOS & ColorOS)
1. Open **Settings** $\rightarrow$ **Apps** $\rightarrow$ **ZopHop** $\rightarrow$ **Battery Usage** $\rightarrow$ Enable **Allow Background Activity** & **Allow Auto Launch**.

---

## 12. Security, Privacy & Data Hardening

1. **Hardware Encryption (`EncryptedSharedPreferences`)**: Tokens and API keys are encrypted at rest with AES-256 GCM via hardware keys in Android KeyStore (`MasterKey`).
2. **Screen Protection (`FLAG_SECURE`)**: Screenshots and screen recordings are disabled in release builds.
3. **Zero Telemetry**: Contains no third-party tracking, ads, or analytics SDKs.
4. **Cloud Backup Exclusion (`allowBackup="false"`)**: Explicitly blocks Google Drive backup of app databases and credentials.

---
## 13. Modular Node Plugin Ecosystem & Developer Guide

ZopHop features an open, zero-recompilation plugin architecture (similar to WhatsApp stickers) that allows third-party developers to create and publish custom workflow pipeline step nodes as standalone Android apps.

### How Plugin Discovery & Execution Works
1. Third-party developers create a standard Android app and declare an `INodePluginService` bound service with an intent-filter:
   ```xml
   <service android:name=".CallNodePluginService" android:exported="true">
       <intent-filter>
           <action android:name="dev.zophop.plugin.ACTION_NODE_SERVICE" />
       </intent-filter>
   </service>
   ```
2. ZopHop's `ExternalPluginRegistry` queries installed apps via Android `PackageManager`.
3. ZopHop binds to the plugin app via AIDL IPC (`dev.zophop.plugin.api.INodePluginService`), reads its `NodePluginManifest` (display name, category, and dynamic `ConfigSchema`), and displays the node in ZopHop's step node selector.
4. During workflow execution, ZopHop delegates node execution to the plugin app over AIDL with sandboxed process isolation.

### Managing Plugin App Permissions
Plugin apps request and manage their own Android runtime permissions independently (such as `READ_PHONE_STATE`, `READ_CALL_LOG`, `ANSWER_PHONE_CALLS`, `SEND_SMS`).
- If a plugin requires a permission that has not been granted by the user, the plugin service returns `isSuccess = false` and `errorReason = "Permission missing in plugin app"`. ZopHop logs this error in the workflow execution stepper modal.

---

### Complete Sample Plugin Implementation: Telegram Bot Action Plugin

Below is a complete, copy-pasteable example of building a **Telegram Bot Action Plugin** that forwards workflow payloads and notifications to a Telegram Chat via Bot API:

#### 1. Plugin `AndroidManifest.xml`
```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.example.telegramplugin">

    <uses-permission android:name="android.permission.INTERNET" />

    <application android:label="ZopHop Telegram Action Plugin">
        <service
            android:name=".TelegramNodePluginService"
            android:exported="true">
            <intent-filter>
                <action android:name="dev.zophop.plugin.ACTION_NODE_SERVICE" />
            </intent-filter>
        </service>
    </application>
</manifest>
```

#### 2. Plugin Service (`TelegramNodePluginService.kt`)
```kotlin
package com.example.telegramplugin

import android.app.Service
import android.content.Intent
import android.os.IBinder
import dev.zophop.plugin.api.INodePluginService
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class TelegramNodePluginService : Service() {

    private val client = OkHttpClient()

    private val binder = object : INodePluginService.Stub() {

        override fun getPluginApiVersion(): Int = 1

        override fun getManifestJson(): String {
            val schema = JSONArray().apply {
                put(JSONObject().apply {
                    put("key", "botToken")
                    put("label", "Telegram Bot Token")
                    put("type", "TEXT")
                    put("tooltip", "Bot token provided by @BotFather")
                    put("singleLine", true)
                })
                put(JSONObject().apply {
                    put("key", "chatId")
                    put("label", "Telegram Chat ID")
                    put("type", "TEXT")
                    put("tooltip", "Target Chat ID or Channel username (e.g. -100123456789)")
                    put("singleLine", true)
                })
                put(JSONObject().apply {
                    put("key", "messageTemplate")
                    put("label", "Message Template")
                    put("type", "TEMPLATE")
                    put("tooltip", "Message body template (supports \${body}, \${sender}, etc.)")
                    put("minLines", 3)
                })
            }

            val manifest = JSONObject().apply {
                put("typeId", "com.example.telegramplugin.TELEGRAM_ACTION")
                put("displayName", "Telegram Notification")
                put("description", "Sends formatted notifications and alerts to a Telegram chat via Bot API")
                put("category", "ACTION")
                put("apiVersion", 1)
                put("configSchema", schema)
            }

            return manifest.toString()
        }

        override fun executeNode(nodeId: String, configJson: String, inputContextJson: String): String {
            val config = try { JSONObject(configJson) } catch (_: Exception) { JSONObject() }
            val inputCtx = try { JSONObject(inputContextJson) } catch (_: Exception) { JSONObject() }

            val botToken = config.optString("botToken", "").trim()
            val chatId = config.optString("chatId", "").trim()
            val template = config.optString("messageTemplate", "\${body}").ifEmpty { "\${body}" }

            if (botToken.isBlank() || chatId.isBlank()) {
                return JSONObject().apply {
                    put("isSuccess", false)
                    put("errorReason", "Telegram botToken or chatId is empty")
                }.toString()
            }

            // Simple variable interpolation
            var message = template
            val keys = inputCtx.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = inputCtx.optString(key, "")
                message = message.replace("\${$key}", value).replace("{$key}", value)
            }

            val url = "https://api.telegram.org/bot$botToken/sendMessage"
            val jsonBody = JSONObject().apply {
                put("chat_id", chatId)
                put("text", message)
                put("parse_mode", "Markdown")
            }

            val requestBody = jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder().url(url).post(requestBody).build()

            return try {
                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    JSONObject().apply {
                        put("isSuccess", true)
                        put("outputPort", "main")
                        put("logMessage", "Telegram message successfully sent to chat $chatId")
                    }.toString()
                } else {
                    JSONObject().apply {
                        put("isSuccess", false)
                        put("errorReason", "Telegram API error (${response.code}): $responseBody")
                    }.toString()
                }
            } catch (e: Exception) {
                JSONObject().apply {
                    put("isSuccess", false)
                    put("errorReason", "Telegram request exception: ${e.message}")
                }.toString()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder
}
```

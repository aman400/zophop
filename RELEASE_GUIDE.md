# ZopHop Signed Release & JitPack SDK Guide

<!-- APP_VERSION = "3.1.0" -->

This guide details how to generate signed Release APKs and Plugin SDK AARs locally, and how automated GitHub Release + JitPack distribution works via [`.github/workflows/release.yml`](.github/workflows/release.yml) and [`jitpack.yml`](jitpack.yml).

---

## 1. Local Signed Build Generation

### Step A: Generate a Keystore (One-Time Setup)
Generate a 2048-bit RSA keystore file using Java `keytool`:

```bash
keytool -genkey -v -keystore release.keystore \
  -alias zophop_key \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000
```

### Step B: Build Release APKs & Plugin SDK AARs
Pass keystore parameters as environment variables when invoking Gradle:

```bash
KEYSTORE_FILE=release.keystore \
KEYSTORE_PASSWORD=your_password \
KEY_ALIAS=zophop_key \
KEY_PASSWORD=your_password \
./gradlew assembleRelease :core:plugin-api:assembleRelease :core:model:assembleRelease
```

The signed release binaries and AAR libraries are generated at:

```text
app/build/outputs/apk/release/ZopHop-v3.1.0.apk
external/telegram/build/outputs/apk/release/ZopHop-Telegram-Plugin-v3.1.0.apk
external/smtp/build/outputs/apk/release/ZopHop-SMTP-Plugin-v3.1.0.apk
core/plugin-api/build/outputs/aar/zophop-plugin-api-v3.1.0.aar
core/model/build/outputs/aar/zophop-core-model-v3.1.0.aar
```

---

## 2. Automated GitHub Release & JitPack Distribution

The repository includes a pre-configured release workflow in `.github/workflows/release.yml` triggered on `v*` tag pushes or manual `workflow_dispatch`.

### Step A: Configure Repository Secrets (Optional)
If you want GitHub Actions to sign `ZopHop-v3.1.0.apk`, `ZopHop-Telegram-Plugin-v3.1.0.apk`, and `ZopHop-SMTP-Plugin-v3.1.0.apk` with your production keystore, encode `release.keystore` to Base64:

```bash
base64 -i release.keystore -o keystore_base64.txt
```

Then in your GitHub Repository, navigate to **Settings → Secrets and variables → Actions** and add:
- `KEYSTORE_BASE64`: Contents of `keystore_base64.txt`
- `KEYSTORE_PASSWORD`: Keystore password
- `KEY_ALIAS`: Keystore alias (e.g., `zophop_key`)
- `KEY_PASSWORD`: Key password

*(If omitted, GitHub Actions falls back to debug-signed release builds. No extra secrets or environment variables are required for GitHub Releases — the workflow uses the built-in `GITHUB_TOKEN` with `contents: write` permission.)*

### Step B: Synchronize Docs & Publish a Release Tag
Before tagging, run `./gradlew syncVersion` if you bumped `ZOPHOP_VERSION` in `gradle.properties`, then tag and push:

```bash
VERSION=$(grep -E '^ZOPHOP_VERSION=' gradle.properties | head -n 1 | cut -d'=' -f2- | tr -d '[:space:]')
VERSION="${VERSION:-1.0.0}"

git tag -a "v$VERSION" -m "ZopHop v$VERSION Release"
git push origin "v$VERSION"
```

GitHub Actions will automatically:
1. Run the full unit and Robolectric integration test suite (`./gradlew test --stacktrace`).
2. Build and sign the main app (`ZopHop-v3.1.0.apk`) and external plugin APKs (`ZopHop-Telegram-Plugin-v3.1.0.apk`, `ZopHop-SMTP-Plugin-v3.1.0.apk`).
3. Build release AAR libraries (`zophop-plugin-api-v3.1.0.aar` and `zophop-core-model-v3.1.0.aar`).
4. Create a GitHub Release and attach all `.apk` and `.aar` assets.

Once the release tag is pushed, **JitPack** (`jitpack.yml`) builds and serves `com.github.aman400.zophop:plugin-api` and `com.github.aman400.zophop:core-model` publicly without authentication.

---

## 3. Public SDK Consumption via JitPack

External plugin developers can consume the SDK publicly without any token via **JitPack** (see [docs/plugins.html](docs/plugins.html) or the [Online Plugin SDK Docs](https://aman400.github.io/zophop/plugins.html)):

```kotlin
// settings.gradle.kts
maven { url = uri("https://jitpack.io") }

// app/build.gradle.kts
dependencies {
    implementation("com.github.aman400.zophop:plugin-api:v3.1.0")
}
```

To test the exact JitPack publication locally in `~/.m2`:

```bash
./gradlew -Pjitpack=true \
  :core:model:publishReleasePublicationToMavenLocal \
  :core:plugin-api:publishReleasePublicationToMavenLocal \
  --stacktrace
```

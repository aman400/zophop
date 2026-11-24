# ZopHop Signed Release & GitHub Publishing Guide

<!-- APP_VERSION = "3.0.0" -->

This guide details how to generate signed Release APKs locally and how to set up automated GitHub Release publishing.

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

### Step B: Build Signed Release APK
Pass keystore parameters as environment variables when invoking Gradle:

```bash
KEYSTORE_FILE=release.keystore \
KEYSTORE_PASSWORD=your_password \
KEY_ALIAS=zophop_key \
KEY_PASSWORD=your_password \
./gradlew assembleRelease
```

The signed, optimized Release APK will be generated at:
```text
app/build/outputs/apk/release/ZopHop-v3.0.0.apk
```

---

## 2. Automated GitHub Release Publishing

The repository includes a pre-configured GitHub Actions workflow in `.github/workflows/release.yml`.

### Step A: Configure Repository Secrets (Optional)
If you want GitHub Actions to sign APKs with your custom production keystore, encode your `release.keystore` file to Base64:

```bash
base64 -i release.keystore -o keystore_base64.txt
```

Then in your GitHub Repository:
Navigate to **Settings** → **Secrets and variables** → **Actions** → Add the following Repository Secrets:
- `KEYSTORE_BASE64`: Content of `keystore_base64.txt`
- `KEYSTORE_PASSWORD`: Keystore password
- `KEY_ALIAS`: Keystore alias (e.g. `zophop_key`)
- `KEY_PASSWORD`: Key password

*(If secrets are omitted, GitHub Actions will fall back to auto-generating signed release builds.)*

### Step B: Publish a New Release Tag
To publish a new GitHub Release automatically, tag your commit with the target version and push to GitHub:

```bash
VERSION="3.0.0"

git tag -a v$VERSION -m "ZopHop v$VERSION Release"
git push origin v$VERSION
```

GitHub Actions will automatically:
1. Compile and optimize R8 ProGuard release binaries.
2. Sign the output APK (`ZopHop-v3.0.0.apk`).
3. Create a GitHub Release and attach the APK asset for direct download.

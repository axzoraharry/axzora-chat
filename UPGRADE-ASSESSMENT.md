# Axzora Chat — Upgrade Assessment

_Generated 7 Nov 2026, on-device (Axzora Coder Linux sandbox)._

## TL;DR
- **Axzora Chat** (`app.axzora.chat`, installed **v1.0.15 / code 15**) is a rebrand of
  **whisperbit-labs/zemzeme-android** (MIT license).
- Upstream **main** builds cleanly **on this phone** (Gradle 8.13 + AGP 8.10.1 +
  Kotlin 2.2.0, compileSdk 35) → `app-arm64-v8a-debug.apk` = 88.4 MB, byte-for-byte the
  same shape as the shipped `axzora-chat-1.0.15-debug.apk` (88.9 MB).
- The upgrade is a **MIT→MIT** line (commercially safe for Axzora Pay). Do **not** pull
  from `permissionlesstech/bitchat-android` (GPL-3.0) — that would infect Axzora Chat
  with copyleft.

## Upstream chain
```
permissionlesstech/bitchat        (Unlicense, 37k★)  — "bluetooth mesh chat, IRC vibes"
  └─ permissionlesstech/bitchat-android (GPL-3.0, 7.9k★, v2.2.0 Oct 2026)
       └─ whisperbit-labs/zemzeme-android (MIT, v2.0.7 May 2026)  ← Axzora Chat's parent
```
zemzeme-android = fork of bitchat v1.7.0, adds a libp2p internet transport (go-libp2p via
gomobile) + Nostr relay on top of the offline BLE mesh. "No accounts, no internet required."

## Stack (upstream `main`, as cloned 7 Nov 2026)
| Area | Value |
|---|---|
| Gradle | 8.13 (wrapper) |
| AGP | 8.10.1 |
| Kotlin | 2.2.0 (+ Compose plugin) |
| compileSdk / targetSdk | 35 / 35 |
| minSdk | 26 (BLE) |
| Compose | BOM 2025.06.01, Material3 |
| BLE | Nordic `no.nordicsemi.android:ble` 2.6.1 |
| Net | OkHttp 4.12.0, Tor binary 0.4.4.6, `golib.aar` (Go libp2p) |
| Crypto | Tink 1.10.0, BouncyCastle 1.70, security-crypto 1.1.0-beta01 |
| Camera/QR | CameraX 1.5.2, MLKit barcode 17.3.0, ZXing 3.5.4 |
| Updates | Play Core in-app update 2.1.0 + `update/UpdateManager.kt` |

Source: **231 Kotlin + 29 Java** files. Java files are the `southernstorm` Noise protocol
impl + `info.guardianproject` (Tor/Arti) + `org` (helpers). `golib.aar` (21 MB) is
**arm64-v8a only**; the Tor `jniLibs/` covers all four ABIs.

## On-device build — what it took
1. `JAVA_HOME=/usr/lib/jvm/java-17-openjdk` (Java 21 JIT fails under proot).
2. `platforms;android-35` + `build-tools;35.0.0` installed via sdkmanager (with Java 17).
3. `-Pandroid.aapt2FromMavenOverride=/root/android-sdk/build-tools/35.0.2/aapt2`
   (the arm64 aapt2; sdkmanager's own aapt2 is x86_64).
4. **Memory split is the critical bit** — a single big JVM OOM-kills the Axzora Coder
   sandbox (Android LMK). Working recipe:
   ```
   org.gradle.jvmargs=-Xmx1g -XX:MaxMetaspaceSize=512m
   kotlin.compiler.execution.strategy=daemon
   kotlin.daemon.jvmargs=-Xmx3g
   org.gradle.parallel=false
   org.gradle.workers.max=2
   ```
5. `golib.aar` is arm64-only, so splits were narrowed to `arm64-v8a` (build-only edit).

Result: **BUILD SUCCESSFUL in 6m47s** → `app-arm64-v8a-debug.apk` (88,411,105 B,
sha256 161f14bc…) and `app-universal-debug.apk` (120,557,745 B).

## Upgrade path (recommended)
1. **Sync from `whisperbit-labs/zemzeme-android` `main`** (MIT) — not bitchat-android.
2. Re-apply the Axzora rebrand on top: `namespace = "app.axzora.chat"`, app name/icon,
   versionCode 16 / versionName 1.0.16 (or jump to match upstream 2.x if desired).
3. Bump the four upgrade-sensitive bits only if needed: nothing in `libs.versions.toml`
   is stale (AGP 8.10.1 / Kotlin 2.2.0 / SDK 35 are current).
4. Sign with the Axzora release key (upstream reads `KEYSTORE_*` from `local.properties`).
5. Distribute via the existing Play-Core in-app-update path, or wire it into the AxzoraOS
   OTA hub (`:8800` → `/update/*`) if you want the same update channel as AxzoraOS.

## License
- zemzeme-android: **MIT** (permissive) → Axzora Chat can stay MIT and is safe to extend
  with commercial Axzora Pay.
- bitchat: Unlicense. bitchat-android: **GPL-3.0** (copyleft) — avoid.

## Changes made to this clone (build-only, do not carry into the fork)
- `app/build.gradle.kts`: ABI splits narrowed to `arm64-v8a` (golib is arm64-only).
- `gradle.properties`: memory tuning (above) + wrapper `networkTimeout` 120s.

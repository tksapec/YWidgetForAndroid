# Internal Release Build Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce an internally distributable `release` APK signed with the standard Android debug key without changing runtime behavior.

**Architecture:** Add an explicit Android `release` build type in the app module. Reuse the existing debug signing configuration, keep shrinking disabled, and verify the release variant locally before device smoke testing.

**Tech Stack:** Android Gradle Plugin, Kotlin DSL, Kotlin/Android, Gradle wrapper.

**Spec:** `docs/superpowers/specs/2026-08-23-release-build-design.md`

## Global Constraints

- `applicationId = "com.tksapec.ywidget"` remains unchanged.
- `versionCode = 3` and `versionName = "1.2"` remain unchanged for this step.
- The release APK uses `signingConfigs["debug"]`.
- `isMinifyEnabled = false` for the release build.
- No dedicated keystore, password, or signing secret is added to Git.
- The embedded Yahoo Client ID remains unchanged.
- No GitHub Actions workflow is added or run.

---

### Task 1: Configure and verify the internal release APK

**Files:**
- Modify: `app/build.gradle.kts`

**Interfaces:**
- Consumes: Android Gradle Plugin's existing `signingConfigs["debug"]`.
- Produces: signed `release` APK at `app/build/outputs/apk/release/app-release.apk`.

- [ ] **Step 1: Establish the pre-change release-build baseline**

Run on the local development PC:

```powershell
.\gradlew.bat signingReport
.\gradlew.bat assembleRelease
```

Expected before the change: `assembleRelease` may build an unsigned release APK or otherwise show that no explicit release signing configuration exists. Record the output only; do not change runtime code.

- [ ] **Step 2: Add the minimal release build configuration**

Add inside `android { ... }` in `app/build.gradle.kts`:

```kotlin
buildTypes {
    release {
        isMinifyEnabled = false
        signingConfig = signingConfigs.getByName("debug")
    }
}
```

Do not alter `defaultConfig`, dependencies, Yahoo Client ID, package name, or Android SDK levels.

- [ ] **Step 3: Run unit tests and release lint**

Run:

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintRelease
```

Expected: both commands end with `BUILD SUCCESSFUL`.

- [ ] **Step 4: Build and inspect the signed release APK**

Run:

```powershell
.\gradlew.bat assembleRelease
.\gradlew.bat signingReport
Get-Item .\app\build\outputs\apk\release\app-release.apk
```

Expected:

- `assembleRelease` ends with `BUILD SUCCESSFUL`;
- the `release` variant reports the debug signing configuration;
- `app-release.apk` exists and has a non-zero file size.

- [ ] **Step 5: Install and smoke-test the release APK**

Install using:

```powershell
adb install -r .\app\build\outputs\apk\release\app-release.apk
```

If Android reports a signature mismatch, uninstall only after acknowledging that app settings and placed widgets will be removed, then install again.

Verify application startup, widget placement/redraw, news, weather, fixed-location Yahoo rain forecast, current-location Yahoo rain forecast, and manual refresh without crashes.

- [ ] **Step 6: Commit**

Commit only `app/build.gradle.kts` for the implementation change with a concise message such as:

```text
Configure signed internal release APK
```

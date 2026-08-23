# Yahoo Rain Alert Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a Yahoo! Weather API based near-term rain warning bar to YWidgetForAndroid without coupling it to the existing news/weather refresh worker.

**Architecture:** Add a pure rain-alert domain/policy module, a Yahoo JSON HTTP client, persistent rain-alert fields in WidgetPreferences, and a dedicated 15-minute RainAlertWorker. The worker resolves the existing weather location, queries the center plus 8 surrounding points, evaluates center/nearby rainfall separately, saves only the derived alert state, and redraws the widget. The Yahoo Client ID is injected through BuildConfig from `YAHOO_CLIENT_ID` rather than committed.

**Tech Stack:** Kotlin 2.0.21, Android SDK 35, DataStore Preferences, WorkManager 2.10.0, Glance 1.1.1, Fused Location Provider, kotlinx.serialization JSON.

**Spec:** `docs/superpowers/specs/2026-08-23-yahoo-rain-alert.md`

## Global Constraints
- Do not add or enable GitHub Actions.
- Keep existing news and Open-Meteo refresh behavior unchanged except for shared location scheduling hooks required by the feature.
- Yahoo rainfall threshold is 0.5 mm/h.
- Probe radius is approximately 3 km with 9 total points.
- Center forecast horizon is 60 minutes; nearby-only horizon is 30 minutes.
- Rain worker periodic interval is 15 minutes; alert data expires after 45 minutes.
- Do not commit the Yahoo Client ID.

---

### Task 1: Rain alert domain policy

**Files:**
- Create: `app/src/main/java/com/tksapec/ywidget/data/RainAlert.kt`
- Test: `app/src/test/java/com/tksapec/ywidget/data/RainAlertTest.kt`

**Interfaces:**
- Produces: `RainAlertLevel`, `RainProbePoint`, `RainObservation`, `RainAlertState`, `buildRainProbePoints()`, `evaluateRainAlert()` and alert freshness helpers.

- [ ] Write unit tests for 9-point geometry, threshold, center observation, center 15/30/60 minute forecast, nearby 30 minute behavior, and stale state.
- [ ] Implement the minimum pure Kotlin policy code to satisfy those tests.
- [ ] Review boundary behavior at exactly 0.5 mm/h, 15, 30, 60 and 45 minutes.

### Task 2: Yahoo Weather API client

**Files:**
- Create: `app/src/main/java/com/tksapec/ywidget/network/YahooRainClient.kt`
- Test: `app/src/test/java/com/tksapec/ywidget/network/YahooRainClientTest.kt`

**Interfaces:**
- Consumes: `RainProbePoint`, `RainObservation`.
- Produces: `YahooRainClient.fetch(points)` and `parseRainObservations(body)`.

- [ ] Write parser tests using representative Yahoo JSON with multiple Feature entries.
- [ ] Implement URL generation with `appid`, `coordinates`, `output=json`, `interval=5`, `past=0` and network timeouts.
- [ ] Validate HTTP status and malformed/empty response handling without swallowing coroutine cancellation in callers.

### Task 3: Build-time Client ID configuration

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `.gitignore` only if needed for local secret/property handling.
- Create: `docs/YAHOO_RAIN_API_SETUP.md`

**Interfaces:**
- Produces: `BuildConfig.YAHOO_CLIENT_ID: String` sourced from Gradle property or environment variable `YAHOO_CLIENT_ID`, default blank.

- [ ] Add escaped BuildConfig field generation without embedding a credential.
- [ ] Document Android Studio/Gradle configuration and explain that blank configuration disables fetching.

### Task 4: Persistent rain alert state

**Files:**
- Modify: `app/src/main/java/com/tksapec/ywidget/data/Models.kt`
- Modify: `app/src/main/java/com/tksapec/ywidget/data/WidgetPreferences.kt`
- Test: `app/src/test/java/com/tksapec/ywidget/data/WidgetPreferencesTest.kt`

**Interfaces:**
- Produces settings fields for alert level, minutes, rainfall, nearby flag, updated timestamp and diagnostic error plus `saveRainAlert()`, `clearRainAlert()`, `saveRainAlertError()`.

- [ ] Write persistence tests for save/clear/error transitions.
- [ ] Add DataStore keys and mapping.
- [ ] Clear rain state when weather location mode or fixed location changes so a prior-location alert is never shown as current.

### Task 5: Dedicated 15-minute RainAlertWorker

**Files:**
- Create: `app/src/main/java/com/tksapec/ywidget/work/RainAlertWorker.kt`
- Test: `app/src/test/java/com/tksapec/ywidget/work/RainAlertWorkerTest.kt`

**Interfaces:**
- Consumes: `BuildConfig.YAHOO_CLIENT_ID`, current WidgetSettings location fields, YahooRainClient and RainAlert policy.
- Produces: `schedule(context)`, `cancel(context)`, a unique 15-minute periodic work registration, and one-shot `doWork()` behavior.

- [ ] Write pure helper tests for target selection and fixed interval policy.
- [ ] Implement fixed/current target resolution with balanced-power current location fallback and fresh cache reuse.
- [ ] Fetch 9 points, evaluate, persist, redraw and classify retryable network failures.
- [ ] Clear stale alert on missing/unusable location; persist diagnostic errors without leaving misleading warnings.

### Task 6: Widget warning bar

**Files:**
- Modify: `app/src/main/java/com/tksapec/ywidget/widget/YWidget.kt`
- Test: `app/src/test/java/com/tksapec/ywidget/widget/RainAlertDisplayTest.kt`

**Interfaces:**
- Produces: `rainAlertDisplay(settings, now)` and conditional `RainAlertBar`.

- [ ] Write display tests for current rain, center forecast, nearby forecast, exact severity boundaries and stale suppression.
- [ ] Render a single prominent colored bar between header/divider and news list only when active.
- [ ] Keep existing widget layout unchanged when no active alert exists.

### Task 7: Lifecycle scheduling

**Files:**
- Modify: `app/src/main/java/com/tksapec/ywidget/MainActivity.kt`
- Modify: `app/src/main/java/com/tksapec/ywidget/widget/YWidget.kt` receiver section if widget lifecycle scheduling occurs there.

**Interfaces:**
- Consumes: `RainAlertWorker.schedule()` / `cancel()`.

- [ ] Schedule rain work when the app/widget initializes and when weather/location settings change.
- [ ] Cancel rain work when the feature has no usable weather location configuration if appropriate.
- [ ] Confirm no GitHub workflow files are created.

### Task 8: Static verification and review

**Files:** all changed files.

- [ ] Re-fetch every changed file from GitHub and review compile-level imports/signatures.
- [ ] Review WorkManager uniqueness, stale-data behavior, location permission behavior, Client ID absence, HTTP error/retry behavior and widget vertical layout.
- [ ] Compare branch against `fix/review-completion` and ensure only intended feature/docs changes remain.
- [ ] Because GitHub Actions must not run and the current container cannot resolve github.com for Gradle dependency retrieval, explicitly report any verification that cannot be executed rather than claiming it passed.

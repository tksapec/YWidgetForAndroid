# Yahoo Rain API setup

The rain-alert feature uses Yahoo! JAPAN Weather API and requires a Yahoo Developer Network Client ID (`appid`).

## Privacy and runtime behavior

Yahoo rain alerts are **off by default**. The app does not start Yahoo rain polling merely because normal weather display is enabled.

When the user explicitly enables `Yahoo!雨予報` in the settings screen:

- the selected current/fixed location and eight surrounding probe coordinates are sent to Yahoo! JAPAN Weather API;
- the rain worker is scheduled at approximately 15-minute intervals;
- periodic polling is registered only while at least one YWidget home-screen widget is placed;
- disabling the rain-alert option stops the periodic and immediate rain work and clears the active rain banner;
- changing location invalidates any in-flight result for the previous location before it can be stored.

Current-location mode requires the Android foreground location permission at the time of each rain lookup. Rain polling never sends a previously cached current-location coordinate after that permission has been revoked. A current-location cache is accepted for rain lookup only while it is at most 15 minutes old. Background location permission is intentionally not requested, so fixed-location mode remains the more predictable choice for unattended operation.

## Rain-data freshness and validation

- The API request contains the center plus all 8 surrounding probe coordinates in one request.
- A response is accepted only when usable weather data is returned for every requested probe; partial/mismatched/duplicate coordinate responses are treated as errors instead of as "no rain".
- The center observation timestamp must be within 15 minutes of the fetch time and no more than 5 minutes in the future.
- `Raining` freshness is anchored to the Yahoo center **observation timestamp**, not to the later HTTP fetch completion time, and is limited to 15 minutes.
- Forecast alerts use the absolute Yahoo forecast timestamp. They are hidden once they become too old for their effective urgency level, and never remain visible more than 5 minutes beyond the predicted rain time without a newer successful forecast.
- A failed refresh does not overwrite a still-fresh previous alert, but the local expiry worker still removes that alert when its freshness window ends.

## Client ID configuration

The Yahoo Client ID is embedded directly in `app/build.gradle.kts` and exposed to application code as `BuildConfig.YAHOO_CLIENT_ID`.

No Gradle property or environment-variable setup is required for local builds. Because this repository is public and an Android APK can also be inspected, the embedded Client ID must be treated as publicly observable. If the Client ID is replaced or revoked, update the `buildConfigField` value in `app/build.gradle.kts` and rebuild the application.

## API quota and release planning

Yahoo! JAPAN Weather API has an application-level request limit. At the currently documented limit of 50,000 requests per 24 hours per application, 15-minute polling is approximately 96 requests per active device per day before retries/manual refreshes. Re-check the latest Yahoo documentation before a public release and assess whether the expected installed-device count fits the shared Client ID quota.

The app version for this feature is `versionCode 3` / `versionName 1.2`.

## Attribution when publishing

Yahoo! Developer Network requires applications using its APIs to provide the prescribed credit. For a smart-device application without its own web site, the current credit rules instruct developers to place the prescribed credit at the bottom of the application store page. Confirm the latest rules before publishing:

- https://developer.yahoo.co.jp/attribution/
- https://developer.yahoo.co.jp/webapi/map/openlocalplatform/v1/weather.html

Do not invent or restyle a Yahoo credit/logo inside the widget; use the current prescribed credit format and placement rules.

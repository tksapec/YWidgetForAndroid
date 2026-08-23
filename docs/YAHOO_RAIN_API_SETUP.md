# Yahoo Rain API setup

The rain-alert feature uses Yahoo! JAPAN Weather API and requires a Yahoo Developer Network Client ID (`appid`).

The Client ID is intentionally not stored in this public repository. Configure it at build time with one of the following methods.

## Option 1: user Gradle properties (recommended for Android Studio)

Add this line to your user-level Gradle properties file (`~/.gradle/gradle.properties` on macOS/Linux or `%USERPROFILE%\.gradle\gradle.properties` on Windows):

```properties
YAHOO_CLIENT_ID=your_client_id_here
```

## Option 2: command-line project property

```text
./gradlew assembleDebug -PYAHOO_CLIENT_ID=your_client_id_here
```

## Option 3: environment variable

Set `YAHOO_CLIENT_ID` in the build environment before invoking Gradle.

If no Client ID is configured, the application continues to build and the existing news/weather features continue to work, but Yahoo rain fetching is disabled and the rain worker records a diagnostic error instead of showing a stale warning.

Use a Client ID registered for this application. Do not commit the Client ID to source control; a client-side application cannot make the identifier truly secret, but keeping it outside the public repository reduces accidental reuse and quota consumption.

## Attribution when publishing

Yahoo! Developer Network requires applications using its APIs to provide the prescribed credit. For a smart-device application without its own web site, the current credit rules instruct developers to place the prescribed credit at the bottom of the application store page. Confirm the latest rules before publishing:

- https://developer.yahoo.co.jp/attribution/
- https://developer.yahoo.co.jp/webapi/map/openlocalplatform/v1/weather.html

Do not invent or restyle a Yahoo credit/logo inside the widget; use the current prescribed credit format and placement rules.

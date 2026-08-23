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

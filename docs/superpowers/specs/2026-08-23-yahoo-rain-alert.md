# Yahoo Rain Alert Specification

## Goal
Add a rain alert to the existing Android home-screen widget using Yahoo! JAPAN Weather API rainfall observation/forecast data.

## Approved behavior
- Use the same location selection as the existing weather feature: current location or fixed location.
- Query the center point plus 8 surrounding points approximately 3 km away (N, NE, E, SE, S, SW, W, NW) in one Yahoo Weather API request.
- Request 5-minute interval rainfall data from the current observation through the 60-minute forecast horizon.
- Treat rainfall intensity below 0.5 mm/h as no alert.
- Show an alert when either:
  - the center point is forecast at 0.5 mm/h or more within 60 minutes, or
  - a surrounding point is at 0.5 mm/h or more within 30 minutes.
- Alert priority:
  - center currently raining: `Raining`
  - rain within 15 minutes: `Imminent`
  - rain within 30 minutes: `Soon`
  - center rain within 60 minutes: `Watch`
- When only surrounding points trigger the alert, label it as nearby rain rather than claiming rain will definitely reach the center.
- Render a noticeable one-line warning bar between the widget header and news list only while an alert is active.
- Do not reserve vertical space when no alert is active.
- Refresh rain data using a separate 15-minute WorkManager periodic worker with network connectivity constraint.
- Expire a stored alert if it has not been refreshed for 45 minutes so stale alerts do not remain indefinitely.
- Do not add or enable GitHub Actions.

## Yahoo API integration
- Endpoint: `https://map.yahooapis.jp/weather/V1/place`
- Method: GET
- Required parameters: `appid`, `coordinates`
- Use `output=json`, `interval=5`, `past=0`.
- `coordinates` is longitude,latitude and supports up to 10 points separated by spaces.
- Client ID must not be committed to the public repository. Resolve it at build time from Gradle property or environment variable `YAHOO_CLIENT_ID`; blank configuration disables remote rain fetching and records a diagnostic error.

## Location and power behavior
- Reuse a recent cached current location where possible.
- If current-location mode is selected and the cache is stale, attempt a balanced-power fused location lookup without adding background-location permission.
- Fixed-location mode reuses already-resolved fixed coordinates.
- If a usable location is unavailable, clear/expire the rain alert rather than showing stale or misleading data.

## Testing
Add unit tests for:
- 9-point coordinate generation and center-first ordering.
- Yahoo JSON parsing, including multiple Features.
- threshold behavior at 0.5 mm/h.
- center current rain, 15/30/60-minute center forecast levels.
- nearby-only 30-minute alert behavior.
- no nearby-only alert beyond 30 minutes.
- stale alert expiration and widget display text.
- missing Client ID behavior and WorkManager interval policy where practical without instrumentation.

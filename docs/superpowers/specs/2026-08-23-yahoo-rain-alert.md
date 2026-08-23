# Yahoo Rain Alert Specification

## Goal
Add an opt-in rain alert to the existing Android home-screen widget using Yahoo! JAPAN Weather API rainfall observation/forecast data.

## Approved behavior
- Yahoo rain alerts are disabled by default and require explicit user opt-in.
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
- Recompute the remaining minutes from the absolute forecast timestamp whenever the widget redraws. Escalate `Watch`/`Soon` display severity as the forecast time approaches.
- Highlight rainfall of 2 mm/h or more in the wording and 10 mm/h or more with the strongest banner treatment.
- When only surrounding points trigger the alert, label it as nearby rain rather than claiming rain will definitely reach the center.
- Render a noticeable one-line warning bar between the widget header and news list only while an alert is active.
- Do not reserve vertical space when no alert is active.
- Refresh rain data using a separate 15-minute WorkManager periodic worker with network connectivity constraint.
- Register periodic rain work only while at least one widget is placed.
- Serialize schedule/cancel operations and wait for WorkManager registration operations so an old schedule cannot survive a later disable.
- Expire urgent alerts sooner than low-urgency alerts: `Raining` 15 minutes, `Imminent` 20 minutes, `Soon` 30 minutes, `Watch` 45 minutes.
- Do not add or enable GitHub Actions.

## Result ownership and race safety
- Maintain a rain-specific generation independent from the normal news/weather refresh generation.
- Increment the rain generation when the rain feature is enabled/disabled or its location source changes.
- Capture the rain generation before remote work begins and validate it atomically inside the DataStore write.
- In current-location mode, capture latitude, longitude, and location timestamp as part of the write guard. Reject a result if a newer current-location snapshot replaced it while the Yahoo request was in flight.
- Never use a separate "check then save" sequence for rain result ownership.

## Yahoo API integration
- Endpoint: `https://map.yahooapis.jp/weather/V1/place`
- Method: GET
- Required parameters: `appid`, `coordinates`
- Use `output=json`, `interval=5`, `past=0`.
- `coordinates` is longitude,latitude and supports up to 10 points separated by spaces.
- Client ID must not be committed to the public repository. Resolve it at build time from Gradle property or environment variable `YAHOO_CLIENT_ID`.
- A blank Client ID does not affect existing news/weather behavior. It becomes a rain diagnostic error only after the user explicitly enables the rain feature.
- Validate returned Feature coordinates against requested probes with a bounded tolerance, reject duplicate Features for the same probe, and require the center observation.

## Location and power behavior
- Reuse a recent cached current location where possible.
- If current-location mode is selected and the cache is stale, attempt a balanced-power fused location lookup without adding background-location permission.
- Fixed-location mode reuses already-resolved fixed coordinates or resolves the configured place name if necessary.
- If a usable location is unavailable, clear/expire the rain alert rather than showing stale or misleading data.
- Because background-location permission is intentionally not requested, fixed-location mode is the preferred mode for predictable unattended operation.

## Testing
Add unit/regression tests for:
- 9-point coordinate generation and center-first ordering.
- Yahoo JSON parsing, including multiple Features.
- unexpected/distant Feature coordinates, duplicate Feature mapping, and missing center observation.
- threshold behavior at 0.5 mm/h.
- center current rain, 15/30/60-minute center forecast levels.
- nearby-only 30-minute alert behavior and nearby current-rain wording.
- absolute forecast time countdown and display-level escalation.
- level-specific stale alert expiration.
- explicit opt-in default and rain generation independence from normal refresh generation.
- fixed-region and current-location in-flight result ownership races.
- retry classification and WorkManager interval policy where practical without instrumentation.

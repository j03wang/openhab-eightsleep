# Eight Sleep Cloud API Specification

*Derived from Android app 7.52.27 (`com.eightsleep.eight.apk`) and validated against the
production API on 2026-08-25 with a live account. Status markers: **[V]** = verified live
(200), **[D]** = declared in the app, live call pending, **[X]** = verified gone.*

## 1. Hosts

| Host | Role |
|---|---|
| `https://auth-api.8slp.net` | OAuth token issuance (password + refresh grants) |
| `https://app-api.8slp.net/v1 · v2 · v3` | Primary REST API (household, temperature, alarms, features) |
| `https://client-api.8slp.net/v1` | Legacy host — **still live**; authoritative for device state, trends, user profiles, identity |
| `wss://app-api.8slp.net/v1/users/{userId}/events` | WebSocket event stream (not used by binding) |
| `GET app-api/v1/devices/{id}/live` | SSE device stream (`Accept: text/event-stream`) |

Both `app-api` and `client-api` are alive and serve **different** resources. Device ids are
bare 24-char hex strings (e.g. `410028000547393339313737`) — no `dev_` prefix anywhere on
the current API. The legacy `dev_xxxxxxxx` form is not used anymore.

Error format (app-api): problem-details JSON —

```json
{"type": "https://tools.ietf.org/html/rfc9110#section-15.5.1",
 "title": "One or more validation errors occurred.",
 "status": 400,
 "errors": {"specialization": ["only 'pod|pillow|all' specialization allowed"]},
 "traceId": "00-…"}
```

## 2. Authentication

### Password grant — `POST https://auth-api.8slp.net/v1/tokens` **[V]**

```json
{"client_id": "0894c7f33bb94800a03f1f4df13a4f38",
 "client_secret": "f0954a3ed5763ba3d06834c73731a32f15f168f47d4f164751275def86db0c76",
 "grant_type": "password",
 "username": "me@example.com",
 "password": "…"}
```

Response **[V]**:

```json
{"access_token": "…", "token_type": "…", "expires_in": 72000,
 "refresh_token": "…", "userId": "548ca07898fc48c9ac50588f30d3544d"}
```

Note the identity key is camelCase `userId`.

### Refresh grant — `POST …/v1/tokens` **[V]**

```json
{"client_id": "…", "client_secret": "…", "grant_type": "refresh_token",
 "refresh_token": "…"}
```

Same response shape. All authenticated calls carry `Authorization: Bearer <access_token>`.

## 3. Identity

- The logged-in userId comes from the **auth response** (`userId`). There is **no**
  `GET app-api/v1/users/me` route (**[X]** verified 404).
- `GET client-api/v1/users/me` **[V]** → `{user: {userId, email, firstName, lastName,
  gender, tempPreference, zip, displaySettings{useRealTemperatures, measurementSystem,
  locale, clockSystem}, createdAt, experimentalFeatures, autopilotEnabled, lastReset,
  nextReset, sleepTracking{enabledSince}, chronotype, isChronotypeCalibrating,
  autoPodTemperatureOff, features[warming,cooling,vibration,alarms],
  currentDevice{id, side}, hotelGuest, devices[deviceId…]}}`.
- `GET client-api/v1/users/{userId}` **[V]** — same shape for any household user.
- `GET app-api/v1/users/{userId}` **[X]** — 404.

## 4. Household

### `GET app-api/v1/household/users/{userId}/summary` **[V]**

```json
{"currentSet": "c09a52e6-…", "previousSet": "…",
 "households": [{
   "householdId": "02dd02fb-…", "householdName": "Your Househould",
   "sets": [{"setId": "c09a52e6-…", "setName": "Your Pod",
             "timeZone": "America/Los_Angeles",
             "devices": [{"deviceId": "410028000547393339313737",
                          "deviceName": "Your Pod",
                          "specialization": "pod",
                          "pairing":   {"leftUserId": "…", "rightUserId": "…"},
                          "assignment": {"leftUserId": "…", "rightUserId": "…"}}]}],
   "users": [{"userId": "…", "role": "PRIMARY", "status": "ACCEPTED",
              "schedules": []}],
   "limits": {"maxHouseholdUsers": 6, "maxHouseholdDevices": 15}}]}
```

Away semantics: a user with a non-empty `schedules[]` array has a scheduled return (away);
`pairing` = who is physically in bed, `assignment` = who owns which side. Solo beds show
the same userId under both sides.

### Bed side — `PUT app-api/v1/household/users/{userId}/current-set` **[V]**

```json
{"deviceId": "410028000547393339313737", "side": "left"}
```

Side enum: `left` | `right` | `solo` | `away` | `none`. Verified response echoes the
affected set: `{"setId": "…", "devices": [{"deviceId": "…", "specialization": "pod",
"side": "solo", "timezone": …}]}`. Also
`DELETE …/current-set` (see Away mode).

### Away mode **[D]** (replacement flow verified via current-set PUT **[V]**)

- Immediate away: `DELETE app-api/v1/household/users/{userId}/current-set` with optional
  headers `X-8S-Include-Partner: true`, `X-8S-Return-Date: <ISO-8601>`,
  `X-8S-Partner-Return-Date: <ISO-8601>`.
- Schedule return: `POST app-api/v1/household/users/{userId}/schedule`
  `{"schedule": {"setId": "…", "dateToReturn": "<instant>"}, "includePartner": false}`.
- Cancel return: `DELETE app-api/v1/household/users/{userId}/schedule/{setId}`.
- The legacy `PUT users/{id}/away-mode {"awayPeriod":{…}}` is **gone** (**[X]** 404).
- Away users appear in `summary.users[].schedules` and in app-state message keys of the
  form `scheduled_return_<userId>_<setId>_<date>`.

## 5. Device

### `GET client-api/v1/devices/{deviceId}` **[V]** — full state

The rich device document lives on **client-api**; the same path on app-api is **[X]** 404.
The response is `{"result": {…}}`. Keys of the `result` object (verified):

| Key | Type | Notes |
|---|---|---|
| `deviceId` | string | bare 24-hex |
| `online`, `reconnecting` | bool | |
| `lastHeard` | instant | |
| `modelString` | string | e.g. `Pod 2 Pro` |
| `firmwareVersion`, `firmwareCommit`, `firmwareUpdated`, `firmwareUpdating` | | |
| `hasWater`, `lastLowWater` | bool / instant | |
| `needsPriming`, `priming`, `lastPrime` | bool / instant | |
| `ledBrightnessLevel` | int 0–100 | |
| `leftHeatingLevel`, `rightHeatingLevel` | int −100..100 | current levels |
| `leftTargetHeatingLevel`, `rightTargetHeatingLevel` | int | |
| `leftNowHeating`, `rightNowHeating` | bool | |
| `leftHeatingDuration`, `rightHeatingDuration` | int seconds | |
| `leftKelvin`, `rightKelvin` | object | `{targetLevels[-30,-22,-11], alarms[], scheduleProfiles[{enabled,startLocalTime,weekDays{monday…}}], phases[], level, currentTargetLevel, active, currentActivity}` |
| `leftSchedule`, `rightSchedule` | object | `{daysUTC{…}, enabled, startUTCHour, startUTCMinute, durationSeconds}` |
| `features` | string[] | subset of `warming cooling vibration alarms elevation audio` |
| `awaySides` | `{leftUserId, rightUserId}` | present even when empty |
| `leftUserId`, `rightUserId`, `ownerId` | string | |
| `leftUserInvitationPending`, `rightUserInvitationPending` | bool | |
| `sensorInfo` | object | `{label, partNumber, sku, skuName, hwRevision, serialNumber, model, version, connected, lastConnected, coverType, supportsMaintenanceInserts}` |
| `sides` | object | `left/right: {expected, connected, fault, coverType}` |
| `expectedPeripherals` | array | |
| `hubInfo`, `hubSerial`, `timezone`, `location`, `wifiInfo`, `deactivated`, `encasementType`, `isTemperatureAvailable`, `mattressInfo`, `lastFirmwareUpdateStart`, `sensors` | | |

### Other device routes

- `GET app-api/v1/devices/{id}/online` **[V]** → `{"lastHeard": "2026-08-26T00:43:46.831Z"}`.
- `PUT app-api/v1/devices/{id}` **[X]** 404 — partial device updates do **not** work on
  app-api. Use the legacy host instead.
- `PUT client-api/v1/devices/{id}` **[V]** — partial `UpdateDeviceRequest`
  (`{"ledBrightnessLevel": 49}` → 200 *"Device successfully updated."* with the full
  updated device document).
- `GET app-api/v1/devices/{id}` **[X]** 404; `GET …/peripherals` **[X]** 404 (only
  PATCH/PUT declared); `GET client-api/v1/devices/{id}?filter=leftUserId,rightUserId,awaySides`
  **[V]** still works.
- Priming: `GET/PUT app-api/v1/devices/{id}/priming/schedule` **[V]** (`{schedule: [7x]}`);
  `POST …/priming/tasks` **[V]** — creates a task and **starts priming immediately**
  (`{"task": {"reason": "requested", "status": "starting", "notifications":
  {"users": […], "meta": "fill_pod"}, "created": …}}`). Meta values observed: accepted
  `fill_pod`; a second POST while a task is active returns **409 Conflict** regardless of
  meta. `DELETE …/priming/tasks` **[V]** cancels the running task
  (`cancellationRequested` timestamp in response).
- Vibration test: `POST app-api/v2/devices/{id}/vibration-test`, `PUT …/stop` **[D]**;
  user-level variant `POST app-api/v1/users/{uid}/vibration-test {durationSeconds,
  powerLevel, specialization}` **[D]**.
- Auto-pairing / security key / owner: `app-api/v1/devices/{DEVICE_ID}/auto-pairing/*`,
  `security/key`, `PUT …/owner` **[D]**.

## 6. Temperature

Read/write path: `app-api/v1/users/{userId}/temperature/{specialization}` where
**specialization ∈ `pod` | `pillow` | `all`** (anything else — including `cover` — fails
validation **[V]** 400 *"only 'pod|pillow|all' specialization allowed"*). Query parameter
`ignoreDeviceErrors=false` accepted.

### `GET …/temperature/all` **[V]**

```json
{"devices": [
   {"device": {"deviceId": "410028000547393339313737",
               "side": "solo", "specialization": "pod"},
    "currentLevel": 0,
    "currentDeviceLevel": -10,
    "overrideLevels": {},
    "currentState": {"type": "off", "started": "2026-08-25T11:30:58Z",
                     "instance": {"timestamp": "2026-08-25T11:30:58Z"}},
    "smart": {"bedTimeLevel": -30, "initialSleepLevel": -22, "finalSleepLevel": -11}}],
 "temperatureSettings": [
   {"name": "410028000547393339313737",
    "bedTimeLevel": -30, "initialSleepLevel": -22, "finalSleepLevel": -11},
   {"name": "default", …}, {"name": "pod", …}],
 "nextScheduledTimestamp": "2026-08-22T21:30:00Z",
 "schedules": [{"id": "…", "enabled": true, "time": "21:30:00",
                "days": ["friday","saturday"], "tags": [],
                "startSettings": {"bedtime": -40}}],
 "currentSchedule": {…same shape…},
 "nextSchedule": {…same shape…}}
```

`currentLevel` = live setting, `currentDeviceLevel` = Autopilot-adjusted target,
`currentState.type` drives on/off.

### Write — `PUT …/temperature/{specialization}?ignoreDeviceErrors=` **[V]**

Verified live: all of the following bodies return 200 on
`PUT app-api/v1/users/{uid}/temperature/pod?ignoreDeviceErrors=false`
(`pod` and `pillow` are valid write specializations; `cover` is rejected for writes too —
400 *"only 'pod|pillow|all' specialization allowed"*).

Body (`TemperatureSettingsRequest`), all fields optional — every shape below was
accepted verbatim:

```json
{"currentLevel": -20,
 "overrideLevels": {"hotFlash": null, "bedtime": null, "initialSleep": null, "finalSleep": null},
 "currentState": {"type": "timeBased", "started": "…", "until": "…"},
 "scheduleType": "smart",
 "smart": {"bedTimeLevel": -30, "initialSleepLevel": -22, "finalSleepLevel": -11},
 "reason": "..."}
```

- Timed level = `currentState: {"type": "timeBased", "until": "<instant>"}`.
- The legacy object `{"timeBased": {"level": -10, "durationSeconds": 300}}` is also
  accepted as-is (200), both on app-api `/temperature/{pod|pillow}` and on
  client-api `/users/{uid}/temperature`.
- Response is the full updated aggregate document (same shape as the reads).
- Whole numbers must serialize as ints (backend rejects `-10.0`).

`currentState.type` enum: `smart`, `smart:initial`, `smart:bedtime`, `smart:final`,
`alarm`, `off`, `timeBased`, `hotFlash`, `nap`, `unknown`.

Legacy `GET/PUT client-api/v1/users/{uid}/temperature` **[V]** still works end-to-end:
GET returns `{currentLevel, currentSchedule, currentState, nextBedtimeDisplayWindow,
nextSchedule, nextScheduledTimestamp, settings}`; PUT applies `{currentLevel}` /
`{currentState:{type,…}}` / legacy `{timeBased{level,durationSeconds}}` and echoes the
updated document.

### Temperature events **[V]**

- `GET …/temp-events` → `{events: [{eventTime, actionType("dial-update"), deviceId,
  currentPhase, previousPhase, previousLevel}], phases: [...]}`.
- `GET …/temp-events/nightly?align=from&fillEmpty=true&from&to&bucket=day&tz=` →
  `{from, to, bucket, align,
    avg: [{stage: bedtime|initial|final, value}], stdDev: […], min: […], max: […],
    samples: [{date, avg[…], stdDev[…], min[…], max[…]}]}`.

### Nap mode **[V]**

- `GET …/temperature/nap-mode` → `{"defaultDuration": "00:20:00",
  "defaultLevels": {"pod": -30, "pillow": -30}, "alarmRequested": false}`.
- `GET …/nap-mode/status` → 404 unless a nap session is running; when active:
  `{startTime, endTime, levels{pod,pillow}, alarmRequested, sessionId}`.
- Start: `POST …/nap-mode/activate {"duration":"01:00:00", "levels":{"pod":-30,"pillow":-30},
  "alarmRequested":false}` (duration is `HH:mm:ss`).
- Extend: `POST …/nap-mode/extend {"additionalDuration":"00:15:00"}`.
- End: `PUT …/nap-mode/deactivate`.
- Alarm-side nap settings: `GET/PUT v1/users/{uid}/temporary-mode/nap-mode` **[D]**.

### Hot flash **[V]**

- `GET …/temperature/hot-flash-mode` → `{"enabled": false, "levelDelta": -100,
  "hotFlashDuration": "0:15:00"}`.
- `PUT …/hot-flash-mode {"hotFlashSettings": {"enabled": true, "hotFlashDuration":
  "00:03:00", "levelDelta": -10}}`; activate/deactivate via
  `PUT …/hot-flash-mode/activate|deactivate`.

### Pregnancy **[V]**

`GET …/pregnancy-mode` → `{}` when inactive; plus `/recommendation`,
`/recommendation/undo`, `/journey-stats?stage=` **[V]/[D]**.

## 7. Adjustable base

- `GET app-api/v1/users/{uid}/base` — 404 when no base paired (resource-per-user).
- `GET app-api/v2/users/{uid}/base/presets` **[V]** → `{presets: [{name, torsoAngle,
  legAngle, isDefault, isEditable, aliasOf}]}` (18 presets incl. `sleep`, `sleep-flat`,
  `anti-snore-low`, `reading-neck-support`, `relax-weightless`, `flat` …).
- `POST app-api/v1/users/{uid}/base/angle?ignoreDeviceErrors=` with either
  `{torsoAngle, legAngle, deviceId, snoreMitigation}` (`SetBaseAngleRequest`) or
  `{preset, deviceId, snoreMitigation}` (`SetBasePresetRequest`) **[V]** — both shapes
  verified accepted (200, empty body).
- `DELETE app-api/v1/devices/{id}/base` (unpair) **[D]**.

## 8. Audio **[V]/[D]**

- `GET app-api/v1/audio/categories` **[V]** → `{categories: [{id:"soundscapes", name,
  description, detailedDescription, accentedTitle, tags[], appData{assetUrl, thumbnailUrl,
  backgroundStyle, backgroundColors[]}}]}`.
- `GET app-api/v1/users/{uid}/audio/player` — 404 without a speaker **[V]**; player state:
  `Unavailable | Ready | Playing | Paused | Loading`.
- Controls: `PUT …/audio/player/state {state}`, `PUT …/audio/player/volume {volume}`,
  `PUT …/audio/player/seek`, `POST …/audio/player/preview-track {trackId, volume}`,
  favorites `PUT/DELETE …/audio/tracks/{trackId}/favorites`, `GET …/audio/tracks?category=`,
  `GET …/audio/tracks/recommended-next-track`, pair `POST app-api/v1/devices/{id}/audio/player/pair`,
  device-level player `GET/DELETE app-api/v1/devices/{id}/audio/player` **[D]**.

## 9. Alarms **[V]**

- `GET app-api/v2/users/{uid}/alarms` →

```json
{"alarms": [{"id": "b7fbf288-…", "enabled": true, "time": "05:15:00",
  "repeat": {"enabled": true, "weekDays": {"monday": true, …}},
  "vibration": {"enabled": false, "powerLevel": 50, "pattern": "INTENSE"},
  "thermal": {"enabled": true, "level": 30},
  "audio": {"enabled": false, "level": 30, "trackId": "futuristic"},
  "smart": {"lightSleepEnabled": true, "sleepCapEnabled": false, "sleepCapMinutes": 480},
  "skipNext": false,
  "nextTimestamp": "2026-08-26T12:15:00Z",
  "startTimestamp": "2026-08-26T12:15:00Z", "endTimestamp": "2026-08-26T12:35:00Z",
  "dismissedUntil": "2026-08-25T12:15:00Z",
  "skippedUntil": "1970-01-01T00:00:00Z", "snoozedUntil": "1970-01-01T00:00:00Z",
  "snoozing": false, "tags": ["routine-…"]}],
 "recommendedAlarm": {…}, "updatedAlarm": null}
```

- Create `POST v1/users/{uid}/alarms` **[D]**; update `PUT v1/users/{uid}/alarms/{alarmId}`
  (bare object, whole-number ints — backend rejects `-10.0`) **[V]** — response is the
  full `{alarms: […]}` list;
- `PUT …/{alarmId}/snooze {snoozeMinutes, ignoreDeviceErrors}` **[V]** — 409 Conflict
  (RFC-9110 problem details) when no alarm is ringing;
- `PUT …/{alarmId}/dismiss {ignoreDeviceErrors}`;
- `PUT v1/users/{uid}/alarms/active/dismiss-all`;
- `DELETE …/{alarmId}`;
- one-time overrides via `oneTimeOverride` field; vibration test
  `POST v1/users/{uid}/vibration-test {durationSeconds, powerLevel, specialization}`.

## 10. Sleep data & metrics

- Trends: `GET **client-api**/v1/users/{uid}/trends?tz=&from=&to=&include-main=&
  include-all-sessions=&model-version=v2[&consistent-read=false]` **[V]** →
  `{days: [{day, score, tnt, presenceDuration, sleepDuration, remDuration, remPercent,
  lightDuration, deepDuration, deepPercent, snoreDuration, heavySnoreDuration, …,
  presenceStart, presenceEnd, sessions: [{timeseries: {heartRate: [[iso,value]…],
  tempBedC: […], tempRoomC: […], respiratoryRate: […]}}]}], avgScore, modelVersion,
  sfsCalculator, …}`. Values may be the literal string `"None"`. The same path on
  app-api is **[X]** 404.
- Metrics: `GET app-api/v1/users/{uid}/metrics/summary?from&to&tz&metrics=` and
  `…/metrics/aggregate?v2=true|…` — routes exist **[V→400]**; exact `metrics` enum and
  required params not yet pinned (common names like `hrv` still 400).
- Sessions: `PUT/DELETE app-api/v1/users/{uid}/intervals/{sessionId}` **[D]**;
  `GET client-api/v1/users/{uid}/intervals` **[V]**.
- Days/tags: `GET …/days/count` **[V]** `{count}`, truth-tags **[V]** `{tags: []}`,
  day tags `PUT v1/users/{uid}/days/{day}/tags` **[D]** (plain `GET /tags`,
  `/tags/summary` are **[X]** 404).
- Tap history/history: `GET app-api/v1/users/{uid}/tap-history?from=` (route exists,
  param contract unresolved — 400 **[V]**); tap-settings
  `GET/PUT v1/users/{uid}/devices/{did}/tap-settings` **[V]**
  (`settings.{deviceId}.{alarm,generic,quadTap}{options[], currentDoubleTap,
  currentQuadTap}`).
- Feedback: `POST app-api/v1/users/{uid}/feedback` **[D]**.

## 11. Autopilot / intelligence **[V]**

- `GET app-api/v1/users/{uid}/level-suggestions-mode` →
  `{"autopilotMode": "automatic", "autopilotEnabled": true,
    "autopilotOptions": {"ambientTempEnabled": true, "llmEnabled": false}}` (also PUT).
- `GET …/level-suggestions` **[V]**; blanket recommendations
  `GET …/recommendations/blanket` **[V]** → `{insights: […]}`.
- `GET …/autopilotDetails` **[V]** → `{snoringMitigation: {enabled, sleepStyle: back,
  mitigationLevel: low, mitigationCount, daysWithMitigation,
  smartElevation: {offPreset: flat}}, canUseMitigationLevels, daysUntilCanUseLevels,
  userMode: {autopilotMode, autopilotToggledOn, autopilotOptions},
  calibrationStatus: {isCalibrated, isFirstSessionAfterCalibration,
  calibrationDaysCompleted, daysRequiredForCalibration},
  hasActiveSubscription, isAutopilotActive}`; PUT `snoringMitigation` subpath **[D]**.
- `GET …/autopilot-history` **[V]** → `{userAutopilotHistory: {totalInterventions,
  totalNights}}`.
- Weekly home card: `POST app-api/v1/intelligence/{uid}/podai/weekly_homecard` **[D]**.
- AI insights: `GET …/llm-insights` **[V]** → `{insights: {analysis: [{id, type: analysis,
  state: complete, text: {eyebrow, title, titleQuestion, body, findings, interpretation,
  explanation, physiologyExplanation}}], headlineCombined, headline, recommendation,
  tagAnalysis}}`; `GET/PUT …/llm-insights/settings` **[V]** → `{priorityMetricsList:
  [sleep_duration, hr, hrv, deep_duration, rem_duration], length, tone}`;
  batch `POST …/llm-insights/batch`, feedback `POST …/llm-insights/{id}/feedback` **[D]**.

## 12. Account / misc **[V]**

- Notifications: `GET app-api/v1/users/{uid}/notifications?active=true` →
  `{notifications: [], pagination: {limit: 10, cursor: "", hasMore: false}}`;
  push registration `POST/DELETE v1/users/me/push-targets/token/{token}`,
  `PUT v1/users/me/push-targets/{deviceId}`; ack `POST v1/push_event/acknowledge`.
- Subscriptions: `GET app-api/v3/users/{uid}/subscriptions` → `{subscriptions: [2x],
  primarySubscription: {ownerUserId, subscriberRelationship, subscriptionId, type, status,
  createdAt, willRenew, hasBillingFailure, isUnpaidPremium, inGracePeriod,
  subscriptionFrequency, subscriptionProductName, provider, isRental, isActive,
  isActivePremium, isActiveBasic}, hasBasic, hasAccessToBasicFeatures,
  hasAccessToPremiumFeatures, isUnpaidPremium, isInGracePeriod,
  capabilities: [17 names incl. autopilot, away_mode, temperature_scheduling,
  smart_elevation, …], churned}`; redeem/temporary `POST v3/...` **[D]**.
- App state: `GET/PUT v1/users/{uid}/app-state/onboard`,
  `GET/POST/PATCH v1/users/{uid}/app-state/messages` **[V]** (message keys include
  `scheduled_return_<uid>_<setId>_<ts>` while away).
- Health: `GET …/health-integrations/metadata` **[V]** → `{from, to, totalDataPoints,
  healthSourceCount, healthSources: [], activityTags: []}`; upload/checkpoint POSTs **[D]**.
- Calendar: `POST v1/users/{uid}/calendar-integrations/sources/{source}`,
  `PUT …/{source}/checkpoint` **[D]**.
- Consent: `POST v1/users/{uid}/consent` **[D]**; SMS: `GET v1/sms/users/{uid}` **[V]** →
  `{id, phoneNumber, settings: {enabled, verified, morningSummaryEnabled, awsEnabled,
  whatsAppEnabled, whatsAppVerified, whatsAppMorningSummaryEnabled}, groups: []}`.
- Maintenance: `GET v1/user/{uid}/device_maintenance/maintenance_insert?v=2` **[V]** →
  `[{deviceId, deviceAddress{…}, previousInsertReplacement{replacementDate,
  invoiceNumber, …}, nextInsertReplacement}]`.
- Travel/jet lag: `GET v1/users/{uid}/travel/trips` **[V]** → `[]`;
  trips/plans/tasks CRUD, `GET v1/travel/airport-search`, `GET v1/travel/flight-status` **[D]**.
- Misc reads **[V]**: `v1/users/{uid}/perks` (member offers), `purchase-tracker`,
  `v1/household/users/{uid}/invitations`, `v1/users/{uid}/tap-history` (400 — param TBD),
  `v1/health-survey/test-drive`, decagon-auth `v1/users/{uid}/decagon-auth` **[D]**.
- User management: `POST app-api/v1/users` (register), `POST …/password-temporary`,
  `POST …/password-reset`, `PUT v1/users/{uid}` (profile), `POST …/{uid}/email`,
  OTP `POST …/{uid}/otp/send|verify|decline` **[D]**.

## 13. Real-time streams (not used by the binding)

- WebSocket: `wss://app-api.8slp.net/v1/users/{userId}/events` (Bearer auth).
- SSE: `GET app-api/v1/devices/{deviceId}/live` with `Accept: text/event-stream`.

## 14. Validation & error behavior

- App-API validation errors use RFC-9110 problem details with an `errors` map keyed by
  query/body field (e.g. `specialization: ["only 'pod|pillow|all' specialization allowed"]`).
- 401 bodies: app-api `{"message":"unauthorized"}`; client-api
  `{"status":401,"code":"Unauthorized"}`.
- Absent hardware surfaces as 404 on otherwise-valid routes (base, audio player,
  nap-mode/status when idle) — callers must treat 404 as "not applicable", not as an
  authentication failure.

---

*Validated against production on 2026-08-25 (account: Pod 2 Pro, solo, no base/speaker).
Probe tools: `tools/api_explorer.py probe` for reads and `tools/api_explorer.py
probe-writes` for echo-safe write validation; raw responses in `.api-probe-results.json`
(gitignored). Decomp sources under `/tmp/eightapk/out/sources`.*

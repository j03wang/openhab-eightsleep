# Eight Sleep Binding — Design & Architecture

*Describes the current implementation.*

## 1. Overview

The binding integrates Eight Sleep smart mattresses (Pod 2/3/4/5, pillows) with openHAB. There is no local protocol: everything flows through Eight Sleep's cloud REST APIs (an OAuth-protected `client-api` and an app-facing `app-api`). The binding therefore has no transport layer of its own beyond HTTPS — its architecture is about **session management, polling, caching, and last-write-wins (LWW) reconciliation** between commanded state and polled state.

```
                    openHAB framework
                           │
    ┌──────────────────────┼──────────────────────┐
    │                      │                      │
AccountHandler       BedSideHandler     BedSideDiscoveryService
(BaseBridgeHandler)  (BaseThingHandler) (ThingHandlerService)
 │      │              │        │
 │      │ writes       │ sync   │ dispatch
 │      ▼              ▼        └─────────────────────────┐
 │ AccountPoller ──► UserDataCache ◄── BedSideChannelSync │
 │      │                                      (pure fn)  │
 │ polls│                                                 │
 ▼      ▼                                                 ▼
 └─► EightSleepApiClient ◄─────────────────────── BedSideCommands
                        │
             TokenManager (OAuth session)
                        │
                ApiHttpClient (JDK HttpClient)
                        │
              Eight Sleep cloud APIs
```

Flows: AccountPoller polls via the client and writes observations into UserDataCache; BedSideChannelSync reads only caches and decides channel updates; BedSideHandler dispatches each incoming channel command to BedSideCommands, which issues mutations through the client (stamped for last-write-wins reconciliation by sync).

## 2. Thing model

| Thing | Type | Purpose |
|---|---|---|
| `account` | bridge | One Eight Sleep login. Owns authentication, polling cadence, device/user caches. |
| `bedSide` | thing | One sleeper (left / right / solo). Exposes sleep metrics, temperature, base, pillow, alarm and away-mode channels. |

- The bed side's `userId` config selects the sleeper; `label` selects the zone (`left`, `right`, `solo`). Solo beds read data from the left zone but send `"solo"` to away-mode calls.
- The user⇄bedSide relationship is strictly 1:1: a bed side has exactly one user, and a user owns at most one bed side. A second thing claiming an already-registered userId is rejected with `OFFLINE(CONFIGURATION_ERROR)`.
- Discovery (`BedSideDiscoveryService`) resolves `/users/me` + household summary + device users into `bedSide` suggestions. It sanitizes labels and never exposes credentials.
- Channel groups mirror the domain: current sleep metrics, last sleep session, temperature control, base control, pillow, alarms, device/hub status.

## 3. Package layout

| Package | Responsibility |
|---|---|
| `internal.handler` | Framework-facing handlers (`AccountHandler` bridge, `BedSideHandler` thing), LWW bookkeeping (`LastWriteWins`), command dispatch (`BedSideCommands`), config DTOs |
| `internal.api` | HTTP client (`ApiHttpClient`), endpoint orchestration (`EightSleepApiClient`), OAuth session (`TokenManager`), typed exceptions (`ApiException`), Gson helpers |
| `internal.api.model` | Gson response DTOs (`Alarm`, `DeviceUsers`, `PillowData`, `PlayerState`, …); mutable because Gson instantiates reflectively |
| `internal.polling` | `AccountPoller`: per-user poll fan-out (trends, player, alarms, temperature, pillow) and away-state poll |
| `internal.model` | Domain parsing/conversion: `TrendParser` (defensive JsonElement walking), `HeatingLevelConversion` (level ⇄ °C/°F), caches (`UserDataCache`), config parsing |
| `internal.alarm` | `AlarmSelector`: picks the actionable alarm from cached alarms |
| `internal.sleep` | `SleepSession`, `DataFreshness`: staleness thresholds derived from poll intervals |

## 4. Key design decisions

### 4.1 Session & authentication

- `TokenManager` keeps one OAuth token per account bridge, refreshing proactively 120 s before expiry.
- Token acquisition is **non-blocking**: `getAccessTokenAsync()` composes the refresh; concurrent callers share a single in-flight refresh. The blocking `getAccessToken()` remains only for off-scheduler callers.
- On HTTP 401 the client invalidates the token and retries **exactly once** (`withAuthRetry`). Subscription-gated endpoints are detected via structured flags on `ApiException` (`isUnauthorized()`, `isSubscriptionRequired()`).
- Reconnects build **new** `TokenManager`/`EightSleepApiClient` instances; the account poller is rebuilt whenever its bound client/device changes so an obsolete credential context is never reused.

### 4.2 Polling model

- All scheduling uses the framework-provided scheduler; no ad-hoc threads.
- `AccountHandler.startPolling` runs four fixed-delay jobs: device data, per-user data fan-out (`AccountPoller`), base data, away-state.
- Initial data comes from each job's zero-delay first run, keeping one code path for both startup and steady state rather than a separate synchronous first poll.
- A `lifecycleGeneration` counter invalidates in-flight work: callbacks capture the generation when scheduled and drop results/status updates if it changed (dispose or reconnect). This prevents stale publishes after disposal or a superseded session.
- Poll failures are expected conditions: DEBUG logs plus Thing status transitions (`OFFLINE(COMMUNICATION_ERROR)`), never warn/error spam.

### 4.3 Caching & staleness

- `AccountHandler` holds a `UserDataCache` per registered user, reference-counted by bed side things; the last unregister drops the cache.
- Freshness is explicit: `DataFreshness` derives staleness thresholds from the configured poll interval (4×). Bed sides go `OFFLINE(COMMUNICATION_ERROR)` on stale data instead of silently showing old values.

### 4.4 Command path & LWW reconciliation

All mutable channels — side power, alarms, temperature targets and away mode — resolve through one mechanism: `BedSideChannelSync.compute()` compares the cached polled observation against a pending command stamp via `LastWriteWins.resolveLatest`. Each observation carries a timestamp (polls stamp their *start*, commands their issue time); the more recent one wins, with ties going to the polled value. Caches hold raw observations; sync is the sole adjudicator.

- `BedSideCommands` is a static command library dispatching on channel id; every command returns immediately by composing futures (`apply(ctx, stage)` → refresh-on-success hook).
- Optimistic feedback: side power, alarms and away mode write a timestamped command stamp into the thing's `commanded` map before/as the request goes out, so the merge sees the command even if the HTTP round trip is still in flight.
- Server confirmations retire the corresponding stamps (`retireSidePowerCommand`, `retireAwayModeCommand`, `retireAlarmId`) so pending stamps do not accumulate.
- Away mode stays `UNDEF` until *this user* has spoken (polled or commanded) — the gate is derived from the cache entry and pending stamp, never global.

### 4.5 Channel sync as a pure function

`BedSideChannelSync.compute(...)` takes only immutable inputs (caches, config flags, command stamps, clock) and returns a `Result` (status action + channel updates + bookkeeping mutations). The handler applies it. Benefits:

- Unit-testable without framework objects (16 sync tests).
- Deterministic status decisions in one place: `BRIDGE_OFFLINE`, `USER_NOT_FOUND`, `STALE_DATA`, `ONLINE`, `NONE`.
- Heating-level quirks isolated (`resolveShownTargetLevel`, absent-target detection).

### 4.6 Error handling & degradation

- Structured JSON everywhere (Gson DTOs; `TrendParser` walks `JsonElement` defensively for heterogeneous trend shapes). No substring matching on bodies except the unavoidable 403 "subscription" classifier.
- Optional accessories degrade silently: speaker 404 → speaker-less state; missing pillow payload → no pillow channels; alarm 403 (no subscription) → empty list stamped fresh.
- Missing external values stay unknown: absent volume/pillow levels surface as `null`, never fabricated zeros.
- Unparseable device payloads keep `rawFieldNames` for diagnostics.

## 5. Testing strategy

| Layer | Tests | Approach |
|---|---|---|
| Request contract | `ControlOperationsTest`, `AuthRetryTest` | Scripted transports pin URL/method/body/clamping of every control operation |
| Parser contract | `EndpointContractTest` | Spec-first: upstream expectations evaluated against embedded samples **and** live captures (`tools/fixtures`, `-Deightsleep.fixtures=…`); fixture-derived invariants (alarm round-trips, chronological days, error-body degradation) |
| Upstream pitfalls | `RegressionTest` | Each test pins a documented pitfall of the Eight Sleep API (snake_case OAuth body, `result` envelope, bare-object alarm updates, integer-only levels) so parsers cannot drift from it |
| Logic tables | LWW, away ordering, alarm selection, heating conversion | Pure-function truth tables |
| Lifecycle/race | `PollRaceTest`, `AlarmSelectionRaceTest`, `AccountLwwLogicTest`, `AccountPollerIdentityTest` | Real handlers/logic classes without OSGi runtime |
| Audits | `FixtureAlignmentAuditTest`, `ChannelUidAuditTest`, `I18nConfigAuditTest` | Structural consistency (fixtures ↔ tests, channels ↔ XML, i18n keys) |

Fixtures are captured with `tools/capture_fixtures.py`; both modes must pass (embedded samples exercise all parser branches deterministically).

## 6. Limitations / open items

1. **No integration tests** (`itests/`) — lifecycle behavior is covered by unit seams only.
2. Hard-coded OAuth app credentials in `ApiConstants` (mirrors official clients; overridable via config) — worth revisiting for distribution.
3. Config-change handling rebuilds the whole connection rather than diffing (simple, correct, slightly wasteful).
4. `Alarm.rollToNextWeek` loops week-by-week — unbounded for corrupt far-past timestamps.

## 7. Resolved design decisions

- **Device properties stay as mirrored.** The account bridge publishes the whole household device map (`device.<id>` entries) into Thing properties. They are bounded in size, contain nothing sensitive, and multi-pod accounts rely on them when choosing a `deviceId` configuration - trimming would trade real utility for negligible surface.
- **LWW tie-break stays uniform (ties go to the polled value).** Command stamps are written *before* the request goes out, so an exact tie means the poll started at-or-after stamping time - at which point the server response reflects post-command truth. One rule across every mutable channel beats per-family exceptions.
- **Optional-endpoint failures do not affect Thing status.** Speaker/pillow/alarm degradation stays at DEBUG without status changes: upstream offers no reliable capability discovery (a 404 and an absent payload are indistinguishable from "not equipped"), and optional accessory health must never mask the primary channels' staleness-driven status.

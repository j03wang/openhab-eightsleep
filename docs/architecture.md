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
 └─► EightSleepService ◄───────────────────────── BedSideCommands
                        │
              EightSleepApiClient
                   │          │
          TokenManager     ApiJsonCodec
          (OAuth session)       │
                   └──── ApiHttpClient
                        (JDK HttpClient)
                        │
              Eight Sleep cloud APIs
```

Flows: AccountPoller polls via the service and writes observations into UserDataCache; BedSideChannelSync reads only caches and decides channel updates; BedSideHandler dispatches each incoming channel command to BedSideCommands, which issues mutations through the service (stamped for last-write-wins reconciliation by sync).

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
| `internal.handler` | Thin openHAB lifecycle adapters (`AccountHandler` bridge and `BedSideHandler` thing) |
| `internal.config` | Thing configuration DTOs, including normalization and polling bounds |
| `internal.command` | Stateful bed-side command dispatch, command execution context and pending command state |
| `internal.api` | Domain-facing orchestration and mapping (`EightSleepService`, `EightSleepApiMapper`), contract-only endpoint client (`EightSleepApiClient`), API scalar parsing, HTTP transport, OAuth session and typed exceptions |
| `internal.api.dto` | Request and response contracts matching the API's JSON payloads |
| `internal.polling` | Poll scheduling (`AccountPollingCoordinator`), per-user fan-out, mutable caches, immutable snapshots and freshness policy |
| `internal.sync` | Last-write-wins reconciliation and focused device, sleep, accessory and alarm channel projection |
| `internal.model` | Immutable mapped domain state, including typed bed sides and temporal values |
| `internal.temperature` | Heating-level and temperature conversion |
| `internal.alarm` | `AlarmSelector`: picks the actionable alarm from cached alarms |
| `internal.sleep` | `SleepSession`: derives sleep stage and bed presence from trend data |

## 4. Key design decisions

### 4.1 Session & authentication

- `TokenManager` keeps one OAuth token per account bridge, refreshing proactively 120 s before expiry.
- Token acquisition is **non-blocking**: `getAccessTokenAsync()` composes the refresh; concurrent callers share a single in-flight refresh. The blocking `getAccessToken()` remains only for off-scheduler callers.
- On HTTP 401 the client invalidates the token and retries **exactly once** (`withAuthRetry`). Subscription-gated endpoints are detected via structured flags on `ApiException` (`isUnauthorized()`, `isSubscriptionRequired()`).
- API infrastructure is instance-scoped: `ApiHttpClient` implements both API and authentication transport ports, while an injected `ApiJsonCodec` handles wire serialization for the client and token manager. No global HTTP client or JSON singleton is used.
- Reconnects build **new** `TokenManager`/`EightSleepService`/`EightSleepApiClient` instances; `AccountPollingCoordinator` replaces its poller and invalidates callbacks from the obsolete session.

### 4.2 Polling model

- All scheduling uses the framework-provided scheduler; no ad-hoc threads.
- `AccountPollingCoordinator` owns four fixed-delay jobs: device data, per-user data fan-out, base data and away state.
- Initial data comes from each job's zero-delay first run, keeping one code path for both startup and steady state rather than a separate synchronous first poll.
- A coordinator generation invalidates in-flight work: callbacks capture the generation when scheduled and drop results if it changed. This prevents stale publishes after disposal or a superseded session.
- Poll failures are expected conditions: DEBUG logs plus Thing status transitions (`OFFLINE(COMMUNICATION_ERROR)`), never warn/error spam.

### 4.3 Caching & staleness

- `AccountHandler` holds a `UserDataCache` per registered user, reference-counted by bed side things; the last unregister drops the cache.
- Consumers receive `UserDataSnapshot` values, so one synchronization or command operation never reads a changing cache piecemeal.
- Freshness is explicit: `DataFreshness` derives staleness thresholds from the configured poll interval (4×). Bed sides go `OFFLINE(COMMUNICATION_ERROR)` on stale data instead of silently showing old values.

### 4.4 Command path & LWW reconciliation

All mutable channels — side power, alarms, temperature targets and away mode — resolve through one mechanism: `BedSideChannelSync.compute()` compares the cached polled observation against a pending command stamp via `LastWriteWins.resolveLatest`. Each observation carries a timestamp (polls stamp their *start*, commands their issue time); the more recent one wins, with ties going to the polled value. Caches hold raw observations; sync is the sole adjudicator.

- `BedSideCommands` owns the single dispatch entry point and executes operations using a context of explicit values rather than depending on either handler. Its injected clock makes alarm selection deterministic.
- Optimistic feedback: side power, alarms and away mode write timestamped stamps into `CommandState` before/as the request goes out, so reconciliation sees the command even if the HTTP round trip is still in flight.
- Server confirmations retire the corresponding stamps (`retireSidePowerCommand`, `retireAwayModeCommand`, `retireAlarmId`) so pending stamps do not accumulate.
- Away mode stays `UNDEF` until *this user* has spoken (polled or commanded) — the gate is derived from the cache entry and pending stamp, never global.

### 4.5 Channel sync as a pure function

`BedSideChannelSync.compute(...)` coordinates focused device, sleep, accessory and alarm projectors over an immutable cache snapshot, then returns an immutable `SyncResult`. The handler applies it. Benefits:

- Unit-testable without framework objects.
- Deterministic status decisions in one place: `BRIDGE_OFFLINE`, `USER_NOT_FOUND`, `STALE_DATA`, `ONLINE`, `NONE`.
- Heating-level quirks stay private to the focused device projector; tests assert the resulting `SyncResult` rather than helper methods.

### 4.6 Composition and dependency injection

- `EightSleepHandlerFactory` is the production composition root. It creates the shared JSON codec, JDK HTTP transport, clock, command dispatcher and channel synchronizer, and supplies connection-scoped token managers, API clients and domain services to account handlers.
- `BedSideHandler` receives its clock, `BedSideCommands` and `BedSideChannelSync` collaborators from the handler factory.
- Discovery tests observe published `DiscoveryResult` values through a publisher seam; thing-ID sanitization and side normalization remain private implementation details.

### 4.7 Error handling & degradation

- The API client accepts request DTOs and returns response DTOs. The service owns validation, clamping and multi-call orchestration; `EightSleepApiMapper` converts DTOs into immutable domain records. No substring matching on bodies except the unavoidable 403 "subscription" classifier.
- Optional accessories degrade silently: speaker 404 → speaker-less state; missing pillow payload → no pillow channels; alarm 403 (no subscription) → empty list stamped fresh.
- Missing external values stay unknown: absent volume/pillow levels surface as `null`, never fabricated zeros.

## 5. Testing strategy

| Layer | Tests | Approach |
|---|---|---|
| Service contract | `EightSleepService*Test`, `AuthRetryTest` | Scripted transports pin orchestration, validation, URL/method/body, and retry behavior |
| DTO and mapping contract | `EndpointContractTest`, `EightSleepApiMapperTest`, `TrendMappingTest` | Embedded samples and live captures (`tools/fixtures`, `-Deightsleep.fixtures=…`) pin deserialization and domain mapping |
| Upstream pitfalls | `RegressionTest` | Each test pins a documented pitfall of the Eight Sleep API (snake_case OAuth body, `result` envelope, bare-object alarm updates, integer-only levels) so parsers cannot drift from it |
| Domain behavior | Model, alarm, sleep, command, polling, and sync package tests | Pure-function truth tables and immutable snapshot behavior |
| Lifecycle/race | `PollRaceTest`, `AlarmSelectorRegressionTest`, `AccountPollerTest`, `AccountHandlerTest` | Focused lifecycle seams without an OSGi runtime |
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

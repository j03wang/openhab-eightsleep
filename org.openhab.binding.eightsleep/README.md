# Eight Sleep Binding for openHAB 5

This binding integrates [Eight Sleep](https://www.eightsleep.com/) smart mattress covers (Pod series) with openHAB via the Eight Sleep cloud API.

It supports sleep tracking metrics (heart rate, respiratory rate, HRV, sleep stages and scores), bed temperature control, adjustable base control (angle and presets), the Pod 5 pillow, hub LED brightness, water/priming status and priming trigger, alarms, away mode, and bed presence.

> **Note:** Ensure neither side of your bed is in away mode when setting up the binding, otherwise users may not be reported correctly by the Eight Sleep cloud.

## Supported Things

| Thing     | Description                                                                 |
|-----------|-----------------------------------------------------------------------------|
| `account` | Bridge representing your Eight Sleep cloud account (e-mail + password).     |
| `bedSide` | One sleeper of a mattress cover: sleep data, temperature, base, pillow, etc. |

## Discovery

1. Add an **Eight Sleep Account** bridge and enter your credentials.
2. Once the bridge is online, start a scan (Inbox → +). The binding discovers one `bedSide` thing per sleeper reported for your device.
3. Approve the discovered things.

**Solo beds ("Both")**: if a single user controls the whole bed, discovery creates one thing with Side = **Both (solo)** — data is read from the left zone, which is the only zone populated for solo beds. You can also pick "Both (solo)" manually in the thing's Side configuration. Away-mode commands send the correct `solo` value and never modify your bed-side assignment.

Manual setup is also possible: create a `bedSide` thing under the account bridge and set the `userId` configuration parameter (the user id looks like `u_xxxxxxxx`). You can find it in the Eight Sleep app under your profile, or capture it from the binding's TRACE log during a scan.

## Bridge Configuration

| Parameter               | Default | Description                                                                 |
|-------------------------|---------|-----------------------------------------------------------------------------|
| `username`              | –       | E-mail address of your Eight Sleep account (required).                      |
| `password`              | –       | Password of your Eight Sleep account (required).                            |
| `clientId`              | –       | Optional OAuth client id override (advanced).                               |
| `clientSecret`          | –       | Optional OAuth client secret override (advanced).                           |
| `deviceRefreshInterval` | 60      | Polling interval in seconds for device data.                                |
| `userRefreshInterval`   | 30      | Polling interval in seconds for sleep data.                                 |
| `baseRefreshInterval`   | 60      | Polling interval in seconds for base data.                                  |
| `temperatureUnit`       | C       | Unit for plain-number temperature commands (`C` or `F`).                    |

## Channels

Channels are grouped on each `bedSide` thing:

### `current` — Current Sleep Session & Alarms

| Channel            | Type                | Description                                    |
|--------------------|---------------------|------------------------------------------------|
| `bedTemperature`   | `Number:Temperature`| Current bed surface temperature (read-only).   |
| `targetTemperature`| `Number:Temperature`| Target bed temperature. **Command it to heat/cool.** |
| `heartRate`        | `Number:Frequency`  | Current heart rate.                            |
| `respiratoryRate`  | `Number`            | Current respiratory rate (breaths/min).        |
| `sleepScore`       | `Number:Dimensionless` | Sleep score of the current/most recent session. |
| `qualityScore`     | `Number:Dimensionless` | Sleep quality score.                        |
| `routineScore`     | `Number:Dimensionless` | Sleep routine score.                        |
| `hrv`              | `Number`            | Heart rate variability.                        |
| `breathRate`       | `Number`            | Breathing rate from sleep quality data.        |
| `sleepStage`       | `String`            | `inProgress` or `complete`.                    |
| `sessionStart`     | `DateTime`          | Start of the current session.                  |
| `sessionEnd`       | `DateTime`          | End of the current session.                    |

### Alarms

All five channels represent **one selected alarm**. After you interact with any of them, that same alarm stays selected — so disabling it doesn't make the switch jump to another alarm. With no selection, the soonest-scheduled alarm is chosen; **disabled alarms included**, with the schedule computed locally rather than read from the server (whose field goes null when an alarm is disabled).

| Channel            | Type                | Description                                    |
|--------------------|---------------------|------------------------------------------------|
| `nextAlarm`        | `DateTime`          | When the selected alarm fires (computed locally; read-only). |
| `alarmEnabled`     | `Switch`            | Enable/disable the selected alarm.             |
| `alarmTime`        | `DateTime`          | Time of day of the selected alarm — command to reschedule.|
| `dismissAlarm`     | `Switch`            | Send ON to dismiss/stop the ringing or selected alarm. |
| `snoozeAlarm`      | `Switch`            | Send ON to snooze for 9 minutes.               |

To control a different alarm, change which one fires next in the Eight Sleep app — new/other alarms appear automatically on the next poll once they become the soonest.

### `lastSleep` — Last Completed Sleep

| Channel                | Type                   | Description                     |
|------------------------|------------------------|---------------------------------|
| `sleepScore`           | `Number:Dimensionless` | Sleep score of the last session.|
| `qualityScore`         | `Number:Dimensionless` | Quality score of the last session. |
| `routineScore`         | `Number:Dimensionless` | Routine score of the last session. |
| `fitnessScore`         | `Number:Dimensionless` | Fitness score of the last session. |
| `timeSlept`            | `Number:Time`          | Total sleep duration.           |
| `lightSleepDuration`   | `Number:Time`          | Light sleep duration.           |
| `deepSleepDuration`    | `Number:Time`          | Deep sleep duration.            |
| `remSleepDuration`     | `Number:Time`          | REM sleep duration.             |
| `awakeDuration`        | `Number:Time`          | Time spent awake.               |
| `tossesTurns`          | `Number`               | Tosses and turns count.         |
| `sessionStart`         | `DateTime`             | Session start.                  |
| `sessionEnd`           | `DateTime`             | Session end.                    |

### `device` — Device State, Away Mode & Priming

| Channel                | Type            | Description                                        |
|------------------------|-----------------|----------------------------------------------------|
| `heatingLevel`         | `Number`        | Raw API heating level (-100 to 100, read-only).    |
| `heatingState`         | `String`        | `heating`, `cooling` or `idle`.                    |
| `heatingRemainingTime` | `Number:Time`   | Remaining heating/cooling time.                    |
| `sidePower`            | `Switch`        | ON = side on (smart mode), OFF = side off.         |
| `ledBrightness`        | `Number:Dimensionless` | Hub LED brightness (0-100, commandable).    |
| `hasWater`             | `Switch`        | ON = water reservoir has water (OFF = refill).     |
| `needsPriming`         | `Switch`        | ON = device reports priming needed.                |
| `isPriming`            | `Switch`        | ON while a priming run is in progress.             |
| `lastPrime`            | `DateTime`      | Timestamp of the last completed priming.           |
| `awayMode`             | `Switch`        | ON while this user is in away mode. Command ON/OFF to start/end away mode. Live state derives from the account side assignment (away users are removed from their side slot); UNDEF only before the first poll after startup. |
| `primeTrigger`         | `Switch`        | Send ON to start pod priming (watch `isPriming`).  |
| `roomTemperature`      | `Number:Temperature` | Ambient room temperature.                    |

### `base` — Adjustable Base (only if a base is paired)

| Channel            | Type            | Description                                       |
|--------------------|-----------------|---------------------------------------------------|
| `preset`           | `String`        | Base preset: `sleep`, `relaxing` or `reading`.    |
| `headAngle`        | `Number:Angle`  | Head/torso angle (0-45°). Command to adjust.      |
| `feetAngle`        | `Number:Angle`  | Feet/leg angle (0-20°). Command to adjust.        |
| `snoreMitigation`  | `Switch`        | ON while snore mitigation is active (read-only).  |
| `bedPresence`      | `Switch`        | ON when someone is in the bed (read-only).        |

### `pillow` — Pod 5 Pillow (only if a pillow is reported for this side)

The Pod 5 pillow heats/cools independently of the pod.

| Channel                     | Type                 | Description                                     |
|-----------------------------|----------------------|-------------------------------------------------|
| `pillowPower`               | `Switch`             | ON = pillow on (smart mode), OFF = off.         |
| `pillowTargetTemperature`   | `Number:Temperature` | Target pillow temperature (commandable; auto-powers the pillow on first). |
| `pillowHeatingLevel`        | `Number`             | Raw API heating level (-100 to 100, read-only). |

## Temperature Control

The Eight Sleep API uses a unit-less level from `-100` (coldest) to `100` (warmest). This binding converts between that raw level and real temperatures using the same lookup tables as the official mobile app:

- Celsius range: 13 °C to 44 °C
- Fahrenheit range: 55 °F to 111 °F

Send a `Number:Temperature` quantity (e.g. `21 °C`) to a target-temperature channel and the binding converts automatically. A plain number is interpreted in the unit configured on the bridge (`temperatureUnit`).

## Example

```java
// things
Bridge eightsleep:account:myaccount "Eight Sleep" [ username="me@example.com", password="secret" ] {
    Thing bedSide left "My Side" [ userId="u_1234567890abcdef", label="left" ]
}

// items
Number:Temperature  Bed_Target_Temp  "Target Temperature"  { channel="eightsleep:bedSide:myaccount:left:current#targetTemperature" }
Number:Temperature  Bed_Temp         "Bed Temperature"     { channel="eightsleep:bedSide:myaccount:left:current#bedTemperature" }
Number              Heart_Rate       "Heart Rate"          { channel="eightsleep:bedSide:myaccount:left:current#heartRate" }
Number:Dimensionless Sleep_Score     "Sleep Score"         { channel="eightsleep:bedSide:myaccount:left:lastSleep#sleepScore" }
DateTime            Next_Alarm       "Next Alarm"          { channel="eightsleep:bedSide:myaccount:left:current#nextAlarm" }
Switch              Alarm_Enabled    "Alarm Enabled"       { channel="eightsleep:bedSide:myaccount:left:current#alarmEnabled" }
Switch              Dismiss_Alarm    "Dismiss Alarm"       { channel="eightsleep:bedSide:myaccount:left:current#dismissAlarm" }
Switch              Has_Water        "Has Water"           { channel="eightsleep:bedSide:myaccount:left:device#hasWater" }
Number              Led_Brightness   "LED Brightness"      { channel="eightsleep:bedSide:myaccount:left:device#ledBrightness" }
Switch              Bed_Presence     "Bed Presence"        { channel="eightsleep:bedSide:myaccount:left:base#bedPresence" }

// rules DSL example
rule "Pre-heat bed"
when
    Time cron "0 0 5 * * ?"
then
    Bed_Target_Temp.sendCommand(new QuantityType<>(31, SIUnits.CELSIUS))
end
```

## Not Yet Exposed

The following upstream features are implemented in the API layer but do not have channels yet:

- Creating brand-new one-off alarms (`POST /alarms` with vibration/thermal settings)
- Speaker media control (state is polled; play/pause/volume/track methods exist)
- Setting which bed side a user controls

## Troubleshooting

- Watch live logs with `log:tail` (or `journalctl -u openhab -f`) while scanning.
- Discovery failures are logged at WARN for the `org.openhab.binding.eightsleep` logger.
- If authentication fails, verify your credentials in the Eight Sleep mobile app first.
- The binding talks to `client-api.8slp.net`, `app-api.8slp.net` and `auth-api.8slp.net`. Ensure outbound HTTPS is allowed.
- Accounts without an active subscription get HTTP 403 from the alarms endpoint; the binding skips alarm data gracefully in that case.

## Building

Requires Java 21 and Maven:

```bash
mvn package
```

Copy the resulting bundle to your openHAB addons folder, e.g. `/usr/share/openhab/addons/` (openHABian), `/opt/openhab/addons/` (manual zip install) or `/openhab/addons/` (Docker). Remove any older version of the jar first.

## License

Eclipse Public License 2.0, consistent with openHAB add-ons.

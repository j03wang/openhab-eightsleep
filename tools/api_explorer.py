#!/usr/bin/env python3
"""Eight Sleep API explorer - validate the endpoints decompiled from app 7.52.27.

Standalone script, Python 3.10+, stdlib only.

Setup (one time):
    python3 api_explorer.py login me@example.com mypassword

Logs in with the app's OAuth credentials, stores access + refresh tokens in
.api-session.json (created next to this script; never committed - it holds
live bearer tokens). The refresh token is long-lived: subsequent runs reuse it
via the refresh_token grant exactly like the official app, so your password is
only needed once.

Then explore:
    python3 api_explorer.py whoami                 # auth response identity
    python3 api_explorer.py get household-summary  # curated endpoint
    python3 api_explorer.py probe                  # sweep every known GET
    python3 api_explorer.py call GET app-api /v1/household/users/me/summary
    python3 api_explorer.py refresh                # force a token refresh
"""
import argparse
import json
import os
import sys
import urllib.error
import urllib.request
from datetime import date, timedelta
from pathlib import Path

HERE = Path(__file__).resolve().parent
SESSION_FILE = HERE / ".api-session.json"

AUTH_URL = "https://auth-api.8slp.net/v1/tokens"
CLIENT_API = "https://client-api.8slp.net/v1"  # legacy host; probes will show what still answers
APP = "https://app-api.8slp.net/"
APP_V1 = APP + "v1"
APP_V2 = APP + "v2"
APP_V3 = APP + "v3"

KNOWN_CLIENT_ID = "0894c7f33bb94800a03f1f4df13a4f38"
KNOWN_CLIENT_SECRET = "f0954a3ed5763ba3d06834c73731a32f15f168f47d4f164751275def86db0c76"

USER_AGENT = "eightsleep-api-explorer"


# ---------------------------------------------------------------- session ---

def load_session() -> dict:
    if SESSION_FILE.exists():
        return json.loads(SESSION_FILE.read_text())
    return {}


def save_session(sess: dict) -> None:
    SESSION_FILE.write_text(json.dumps(sess, indent=2))
    try:
        SESSION_FILE.chmod(0o600)
    except OSError:
        pass


def request(method: str, url: str, token: str | None = None,
            body=None, headers: dict | None = None) -> tuple[int, str]:
    data = None
    hdrs = {"accept": "application/json", "user-agent": USER_AGENT}
    if body is not None:
        data = json.dumps(body).encode()
        hdrs["content-type"] = "application/json"
    if token:
        hdrs["authorization"] = f"Bearer {token}"
    for k, v in (headers or {}).items():
        hdrs[k] = v
    req = urllib.request.Request(url, data=data, headers=hdrs, method=method)
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            return resp.status, resp.read().decode(errors="replace")
    except urllib.error.HTTPError as err:
        return err.code, err.read().decode(errors="replace")


def tokens_post(grant_body: dict) -> dict:
    status, text = request("POST", AUTH_URL, body=grant_body)
    if status != 200:
        sys.exit(f"token request failed: HTTP {status}\n{text}")
    tok = json.loads(text)
    sess = load_session()
    sess.update({
        "access_token": tok["access_token"],
        "refresh_token": tok.get("refresh_token") or sess.get("refresh_token"),
        "userId": tok.get("userId") or tok.get("user_id"),
        "expires_in": tok.get("expires_in"),
    })
    save_session(sess)
    print(f"[ok] authenticated; userId={sess.get('userId')} "
          f"(refresh token {'present' if sess.get('refresh_token') else 'ABSENT'})")
    return sess


def require_session() -> dict:
    sess = load_session()
    if not sess.get("access_token"):
        sys.exit("No session. Run: python3 api_explorer.py login me@example.com mypassword")
    return sess


def authorized_call(method: str, url: str, body=None, headers: dict | None = None,
                    allow_refresh: bool = True) -> tuple[int, str]:
    """One authorized call; on 401 retry once through a token refresh."""
    sess = require_session()
    status, text = request(method, url, sess["access_token"], body, headers)
    if status == 401 and allow_refresh and sess.get("refresh_token"):
        print("[..] 401 - refreshing token and retrying once")
        do_refresh()
        sess = require_session()
        status, text = request(method, url, sess["access_token"], body, headers)
    return status, text


# ---------------------------------------------------------------- commands --

def cmd_login(args) -> None:
    tokens_post({
        "client_id": KNOWN_CLIENT_ID,
        "client_secret": KNOWN_CLIENT_SECRET,
        "grant_type": "password",
        "username": args.username,
        "password": args.password,
    })


def cmd_refresh(_args) -> None:
    sess = load_session()
    if not sess.get("refresh_token"):
        sys.exit("No refresh token stored. Run: python3 api_explorer.py login me@example.com mypassword")
    tokens_post({
        "client_id": KNOWN_CLIENT_ID,
        "client_secret": KNOWN_CLIENT_SECRET,
        "grant_type": "refresh_token",
        "refresh_token": sess["refresh_token"],
    })


def cmd_whoami(_args) -> None:
    sess = require_session()
    print(f"userId       : {sess.get('userId')}")
    print(f"expires_in   : {sess.get('expires_in')}")
    print(f"has refresh  : {bool(sess.get('refresh_token'))}")


def cmd_get(args) -> None:
    base = {"app-api": APP, "client-api": CLIENT_API}[args.host]
    status, text = authorized_call("GET", base + args.path.lstrip("/"))
    pretty(text, status)


def cmd_call(args) -> None:
    base = {"app-api": APP, "app-v1": APP_V1, "app-v2": APP_V2, "app-v3": APP_V3,
            "client-api": CLIENT_API, "auth": AUTH_URL.rsplit("/", 1)[0] + "/"}[args.host]
    body = json.loads(args.body) if args.body else None
    status, text = authorized_call(args.method.upper(), base + args.path.lstrip("/"), body)
    pretty(text, status)


def cmd_probe(_args) -> None:
    """Sweep every GET the decompiled app declares. Read-only by design."""
    sess = require_session()
    uid, did = sess.get("userId"), sess.get("deviceId")
    if not uid or not did:
        uid, did = resolve_ids(sess)
    today = date.today()
    start = today - timedelta(days=3)

    probes = [
        # ---- reads used by the binding today ----
        ("devices/{id}", f"{APP_V1}/devices/{did}"),
        ("users/{id}", f"{APP_V1}/users/{uid}"),
        ("household summary", f"{APP_V1}/household/users/{uid}/summary"),
        ("trends", f"{APP_V1}/users/{uid}/trends?tz=UTC&from={start}&to={today}"
                   "&include-main=false&include-all-sessions=true&model-version=v2&consistent-read=false"),
        ("alarms v2", f"{APP_V2}/users/{uid}/alarms"),
        ("temperature cover", f"{APP_V1}/users/{uid}/temperature/cover"),
        ("temperature all", f"{APP_V1}/users/{uid}/temperature/all"),
        ("base state", f"{APP_V1}/users/{uid}/base"),
        ("base presets v2", f"{APP_V2}/users/{uid}/base/presets"),
        ("audio player", f"{APP_V1}/users/{uid}/audio/player"),
        ("audio categories", f"{APP_V1}/audio/categories"),
        # ---- features added to the binding from the decompile ----
        ("nap-mode", f"{APP_V1}/users/{uid}/temperature/nap-mode"),
        ("nap-mode status", f"{APP_V1}/users/{uid}/temperature/nap-mode/status"),
        ("hot-flash-mode", f"{APP_V1}/users/{uid}/temperature/hot-flash-mode"),
        ("level-suggestions-mode", f"{APP_V1}/users/{uid}/level-suggestions-mode"),
        ("autopilotDetails", f"{APP_V1}/users/{uid}/autopilotDetails"),
        ("autopilot-history", f"{APP_V1}/users/{uid}/autopilot-history"),
        # ---- decompiled but unverified candidates ----
        ("metrics summary", f"{APP_V1}/users/{uid}/metrics/summary"
                            f"?from={start}&to={today}&tz=UTC&metrics=sleep_quality_score"),
        ("metrics aggregate v1", f"{APP_V1}/users/{uid}/metrics/aggregate?v2=true&to={today}&tz=UTC"),
        ("metrics aggregate v2", f"{APP_V2}/users/{uid}/metrics/aggregate"
                                 f"?align=from&fillEmpty=true&from={start}&to={today}&tz=UTC&bucket=day"),
        ("temp-events", f"{APP_V1}/users/{uid}/temp-events"),
        ("temp-events nightly", f"{APP_V1}/users/{uid}/temp-events/nightly"
                                f"?align=from&fillEmpty=true&from={start}&to={today}&bucket=day&tz=UTC"),
        ("bedtime", f"{APP_V1}/users/{uid}/bedtime"),
        ("bedtime recommendation", f"{APP_V1}/users/{uid}/bedtime/recommendation"),
        ("llm-insights", f"{APP_V1}/users/{uid}/llm-insights"),
        ("llm-insights settings", f"{APP_V1}/users/{uid}/llm-insights/settings"),
        ("perks", f"{APP_V1}/users/{uid}/perks"),
        ("days count", f"{APP_V1}/users/{uid}/days/count"),
        ("truth-tags", f"{APP_V1}/users/{uid}/truth-tags"),
        ("tags", f"{APP_V1}/users/{uid}/tags?from={start}&to={today}"),
        ("tags summary", f"{APP_V1}/users/{uid}/tags/summary?to={today}"),
        ("tap-history", f"{APP_V1}/users/{uid}/tap-history?from={start}"),
        ("app-state onboard", f"{APP_V1}/users/{uid}/app-state/onboard"),
        ("app-state messages", f"{APP_V1}/users/{uid}/app-state/messages"),
        ("notifications", f"{APP_V1}/users/{uid}/notifications?active=true"),
        ("invitations", f"{APP_V1}/household/users/{uid}/invitations"),
        ("travel trips", f"{APP_V1}/users/{uid}/travel/trips"),
        ("pregnancy mode", f"{APP_V1}/users/{uid}/pregnancy-mode"),
        ("health integrations meta", f"{APP_V1}/users/{uid}/health-integrations/metadata"),
        ("subscriptions v3", f"{APP_V3}/users/{uid}/subscriptions"),
        ("purchase tracker", f"{APP_V1}/purchase-tracker"),
        ("sms users", f"{APP_V1}/sms/users/{uid}"),
        ("device online", f"{APP_V1}/devices/{did}/online"),
        ("device peripherals", f"{APP_V1}/devices/{did}/peripherals"),
        ("priming schedule", f"{APP_V1}/devices/{did}/priming/schedule"),
        ("priming tasks", f"{APP_V1}/devices/{did}/priming/tasks"),
        ("maintenance insert", f"{APP_V1}/user/{uid}/device_maintenance/maintenance_insert?v=2"),
        ("recommendations blanket", f"{APP_V1}/users/{uid}/recommendations/blanket"),
        ("tap-settings", f"{APP_V1}/users/{uid}/devices/{did}/tap-settings"),
        # ---- LEGACY client-api shapes ----
        ("LEGACY users/me", f"{CLIENT_API}/users/me"),
        ("LEGACY devices/{id}", f"{CLIENT_API}/devices/{did}"),
        ("LEGACY awaySides filter", f"{CLIENT_API}/devices/{did}?filter=leftUserId,rightUserId,awaySides"),
        ("LEGACY temperature doc", f"{CLIENT_API}/users/{uid}/temperature"),
        ("LEGACY current-device", f"{CLIENT_API}/users/{uid}/current-device"),
        ("LEGACY away-mode PUT route (GET probe)", f"{CLIENT_API}/users/{uid}/away-mode"),
        ("LEGACY currentDevice on app host", f"{APP_V1}/users/{uid}/current-device"),
        # ---- ROUND 2: follow-ups on ambiguous results ----
        ("R2 client users/{id}", f"{CLIENT_API}/users/{uid}"),
        ("R2 client trends", f"{CLIENT_API}/users/{uid}/trends"
                             f"?tz=UTC&from={start}&to={today}"
                             "&include-main=false&include-all-sessions=true&model-version=v2"),
        ("R2 client trends (no model-version)", f"{CLIENT_API}/users/{uid}/trends"
                                                f"?tz=UTC&from={start}&to={today}&include-main=true"),
        ("R2 temp cover + ignoreErr", f"{APP_V1}/users/{uid}/temperature/cover?ignoreDeviceErrors=false"),
        ("R2 temp pillow + ignoreErr", f"{APP_V1}/users/{uid}/temperature/pillow?ignoreDeviceErrors=false"),
        ("R2 temp plain + ignoreErr", f"{APP_V1}/users/{uid}/temperature?ignoreDeviceErrors=false"),
        ("R2 client temp/all", f"{CLIENT_API}/users/{uid}/temperature/all"),
        ("R2 client base state", f"{CLIENT_API}/users/{uid}/base"),
        ("R2 client audio player", f"{CLIENT_API}/users/{uid}/audio/player"),
        ("R2 client nap-mode", f"{CLIENT_API}/users/{uid}/temperature/nap-mode"),
        ("R2 client hot-flash", f"{CLIENT_API}/users/{uid}/temperature/hot-flash-mode"),
        ("R2 client level-suggestions", f"{CLIENT_API}/users/{uid}/level-suggestions"),
        ("R2 client autopilotDetails", f"{CLIENT_API}/users/{uid}/autopilotDetails"),
        ("R2 client temp-events", f"{CLIENT_API}/users/{uid}/temp-events"),
        ("R2 client notifications", f"{CLIENT_API}/users/{uid}/notifications?active=true"),
        ("R2 client days count", f"{CLIENT_API}/users/{uid}/days/count"),
        ("R2 client truth-tags", f"{CLIENT_API}/users/{uid}/truth-tags"),
        ("R2 client perks", f"{CLIENT_API}/users/{uid}/perks"),
        ("R2 client alarms v1", f"{CLIENT_API}/users/{uid}/alarms"),
        ("R2 client intervals today", f"{CLIENT_API}/users/{uid}/intervals"),
        ("R2 metrics summary common", f"{APP_V1}/users/{uid}/metrics/summary"
                                      f"?from={start}&to={today}&tz=UTC"
                                      "&metrics=hrv,breathing_rate,heart_rate,sleep_quality_score"),
        ("R2 metrics aggregate minimal", f"{APP_V1}/users/{uid}/metrics/aggregate"
                                         f"?from={start}&to={today}&tz=UTC&bucket=day"
                                         "&metrics=hrv,heart_rate"),
        ("R2 bedtime recommendation alt", f"{APP_V1}/users/{uid}/bedtime/recommendations"),
    ]

    width = max(len(n) for n, _ in probes)
    results = []
    for name, url in probes:
        status, text = authorized_call("GET", url)
        tag = {200: "OK ", 404: "404", 401: "401", 403: "403", 400: "400"}.get(status, str(status))
        results.append((name, url, status, text))
        print(f"  [{tag}] {name:<{width}}  {url}")

    print("\n==== summary ====")
    ok = [r for r in results if r[2] == 200]
    dead = [r for r in results if r[2] == 404]
    other = [r for r in results if r[2] not in (200, 404)]
    print(f"200 OK : {len(ok):3d}  (valid)")
    print(f"404    : {len(dead):3d}  (route gone/never existed)")
    for n, _u, s, t in dead:
        print(f"         - {n}")
    print(f"other  : {len(other):3d}  (auth/subscription/validation)")
    for n, _u, s, t in other:
        snippet = t[:80].replace("\n", " ")
        print(f"         - {n}: HTTP {s} {snippet}")

    out = HERE / ".api-probe-results.json"
    out.write_text(json.dumps(
        [{"name": n, "url": u, "status": s, "body": safe_json(t)} for n, u, s, t in results], indent=2))
    print(f"\nfull responses saved to {out}")


def cmd_set_device(args) -> None:
    sess = load_session()
    sess["deviceId"] = args.device_id
    save_session(sess)
    print(f"deviceId pinned to {args.device_id}")


# ---------------------------------------------------------------- writes ---

def current_state() -> dict:
    """Fetch the live temperature/all pod entry + device doc for echo values."""
    sess = require_session()
    uid, did = sess.get("userId"), sess.get("deviceId")
    _, ta_text = authorized_call("GET", f"{APP_V1}/users/{uid}/temperature/all")
    ta = json.loads(ta_text)
    pods = [d for d in ta.get("devices") or []
            if (d.get("device") or {}).get("specialization") == "pod"]
    pod = pods[0] if pods else {}
    _, dev_text = authorized_call("GET", f"{CLIENT_API}/devices/{did}")
    dev = json.loads(dev_text)
    result = dev.get("result") if isinstance(dev, dict) else {}
    return {"uid": uid, "did": did, "pod": pod, "device": result or {},
            "alarms": _first_alarm()}


def _first_alarm() -> dict:
    sess = require_session()
    _, text = authorized_call("GET", f"{APP_V2}/users/{sess.get('userId')}/alarms")
    alarms = json.loads(text).get("alarms") or []
    return alarms[0] if alarms else {}


def report(name: str, status: int, text: str) -> None:
    tag = {200: "OK ", 201: "201"}.get(status, str(status))
    snippet = text[:160].replace("\n", " ")
    print(f"  [{tag}] {name}: {snippet}")


def w(method: str, url: str, body=None, headers: dict | None = None,
      expect: tuple = ()) -> int:
    """Issue a write, print outcome; raises SystemExit only on --abort."""
    status, text = authorized_call(method, url, body, headers, allow_refresh=True)
    report(url.split("?")[0].rsplit("/", 2)[-2] + "/" + url.rsplit("/", 1)[-1],
           status, text)
    if expect and status not in expect and os.environ.get("PROBE_STRICT"):
        sys.exit(f"unexpected {status}; PROBE_STRICT abort")
    return status


def cmd_probe_writes(args) -> None:
    """Echo-safe write validation. Every probe restores the value it found."""
    st = current_state()
    uid, did = st["uid"], st["did"]
    level = int(st["pod"].get("currentLevel") or 0)
    led = int((st["device"].get("ledBrightnessLevel") or 49))
    alarm = st["alarms"]

    print(f"== echo-safe writes for user={uid} device={did}")
    print(f"   (currentLevel={level}, led={led}, alarm={'yes' if alarm else 'none'})\n")

    # ---- T1/T2: temperature write shape duel on app-api ----
    t_url = f"{APP_V1}/users/{uid}/temperature/cover?ignoreDeviceErrors=false"
    print("[T1] app-api PUT temperature/cover OLD shape timeBased{level,durationSeconds}")
    w("PUT", t_url, {"timeBased": {"level": level, "durationSeconds": 0}})
    print("[T2] app-api PUT temperature/cover NEW shape currentState timeBased+until")
    w("PUT", t_url, {"currentState": {"type": "timeBased",
                                      "until": "1970-01-01T00:05:00Z"}})
    print("[T3] client-api PUT temperature OLD shape (binding's current behavior)")
    w("PUT", f"{CLIENT_API}/users/{uid}/temperature",
       {"timeBased": {"level": level, "durationSeconds": 0}})

    # ---- T4: restore off state exactly as found ----
    print("[T4] restore: currentState off (as found)")
    cs = st["pod"].get("currentState") or {}
    restore = {"type": cs.get("type", "off")}
    if cs.get("until"):
        restore["until"] = cs["until"]
    w("PUT", f"{APP_V1}/users/{uid}/temperature/cover?ignoreDeviceErrors=false",
       {"currentState": restore})

    # ---- S1: smart echo (write the same three stage levels back) ----
    smart = (st["pod"].get("smart") or {})
    if smart:
        print("[S1] app-api PUT smart echo")
        w("PUT", f"{APP_V1}/users/{uid}/temperature/cover?ignoreDeviceErrors=false",
          {"smart": smart})

    # ---- L1: LED brightness echo on app-api partial device update ----
    print(f"[L1] app-api PUT devices/{{id}} ledBrightnessLevel={led} (echo)")
    w("PUT", f"{APP_V1}/devices/{did}", {"ledBrightnessLevel": led})

    # ---- L2: LED brightness echo on legacy host ----
    print(f"[L2] client-api PUT devices/{{id}} ledBrightnessLevel={led} (echo)")
    w("PUT", f"{CLIENT_API}/devices/{did}", {"ledBrightnessLevel": led})

    # ---- P1/P2: priming task meta acceptance (no physical side effect beyond a
    # notification; tasks endpoint is read-only GET so POST meta is the question) ----
    print("[P1] app-api POST priming/tasks meta=fill_pod (decompiled enum)")
    w("POST", f"{APP_V1}/devices/{did}/priming/tasks",
      {"notifications": {"users": [uid], "meta": "fill_pod"}})
    print("[P2] app-api POST priming/tasks meta=rePriming (binding's current value)")
    w("POST", f"{APP_V1}/devices/{did}/priming/tasks",
      {"notifications": {"users": [uid], "meta": "rePriming"}})

    # ---- A1: alarm bare-object PUT with unchanged enabled value ----
    if alarm:
        print("[A1] app-api PUT alarm bare-object echo (enabled unchanged)")
        payload = {k: v for k, v in alarm.items()
                   if k not in ("nextTimestamp", "startTimestamp", "endTimestamp",
                                "dismissedUntil", "skippedUntil", "snoozedUntil")}
        w("PUT", f"{APP_V1}/users/{uid}/alarms/{alarm['id']}", payload)
    else:
        print("[A1] skipped - no alarm configured")

    # ---- A2: snooze contract check WITHOUT an active ring (expect 409) ----
    if alarm:
        print("[A2] app-api PUT alarm snooze while idle (expect 409)")
        w("PUT", f"{APP_V1}/users/{uid}/alarms/{alarm['id']}/snooze",
          {"snoozeMinutes": 1, "ignoreDeviceErrors": False})

    # ---- B1/B2: base angle POST (only meaningful with hardware; harmless otherwise) ----
    print("[B1] app-api POST base/angle SetBaseAngleRequest shape")
    w("POST", f"{APP_V1}/users/{uid}/base/angle?ignoreDeviceErrors=true",
      {"torsoAngle": 0, "legAngle": 0, "deviceId": did, "snoreMitigation": False})
    print("[B2] app-api POST base/angle SetBasePresetRequest shape (preset sleep)")
    w("POST", f"{APP_V1}/users/{uid}/base/angle?ignoreDeviceErrors=true",
      {"preset": "sleep", "deviceId": did, "snoreMitigation": False})

    # ---- C1: current-set PUT echo (same deviceId/side as summary assignment) ----
    assign_side = "solo"
    print(f"[C1] app-api PUT household current-set {{deviceId, side:{assign_side}}}")
    w("PUT", f"{APP_V1}/household/users/{uid}/current-set",
      {"deviceId": did, "side": assign_side})

    print("\nDone. Interpretation:")
    print("  200/2xx      -> contract accepted")
    print("  400 w/errors -> route exists; body wrong (capture 'errors' map)")
    print("  404          -> route gone on that host")
    print("\nNOTE: T1-T4/S1 briefly touched bed temperature settings (restored to off);")
    print("P1/P2 may fire a priming notification to your app.")


def cmd_devices(_args) -> None:
    """List every device on the account from the household summary."""
    sess = require_session()
    uid = sess.get("userId")
    if not uid:
        sys.exit("No userId in session - run login first.")
    status, text = authorized_call("GET", f"{APP_V1}/household/users/{uid}/summary")
    if status != 200:
        sys.exit(f"summary failed: HTTP {status}\n{text}")
    data = json.loads(text)
    found = []
    for house in data.get("households") or []:
        for hset in house.get("sets") or []:
            for dev in hset.get("devices") or []:
                found.append(dev)
                assign = dev.get("assignment") or {}
                sides = ",".join(k.replace("UserId", "") for k, v in assign.items() if v) or "-"
                print(f"deviceId      : {dev.get('deviceId')}")
                print(f"  name        : {dev.get('deviceName')}")
                print(f"  type        : {dev.get('specialization')}")
                print(f"  assigned    : {sides}")
    if not found:
        print("No devices reported for this account.")
        return
    first = found[0].get("deviceId")
    print(f"\npin one with: python3 api_explorer.py set-device {first}")


# ---------------------------------------------------------------- helpers ---

def resolve_ids(sess: dict) -> tuple[str, str]:
    """Best-effort id resolution for probing when none are pinned."""
    uid = sess.get("userId") or ""
    did = sess.get("deviceId") or ""
    if not uid or not did:
        status, text = authorized_call("GET", f"{APP_V1}/users/{uid}/insights?date={date.today()}")
    return uid, did


def safe_json(text: str):
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        return text[:500]


def pretty(text: str, status: int) -> None:
    print(f"HTTP {status}")
    try:
        print(json.dumps(json.loads(text), indent=2))
    except json.JSONDecodeError:
        print(text)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("login", help="initial password login; stores tokens")
    p.add_argument("username")
    p.add_argument("password")

    sub.add_parser("refresh", help="force a refresh_token grant now")
    sub.add_parser("whoami", help="show cached session identity")

    p = sub.add_parser("get", help="GET an endpoint: <host> <path>")
    p.add_argument("host", choices=["app-api", "client-api"])
    p.add_argument("path", help="path after the version root, e.g. /users/me")

    p = sub.add_parser("call", help="arbitrary method: <method> <host> <path> [json-body]")
    p.add_argument("method", choices=["GET", "POST", "PUT", "PATCH", "DELETE"])
    p.add_argument("host", choices=["app-api", "app-v1", "app-v2", "app-v3", "client-api", "auth"])
    p.add_argument("path")
    p.add_argument("body", nargs="?", help="JSON request body")

    sub.add_parser("probe", help="sweep all known GETs and report validity")
    sub.add_parser("devices", help="list account devices from the household summary")
    sub.add_parser("probe-writes",
                   help="echo-safe write validation (briefly touches bed settings)")
    p = sub.add_parser("set-device", help="pin deviceId used by probe paths")
    p.add_argument("device_id")

    args = ap.parse_args()
    {"login": lambda a: cmd_login(a),
     "refresh": cmd_refresh,
     "whoami": cmd_whoami,
     "get": cmd_get,
     "call": cmd_call,
     "probe": cmd_probe,
     "devices": cmd_devices,
     "probe-writes": cmd_probe_writes,
     "set-device": cmd_set_device}[args.cmd](args)
    return 0


if __name__ == "__main__":
    sys.exit(main())

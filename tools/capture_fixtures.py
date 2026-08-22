#!/usr/bin/env python3
"""Capture real Eight Sleep API payloads for the openHAB binding's contract tests.

Standalone script - no dependencies beyond Python 3.9+.

Usage:
    python3 capture_fixtures.py me@example.com mypassword [-o fixtures]

It logs in with your credentials, calls every endpoint the binding consumes, and
writes one pretty-printed JSON file per endpoint into the output directory:

    auth-tokens.json      (response shape only; token redacted)
    users-me.json
    household-summary.json
    device-data.json
    device-users.json
    trends-days.json
    temperature.json
    temperature-all.json  (pod + pillow)
    base-data.json
    alarms-v2.json
    player-state.json

Then point the contract tests at the directory:
    mvn test -Deightsleep.fixtures=fixtures
(or copy the files into src/test/resources/).

Notes:
- Passwords are never written to disk; the auth response is saved with all
  token fields replaced by "<redacted>".
- Endpoints that don't apply to your hardware (pillow, base, speaker) are saved
  as empty captures so the tests know the shape was absent.
"""
import argparse
import json
import os
import sys
import urllib.request
import urllib.error

CLIENT_API = "https://client-api.8slp.net/v1"
APP_API = "https://app-api.8slp.net/v1"
# the alarms API lives under /v2, not /v1
APP_API_V2 = "https://app-api.8slp.net/v2"
AUTH_URL = "https://auth-api.8slp.net/v1/tokens"

KNOWN_CLIENT_ID = "0894c7f33bb94800a03f1f4df13a4f38"
KNOWN_CLIENT_SECRET = "f0954a3ed5763ba3d06834c73731a32f15f168f47d4f164751275def86db0c76"

TOKEN_FIELDS = {"access_token", "refresh_token"}


def http(method: str, url: str, token: str | None = None, body: dict | None = None) -> tuple[int, str]:
    data = None
    headers = {"accept": "application/json", "user-agent": "eightsleep-fixture-capture"}
    if body is not None:
        data = json.dumps(body).encode()
        headers["content-type"] = "application/json"
    if token:
        headers["authorization"] = f"Bearer {token}"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            return resp.status, resp.read().decode()
    except urllib.error.HTTPError as err:
        return err.code, err.read().decode(errors="replace")


def save(out_dir: str, name: str, status: int, body_text: str, redact: bool = False) -> bool:
    try:
        parsed = json.loads(body_text)
    except json.JSONDecodeError:
        # keep the raw text so the failure mode itself is inspectable
        path = os.path.join(out_dir, f"{name}.HTTP{status}.txt")
        with open(path, "w", encoding="utf-8") as fh:
            fh.write(body_text)
        print(f"  [!!]   {name}: non-JSON ({status}) -> {path}")
        return False
    if redact:
        parsed = redact_tokens(parsed)

    # IMPORTANT: save the response EXACTLY as received - the contract tests must
    # exercise the same envelope-unwrapping the production code performs.
    path = os.path.join(out_dir, f"{name}.json")
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(parsed, fh, indent=2)
        fh.write("\n")
    print(f"  [ok]   {name}.json ({status})")
    return True


def redact_tokens(obj):
    if isinstance(obj, dict):
        return {
            k: ("<redacted>" if k in TOKEN_FIELDS else redact_tokens(v))
            for k, v in obj.items()
        }
    if isinstance(obj, list):
        return [redact_tokens(v) for v in obj]
    return obj


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("username", help="Eight Sleep account e-mail")
    ap.add_argument("password", help="Eight Sleep account password")
    ap.add_argument("-o", "--out", default="fixtures", help="output dir (default ./fixtures)")
    args = ap.parse_args()

    os.makedirs(args.out, exist_ok=True)

    # ---- auth ----
    print("Authenticating...")
    status, body = http("POST", AUTH_URL, body={
        "client_id": KNOWN_CLIENT_ID,
        "client_secret": KNOWN_CLIENT_SECRET,
        "grant_type": "password",
        "username": args.username,
        "password": args.password,
    })
    if status != 200:
        print(f"Authentication failed: HTTP {status}\n{body}")
        return 1
    save(args.out, "auth-tokens", status, body, redact=True)
    token = json.loads(body)["access_token"]
    print("Authenticated.\n")

    def get(name: str, url: str) -> dict | list | None:
        status, body = http("GET", url, token)
        ok = save(args.out, name, status, body)
        return json.loads(body) if ok else None

    # ---- resolve user + device ----
    me = get("users-me", f"{CLIENT_API}/users/me")
    if not me:
        return 1
    user_id = me["user"]["userId"]
    print(f"  user id: {user_id}")

    summary = get("household-summary",
                  f"{APP_API}/household/users/{user_id}/summary") or {}
    devices = {}
    for house_set in (summary.get("households") or [{}])[0].get("sets", []):
        for dev in house_set.get("devices", []):
            if dev.get("deviceId"):
                devices[dev["deviceId"]] = dev.get("deviceName", "?")
    if not devices:
        print("No devices found; cannot continue.")
        return 1
    device_id = next(iter(devices))
    print(f"  device: {devices[device_id]} ({device_id})\n")

    # ---- device ----
    status, body = http("GET", f"{CLIENT_API}/devices/{device_id}", token)
    save(args.out, "device-data", status, body)

    status, body = http(
        "GET",
        f"{CLIENT_API}/devices/{device_id}?filter=leftUserId,rightUserId,awaySides",
        token,
    )
    save(args.out, "device-users", status, body)

    away_sides = {}
    try:
        result = json.loads(body).get("result", {})
        away_sides = result.get("awaySides", {}) or {}
    except json.JSONDecodeError:
        pass

    # ---- per-user data (all users incl. away ones) ----
    user_ids = set()
    try:
        result = json.loads(open(os.path.join(args.out, "device-users.json")).read())
        user_ids.update({result.get("leftUserId"), result.get("rightUserId")} - {None})
        user_ids.update(v for v in away_sides.values() if v)
    except (json.JSONDecodeError, OSError):
        pass
    if not user_ids:
        user_ids = {user_id}

    first_user = True
    for uid in sorted(user_ids):
        suffix = "" if first_user else f"-{uid[-6:]}"
        first_user = False
        print(f"user {uid}:")
        import datetime
        today = datetime.date.today()
        start = today - datetime.timedelta(days=3)
        get(f"trends-days{suffix}",
            f"{CLIENT_API}/users/{uid}/trends?tz=UTC&from={start}&to={today}"
            "&include-main=false&include-all-sessions=true&model-version=v2")

        temp = get(f"temperature{suffix}", f"{APP_API}/users/{uid}/temperature")
        get(f"temperature-all{suffix}", f"{APP_API}/users/{uid}/temperature/all")
        base = get(f"base-data{suffix}", f"{APP_API}/users/{uid}/base")
        alarms = get(f"alarms-v2{suffix}", f"{APP_API_V2}/users/{uid}/alarms")
        player = get(f"player-state{suffix}", f"{APP_API}/users/{uid}/audio/player")

        # surface what the raw payloads look like for quick eyeballing
        if isinstance(temp, dict) and "smart" in temp:
            print(f"    smart schedule keys: {sorted(temp['smart'].keys())}")
        if isinstance(base, dict) and base:
            side = base.get("left") or base.get("right") or {}
            if side:
                print(f"    base side keys: {sorted(side.keys())}")

    print(f"\nDone. Fixtures written to {args.out}/")
    print("Validate the parsers against them:")
    print(f"  mvn test -Deightsleep.fixtures={args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

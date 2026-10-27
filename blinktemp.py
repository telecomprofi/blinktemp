#!/usr/bin/env python3
"""
Blink Outdoor camera temperature reader
Credentials are stored securely in the system keychain / credential store.
"""

import asyncio
import argparse
import json
import sys
from aiohttp import ClientSession
from blinkpy.blinkpy import Blink
from blinkpy.auth import Auth, BlinkTwoFARequiredError

try:
    import keyring
except ImportError:
    print("Please install keyring:  pip install keyring", file=sys.stderr)
    raise SystemExit(1)


# ============================================================
# CONFIGURATION
# ============================================================
DEFAULT_CAMERAS = None
KEYRING_SERVICE = "blink-temperature"
KEYRING_USERNAME = "blink_auth"
# ============================================================


def load_credentials_from_keyring():
    """Load the full auth dictionary from the system keyring."""
    raw = keyring.get_password(KEYRING_SERVICE, KEYRING_USERNAME)
    if not raw:
        return None
    try:
        data = json.loads(raw)
        if not data.get("refresh_token") or not data.get("hardware_id"):
            return None
        return data
    except json.JSONDecodeError:
        return None


def save_credentials_to_keyring(auth):
    """Save the COMPLETE login state (tokens + hardware_id + region etc.)."""
    payload = json.dumps(auth.login_attributes)
    keyring.set_password(KEYRING_SERVICE, KEYRING_USERNAME, payload)


def delete_credentials_from_keyring():
    try:
        keyring.delete_password(KEYRING_SERVICE, KEYRING_USERNAME)
    except Exception:
        pass


async def get_blink(reauth: bool = False, quiet: bool = False):
    """Login (or load from keyring) and return a ready Blink instance."""
    session = ClientSession()
    blink = Blink(session=session)

    if reauth:
        delete_credentials_from_keyring()
        if not quiet:
            print("Forcing re-authentication…", file=sys.stderr)

    saved = None if reauth else load_credentials_from_keyring()

    if saved:
        if not quiet:
            print("Loaded credentials from system keyring", file=sys.stderr)
        auth = Auth(saved, no_prompt=True)
        blink.auth = auth

    try:
        await blink.start()
    except BlinkTwoFARequiredError:
        if not quiet:
            print("2FA required. Enter the PIN sent to your email:", file=sys.stderr)
        await blink.prompt_2fa()

    try:
        save_credentials_to_keyring(blink.auth)
        if not quiet:
            print("Credentials saved securely to system keyring", file=sys.stderr)
    except Exception as e:
        if not quiet:
            print(f"Warning: could not save credentials to keyring: {e}", file=sys.stderr)

    await blink.refresh()
    return blink, session


def get_temperatures(blink, camera_filter=None):
    """Return dict of {camera_name: temperature_f} for cameras with valid readings."""
    temps = {}
    for name, camera in blink.cameras.items():
        if camera_filter and name not in camera_filter:
            continue
        temp = camera.temperature_calibrated or camera.temperature
        if temp is not None:
            temps[name] = float(temp)
    return temps


async def main():
    parser = argparse.ArgumentParser(description="Blink camera temperature tool")
    parser.add_argument("--avg", action="store_true", help="Show average temperature")
    parser.add_argument(
        "--cameras", type=str, default=None,
        help='Comma-separated camera names, e.g. "Garden Back,GardenBack2"'
    )
    parser.add_argument(
        "--prompt", action="store_true",
        help="Print single clean value for zsh prompt (no extra text)"
    )
    parser.add_argument(
        "--celsius", action="store_true",
        help="Show temperature in Celsius"
    )
    parser.add_argument(
        "--reauth", action="store_true",
        help="Force new login and re-save credentials to keyring"
    )
    args = parser.parse_args()

    if args.cameras:
        camera_filter = [c.strip() for c in args.cameras.split(",") if c.strip()]
    else:
        camera_filter = DEFAULT_CAMERAS

    blink, session = await get_blink(
        reauth=args.reauth,
        quiet=args.prompt          # silent when used for the prompt
    )

    try:
        temps = get_temperatures(blink, camera_filter)

        if not temps:
            if args.prompt:
                print("N/A")
            else:
                print("No temperature data found from the selected cameras.")
            return

        if args.celsius:
            display_temps = {k: round((v - 32) * 5 / 9, 1) for k, v in temps.items()}
            unit = "°C"
        else:
            display_temps = temps
            unit = "°F"

        if args.avg:
            avg = round(sum(display_temps.values()) / len(display_temps), 1)
            if args.prompt:
                print(f"{avg}{unit}")
            else:
                print(f"Average temperature ({len(display_temps)} cameras): {avg}{unit}")
                print("\nCameras used:")
                for name, t in display_temps.items():
                    print(f"  • {name}: {t}{unit}")
        else:
            if args.prompt:
                first = next(iter(display_temps.values()))
                print(f"{first}{unit}")
            else:
                print("=== Blink Camera Temperatures ===\n")
                for name, t in display_temps.items():
                    print(f"{name}: {t}{unit}")
    finally:
        await session.close()


if __name__ == "__main__":
    asyncio.run(main())

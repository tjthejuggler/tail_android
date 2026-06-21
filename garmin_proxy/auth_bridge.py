"""
Garmin Authentication Bridge (token-based)

Performs a SINGLE login to Garmin Connect and persists the resulting OAuth
tokens to a local token store (default: ~/.garminconnect). Every subsequent
data fetch resumes from these tokens WITHOUT logging in again.

Why this design:
    Garmin rate-limits *logins*, not data reads. The previous approach
    re-authenticated on every metric fetch (dozens of logins per run), which is
    what triggered the 429 "Too Many Requests" errors. Browser automation
    (Playwright) made this worse by hitting the human-facing web app, which
    serves an interactive Cloudflare/"verify you are human" CAPTCHA.

    The installed `garminconnect` here is a customized fork. Its
    Garmin.login(tokenstore) does everything in one call:
      - if the tokenstore already holds valid tokens, it loads them (and
        proactively refreshes the DI token without hitting SSO);
      - otherwise it runs a 5-strategy credential login (mobile/widget/portal,
        with TLS-fingerprint rotation + Cloudflare-aware backoff) and then
        auto-dumps the fresh tokens back to the tokenstore path.
    So we never call dump() ourselves - we just pass the path.

    If your IP has been rate limited by recent over-fetching, EVERY login
    strategy returns 429 immediately and login() raises
    GarminConnectTooManyRequestsError. The only cure is to WAIT for the IP
    cooldown - not to retry harder.

Run this ONCE (or only when fetch_data.py reports the tokens have expired):

    . venv/bin/activate && python3 auth_bridge.py

If your account has Multi-Factor Authentication (MFA/2FA) enabled, you will be
prompted to enter the emailed/app code in the terminal.
"""

import os
import sys
import time
import logging
from pathlib import Path

from garminconnect import Garmin, GarminConnectTooManyRequestsError

# Load environment variables from .env file if present
env_path = Path(__file__).parent / ".env"
if env_path.exists():
    from dotenv import load_dotenv
    load_dotenv(env_path)

logging.basicConfig(level=logging.INFO, format="%(asctime)s - %(levelname)s - %(message)s")
logger = logging.getLogger(__name__)

# Where the OAuth tokens live. The garminconnect fork loads/dumps here.
TOKEN_STORE = os.getenv("GARMINTOKENS", os.path.expanduser("~/.garminconnect"))

# At most ONE retry on an IP rate-limit, after a long, patient wait.
# Never tighten this into a loop - aggressive retries are what flag the IP.
RATE_LIMIT_BACKOFF_SECONDS = int(os.getenv("GARMIN_AUTH_BACKOFF", "300"))  # 5 min
MAX_RATE_LIMIT_RETRIES = 1


def _mfa_prompt() -> str:
    """Prompt the user for an MFA/2FA code when Garmin requires one."""
    return input("Garmin MFA code (check your email/authenticator app): ").strip()


def _do_login(email: str, password: str) -> None:
    """
    One call that loads existing tokens OR logs in and auto-persists them.

    Passing prompt_mfa lets the fork handle 2FA inline and still auto-dump the
    tokens to TOKEN_STORE on success. Raises on failure.
    """
    Path(TOKEN_STORE).mkdir(parents=True, exist_ok=True)

    client = Garmin(email=email, password=password, prompt_mfa=_mfa_prompt)
    # tokenstore path -> load-if-present, else credential login + auto-dump.
    client.login(TOKEN_STORE)

    full_name = client.get_full_name()
    logger.info(f"Authenticated as: {full_name}")
    logger.info(f"OAuth tokens are stored in: {TOKEN_STORE}")


def generate_garmin_session(email: str, password: str) -> bool:
    """
    Ensure a valid Garmin token store exists.

    On an IP rate-limit (429), wait once and retry a single time, then give up
    with actionable guidance.
    """
    logger.info("Ensuring a valid Garmin session (token-based, single login)...")

    attempt = 0
    while True:
        try:
            _do_login(email, password)
            logger.info("Done. You can now run: python3 fetch_data.py")
            return True
        except GarminConnectTooManyRequestsError as e:
            if attempt >= MAX_RATE_LIMIT_RETRIES:
                logger.error(
                    "Garmin is rate-limiting this IP (429) on every login strategy."
                )
                logger.error(
                    "This is a temporary IP-level block from recent over-fetching, "
                    "NOT a code/credential problem."
                )
                logger.error(
                    "Wait ~30-60 minutes (or switch networks / use a VPN) and run "
                    "this script again. Do not loop it - that extends the block."
                )
                raise
            attempt += 1
            logger.warning(f"IP rate limited (429): {e}")
            logger.warning(
                f"Waiting {RATE_LIMIT_BACKOFF_SECONDS}s before a single retry "
                f"({attempt}/{MAX_RATE_LIMIT_RETRIES})..."
            )
            time.sleep(RATE_LIMIT_BACKOFF_SECONDS)


if __name__ == "__main__":
    email = os.getenv("GARMIN_EMAIL")
    password = os.getenv("GARMIN_PASSWORD")

    if not email or not password:
        logger.error("GARMIN_EMAIL and GARMIN_PASSWORD must be set (env vars or .env file)")
        logger.error(f"Looked for .env at: {env_path} (exists: {env_path.exists()})")
        sys.exit(1)

    try:
        generate_garmin_session(email, password)
    except GarminConnectTooManyRequestsError:
        sys.exit(3)  # distinct code: IP rate limited, retry later
    except Exception as e:
        logger.error(f"Authentication failed: {e}")
        sys.exit(1)

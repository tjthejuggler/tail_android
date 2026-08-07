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
import shutil
import logging
import subprocess
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

# The legacy garmin_fetcher.py logs in on every metric fetch. If it (or its
# systemd wrapper garmin-fetcher.service) is still running, it will keep the IP
# rate-limited and this auth bridge will never succeed. Detect and block.
ROGUE_PROCESS_PATTERNS = ["garmin_fetcher.py"]
ROGUE_SERVICES = ["garmin-fetcher.service"]


def _check_for_rogue_processes() -> list[str]:
    """
    Detect legacy fetcher processes/services that login on every run.

    Returns a list of human-readable warnings. If the list is non-empty, the
    caller should abort — logging in while a rogue process hammers Garmin will
    only extend the IP rate-limit block.
    """
    warnings: list[str] = []

    # Check for stray Python processes running the old script
    try:
        result = subprocess.run(
            ["pgrep", "-af", "|".join(ROGUE_PROCESS_PATTERNS)],
            capture_output=True, text=True, timeout=5,
        )
        if result.returncode == 0 and result.stdout.strip():
            for line in result.stdout.strip().splitlines():
                if "pgrep" not in line and line.strip():
                    warnings.append(f"Rogue process detected: {line.strip()}")
    except Exception:
        pass  # pgrep not available or other issue — non-fatal

    # Check for the old systemd service
    for svc in ROGUE_SERVICES:
        try:
            result = subprocess.run(
                ["systemctl", "--user", "is-active", svc],
                capture_output=True, text=True, timeout=5,
            )
            if result.returncode == 0 and result.stdout.strip() == "active":
                warnings.append(
                    f"Rogue systemd service '{svc}' is still active. "
                    f"Stop it with: systemctl --user stop {svc}"
                )
        except Exception:
            pass

    return warnings


def _backup_token_store() -> Path | None:
    """Copy the current token store to a timestamped backup. Returns the path."""
    token_path = Path(TOKEN_STORE)
    # The garth fork stores tokens as garmin_tokens.json inside the dir.
    token_file = token_path / "garmin_tokens.json"
    if not token_file.exists():
        return None
    backup = token_path / f"garmin_tokens.json.backup.{int(time.time())}"
    try:
        shutil.copy2(token_file, backup)
        logger.debug(f"Token store backed up to {backup}")
        return backup
    except Exception as e:
        logger.warning(f"Could not back up token store: {e}")
        return None


def _restore_token_store(backup: Path | None) -> None:
    """Restore the token store from a backup (used when login fails)."""
    if backup is None or not backup.exists():
        return
    token_path = Path(TOKEN_STORE)
    token_file = token_path / "garmin_tokens.json"
    try:
        shutil.copy2(backup, token_file)
        logger.info(f"Token store restored from backup (login did not succeed).")
    except Exception as e:
        logger.warning(f"Could not restore token store from backup: {e}")


def _mfa_prompt() -> str:
    """Prompt the user for an MFA/2FA code when Garmin requires one."""
    return input("Garmin MFA code (check your email/authenticator app): ").strip()


def _do_login(email: str, password: str) -> None:
    """
    One call that loads existing tokens OR logs in and auto-persists them.

    Passing prompt_mfa lets the fork handle 2FA inline and still auto-dump the
    tokens to TOKEN_STORE on success. Raises on failure.

    Safety: the token store is backed up before login and restored if login
    fails, so a 429/403 during re-auth never destroys the last-known-good
    tokens.
    """
    Path(TOKEN_STORE).mkdir(parents=True, exist_ok=True)

    # Back up existing tokens so a failed login doesn't leave us with nothing.
    backup = _backup_token_store()

    client = Garmin(email=email, password=password, prompt_mfa=_mfa_prompt)
    try:
        # tokenstore path -> load-if-present, else credential login + auto-dump.
        client.login(TOKEN_STORE)
    except Exception:
        # Login failed (429, 403, network, etc.). Restore the backup so the
        # next attempt starts from the same state rather than empty/corrupted.
        _restore_token_store(backup)
        raise

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

    # Pre-flight: refuse to proceed if the legacy per-run-login fetcher is
    # still active — it will keep the IP blocked and this auth will never work.
    rogue_warnings = _check_for_rogue_processes()
    if rogue_warnings:
        logger.error("=" * 60)
        logger.error("ABORTING: A legacy Garmin fetcher process/service is still")
        logger.error("running. It logs in on every metric fetch, which is what")
        logger.error("causes the 429 IP rate-limit that blocks this auth bridge.")
        logger.error("=" * 60)
        for w in rogue_warnings:
            logger.error(f"  • {w}")
        logger.error("")
        logger.error("Fix: stop the rogue process/service first, then re-run this")
        logger.error("script. Commands:")
        logger.error("  systemctl --user stop garmin-fetcher.service")
        logger.error("  systemctl --user disable garmin-fetcher.service")
        logger.error("  pkill -f garmin_fetcher.py")
        logger.error("=" * 60)
        raise SystemExit(4)  # distinct code: rogue process detected

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

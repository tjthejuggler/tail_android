#!/usr/bin/env python3
"""
Tail Supervisor — Single-entry-point process manager for all PC↔Phone services.

Reads service definitions from tail_services.toml and manages them:
  - Daemons:   started as long-running processes, auto-restarted on crash
  - Periodic:  run on interval or daily schedule, then exit

Only this script (via tail-supervisor.service) needs to be in autostart.
Adding a new service = add a [[service]] block to tail_services.toml and restart.

Usage:
  python3 tail_supervisor.py             # run in foreground
  python3 tail_supervisor.py --status    # show managed services
  python3 tail_supervisor.py --check     # one-shot health check, then exit
"""

from __future__ import annotations

import os
import sys
import signal
import time
import shlex
import subprocess
import threading
import tomllib
from datetime import datetime, timedelta
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent
CONFIG_FILE = PROJECT_ROOT / "tail_services.toml"
LOG_FILE = PROJECT_ROOT / "supervisor.log"
POLL_INTERVAL = 5  # seconds between management loop iterations


# ── Logging ──────────────────────────────────────────────────────────────────

_lock = threading.Lock()


def log(msg: str) -> None:
    ts = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    line = f"[{ts}] {msg}"
    with _lock:
        print(line, flush=True)
        try:
            with open(LOG_FILE, "a") as f:
                f.write(line + "\n")
        except Exception:
            pass


# ── .env loader ──────────────────────────────────────────────────────────────

def load_env_file(path: Path) -> dict[str, str]:
    """Parse a simple KEY=VALUE .env file, ignoring comments and blanks."""
    env: dict[str, str] = {}
    if not path.exists():
        return env
    with open(path) as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            if "=" in line:
                key, val = line.split("=", 1)
                env[key.strip()] = val.strip()
    return env


# ── Service ──────────────────────────────────────────────────────────────────

class Service:
    """Represents one managed service (daemon or periodic task)."""

    def __init__(self, cfg: dict) -> None:
        self.name: str = cfg["name"]
        self.type: str = cfg["type"]  # "daemon" | "periodic"
        self.dir: Path = PROJECT_ROOT / cfg.get("dir", ".")
        self.cmd: str = cfg["cmd"]
        self.env_file: str | None = cfg.get("env_file")
        self.restart_sec: int = cfg.get("restart_sec", 10)
        self.interval_min: float | None = cfg.get("interval_min")
        self.schedule: str | None = cfg.get("schedule")  # "HH:MM"
        self.delay_sec: float = cfg.get("delay_sec", 0)

        # venv resolution: explicit override, or {dir}/venv, or none
        venv_rel = cfg.get("venv")
        if venv_rel:
            self.venv_bin = PROJECT_ROOT / venv_rel / "bin"
        elif (self.dir / "venv" / "bin").exists():
            self.venv_bin = self.dir / "venv" / "bin"
        else:
            self.venv_bin = None

        # Runtime state
        self.process: subprocess.Popen | None = None
        self.last_run: datetime | None = None
        self.start_time: datetime | None = None
        self.consecutive_crashes = 0
        self.is_running = False  # guard for periodic tasks

    # ── Environment ──────────────────────────────────────────────────────────

    def build_env(self) -> dict[str, str]:
        env = os.environ.copy()
        if self.env_file:
            env.update(load_env_file(PROJECT_ROOT / self.env_file))
        if self.venv_bin and self.venv_bin.exists():
            env["PATH"] = f"{self.venv_bin}:{env.get('PATH', '')}"
        env["PYTHONUNBUFFERED"] = "1"
        return env

    # ── Daemon management ────────────────────────────────────────────────────

    def start_daemon(self) -> None:
        """Start (or restart) the daemon subprocess."""
        args = shlex.split(self.cmd)
        try:
            self.process = subprocess.Popen(
                args,
                cwd=str(self.dir),
                env=self.build_env(),
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
            )
        except FileNotFoundError:
            log(f"[{self.name}] ✗ Executable not found — check venv / cmd")
            self.process = None
            return

        log(f"[{self.name}] ▶ Started (pid={self.process.pid})")
        # Pipe child stdout to our log in a background thread
        t = threading.Thread(target=self._pipe_output, daemon=True)
        t.start()

    def _pipe_output(self) -> None:
        """Forward child process output to the supervisor log."""
        assert self.process is not None
        try:
            for raw in self.process.stdout:
                line = raw.decode(errors="replace").rstrip()
                if line:
                    log(f"[{self.name}]   {line}")
        except Exception:
            pass

    def check_daemon(self) -> None:
        """Check if the daemon is alive; restart if it crashed."""
        if self.process is None:
            self.start_daemon()
            return

        rc = self.process.poll()
        if rc is not None:
            self.consecutive_crashes += 1
            log(
                f"[{self.name}] ✗ Exited (rc={rc}), "
                f"restart in {self.restart_sec}s "
                f"(crash #{self.consecutive_crashes})"
            )
            self.process = None
            # Wait before restarting (but keep the loop responsive)
            time.sleep(self.restart_sec)
            self.start_daemon()
        else:
            # Reset crash counter on stable运行
            if self.consecutive_crashes > 0:
                uptime = time.time() - self.process.pid  # rough
                if uptime > 60:
                    self.consecutive_crashes = 0

    def stop_daemon(self) -> None:
        """Gracefully terminate the daemon."""
        if self.process is None:
            return
        log(f"[{self.name}] ■ Stopping (pid={self.process.pid})…")
        try:
            self.process.terminate()
            try:
                self.process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait(timeout=5)
        except Exception as e:
            log(f"[{self.name}] Error during stop: {e}")
        self.process = None

    # ── Periodic task ────────────────────────────────────────────────────────

    def should_run(self, now: datetime) -> bool:
        """Return True if this periodic task should execute now."""
        # Honour initial delay
        if self.start_time:
            elapsed = (now - self.start_time).total_seconds()
            if elapsed < self.delay_sec:
                return False

        if self.interval_min is not None:
            if self.last_run is None:
                return True
            elapsed_min = (now - self.last_run).total_seconds() / 60
            return elapsed_min >= self.interval_min

        if self.schedule:
            h, m = self.schedule.split(":")
            sched_today = now.replace(
                hour=int(h), minute=int(m), second=0, microsecond=0
            )
            if now < sched_today:
                return False
            if self.last_run is None:
                return True
            # Already ran today after the scheduled time?
            return self.last_run < sched_today

        return False

    def run_periodic(self) -> None:
        """Execute the periodic task synchronously (called from a worker thread)."""
        if self.is_running:
            log(f"[{self.name}] ⏭ Already running, skipping")
            return
        self.is_running = True
        # Set last_run at the START so the scheduler doesn't re-trigger
        # while the task is still executing in this thread.
        self.last_run = datetime.now()
        args = shlex.split(self.cmd)
        log(f"[{self.name}] ▶ Running periodic task…")
        try:
            result = subprocess.run(
                args,
                cwd=str(self.dir),
                env=self.build_env(),
                capture_output=True,
                text=True,
                timeout=900,
            )
            if result.stdout:
                for line in result.stdout.strip().splitlines():
                    if line:
                        log(f"[{self.name}]   {line}")
            if result.returncode != 0:
                log(f"[{self.name}] ✗ Periodic task failed (rc={result.returncode})")
                if result.stderr:
                    for line in result.stderr.strip().splitlines():
                        if line:
                            log(f"[{self.name}]   STDERR: {line}")
            else:
                log(f"[{self.name}] ✓ Periodic task completed")
        except subprocess.TimeoutExpired:
            log(f"[{self.name}] ✗ Timed out after 900s")
        except FileNotFoundError:
            log(f"[{self.name}] ✗ Executable not found — check venv / cmd")
        except Exception as e:
            log(f"[{self.name}] ✗ Error: {e}")
        finally:
            self.is_running = False


# ── Supervisor ───────────────────────────────────────────────────────────────

class Supervisor:
    """Manages a collection of Services."""

    def __init__(self) -> None:
        self.services: list[Service] = []
        self._stop = False

    def load_config(self) -> None:
        with open(CONFIG_FILE, "rb") as f:
            data = tomllib.load(f)
        self.services = [Service(s) for s in data.get("service", [])]
        log(
            f"Loaded {len(self.services)} service(s): "
            + ", ".join(s.name for s in self.services)
        )

    # ── Lifecycle ────────────────────────────────────────────────────────────

    def start(self) -> None:
        self.load_config()
        now = datetime.now()
        for svc in self.services:
            svc.start_time = now
            if svc.type == "daemon":
                svc.start_daemon()
                time.sleep(0.5)  # stagger starts slightly

        log("Supervisor running — Ctrl-C or SIGTERM to stop")

    def stop(self) -> None:
        log("Supervisor shutting down…")
        self._stop = True
        for svc in self.services:
            if svc.type == "daemon":
                svc.stop_daemon()
        log("All services stopped. Goodbye.")

    def run(self) -> None:
        """Main management loop."""
        self.start()

        # Register signal handlers
        signal.signal(signal.SIGTERM, lambda *_: setattr(self, "_stop", True))
        signal.signal(signal.SIGINT, lambda *_: setattr(self, "_stop", True))

        while not self._stop:
            now = datetime.now()

            for svc in self.services:
                if svc.type == "daemon":
                    svc.check_daemon()
                elif svc.type == "periodic":
                    if svc.should_run(now):
                        # Run periodic task in a thread so it doesn't block
                        t = threading.Thread(target=svc.run_periodic, daemon=True)
                        t.start()

            time.sleep(POLL_INTERVAL)

        self.stop()

    # ── Status ───────────────────────────────────────────────────────────────

    def status(self) -> None:
        """Print a summary table of all services."""
        self.load_config()
        print(f"\n{'Name':<25} {'Type':<10} {'Status':<15} {'Details'}")
        print("─" * 75)
        for svc in self.services:
            if svc.type == "daemon":
                detail = f"cmd: {svc.cmd}"
                if svc.venv_bin:
                    detail += f"  venv: {svc.venv_bin.relative_to(PROJECT_ROOT)}"
            else:
                if svc.interval_min:
                    detail = f"every {svc.interval_min} min"
                elif svc.schedule:
                    detail = f"daily at {svc.schedule}"
                else:
                    detail = "no schedule"
            print(f"{svc.name:<25} {svc.type:<10} {'configured':<15} {detail}")
        print()

    def health_check(self) -> int:
        """One-shot check: start daemons briefly, verify they don't immediately crash."""
        self.load_config()
        now = datetime.now()
        failures = 0
        for svc in self.services:
            if svc.type != "daemon":
                continue
            svc.start_time = now
            svc.start_daemon()
            time.sleep(3)
            if svc.process and svc.process.poll() is None:
                log(f"[{svc.name}] ✓ Healthy")
                svc.stop_daemon()
            else:
                log(f"[{svc.name}] ✗ FAILED to start")
                failures += 1
        return 1 if failures else 0


# ── CLI ──────────────────────────────────────────────────────────────────────

def main() -> None:
    if not CONFIG_FILE.exists():
        print(f"Config file not found: {CONFIG_FILE}", file=sys.stderr)
        sys.exit(1)

    sup = Supervisor()

    if "--status" in sys.argv:
        sup.status()
        return

    if "--check" in sys.argv:
        sys.exit(sup.health_check())

    sup.run()


if __name__ == "__main__":
    main()

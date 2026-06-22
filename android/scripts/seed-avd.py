#!/usr/bin/env python3
"""
Seed the attached Android emulator with Contacts and CallLog rows so the app
can exercise its real ingestion path (PRD §Call Detection, §Contact Data Model).

Performance notes
-----------------
- Contacts: inserted via the ContentProvider (`content insert`). Stays correct
  with regard to sync adapters and provider invariants.
- Call log: inserted via direct sqlite3 writes against the provider's db.
  One `content insert` per row is ~5s on the emulator (the CLI spawns a fresh
  Java process each call); at 300+ rows we'd wait >20 minutes. Writing straight
  to the SQLite file skips that overhead and finishes in a couple of seconds.
  Requires a rooted emulator (standard AOSP/Google-APIs images). The content
  provider picks up the new rows on next read — the app re-queries on every
  refresh so there's no stale-cache hazard for us.
"""

from __future__ import annotations

import random
import re
import subprocess
import sys
import tempfile
import time
from dataclasses import dataclass

ACCOUNT_NAME = "orbit-seed"
ACCOUNT_TYPE = "com.orbit.seed"
CALL_LOG_LOOKBACK_DAYS = 90
REMOTE_SCRIPT_PATH = "/data/local/tmp/orbit-seed.sh"
REMOTE_SQL_PATH = "/data/local/tmp/orbit-seed.sql"
CALLLOG_DB = "/data/user/0/com.android.providers.contacts/databases/calllog.db"


@dataclass
class Persona:
    name: str
    phone: str
    heat: list[float]
    pickup_rate: float
    avg_len_sec: int
    call_density: float = 1.0


def _flat_heat(v: float) -> list[float]:
    return [v] * 24


PERSONAS: list[Persona] = [
    Persona("Sarah Chen",    "+14155550182",
            [0.02,0.01,0,0,0,0,0.05,0.18,0.32,0.42,0.35,0.15,0.12,0.22,0.35,0.48,0.58,0.72,0.88,0.94,0.85,0.60,0.30,0.10],
            0.82, 14 * 60, call_density=1.1),
    Persona("Jamie Torres",  "+15105550244",
            [0.25,0.10,0.02,0,0,0,0,0.05,0.08,0.10,0.08,0.05,0.10,0.15,0.12,0.18,0.22,0.30,0.45,0.62,0.85,0.94,0.80,0.55],
            0.71, 22 * 60, call_density=0.9),
    Persona("Marcus Reed",   "+14155550117",
            [0.05,0.02,0,0,0,0,0.08,0.25,0.40,0.45,0.50,0.48,0.55,0.60,0.58,0.52,0.45,0.30,0.22,0.18,0.12,0.10,0.08,0.06],
            0.64, 9 * 60, call_density=0.6),
    Persona("Priya Anand",   "+14155550399",
            [0.02,0,0,0,0,0.15,0.65,0.90,0.92,0.78,0.40,0.22,0.18,0.15,0.12,0.18,0.25,0.30,0.28,0.20,0.15,0.08,0.05,0.02],
            0.89, 31 * 60, call_density=1.3),
    Persona("Alex Kim",      "+16465550012", _flat_heat(0.20), 0.73, 12 * 60),
    Persona("Dana Walsh",    "+12125550456", _flat_heat(0.15), 0.58, 17 * 60, call_density=0.7),
    Persona("Owen Brooks",   "+17185550839", _flat_heat(0.25), 0.82,  6 * 60, call_density=0.8),
    Persona("Nia Ferreira",  "+13055550588", _flat_heat(0.30), 0.91, 28 * 60, call_density=1.2),
    Persona("Theo Park",     "+14155550772", _flat_heat(0.18), 0.66, 10 * 60, call_density=0.6),
    Persona("Luma Reyes",    "+14155550901", _flat_heat(0.22), 0.77, 19 * 60, call_density=0.9),
    Persona("Kai Nakamura",  "+14155550663", _flat_heat(0.20), 0.70, 11 * 60, call_density=0.5),
    Persona("Rhea Patel",    "+14155550214", _flat_heat(0.22), 0.75, 15 * 60, call_density=0.7),
]


def _sh(cmd: list[str], check: bool = True) -> str:
    r = subprocess.run(cmd, capture_output=True, text=True)
    if check and r.returncode != 0:
        sys.stderr.write(f"$ {' '.join(cmd)}\n{r.stderr}\n")
        raise RuntimeError(f"command failed: {cmd[0]}")
    return r.stdout


def log(msg: str) -> None:
    # Force line-buffering so progress shows up when stdout is redirected.
    print(msg, flush=True)


def require_one_device() -> None:
    out = _sh(["adb", "devices"])
    devices = [l for l in out.splitlines() if l.endswith("\tdevice")]
    if len(devices) != 1:
        sys.stderr.write(f"expected one emulator/device, got {len(devices)}:\n{out}\n")
        sys.exit(2)


def require_root() -> None:
    out = _sh(["adb", "shell", "id"])
    if "uid=0" not in out:
        log("adb root…")
        _sh(["adb", "root"])
        # adb restarts adbd as root; give it a breath.
        time.sleep(2)
        out = _sh(["adb", "shell", "id"])
        if "uid=0" not in out:
            sys.stderr.write("adb root failed; emulator image must allow root.\n")
            sys.exit(2)


def _slug(name: str) -> str:
    return re.sub(r"[^a-z]+", "-", name.lower()).strip("-")


_ID_RE = re.compile(r"_id=(\d+)")


def build_contact_insert_script() -> str:
    lines = [
        "#!/system/bin/sh",
        "set -e",
        # Sync-adapter URI so delete is a hard delete, not a tombstone.
        "U='content://com.android.contacts/raw_contacts?caller_is_syncadapter=true'",
        # Pristine wipe: remove EVERY device contact, not just prior orbit-seed
        # rows. Emulators accumulate messy imported / Google / NULL-account
        # contacts (real-looking names + real phone numbers) that leak into
        # screenshots and demos. Seeding is the single source of truth for
        # device contact data, so we start from an empty address book every run.
        "content delete --uri \"$U\" --where \"1=1\"",
        "content delete --uri content://call_log/calls --where \"1=1\"",
    ]
    for p in PERSONAS:
        sid = f"orbit-{_slug(p.name)}"
        lines.append(
            f'content insert --uri "$U" '
            f'--bind account_type:s:{ACCOUNT_TYPE} '
            f'--bind account_name:s:{ACCOUNT_NAME} '
            f'--bind sourceid:s:{sid}'
        )
    return "\n".join(lines) + "\n"


def push_and_run_sh(script: str) -> str:
    with tempfile.NamedTemporaryFile("w", suffix=".sh", delete=False) as f:
        f.write(script)
        local = f.name
    _sh(["adb", "push", local, REMOTE_SCRIPT_PATH])
    return _sh(["adb", "shell", f"sh {REMOTE_SCRIPT_PATH}"])


def push_and_run_sql(sql: str, db_path: str) -> str:
    with tempfile.NamedTemporaryFile("w", suffix=".sql", delete=False) as f:
        f.write(sql)
        local = f.name
    _sh(["adb", "push", local, REMOTE_SQL_PATH])
    return _sh(["adb", "shell", f"sqlite3 {db_path} < {REMOTE_SQL_PATH}"])


def fetch_raw_contact_ids() -> dict[str, int]:
    """Query the provider once and return {sourceid: raw_contact_id}."""
    out = _sh(["adb", "shell",
               "content query --uri content://com.android.contacts/raw_contacts "
               "--projection _id:sourceid "
               f'--where "account_type=\'{ACCOUNT_TYPE}\' AND deleted=0"'])
    mapping: dict[str, int] = {}
    for line in out.splitlines():
        m_id = _ID_RE.search(line)
        m_src = re.search(r"sourceid=([^\s,]+)", line)
        if m_id and m_src:
            mapping[m_src.group(1)] = int(m_id.group(1))
    return mapping


def build_data_insert_script(ids: dict[str, int]) -> str:
    lines = ["#!/system/bin/sh", "set -e"]
    for p in PERSONAS:
        rid = ids.get(f"orbit-{_slug(p.name)}")
        if rid is None:
            raise RuntimeError(f"missing raw_contact_id for {p.name}")
        lines.append(
            f'content insert --uri content://com.android.contacts/data '
            f'--bind raw_contact_id:i:{rid} '
            f'--bind mimetype:s:vnd.android.cursor.item/name '
            f'--bind data1:s:"{p.name}"'
        )
        lines.append(
            f'content insert --uri content://com.android.contacts/data '
            f'--bind raw_contact_id:i:{rid} '
            f'--bind mimetype:s:vnd.android.cursor.item/phone_v2 '
            f'--bind data1:s:{p.phone} '
            f'--bind data2:i:2'
        )
    return "\n".join(lines) + "\n"


TYPE_INCOMING, TYPE_OUTGOING, TYPE_MISSED = 1, 2, 3


def _weighted_hour(heat: list[float], rng: random.Random) -> int:
    total = sum(heat)
    if total <= 0:
        return rng.randrange(24)
    r = rng.uniform(0, total)
    acc = 0.0
    for h, w in enumerate(heat):
        acc += w
        if r <= acc:
            return h
    return 23


def _sql_escape(s: str) -> str:
    return s.replace("'", "''")


def build_call_log_sql(rng: random.Random) -> tuple[str, int]:
    """Generate a single SQL script that inserts every call log row in one
    transaction. Dramatically faster than one `content insert` per row."""
    lines = [
        "BEGIN;",
        "DELETE FROM calls;",
    ]
    count = 0
    now_ms = int(time.time() * 1000)
    day_ms = 24 * 3600 * 1000
    for p in PERSONAS:
        n = max(1, int(30 * p.call_density))
        for _ in range(n):
            days_ago = rng.randint(0, CALL_LOG_LOOKBACK_DAYS - 1)
            hour = _weighted_hour(p.heat, rng)
            minute = rng.randrange(60)
            base = now_ms - days_ago * day_ms
            midnight = base - (base % day_ms)
            when = midnight + (hour * 3600 + minute * 60) * 1000

            r = rng.random()
            if r < p.pickup_rate * 0.7:
                t, dur = TYPE_OUTGOING, max(60, int(rng.gauss(p.avg_len_sec, p.avg_len_sec * 0.3)))
            elif r < p.pickup_rate:
                t, dur = TYPE_INCOMING, max(60, int(rng.gauss(p.avg_len_sec * 0.8, p.avg_len_sec * 0.3)))
            else:
                t, dur = TYPE_MISSED, 0

            name = _sql_escape(p.name)
            lines.append(
                f"INSERT INTO calls(number, date, duration, type, name, new, is_read, presentation) "
                f"VALUES ('{p.phone}', {when}, {dur}, {t}, '{name}', 0, 1, 1);"
            )
            count += 1
    lines.append("COMMIT;")
    return "\n".join(lines) + "\n", count


def main() -> None:
    require_one_device()
    require_root()
    seed = int(sys.argv[1]) if len(sys.argv) > 1 else 42
    rng = random.Random(seed)

    started = time.time()
    log(f"seeding with rng seed={seed}")

    log("1/4 wiping + inserting raw_contacts…")
    push_and_run_sh(build_contact_insert_script())

    log("2/4 fetching new raw_contact ids…")
    ids = fetch_raw_contact_ids()
    if len(ids) != len(PERSONAS):
        raise RuntimeError(f"expected {len(PERSONAS)} raw_contacts, got {len(ids)}: {ids}")

    log("3/4 inserting contact data rows (name + phone)…")
    push_and_run_sh(build_data_insert_script(ids))

    log("4/4 writing call log via sqlite3 (bypass provider for speed)…")
    sql, n_calls = build_call_log_sql(rng)
    push_and_run_sql(sql, CALLLOG_DB)

    elapsed = time.time() - started
    log(f"done in {elapsed:.1f}s. {len(PERSONAS)} contacts, {n_calls} call log entries.")


if __name__ == "__main__":
    main()

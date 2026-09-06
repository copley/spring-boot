#!/usr/bin/env python3
"""Analyze gh-51463 thread dumps for the production failure signature."""

from __future__ import annotations

import json
import re
import sys
from collections import defaultdict
from pathlib import Path

RELEVANT_FRAMES = (
    "org.springframework.boot.loader.jar.NestedJarFile.hasEntry",
    "org.springframework.boot.loader.jar.NestedJarFile.getContentEntry",
    "org.springframework.boot.loader.jar.NestedJarFile.getNestedJarEntry",
    "org.springframework.boot.loader.zip.FileDataBlock$FileAccess.ensureOpen",
    "org.springframework.boot.loader.zip.FileDataBlock.read",
)


def all_threads(document: dict) -> list[dict]:
    result = []
    for container in document["threadDump"]["threadContainers"]:
        result.extend(container.get("threads", []))
    return result


def relevant_frame(thread: dict) -> str | None:
    for frame in thread.get("stack", []):
        if any(marker in frame for marker in RELEVANT_FRAMES):
            return frame
    return None


def owned_locks(thread: dict) -> set[str]:
    result = set()
    for monitor in thread.get("monitorsOwned", []):
        result.update(monitor.get("locks", []))
    return result


def snapshot_number(path: Path) -> tuple[int, int]:
    match = re.search(r"thread-(\d+)-(\d+)\.json$", path.name)
    if not match:
        return (0, 0)
    return (int(match.group(1)), int(match.group(2)))


def read_progress(diag: Path, attempt: int, snapshot: int) -> str | None:
    path = diag / f"progress-{attempt}-{snapshot}.txt"
    if not path.exists():
        return None
    value = path.read_text(errors="replace").strip()
    return value or None


def main() -> int:
    diag = Path(sys.argv[1] if len(sys.argv) > 1 else "gh-51463-diagnostics")
    paths = sorted(diag.glob("thread-*-*.json"), key=snapshot_number)
    if not paths:
        print("No JSON thread dumps found")
        return 0

    observations: dict[tuple[int, str, str], list[dict]] = defaultdict(list)
    total_hits = 0

    for path in paths:
        attempt, snapshot = snapshot_number(path)
        document = json.loads(path.read_text())
        threads = all_threads(document)
        owners = {}
        for thread in threads:
            for lock in owned_locks(thread):
                owners[lock] = thread

        for thread in threads:
            frame = relevant_frame(thread)
            if frame is None:
                continue
            total_hits += 1
            blocked_on = thread.get("blockedOn")
            owner = owners.get(blocked_on)
            progress = read_progress(diag, attempt, snapshot)
            entry = {
                "attempt": attempt,
                "snapshot": snapshot,
                "name": thread.get("name"),
                "tid": thread.get("tid"),
                "virtual": thread.get("virtual", False),
                "state": thread.get("state"),
                "blockedOn": blocked_on,
                "frame": frame,
                "stack": thread.get("stack", []),
                "owner": owner,
                "progress": progress,
            }
            key = (attempt, str(thread.get("tid")), frame)
            observations[key].append(entry)

            print(
                f"attempt={attempt} snapshot={snapshot} tid={thread.get('tid')} "
                f"virtual={thread.get('virtual', False)} state={thread.get('state')} "
                f"frame={frame} blockedOn={blocked_on} progress={progress}"
            )
            if owner is not None:
                owner_stack = owner.get("stack", [])
                print(
                    "  owner: "
                    f"tid={owner.get('tid')} name={owner.get('name')} "
                    f"state={owner.get('state')} virtual={owner.get('virtual', False)}"
                )
                for owner_frame in owner_stack[:12]:
                    print(f"    {owner_frame}")

    print(f"Relevant thread observations: {total_hits}")

    reproduced = False
    for entries in observations.values():
        entries.sort(key=lambda item: item["snapshot"])
        if len(entries) < 2:
            continue

        # Strongest form: the same thread remains BLOCKED on the same object across
        # consecutive snapshots. This mirrors the production dump where the thread
        # remains stuck in FileDataBlock/NestedJarFile rather than merely visiting it.
        for first, second in zip(entries, entries[1:]):
            consecutive = second["snapshot"] == first["snapshot"] + 1
            same_lock = first["blockedOn"] is not None and first["blockedOn"] == second["blockedOn"]
            blocked = first["state"] == "BLOCKED" and second["state"] == "BLOCKED"
            if consecutive and same_lock and blocked:
                print("PRODUCTION-SIGNATURE: same relevant thread stayed BLOCKED on the same monitor")
                reproduced = True

        # Java 25's JSON dump can report a stuck virtual thread as RUNNABLE even when
        # it has made no progress (as in the production evidence). Require three
        # consecutive snapshots with the same relevant frame and no observed progress.
        if len(entries) >= 3:
            for index in range(len(entries) - 2):
                window = entries[index : index + 3]
                snapshots = [item["snapshot"] for item in window]
                consecutive = snapshots == list(range(snapshots[0], snapshots[0] + 3))
                runnable = all(item["state"] == "RUNNABLE" for item in window)
                progress_values = [item["progress"] for item in window]
                no_progress = len(set(progress_values)) == 1
                if consecutive and runnable and no_progress:
                    print(
                        "PRODUCTION-SIGNATURE: relevant RUNNABLE thread remained at the same frame "
                        "for three snapshots with no application progress"
                    )
                    reproduced = True

    if reproduced:
        return 2
    print("No persistent FileDataBlock/NestedJarFile production signature detected")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

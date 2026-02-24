#!/usr/bin/env python3
from __future__ import annotations
import argparse
import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, List, Tuple

DIRECTION_MAP = {"0": "north", "1": "east", "2": "south", "3": "west", "4": "up", "5": "down"}

@dataclass
class Room:
    vnum: str
    title: str
    description: str
    exits: Dict[str, str] = field(default_factory=dict)

@dataclass
class Mob:
    vnum: str
    name: str
    room_vnum: str | None = None

@dataclass
class Item:
    vnum: str
    name: str
    room_vnum: str | None = None


def clean_tilde(text: str) -> str:
    return text.replace("~", "").strip()


def slug(text: str) -> str:
    return re.sub(r"[^a-z0-9]+", "_", text.lower()).strip("_") or "zone"


def quote(s: str) -> str:
    return '"' + s.replace('\\', '\\\\').replace('"', '\\"') + '"'


def dump_yaml(value, indent: int = 0) -> List[str]:
    pad = "  " * indent
    out: List[str] = []
    if isinstance(value, dict):
        for k, v in value.items():
            if isinstance(v, (dict, list)):
                out.append(f"{pad}{k}:")
                out.extend(dump_yaml(v, indent + 1))
            elif isinstance(v, str):
                out.append(f"{pad}{k}: {quote(v)}")
            elif isinstance(v, bool):
                out.append(f"{pad}{k}: {'true' if v else 'false'}")
            else:
                out.append(f"{pad}{k}: {v}")
    elif isinstance(value, list):
        for item in value:
            if isinstance(item, (dict, list)):
                out.append(f"{pad}-")
                out.extend(dump_yaml(item, indent + 1))
            elif isinstance(item, str):
                out.append(f"{pad}- {quote(item)}")
            else:
                out.append(f"{pad}- {item}")
    return out


def parse_rooms(lines: List[str], i: int) -> Tuple[int, Dict[str, Room]]:
    rooms: Dict[str, Room] = {}
    while i < len(lines):
        line = lines[i].strip()
        if line == "#0":
            return i + 1, rooms
        if not line.startswith("#") or line == "#ROOMS":
            i += 1
            continue

        vnum = line[1:]
        i += 1
        title = clean_tilde(lines[i])
        i += 1

        desc: List[str] = []
        while i < len(lines) and "~" not in lines[i]:
            desc.append(lines[i].rstrip())
            i += 1
        if i < len(lines):
            desc.append(lines[i].replace("~", "").rstrip())
            i += 1

        i += 1  # flags/sector line
        exits: Dict[str, str] = {}
        while i < len(lines):
            cur = lines[i].strip()
            if cur.startswith("D") and len(cur) == 2 and cur[1].isdigit():
                d = cur[1]
                i += 1
                while i < len(lines) and "~" not in lines[i]:
                    i += 1
                i += 1
                while i < len(lines) and "~" not in lines[i]:
                    i += 1
                i += 1
                if i < len(lines):
                    parts = lines[i].split()
                    if len(parts) >= 3:
                        exits[DIRECTION_MAP.get(d, f"dir_{d}")] = parts[2]
                    i += 1
                continue
            if cur == "S":
                i += 1
                break
            i += 1

        rooms[vnum] = Room(vnum, title, "\n".join([x for x in desc if x]).strip() or "(no description)", exits)
    return i, rooms


def parse_entities(lines: List[str], i: int, section: str) -> Tuple[int, Dict[str, str]]:
    out: Dict[str, str] = {}
    while i < len(lines):
        line = lines[i].strip()
        if line == "#0":
            return i + 1, out
        if not line.startswith("#") or line == section:
            i += 1
            continue
        vnum = line[1:]
        i += 1
        out[vnum] = clean_tilde(lines[i]) or f"{section.lower()} {vnum}"
        while i < len(lines) and not lines[i].strip().startswith("#"):
            i += 1
    return i, out


def parse_resets(lines: List[str], i: int, mobs: Dict[str, Mob], items: Dict[str, Item]) -> int:
    room: str | None = None
    while i < len(lines):
        line = lines[i].strip()
        if line == "S":
            return i + 1
        parts = line.split()
        code = parts[0] if parts else ""
        if code == "M" and len(parts) >= 5 and parts[2] in mobs:
            mobs[parts[2]].room_vnum = parts[4]
            room = parts[4]
        elif code == "O" and len(parts) >= 4 and parts[2] in items:
            items[parts[2]].room_vnum = parts[3]
            room = parts[3]
        elif code == "G" and len(parts) >= 3 and room and parts[2] in items:
            items[parts[2]].room_vnum = room
        i += 1
    return i


def parse_area(path: Path) -> dict:
    lines = path.read_text(encoding="latin-1", errors="ignore").splitlines()
    rooms: Dict[str, Room] = {}
    mobs: Dict[str, Mob] = {}
    items: Dict[str, Item] = {}
    i = 0
    while i < len(lines):
        s = lines[i].strip()
        if s == "#ROOMS":
            i, r = parse_rooms(lines, i + 1)
            rooms.update(r)
            continue
        if s == "#MOBILES":
            i, m = parse_entities(lines, i + 1, "#MOBILES")
            mobs.update({k: Mob(k, v) for k, v in m.items()})
            continue
        if s == "#OBJECTS":
            i, o = parse_entities(lines, i + 1, "#OBJECTS")
            items.update({k: Item(k, v) for k, v in o.items()})
            continue
        if s == "#RESETS":
            i = parse_resets(lines, i + 1, mobs, items)
            continue
        i += 1

    if not rooms:
        raise ValueError(f"No rooms found in {path}")

    rid = lambda v: f"r{v}"
    zone = slug(path.stem)
    out = {"zone": zone, "lifespan": 30, "startRoom": rid(next(iter(rooms.keys()))), "rooms": {}, "mobs": {}, "items": {}}
    for v, r in rooms.items():
        out["rooms"][rid(v)] = {"title": r.title, "description": r.description, "exits": {d: rid(t) for d, t in r.exits.items() if t in rooms}}
    for v, m in mobs.items():
        if m.room_vnum in rooms:
            out["mobs"][f"m{v}"] = {"name": m.name, "room": rid(m.room_vnum), "tier": "standard", "level": 1}
    for v, it in items.items():
        e = {"displayName": it.name, "description": "Imported from ROM area file.", "keyword": slug(it.name).split("_")[0] or f"item{v}"}
        if it.room_vnum in rooms:
            e["room"] = rid(it.room_vnum)
        out["items"][f"i{v}"] = e
    return out


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--input-dir", required=True, type=Path)
    ap.add_argument("--output-dir", required=True, type=Path)
    args = ap.parse_args()
    args.output_dir.mkdir(parents=True, exist_ok=True)
    files = sorted(args.input_dir.glob("*.are"), key=lambda p: (p.stem != "midgaard", p.stem))
    if not files:
        raise SystemExit(f"No .are files found in {args.input_dir}")
    for f in files:
        out = args.output_dir / f"{slug(f.stem)}.yaml"
        out.write_text("\n".join(dump_yaml(parse_area(f))) + "\n", encoding="utf-8")
        print(f"wrote {out}")


if __name__ == "__main__":
    main()

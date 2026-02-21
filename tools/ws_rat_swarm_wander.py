#!/usr/bin/env python3
"""
AmbonMUD WebSocket swarm bot (wander + kill rats + optionally get lantern).

Fixes included:
- Correct login sequence: Name -> yes -> password
- Prompt detection robust to:
    - '> ', '>', '\r\n> ', etc.
    - ANSI escape sequences around the prompt
    - weird whitespace / non-printables
- recv_until_prompt returns immediately if already at prompt
- Verbose mode prints repr() so hidden chars are visible
- Avoid immediate backtrack option
- Ramp-up clients per second
- Think time between actions
- Optional "get lantern" every N kills
- Clean shutdown (no noisy CancelledError traceback)
"""

from __future__ import annotations

import argparse
import asyncio
import random
import re
import string
import time
import websockets
from dataclasses import dataclass
from typing import Optional

LOGIN_NEEDLES = {
    "enter_name": "Enter your name:",
    "create_user": "Create a new user? (yes/no)",
    "create_password": "Create a password:",
}

DEFAULT_MOVES = ["north", "south", "east", "west", "up", "down"]
OPPOSITE = {
    "north": "south",
    "south": "north",
    "east": "west",
    "west": "east",
    "up": "down",
    "down": "up",
}

# ANSI escape sequences (most common CSI sequences)
ANSI_RE = re.compile(r"\x1b\[[0-9;]*[A-Za-z]")


@dataclass
class SwarmConfig:
    url: str
    clients: int
    minutes: float
    ramp_per_sec: float
    verbose: bool

    # Behavior mix
    move_weight: float
    kill_weight: float
    look_weight: float
    moves: list[str]
    avoid_immediate_backtrack: bool

    # Loot behavior
    get_lantern: bool
    get_every: int

    # Timing
    think_min_ms: int
    think_max_ms: int
    progress_every_s: float

    # Timeouts
    connect_timeout_s: float
    io_timeout_s: float


@dataclass
class BotStats:
    kills: int = 0
    moves: int = 0
    gets: int = 0


class PromptState:
    """
    Tracks whether we are currently "at prompt".

    Key rule:
    - Only wait for prompt after sending a command.
    - If we already have prompt, don't wait for another one.
    """

    def __init__(self) -> None:
        self.at_prompt: bool = False

    @staticmethod
    def _strip_ansi(s: str) -> str:
        return ANSI_RE.sub("", s)

    @classmethod
    def _looks_like_prompt(cls, frame: str) -> bool:
        """
        Normalize the frame and detect the prompt.

        We treat prompt as:
        - a frame that becomes exactly ">" after stripping ANSI + whitespace
        - OR a frame that contains a trailing line ending in ">" (common combined frames)
        """
        if not frame:
            return False

        s = cls._strip_ansi(frame)
        s = s.replace("\r", "")
        # Common case: server sends just "> " (maybe with weird whitespace)
        if s.strip() == ">":
            return True

        # Combined-frame case: "... \n> " or "... \n>"
        # Normalize to check last line after splitting on '\n'
        parts = s.split("\n")
        if parts:
            last = parts[-1].strip()
            if last == ">":
                return True

        return False

    def observe_incoming(self, text: str) -> None:
        if self._looks_like_prompt(text):
            self.at_prompt = True

    def sent_command(self) -> None:
        self.at_prompt = False


def _rand_suffix(n: int = 6) -> str:
    return "".join(random.choice(string.ascii_lowercase + string.digits) for _ in range(n))


def _vprint(verbose: bool, prefix: str, direction: str, msg: str) -> None:
    if not verbose:
        return
    # repr() is KEY so we can see hidden characters / ANSI
    print(f"{prefix} {direction} {msg!r}")


async def recv_with_timeout(ws, timeout_s: float) -> str:
    return await asyncio.wait_for(ws.recv(), timeout=timeout_s)


async def recv_until_contains(
        ws,
        needle: str,
        ps: PromptState,
        timeout_s: float,
        bot_prefix: str,
        verbose: bool,
) -> None:
    """
    Receive frames until we see a substring needle anywhere.
    Also updates prompt-state when prompt appears.
    """
    deadline = time.monotonic() + timeout_s
    buf = ""

    while True:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError(f"timeout waiting for {needle!r} (buffer tail={buf[-180:]!r})")

        msg = await recv_with_timeout(ws, remaining)
        if not isinstance(msg, str):
            continue

        _vprint(verbose, bot_prefix, "<<", msg)
        ps.observe_incoming(msg)
        buf += msg

        if needle in buf:
            return


async def recv_until_prompt(
        ws,
        ps: PromptState,
        timeout_s: float,
        bot_prefix: str,
        verbose: bool,
) -> None:
    """
    Wait for prompt to appear.

    IMPORTANT: If we're already at prompt, return immediately.
    """
    if ps.at_prompt:
        return

    deadline = time.monotonic() + timeout_s
    while True:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError("timeout waiting for prompt")

        msg = await recv_with_timeout(ws, remaining)
        if not isinstance(msg, str):
            continue

        _vprint(verbose, bot_prefix, "<<", msg)
        ps.observe_incoming(msg)
        if ps.at_prompt:
            return


async def send_line(
        ws,
        ps: PromptState,
        line: str,
        bot_prefix: str,
        verbose: bool,
) -> None:
    await ws.send(line + "\n")
    ps.sent_command()
    _vprint(verbose, bot_prefix, ">>", line)


async def send_and_wait_prompt(
        ws,
        ps: PromptState,
        cmd: str,
        timeout_s: float,
        bot_prefix: str,
        verbose: bool,
) -> None:
    """
    Interaction primitive:
    - Ensure at prompt before sending (best-effort; if not at prompt, wait)
    - Send command
    - Wait for prompt that comes after the command output
    """
    await recv_until_prompt(ws, ps, timeout_s=timeout_s, bot_prefix=bot_prefix, verbose=verbose)
    await send_line(ws, ps, cmd, bot_prefix=bot_prefix, verbose=verbose)
    await recv_until_prompt(ws, ps, timeout_s=timeout_s, bot_prefix=bot_prefix, verbose=verbose)


async def login_new_user(
        ws,
        ps: PromptState,
        name: str,
        password: str,
        timeout_s: float,
        bot_prefix: str,
        verbose: bool,
) -> None:
    # Wait for "Enter your name:"
    await recv_until_contains(ws, LOGIN_NEEDLES["enter_name"], ps, timeout_s, bot_prefix, verbose)

    # Now ensure we have the prompt
    await recv_until_prompt(ws, ps, timeout_s, bot_prefix, verbose)
    await send_and_wait_prompt(ws, ps, name, timeout_s, bot_prefix, verbose)

    # Create new user? (yes/no)
    await recv_until_contains(ws, LOGIN_NEEDLES["create_user"], ps, timeout_s, bot_prefix, verbose)
    await recv_until_prompt(ws, ps, timeout_s, bot_prefix, verbose)
    await send_and_wait_prompt(ws, ps, "yes", timeout_s, bot_prefix, verbose)

    # Create password
    await recv_until_contains(ws, LOGIN_NEEDLES["create_password"], ps, timeout_s, bot_prefix, verbose)
    await recv_until_prompt(ws, ps, timeout_s, bot_prefix, verbose)
    await send_and_wait_prompt(ws, ps, password, timeout_s, bot_prefix, verbose)

    # Ensure prompt in game
    await recv_until_prompt(ws, ps, timeout_s, bot_prefix, verbose)


def choose_action(cfg: SwarmConfig) -> str:
    total = cfg.move_weight + cfg.kill_weight + cfg.look_weight
    r = random.random() * total
    if r < cfg.move_weight:
        return "move"
    r -= cfg.move_weight
    if r < cfg.kill_weight:
        return "kill"
    return "look"


def choose_move(cfg: SwarmConfig, last_move: Optional[str]) -> str:
    if not cfg.avoid_immediate_backtrack or not last_move:
        return random.choice(cfg.moves)

    back = OPPOSITE.get(last_move)
    candidates = [m for m in cfg.moves if m != back]
    return random.choice(candidates) if candidates else random.choice(cfg.moves)


async def bot_task(bot_id: int, cfg: SwarmConfig, stats: BotStats, stop_at: float) -> None:
    bot_prefix = f"[bot {bot_id:04d}]"
    name = f"Bot{bot_id:04d}_{_rand_suffix(4)}"
    password = f"Pw{bot_id:04d}_{_rand_suffix(6)}"

    ps = PromptState()
    last_move: Optional[str] = None

    async with websockets.connect(cfg.url, open_timeout=cfg.connect_timeout_s) as ws:
        if cfg.verbose:
            print(f"{bot_prefix} connected")

        await login_new_user(ws, ps, name, password, cfg.io_timeout_s, bot_prefix, cfg.verbose)

        while time.monotonic() < stop_at:
            think_ms = random.randint(cfg.think_min_ms, cfg.think_max_ms)
            await asyncio.sleep(think_ms / 1000.0)

            action = choose_action(cfg)

            if action == "look":
                await send_and_wait_prompt(ws, ps, "look", cfg.io_timeout_s, bot_prefix, cfg.verbose)
                continue

            if action == "move":
                mv = choose_move(cfg, last_move)
                await send_and_wait_prompt(ws, ps, mv, cfg.io_timeout_s, bot_prefix, cfg.verbose)
                stats.moves += 1
                last_move = mv
                continue

            # kill
            await send_and_wait_prompt(ws, ps, "kill rat", cfg.io_timeout_s, bot_prefix, cfg.verbose)
            stats.kills += 1

            if cfg.get_lantern and cfg.get_every > 0 and (stats.kills % cfg.get_every == 0):
                await send_and_wait_prompt(ws, ps, "get lantern", cfg.io_timeout_s, bot_prefix, cfg.verbose)
                stats.gets += 1


async def progress_printer(started_ref, stats_list: list[BotStats], cfg: SwarmConfig, start_time: float) -> None:
    try:
        while True:
            await asyncio.sleep(cfg.progress_every_s)
            started = started_ref[0]
            kills = sum(s.kills for s in stats_list)
            moves = sum(s.moves for s in stats_list)
            gets = sum(s.gets for s in stats_list)
            t = time.monotonic() - start_time
            print(f"[progress] t={t:5.1f}s started={started}/{cfg.clients} (kills={kills} moves={moves} gets={gets})")
    except asyncio.CancelledError:
        return


async def run_swarm(cfg: SwarmConfig) -> None:
    start_time = time.monotonic()
    stop_at = start_time + (cfg.minutes * 60.0)

    print(
        f"Starting swarm (wander): clients={cfg.clients}, duration={cfg.minutes:.1f}m, url={cfg.url}\n"
        f"Mix: move={cfg.move_weight:.2f}, kill={cfg.kill_weight:.2f}, look={cfg.look_weight:.2f} | "
        f"moves={cfg.moves} | backtrack_avoid={cfg.avoid_immediate_backtrack}\n"
        f"Ramp: {cfg.ramp_per_sec:.1f}/sec (interval={1.0/cfg.ramp_per_sec if cfg.ramp_per_sec>0 else 0:.3f}s), "
        f"think={cfg.think_min_ms}-{cfg.think_max_ms}ms, get={cfg.get_lantern} (every {cfg.get_every} kills)"
    )

    started_ref = [0]
    stats_list = [BotStats() for _ in range(cfg.clients)]
    tasks: list[asyncio.Task] = []
    progress_task = asyncio.create_task(progress_printer(started_ref, stats_list, cfg, start_time))

    try:
        interval = (1.0 / cfg.ramp_per_sec) if cfg.ramp_per_sec > 0 else 0.0

        for i in range(cfg.clients):
            bot_id = i + 1

            async def start_one(bid: int, s: BotStats) -> None:
                started_ref[0] += 1
                try:
                    await bot_task(bid, cfg, s, stop_at)
                except asyncio.CancelledError:
                    raise
                except Exception as e:
                    print(f"[bot {bid:04d}] error: {e!r}")

            tasks.append(asyncio.create_task(start_one(bot_id, stats_list[i])))

            if interval > 0:
                await asyncio.sleep(interval)

        remaining = stop_at - time.monotonic()
        if remaining > 0:
            await asyncio.sleep(remaining)

    finally:
        for t in tasks:
            t.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)

        progress_task.cancel()
        await asyncio.gather(progress_task, return_exceptions=True)

        kills = sum(s.kills for s in stats_list)
        moves = sum(s.moves for s in stats_list)
        gets = sum(s.gets for s in stats_list)
        elapsed = time.monotonic() - start_time
        print(f"[done] elapsed={elapsed:.1f}s kills={kills} moves={moves} gets={gets}")


def parse_args() -> SwarmConfig:
    p = argparse.ArgumentParser(description="AmbonMUD WebSocket swarm (wander + kill rats + get lantern).")
    p.add_argument("--url", required=True, help="WebSocket URL, e.g. ws://localhost:8080/ws")
    p.add_argument("--clients", type=int, default=50, help="Number of bot clients")
    p.add_argument("--minutes", type=float, default=5.0, help="How long to run (minutes)")
    p.add_argument("--ramp-per-sec", type=float, default=10.0, help="Clients to start per second")
    p.add_argument("--verbose", action="store_true", help="Print per-bot traffic (VERY noisy)")

    p.add_argument("--move-weight", type=float, default=1.0, help="Relative weight for moving")
    p.add_argument("--kill-weight", type=float, default=2.5, help="Relative weight for killing rats")
    p.add_argument("--look-weight", type=float, default=0.4, help="Relative weight for look")

    p.add_argument("--moves", nargs="*", default=DEFAULT_MOVES, help="Allowed movement commands")
    p.add_argument("--avoid-immediate-backtrack", action="store_true", help="Avoid instantly reversing direction")

    p.add_argument("--get-lantern", action="store_true", help="Occasionally run 'get lantern'")
    p.add_argument("--get-every", type=int, default=6, help="Run 'get lantern' every N kills (if enabled)")

    p.add_argument("--think-min-ms", type=int, default=250, help="Min think time between actions (ms)")
    p.add_argument("--think-max-ms", type=int, default=900, help="Max think time between actions (ms)")

    p.add_argument("--progress-every-s", type=float, default=10.0, help="Progress print interval (seconds)")
    p.add_argument("--connect-timeout-s", type=float, default=10.0, help="WS connect timeout")
    p.add_argument("--io-timeout-s", type=float, default=30.0, help="Timeout for waiting on prompts / login text")

    a = p.parse_args()

    return SwarmConfig(
        url=a.url,
        clients=max(0, a.clients),
        minutes=max(0.1, a.minutes),
        ramp_per_sec=max(0.0, a.ramp_per_sec),
        verbose=bool(a.verbose),
        move_weight=max(0.0, a.move_weight),
        kill_weight=max(0.0, a.kill_weight),
        look_weight=max(0.0, a.look_weight),
        moves=list(a.moves),
        avoid_immediate_backtrack=bool(a.avoid_immediate_backtrack),
        get_lantern=bool(a.get_lantern),
        get_every=max(1, a.get_every),
        think_min_ms=max(0, a.think_min_ms),
        think_max_ms=max(0, a.think_max_ms),
        progress_every_s=max(1.0, a.progress_every_s),
        connect_timeout_s=max(1.0, a.connect_timeout_s),
        io_timeout_s=max(2.0, a.io_timeout_s),
    )


def main() -> None:
    cfg = parse_args()
    random.seed()
    asyncio.run(run_swarm(cfg))


if __name__ == "__main__":
    main()
import { useEffect, useRef, useState } from "react";
import type { ChatMessage, RoomState, Vitals } from "../types";

interface GameNarratorProps {
  /** Mirrors the server-persisted screen-reader setting (Char.Name GMCP). */
  enabled: boolean;
  room: RoomState;
  /** Sorted [direction, targetRoomId] pairs for the current room. */
  exits: Array<[string, string]>;
  vitals: Vitals;
  /** Tell-channel history; new entries are announced while the chat board is closed. */
  tells: ChatMessage[];
  /** Suppresses tell announcements while the chat board's own log is on screen. */
  chatBoardOpen: boolean;
}

/** Announce a low-health warning when HP falls to this fraction of max. */
const LOW_HP_FRACTION = 0.25;
/** Clear spoken text after a beat so SR cursor navigation doesn't re-read it. */
const POLITE_CLEAR_MS = 8000;
const URGENT_CLEAR_MS = 5000;

function roomSentence(room: RoomState, exits: Array<[string, string]>): string {
  const exitNames = exits.map(([direction]) => direction);
  return `${room.title}. Exits: ${exitNames.length > 0 ? exitNames.join(", ") : "none"}.`;
}

/**
 * Spoken game narration for screen-reader players. The PixiJS canvas renders
 * room movement, health, and social context purely visually; when screen-reader
 * mode is on, this component mirrors those beats into sr-only live regions:
 *
 *  - room changes (and an orienting announcement when the mode turns on):
 *    title + available exits, polite
 *  - health dropping to 25% of max: assertive alert
 *  - incoming tells while the chat board is closed: polite
 *
 * Combat blow-by-blow intentionally stays out — CombatLog already maintains a
 * `role="log"` live region, and duplicating it here would double-announce.
 * The regions render even while disabled so assistive tech registers them
 * before the first announcement.
 */
export function GameNarrator({ enabled, room, exits, vitals, tells, chatBoardOpen }: GameNarratorProps) {
  const [polite, setPolite] = useState("");
  const [urgent, setUrgent] = useState("");
  const lastRoomIdRef = useRef<string | null>(room.id);
  const wasLowRef = useRef(false);
  // Pre-seed with history so enabling the mode doesn't replay old tells.
  const seenTellsRef = useRef<Set<string>>(new Set(tells.map((t) => t.id)));

  // Auto-clear so the regions hold no stale text for SR cursor passes.
  useEffect(() => {
    if (!polite) return;
    const timer = window.setTimeout(() => setPolite(""), POLITE_CLEAR_MS);
    return () => window.clearTimeout(timer);
  }, [polite]);

  useEffect(() => {
    if (!urgent) return;
    const timer = window.setTimeout(() => setUrgent(""), URGENT_CLEAR_MS);
    return () => window.clearTimeout(timer);
  }, [urgent]);

  // Orient the player with the current room when the mode turns on.
  useEffect(() => {
    if (!enabled || room.id === null || room.title === "-") return;
    const timer = window.setTimeout(() => setPolite(roomSentence(room, exits)), 0);
    return () => window.clearTimeout(timer);
    // Announce only on the enabled flip, not on every room/exit change (the
    // movement effect below owns those).
  }, [enabled]); // eslint-disable-line react-hooks/exhaustive-deps

  // Movement: announce the new room once per room-id change.
  useEffect(() => {
    const prev = lastRoomIdRef.current;
    lastRoomIdRef.current = room.id;
    if (!enabled || room.id === null || room.id === prev || room.title === "-") return;
    if (prev === null) return; // first room is covered by the orientation effect
    const timer = window.setTimeout(() => setPolite(roomSentence(room, exits)), 0);
    return () => window.clearTimeout(timer);
  }, [enabled, room, exits]);

  // Health: single assertive warning when HP crosses below the threshold.
  useEffect(() => {
    const low = vitals.maxHp > 0 && vitals.hp > 0 && vitals.hp / vitals.maxHp <= LOW_HP_FRACTION;
    const was = wasLowRef.current;
    wasLowRef.current = low;
    if (!enabled || !low || was) return;
    const timer = window.setTimeout(
      () => setUrgent(`Health low: ${vitals.hp} of ${vitals.maxHp}.`),
      0,
    );
    return () => window.clearTimeout(timer);
  }, [enabled, vitals.hp, vitals.maxHp]);

  // Private messages: speak tells that arrive while the chat board is closed.
  useEffect(() => {
    const seen = seenTellsRef.current;
    const fresh = tells.filter((t) => !seen.has(t.id));
    for (const t of tells) seen.add(t.id);
    if (!enabled || chatBoardOpen || fresh.length === 0) return;
    const latest = fresh[fresh.length - 1];
    const timer = window.setTimeout(
      () => setPolite(`Tell from ${latest.sender}: ${latest.message}`),
      0,
    );
    return () => window.clearTimeout(timer);
  }, [enabled, chatBoardOpen, tells]);

  return (
    <>
      <p className="sr-only" role="status" aria-live="polite" aria-atomic="true">{polite}</p>
      <p className="sr-only" role="alert" aria-atomic="true">{urgent}</p>
    </>
  );
}

import { useEffect, useState, type ReactNode } from "react";
import { PixiCanvas } from "../canvas/PixiCanvas";
import { CombatLog } from "./CombatLog";
import { RoomPanel } from "./RoomPanel";
import { CanvasVitalsHud } from "./CanvasVitalsHud";
import { KioskBar } from "./KioskBar";
import { SkillBar } from "./SkillBar";
import { WorldAtmosphereHud } from "./WorldAtmosphereHud";
import { ExpandRoomIcon } from "./Icons";
import type { CombatLogMessage, CombatTarget, ItemSummary, PopoutPanel, RoomState, SkillSummary, Vitals, WorldEvent, WorldTime, WorldWeather } from "../types";
import type { AudioEngine } from "../hooks/useAudioEngine";

interface GameShellProps {
  connected: boolean;
  hasCharacterProfile: boolean;
  vitals: Vitals;
  room: RoomState;
  exits: Array<[string, string]>;
  serverAssets: Record<string, string>;
  worldTime: WorldTime | null;
  worldWeather: WorldWeather | null;
  worldEvents: WorldEvent[];
  combatLogMessages: CombatLogMessage[];
  combatTarget: CombatTarget | null;
  inCombat: boolean;
  inventory: ItemSummary[];
  quickbarSlots: (SkillSummary | null)[];
  petSkills: SkillSummary[];
  onCastSkill: (skillId: string, cooldownMs: number) => void;
  onQuickbarSwap: (fromIndex: number, toIndex: number) => void;
  onQuickbarAssign: (slotIndex: number, skillId: string) => void;
  onQuickbarClear: (slotIndex: number) => void;
  activePopout: PopoutPanel;
  onCommand: (cmd: string) => void;
  onOpenPanel: (panel: PopoutPanel) => void;
  audio: AudioEngine;
  children?: ReactNode;
}

/** Font-size multiplier so longer room names shrink to fit the sign plaque. */
function signFit(title: string): number {
  const n = title.length;
  if (n <= 12) return 1;
  if (n <= 18) return 0.86;
  if (n <= 26) return 0.72;
  if (n <= 36) return 0.6;
  return 0.5;
}

export function GameShell({
  connected,
  hasCharacterProfile,
  vitals,
  room,
  exits,
  serverAssets,
  worldTime,
  worldWeather,
  worldEvents,
  combatLogMessages,
  combatTarget,
  inCombat,
  inventory,
  quickbarSlots,
  petSkills,
  onCastSkill,
  onQuickbarSwap,
  onQuickbarAssign,
  onQuickbarClear,
  activePopout,
  onCommand,
  onOpenPanel,
  audio,
  children,
}: GameShellProps) {
  const loggedIn = connected && hasCharacterProfile;
  const [signZoom, setSignZoom] = useState(false);

  // Dismiss the enlarged sign on Escape.
  useEffect(() => {
    if (!signZoom) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") setSignZoom(false);
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [signZoom]);

  return (
    <main className="game-shell">
      {/* Canvas fills the shell; all HUD elements are overlays on top of it */}
      <div className="game-canvas-layer">
        <PixiCanvas />

        {loggedIn && (
          <>
            {/* HP / MP / Gold — horizontal strip across the top */}
            <CanvasVitalsHud
              vitals={vitals}
              inventory={inventory}
              serverAssets={serverAssets}
              onCommand={onCommand}
              audio={audio}
            />

            {/* Room name — a hand-made wooden sign hung by chains from the branch.
                Skinned: the room_sign_bg art has its own rope + decorations, so we
                just crop it and center the title in the blank plaque. */}
            {room.title !== "-" && serverAssets["room_sign_bg"] && (
              <button
                type="button"
                className="canvas-room-sign canvas-room-sign-skinned"
                onClick={() => setSignZoom(true)}
                title="Enlarge room name"
                aria-label={`Room: ${room.title}. Click to enlarge.`}
              >
                <img className="rsign-art" src={serverAssets["room_sign_bg"]} alt="" aria-hidden="true" />
                <span className="rsign-title" style={{ ["--rsign-fit" as string]: signFit(room.title) }}>{room.title}</span>
              </button>
            )}
            {room.title !== "-" && !serverAssets["room_sign_bg"] && (
              <div className="canvas-room-sign">
                <svg className="sign-chain sign-chain-left" viewBox="0 0 10 20" aria-hidden="true">
                  <ellipse cx="5" cy="5" rx="2.6" ry="4" fill="none" stroke="#6e6450" strokeWidth="1.6" />
                  <ellipse cx="5" cy="13" rx="2.6" ry="4" fill="none" stroke="#6e6450" strokeWidth="1.6" />
                </svg>
                <svg className="sign-chain sign-chain-right" viewBox="0 0 10 20" aria-hidden="true">
                  <ellipse cx="5" cy="5" rx="2.6" ry="4" fill="none" stroke="#6e6450" strokeWidth="1.6" />
                  <ellipse cx="5" cy="13" rx="2.6" ry="4" fill="none" stroke="#6e6450" strokeWidth="1.6" />
                </svg>
                <div className="sign-plank">
                  <h2 className="sign-title">{room.title}</h2>
                  <button
                    type="button"
                    className="sign-expand"
                    aria-label="Expand room details"
                    title="Expand room details"
                    onClick={() => onOpenPanel("room")}
                  >
                    <ExpandRoomIcon className="canvas-room-title-icon" />
                  </button>
                  <svg className="sign-mushroom sign-mushroom-left" viewBox="0 0 20 18" aria-hidden="true">
                    <ellipse cx="10" cy="13" rx="3" ry="4" fill="#efe1bf" />
                    <path d="M2 8 a8 6 0 0 1 16 0 z" fill="#c0392b" />
                    <circle cx="7" cy="6" r="1.4" fill="#f5d9c0" />
                    <circle cx="12.5" cy="5" r="1.1" fill="#f5d9c0" />
                  </svg>
                  <svg className="sign-mushroom sign-mushroom-right" viewBox="0 0 20 18" aria-hidden="true">
                    <ellipse cx="10" cy="13" rx="3" ry="4" fill="#efe1bf" />
                    <path d="M2 8 a8 6 0 0 1 16 0 z" fill="#c0392b" />
                    <circle cx="7" cy="6" r="1.4" fill="#f5d9c0" />
                    <circle cx="12.5" cy="5" r="1.1" fill="#f5d9c0" />
                  </svg>
                </div>
              </div>
            )}

            {/* Combat log — centered above the fight */}
            <CombatLog messages={combatLogMessages} />

            {/* Combat target indicator — top-center */}
            {combatTarget?.targetName && (
              <div className="game-target-hud">
                <span className="game-target-name">{combatTarget.targetName}</span>
                {combatTarget.targetMaxHp != null && combatTarget.targetHp != null && (
                  <div className="game-target-hp-track">
                    <span
                      className="game-target-hp-fill"
                      style={{ width: `${Math.max(0, Math.min(100, (combatTarget.targetHp / combatTarget.targetMaxHp) * 100))}%` }}
                    />
                  </div>
                )}
              </div>
            )}

            {/* World atmosphere — time / weather */}
            <WorldAtmosphereHud
              worldTime={worldTime}
              worldWeather={worldWeather}
              worldEvents={worldEvents}
            />

            {/* Help — small glyph near the time / weather HUD */}
            <button
              type="button"
              className="canvas-help-btn"
              onClick={() => onOpenPanel("help")}
              title="Help"
              aria-label="Help"
            >
              ?
            </button>

            {/* Flee — only during combat */}
            {inCombat && (
              <button
                type="button"
                className="canvas-flee-btn"
                onClick={() => onCommand("flee")}
                title="Flee from combat"
                aria-label="Flee from combat"
              >
                Flee
              </button>
            )}
          </>
        )}
      </div>

      {/* Room description + action dock share one continuous background. */}
      {loggedIn && (
        <div
          className="bottom-stack"
          style={serverAssets["room_panel_bg"] ? { ["--room-bg" as string]: `url("${serverAssets["room_panel_bg"]}")` } : undefined}
        >
          <RoomPanel
            room={room}
            exits={exits}
            serverAssets={serverAssets}
            loggedIn={loggedIn}
            onCommand={onCommand}
          />

          {/* Kiosks (out of combat) / skill bar (in combat). The command input
              now lives in the Terminal overlay. */}
          <div className="action-dock">
            {inCombat ? (
              <SkillBar
                quickbarSlots={quickbarSlots}
                petSkills={petSkills}
                onCastSkill={onCastSkill}
                onQuickbarSwap={onQuickbarSwap}
                onQuickbarAssign={onQuickbarAssign}
                onQuickbarClear={onQuickbarClear}
              />
            ) : (
              <KioskBar
                serverAssets={serverAssets}
                activePopout={activePopout}
                onOpenPanel={onOpenPanel}
              />
            )}
          </div>
        </div>
      )}

      {/* Enlarged, static view of the room sign — click anywhere / Esc to close. */}
      {signZoom && room.title !== "-" && serverAssets["room_sign_bg"] && (
        <div
          className="sign-zoom-backdrop"
          role="dialog"
          aria-modal="true"
          aria-label={`Room: ${room.title}`}
          onClick={() => setSignZoom(false)}
        >
          <div className="sign-zoom">
            <img className="rsign-art" src={serverAssets["room_sign_bg"]} alt="" aria-hidden="true" />
            <span className="rsign-title" style={{ ["--rsign-fit" as string]: signFit(room.title) }}>{room.title}</span>
          </div>
        </div>
      )}

      {children}
    </main>
  );
}

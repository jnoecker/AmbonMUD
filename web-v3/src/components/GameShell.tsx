import type { ReactNode } from "react";
import { PixiCanvas } from "../canvas/PixiCanvas";
import { CombatLog } from "./CombatLog";
import { RoomPanel } from "./RoomPanel";
import { CanvasVitalsHud } from "./CanvasVitalsHud";
import { CanvasKiosks } from "./CanvasKiosks";
import { CommandInput } from "./CommandInput";
import { SkillBar } from "./SkillBar";
import { WorldAtmosphereHud } from "./WorldAtmosphereHud";
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
  inputValue: string;
  onInputChange: (value: string) => void;
  onInputKeyDown?: (event: React.KeyboardEvent<HTMLInputElement>) => void;
  children?: ReactNode;
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
  inputValue,
  onInputChange,
  onInputKeyDown,
  children,
}: GameShellProps) {
  const loggedIn = connected && hasCharacterProfile;

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
              onCommand={onCommand}
              audio={audio}
            />

            {/* Panel kiosks — left column (context services use Pixi room badges) */}
            <CanvasKiosks
              serverAssets={serverAssets}
              activePopout={activePopout}
              onOpenPanel={onOpenPanel}
            />

            {/* Combat log — over the canvas, clear of the kiosks + command input */}
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

            {/* Skill bar — bottom-center */}
            <SkillBar
              quickbarSlots={quickbarSlots}
              petSkills={petSkills}
              onCastSkill={onCastSkill}
              onQuickbarSwap={onQuickbarSwap}
              onQuickbarAssign={onQuickbarAssign}
              onQuickbarClear={onQuickbarClear}
            />
          </>
        )}
      </div>

      {/* Room description panel — below the canvas */}
      <RoomPanel
        room={room}
        exits={exits}
        loggedIn={loggedIn}
        onCommand={onCommand}
        onOpenPanel={onOpenPanel}
      />

      {/* Command input — bottom of the stack, below the room description */}
      {loggedIn && (
        <CommandInput
          inputValue={inputValue}
          onInputChange={onInputChange}
          onInputKeyDown={onInputKeyDown}
          onCommand={onCommand}
        />
      )}

      {children}
    </main>
  );
}

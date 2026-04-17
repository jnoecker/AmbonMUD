import { useState } from "react";
import type { ReactNode } from "react";
import { PixiCanvas } from "../canvas/PixiCanvas";
import { CombatLog } from "./CombatLog";
import { VitalsBar } from "./VitalsBar";
import type { CombatLogMessage, CombatTarget, PopoutPanel, SkillSummary, Vitals } from "../types";
import type { AudioEngine } from "../hooks/useAudioEngine";

interface GameShellProps {
  connected: boolean;
  hasCharacterProfile: boolean;
  vitals: Vitals;
  combatLogMessages: CombatLogMessage[];
  combatTarget: CombatTarget | null;
  quickbarSlots: (SkillSummary | null)[];
  onQuickbarSwap: (fromIndex: number, toIndex: number) => void;
  onQuickbarAssign: (slotIndex: number, skillId: string) => void;
  onQuickbarClear: (slotIndex: number) => void;
  activePopout: PopoutPanel;
  onCommand: (cmd: string) => void;
  onOpenPanel: (panel: PopoutPanel) => void;
  onCastSkill: (skillId: string, cooldownMs: number) => void;
  onReconnect: () => void;
  dungeonActive: boolean;
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
  combatLogMessages,
  combatTarget,
  quickbarSlots,
  onQuickbarSwap,
  onQuickbarAssign,
  onQuickbarClear,
  activePopout,
  onCommand,
  onOpenPanel,
  onCastSkill,
  onReconnect,
  dungeonActive,
  audio,
  inputValue,
  onInputChange,
  onInputKeyDown,
  children,
}: GameShellProps) {
  const loggedIn = connected && hasCharacterProfile;
  const [hudBottomInset, setHudBottomInset] = useState(0);

  return (
    <main
      className="game-shell"
      style={{ ["--game-bottom-inset" as string]: `${hudBottomInset}px` }}
    >
      {/* Full-screen canvas — renders room title, description, entities, minimap/compass */}
      <div className="game-canvas-layer">
        <PixiCanvas />
      </div>

      {loggedIn && (
        <>
          {/* Combat log — bottom-left, above action bar */}
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
        </>
      )}

      {/* Vitals + quick actions — fixed bottom */}
      <VitalsBar
        connected={connected}
        loggedIn={loggedIn}
        vitals={vitals}
        quickbarSlots={quickbarSlots}
        onQuickbarSwap={onQuickbarSwap}
        onQuickbarAssign={onQuickbarAssign}
        onQuickbarClear={onQuickbarClear}
        activePopout={activePopout}
        onOpenPanel={onOpenPanel}
        onCastSkill={onCastSkill}
        onCommand={onCommand}
        onReconnect={onReconnect}
        dungeonActive={dungeonActive}
        audio={audio}
        inputValue={inputValue}
        onInputChange={onInputChange}
        onInputKeyDown={onInputKeyDown}
        onHeightChange={setHudBottomInset}
      />

      {children}
    </main>
  );
}

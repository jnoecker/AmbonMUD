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
  activePopout: PopoutPanel;
  onCommand: (cmd: string) => void;
  onOpenPanel: (panel: PopoutPanel) => void;
  onCastSkill: (skillId: string, cooldownMs: number) => void;
  audio: AudioEngine;
  inputValue: string;
  onInputChange: (value: string) => void;
  showInput: boolean;
  onShowInputChange: (open: boolean) => void;
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
  activePopout,
  onCommand,
  onOpenPanel,
  onCastSkill,
  audio,
  inputValue,
  onInputChange,
  showInput,
  onShowInputChange,
  onInputKeyDown,
  children,
}: GameShellProps) {
  const loggedIn = connected && hasCharacterProfile;

  return (
    <main className="game-shell">
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
        activePopout={activePopout}
        onOpenPanel={onOpenPanel}
        onCastSkill={onCastSkill}
        onCommand={onCommand}
        audio={audio}
        inputValue={inputValue}
        onInputChange={onInputChange}
        showInput={showInput}
        onShowInputChange={onShowInputChange}
        onInputKeyDown={onInputKeyDown}
      />

      {children}
    </main>
  );
}

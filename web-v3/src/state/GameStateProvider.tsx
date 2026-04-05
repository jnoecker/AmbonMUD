import type { ReactNode } from "react";
import { CoreProvider } from "./CoreContext";
import { CombatProvider } from "./CombatContext";
import { InventoryProvider } from "./InventoryContext";
import { SocialProvider } from "./SocialContext";
import { WorldProvider } from "./WorldContext";
import { QuestProvider } from "./QuestContext";
import { UIProvider } from "./UIContext";
import { GameStateBridgeSync } from "./GameStateBridgeSync";

export function GameStateProvider({ children }: { children: ReactNode }) {
  return (
    <CoreProvider>
      <CombatProvider>
        <InventoryProvider>
          <SocialProvider>
            <WorldProvider>
              <QuestProvider>
                <UIProvider>
                  <GameStateBridgeSync />
                  {children}
                </UIProvider>
              </QuestProvider>
            </WorldProvider>
          </SocialProvider>
        </InventoryProvider>
      </CombatProvider>
    </CoreProvider>
  );
}

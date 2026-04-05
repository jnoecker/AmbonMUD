import { gameStateRef } from "../canvas/GameStateBridge";
import { useCoreState } from "./CoreContext";
import { useCombatState } from "./CombatContext";
import { useInventoryState } from "./InventoryContext";
import { useSocialState } from "./SocialContext";
import { useWorldState } from "./WorldContext";
import { useQuestState } from "./QuestContext";

/**
 * Syncs all React context state into gameStateRef for 60fps PixiJS canvas reads.
 *
 * CRITICAL: The write to gameStateRef.current happens during render (not in useEffect)
 * so the canvas always reads frame-perfect state without a one-frame delay.
 */
export function GameStateBridgeSync() {
  const core = useCoreState();
  const combat = useCombatState();
  const inventory = useInventoryState();
  const social = useSocialState();
  const world = useWorldState();
  const quest = useQuestState();

  // Write during render -- not in useEffect -- for frame-perfect canvas reads
  gameStateRef.current = {
    room: world.room,
    vitals: core.vitals,
    mobs: world.mobs,
    players: world.players,
    roomItems: world.roomItems,
    combatTarget: combat.combatTarget,
    inCombat: core.vitals.inCombat,
    effects: combat.effects,
    character: core.character,
    mobInfo: world.mobInfo,
    groupInfo: social.groupInfo,
    dialogue: world.dialogue,
    questsAvailable: quest.questsAvailable,
    shop: inventory.shop,
    craftingNodes: inventory.craftingNodes,
    questTargetRoomIds: new Set(
      quest.quests.flatMap((q) =>
        q.objectives
          .filter((o) => o.current < o.required)
          .flatMap((o) => o.targetRoomIds ?? []),
      ),
    ),
    serverAssets: core.serverAssets,
  };

  return null;
}

export const FACTION_TIERS = [
  { label: "Hated", min: Number.NEGATIVE_INFINITY, short: "Kill-on-sight hostility" },
  { label: "Hostile", min: -1000, short: "Enemies will treat you as a threat" },
  { label: "Unfriendly", min: -500, short: "Expect distrust and limited access" },
  { label: "Neutral", min: -100, short: "No special favor or penalty" },
  { label: "Friendly", min: 100, short: "Recognized ally with improving trust" },
  { label: "Honored", min: 500, short: "Respected champion of the faction" },
  { label: "Revered", min: 1000, short: "Maximum standing and elite favor" },
] as const;

export const FACTION_METADATA: Record<string, { description: string; gainHints: string[] }> = {
  iron_order: {
    description: "A mercenary company occupying the Ruined Fortress and loyal to coin over conscience.",
    gainHints: [
      "Lose standing by killing Iron Order mobs.",
      "Gain standing with their enemies when you strike Iron Order targets.",
    ],
  },
  free_swords: {
    description: "Rebels pushing back against the Iron Order's occupation of the fortress.",
    gainHints: [
      "Lose standing by killing Free Swords fighters.",
      "Gain standing when you oppose Iron Order mobs or quests.",
    ],
  },
  shadowmere_cult: {
    description: "A shadow-worshipping cult spreading corruption through the eastern fens.",
    gainHints: [
      "Lose standing by killing Shadowmere Cult mobs.",
      "Gain standing with their enemies by purging cult forces.",
    ],
  },
  silver_flame: {
    description: "Paladins and zealots sworn to cleanse the Shadowmere blight.",
    gainHints: [
      "Lose standing by attacking Silver Flame defenders.",
      "Gain standing when you fight the Shadowmere Cult.",
    ],
  },
};

export const CURRENCY_METADATA: Record<string, { description: string; gainHints: string[] }> = {
  quest_points: {
    description: "Quest Points track your progress through structured story and contract content.",
    gainHints: [
      "Earned from completing quests.",
      "Watch quest rewards and updates for incoming QP.",
    ],
  },
  honor: {
    description: "Honor reflects your success in player-versus-player combat.",
    gainHints: [
      "Earned from PvP kills.",
      "Useful for tracking your standing in competitive play.",
    ],
  },
  crafting_tokens: {
    description: "Crafting Tokens reward time spent gathering recipes and making gear.",
    gainHints: [
      "Earned from crafting activities.",
      "Craft more items to steadily build your token wallet.",
    ],
  },
};

export type RoomSurfaceWidgetId = "auction" | "dungeon" | "duel" | "lottery" | "dice";

export const ROOM_SURFACE_WIDGETS: Record<
  RoomSurfaceWidgetId,
  {
    label: string;
    assetKey: string;
    fallbackFilename: string;
    roomIds: string[];
  }
> = {
  auction: {
    label: "Auction",
    assetKey: "auction_hall_widget",
    fallbackFilename: "auction_hall_widget.png",
    roomIds: ["thornhaven_city:auction_hall"],
  },
  dungeon: {
    label: "Dungeon",
    assetKey: "dungeon_portal_widget",
    fallbackFilename: "dungeon_portal_widget.png",
    roomIds: ["thornhaven_city:grimlys_study", "dungeon_of_echoes:portal_chamber"],
  },
  duel: {
    label: "Duel",
    assetKey: "duel_arena_widget",
    fallbackFilename: "duel_arena_widget.png",
    roomIds: ["thornhaven_city:arena_district", "thornhaven_city:dueling_grounds"],
  },
  lottery: {
    label: "Lottery",
    assetKey: "lottery_board_widget",
    fallbackFilename: "lottery_board_widget.png",
    roomIds: ["thornhaven_city:tarnished_flagon_inn"],
  },
  dice: {
    label: "Dice",
    assetKey: "dice_table_widget",
    fallbackFilename: "dice_table_widget.png",
    roomIds: ["thornhaven_city:tarnished_flagon_inn"],
  },
};

export function roomHasSurfaceWidget(roomId: string | null, widgetId: RoomSurfaceWidgetId): boolean {
  if (!roomId) return false;
  return ROOM_SURFACE_WIDGETS[widgetId].roomIds.includes(roomId);
}

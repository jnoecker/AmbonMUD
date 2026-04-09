export type AdminAction =
  | "goto"
  | "transfer"
  | "spawn"
  | "smite"
  | "kick"
  | "setlevel"
  | "dispel"
  | "reload"
  | "broadcast"
  | "shutdown"
  | "possess"
  | "return"
  | "invis";

export type AdminActionSectionId =
  | "mobility"
  | "intervention"
  | "world"
  | "presence";

export interface AdminActionDefinition {
  id: AdminAction;
  label: string;
  description: string;
  section: AdminActionSectionId;
  tone: "standard" | "danger" | "utility";
}

export interface AdminActionSection {
  id: AdminActionSectionId;
  kicker: string;
  title: string;
  description: string;
}

export const ADMIN_ACTIONS: AdminActionDefinition[] = [
  { id: "goto", label: "Goto", description: "Teleport to a room or player", section: "mobility", tone: "standard" },
  { id: "transfer", label: "Transfer", description: "Move a player to a room", section: "mobility", tone: "danger" },
  { id: "smite", label: "Smite", description: "Strike down a player or room mob", section: "intervention", tone: "danger" },
  { id: "kick", label: "Kick", description: "Disconnect a live player", section: "intervention", tone: "danger" },
  { id: "setlevel", label: "Set Level", description: "Rewrite a player's progression", section: "intervention", tone: "danger" },
  { id: "dispel", label: "Dispel", description: "Strip active effects from a target", section: "intervention", tone: "standard" },
  { id: "spawn", label: "Spawn", description: "Create a mob from a world template", section: "world", tone: "standard" },
  { id: "reload", label: "Reload", description: "Reload world or rules data live", section: "world", tone: "danger" },
  { id: "broadcast", label: "Broadcast", description: "Send an announcement to every player", section: "world", tone: "danger" },
  { id: "shutdown", label: "Shutdown", description: "Stop the live server", section: "world", tone: "danger" },
  { id: "possess", label: "Possess", description: "Take control of a mob in the room", section: "presence", tone: "utility" },
  { id: "return", label: "Return", description: "Release your possessed mob", section: "presence", tone: "utility" },
  { id: "invis", label: "Invis", description: "Toggle staff invisibility", section: "presence", tone: "utility" },
];

export const ADMIN_ACTION_SECTIONS: AdminActionSection[] = [
  {
    id: "mobility",
    kicker: "Travel and positioning",
    title: "Movement Control",
    description: "Jump to any live player or room, or relocate someone else with destination context.",
  },
  {
    id: "intervention",
    kicker: "Moderation and correction",
    title: "Player Intervention",
    description: "Resolve live problems with clear targets, consequence messaging, and immediate feedback.",
  },
  {
    id: "world",
    kicker: "Live-ops authority",
    title: "World Operations",
    description: "Spawn, reload, announce, or stop the world from one operational surface.",
  },
  {
    id: "presence",
    kicker: "Staff embodiment",
    title: "Presence Tools",
    description: "Move through the world as staff, slip out of sight, or inhabit a creature directly.",
  },
];

export const ADMIN_RELOAD_SCOPES = ["all", "world", "abilities", "effects"] as const;

export function getAdminActionDefinition(action: AdminAction): AdminActionDefinition {
  return ADMIN_ACTIONS.find((entry) => entry.id === action) ?? ADMIN_ACTIONS[0];
}

export function buildAdminCommand(
  action: AdminAction,
  inputA: string,
  inputB: string,
): string | null {
  const primary = inputA.trim();
  const secondary = inputB.trim();

  switch (action) {
    case "goto":
      return primary ? `goto ${primary}` : null;
    case "transfer":
      return primary && secondary ? `transfer ${primary} ${secondary}` : null;
    case "spawn":
      return primary ? `spawn ${primary}` : null;
    case "smite":
      return primary ? `smite ${primary}` : null;
    case "kick":
      return primary ? `kick ${primary}` : null;
    case "setlevel":
      return primary && secondary ? `setlevel ${primary} ${secondary}` : null;
    case "dispel":
      return primary ? `dispel ${primary}` : null;
    case "reload":
      return primary && primary.toLowerCase() !== "all" ? `reload ${primary}` : "reload";
    case "broadcast":
      return primary ? `broadcast ${primary}` : null;
    case "shutdown":
      return "shutdown";
    case "possess":
      return primary ? `possess ${primary}` : null;
    case "return":
      return "return";
    case "invis":
      return "invis";
  }
}

export function requiresAdminConfirmation(action: AdminAction): boolean {
  return (
    action === "transfer" ||
    action === "smite" ||
    action === "kick" ||
    action === "setlevel" ||
    action === "reload" ||
    action === "broadcast" ||
    action === "shutdown"
  );
}

export function getAdminConfirmationCopy(
  action: AdminAction,
  targetSummary: string | null,
): string {
  switch (action) {
    case "transfer":
      return `Move ${targetSummary ?? "the selected player"} immediately. They will receive a forced relocation and a fresh room view.`;
    case "smite":
      return `Strike ${targetSummary ?? "the selected target"} immediately. Players are dropped to 1 HP at the start room; room mobs are killed outright.`;
    case "kick":
      return `Disconnect ${targetSummary ?? "the selected player"} immediately from the live server.`;
    case "setlevel":
      return `Rewrite progression for ${targetSummary ?? "the selected player"} immediately. This takes effect live.`;
    case "reload":
      return `Reload live data without restarting. Invalid scopes will be rejected by the engine, but successful reloads change the running world immediately.`;
    case "broadcast":
      return `Announce this message to every connected player across active engines right away.`;
    case "shutdown":
      return "Stop the live server for everyone. All connected players will receive the shutdown announcement and be disconnected.";
    default:
      return getAdminActionDefinition(action).description;
  }
}

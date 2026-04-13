export type AdminAction =
  | "goto"
  | "transfer"
  | "spawn"
  | "smite"
  | "kick"
  | "setlevel"
  | "setstaff"
  | "setgold"
  | "setrace"
  | "setclass"
  | "setgender"
  | "setxp"
  | "heal"
  | "pinfo"
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
  title: string;
}

export const ADMIN_ACTIONS: AdminActionDefinition[] = [
  { id: "goto", label: "Goto", description: "Teleport to a room or player", section: "mobility", tone: "standard" },
  { id: "transfer", label: "Transfer", description: "Move a player to a room", section: "mobility", tone: "danger" },
  { id: "smite", label: "Smite", description: "Strike down a player or room mob", section: "intervention", tone: "danger" },
  { id: "kick", label: "Kick", description: "Disconnect a live player", section: "intervention", tone: "danger" },
  { id: "setlevel", label: "Set Level", description: "Rewrite a player's progression", section: "intervention", tone: "danger" },
  { id: "setstaff", label: "Set Staff", description: "Grant or revoke staff on an online player", section: "intervention", tone: "danger" },
  { id: "setgold", label: "Set Gold", description: "Set a player's gold amount", section: "intervention", tone: "danger" },
  { id: "setrace", label: "Set Race", description: "Change a player's race", section: "intervention", tone: "danger" },
  { id: "setclass", label: "Set Class", description: "Change a player's class", section: "intervention", tone: "danger" },
  { id: "setgender", label: "Set Gender", description: "Change a player's gender", section: "intervention", tone: "danger" },
  { id: "setxp", label: "Set XP", description: "Set a player's experience points", section: "intervention", tone: "danger" },
  { id: "heal", label: "Heal", description: "Fully restore a player's HP and mana", section: "intervention", tone: "standard" },
  { id: "pinfo", label: "Player Info", description: "Inspect detailed player stats", section: "intervention", tone: "standard" },
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
  { id: "mobility", title: "Movement" },
  { id: "intervention", title: "Intervention" },
  { id: "world", title: "World Ops" },
  { id: "presence", title: "Presence" },
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
    case "setstaff":
      if (!primary) return null;
      return secondary.toLowerCase() === "revoke" ? `revokestaff ${primary}` : `setstaff ${primary}`;
    case "setgold":
      return primary && secondary ? `setgold ${primary} ${secondary}` : null;
    case "setrace":
      return primary && secondary ? `setrace ${primary} ${secondary}` : null;
    case "setclass":
      return primary && secondary ? `setclass ${primary} ${secondary}` : null;
    case "setgender":
      return primary && secondary ? `setgender ${primary} ${secondary}` : null;
    case "setxp":
      return primary && secondary ? `setxp ${primary} ${secondary}` : null;
    case "heal":
      return primary ? `heal ${primary}` : "heal";
    case "pinfo":
      return primary ? `pinfo ${primary}` : null;
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
    action === "setstaff" ||
    action === "setgold" ||
    action === "setrace" ||
    action === "setclass" ||
    action === "setgender" ||
    action === "setxp" ||
    action === "reload" ||
    action === "broadcast" ||
    action === "shutdown"
  );
}


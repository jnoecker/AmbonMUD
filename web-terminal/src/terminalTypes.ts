export type MobileTab = "play" | "world" | "chat" | "character";

export const TABS: Array<{ id: MobileTab; label: string }> = [
  { id: "play", label: "Play" },
  { id: "world", label: "World" },
  { id: "chat", label: "Social" },
  { id: "character", label: "Character" },
];

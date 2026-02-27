import { EXIT_ORDER } from "./constants";

export function safeNumber(value: unknown, fallback = 0): number {
  return typeof value === "number" && Number.isFinite(value) ? value : fallback;
}

export function percent(current: number, max: number): number {
  if (max <= 0) return 0;
  return Math.max(0, Math.min(100, Math.round((current / max) * 100)));
}

export function titleCaseWords(value: string): string {
  return value
    .split(/[\s_-]+/)
    .filter((part) => part.length > 0)
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1).toLowerCase())
    .join(" ");
}

export function sortExits(exits: Record<string, string>): Array<[string, string]> {
  return Object.entries(exits).sort(([left], [right]) => {
    const li = EXIT_ORDER.indexOf(left);
    const ri = EXIT_ORDER.indexOf(right);
    if (li === -1 && ri === -1) return left.localeCompare(right);
    if (li === -1) return 1;
    if (ri === -1) return -1;
    return li - ri;
  });
}

export function parseGmcp(text: string): { pkg: string; data: unknown } | null {
  if (!text.trimStart().startsWith("{")) return null;
  try {
    const parsed = JSON.parse(text) as { gmcp?: unknown; data?: unknown };
    if (typeof parsed.gmcp !== "string" || parsed.gmcp.length === 0) return null;
    return { pkg: parsed.gmcp, data: parsed.data ?? {} };
  } catch {
    return null;
  }
}


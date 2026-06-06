import type { ReactNode } from "react";

/**
 * Renders auto-peek entries as a sentence matching the server's telnet line, e.g.
 * "You see The Old Mill to the north, The Riverbank to the south, and The Attic above you."
 * Room names are highlighted (dusty rose) so they stand out from the connective prose.
 */
export function PeekSentence({ peek }: { peek: Array<{ direction: string; title: string }> }) {
  const parts: ReactNode[] = [];
  peek.forEach((p, i) => {
    if (i > 0) {
      parts.push(i === peek.length - 1 ? (peek.length === 2 ? " and " : ", and ") : ", ");
    }
    const where = p.direction === "up" ? "above you" : p.direction === "down" ? "below you" : `to the ${p.direction}`;
    parts.push(
      <span key={p.direction} className="peek-room-name">{p.title}</span>,
      ` ${where}`,
    );
  });
  return <>You see {parts}.</>;
}

import { useEffect, useState } from "react";
import type { ArcanumStatus, RoomState } from "../../types";

interface Props {
  status: ArcanumStatus | null;
  room: RoomState;
  /** The player's current gold, for the renounce affordability check. */
  gold: number;
  connected: boolean;
  onCommand: (cmd: string) => void;
}

/**
 * The Akathavae shrine — pledge and renounce without touching the terminal.
 *
 * Opens from its kiosk anywhere, but the vow itself can only be spoken or
 * unsaid at a shrine room (`Room.Info` `shrine` flag), so the action buttons
 * gate on the player's location. Costs and the re-pledge cooldown come from
 * `Arcanum.Status` (`renounceCostGold`, `repledgeAvailableAtMs`), so the
 * panel always shows the server's real numbers. Renouncing is a deliberate
 * two-step: the first click arms an inline confirm that names the price.
 */
export function ShrinePanel({ status, room, gold, connected, onCommand }: Props) {
  const pledged = status?.pledged ?? false;

  // The armed confirm remembers where it was armed: moving rooms or flipping
  // pledge state changes the key, which disarms it without any effect.
  const confirmKey = `${room.id}|${pledged}`;
  const [armedConfirmKey, setArmedConfirmKey] = useState<string | null>(null);
  const confirmingRenounce = armedConfirmKey === confirmKey;
  const setConfirmingRenounce = (on: boolean) => setArmedConfirmKey(on ? confirmKey : null);

  const atShrine = room.shrine === true;
  const renounceCost = status?.renounceCostGold ?? 0;
  const repledgeAt = status?.repledgeAvailableAtMs ?? 0;

  // Wall-clock lives in state (render must stay pure); a slow ticker keeps the
  // re-pledge countdown honest while the panel sits open.
  const [nowMs, setNowMs] = useState(() => Date.now());
  useEffect(() => {
    if (repledgeAt === 0) return;
    const timer = setInterval(() => setNowMs(Date.now()), 60_000);
    return () => clearInterval(timer);
  }, [repledgeAt]);

  const repledgeWaitMs = Math.max(0, repledgeAt - nowMs);
  const repledgeHours = Math.ceil(repledgeWaitMs / 3_600_000);
  const canAffordRenounce = gold >= renounceCost;

  const seekShrine = (
    <p className="shrine-hint">
      Only at an Akathavae shrine can the vow be spoken or unsaid. Look for the ✨ mark on your map.
    </p>
  );

  return (
    <div className="shrine-panel">
      {pledged ? (
        <>
          <p className="shrine-state shrine-state-pledged">
            You are an Akathavae — a keeper of the Arcanum. Your pledge stays your hand; the world is your subject.
          </p>
          <ul className="shrine-terms">
            <li>Progression flows from illumination and discovery, never violence.</li>
            <li>Your Arcanum seals world-firsts at the shrines — "First illuminated by" is yours to claim.</li>
            <li>Renouncing costs {renounceCost.toLocaleString()} gold and the Akathavae will not hear a new pledge for a time.</li>
          </ul>
          {!atShrine && seekShrine}
          {atShrine && !confirmingRenounce && (
            <button
              className="shrine-btn shrine-btn-renounce"
              disabled={!connected}
              onClick={() => setConfirmingRenounce(true)}
            >
              Renounce the Vow…
            </button>
          )}
          {atShrine && confirmingRenounce && (
            <div className="shrine-confirm">
              <p className="shrine-warning">
                Lay {renounceCost.toLocaleString()} gold upon the shrine and unsay your vow? Your Arcanum is kept —
                but it earns nothing while you bear arms, and the Akathavae will not accept a new pledge for a time.
              </p>
              {!canAffordRenounce && (
                <p className="shrine-warning shrine-warning-gold">
                  You carry {gold.toLocaleString()} gold — the offering demands {renounceCost.toLocaleString()}.
                </p>
              )}
              <div className="shrine-confirm-row">
                <button
                  className="shrine-btn shrine-btn-renounce"
                  disabled={!connected || !canAffordRenounce}
                  onClick={() => { onCommand("renounce confirm"); setConfirmingRenounce(false); }}
                >
                  Pay {renounceCost.toLocaleString()} gold &amp; renounce
                </button>
                <button className="shrine-btn shrine-btn-ghost" onClick={() => setConfirmingRenounce(false)}>
                  Keep the vow
                </button>
              </div>
            </div>
          )}
        </>
      ) : (
        <>
          <p className="shrine-state">
            The Akathavae are chroniclers sworn to peace: they set violence aside and level by illuminating the
            world — creatures, places, and things — into their Arcanum.
          </p>
          <ul className="shrine-terms">
            <li>Pledging is free, but combat is forbidden while the vow holds.</li>
            <li>Your class is set aside and restored when you renounce.</li>
            <li>Full illumination odds, passive discovery, the wardrobe, and shrine-sealed world-firsts.</li>
          </ul>
          {repledgeWaitMs > 0 ? (
            <p className="shrine-hint">
              The Arcanum remembers your renunciation. The Akathavae will hear your pledge again in about{" "}
              {repledgeHours} hour{repledgeHours === 1 ? "" : "s"}.
            </p>
          ) : atShrine ? (
            <button
              className="shrine-btn shrine-btn-pledge"
              disabled={!connected}
              onClick={() => onCommand("pledge")}
            >
              Take the Pledge of the Akathavae
            </button>
          ) : (
            seekShrine
          )}
        </>
      )}
    </div>
  );
}

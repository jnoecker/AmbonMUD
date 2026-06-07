import { useEffect, useMemo, useRef, useState } from "react";
import type { DiceGambleResult, LotteryInfo, UiFeedbackEntry } from "../../types";

interface DicePanelProps {
  diceResult: DiceGambleResult | null;
  lotteryInfo: LotteryInfo | null;
  uiFeedbackFeed: UiFeedbackEntry[];
  gold: number;
  onCommand: (command: string) => void;
}

/** How long the die tumbles before settling on the real roll. */
const ROLL_DURATION_MS = 1100;
/** Number-shuffle cadence while tumbling. */
const ROLL_TICK_MS = 70;

type DiePhase = "idle" | "rolling" | "won" | "lost";

export function DicePanel({ diceResult, lotteryInfo, uiFeedbackFeed, gold, onCommand }: DicePanelProps) {
  const minBet = lotteryInfo?.diceMinBet ?? 10;
  const maxBet = lotteryInfo?.diceMaxBet ?? 1000;
  const multiplier = lotteryInfo?.diceWinMultiplier ?? 2;
  const threshold = lotteryInfo?.diceWinThreshold ?? 45;

  const [betDraft, setBetDraft] = useState(`${minBet}`);
  const [phase, setPhase] = useState<DiePhase>("idle");
  const [displayRoll, setDisplayRoll] = useState<number | null>(null);
  const [settled, setSettled] = useState<DiceGambleResult | null>(null);
  const [cooldownLeft, setCooldownLeft] = useState(0);
  const [animatedSeq, setAnimatedSeq] = useState(0);
  const cooldownUntilRef = useRef(0);

  const activeFeedback = useMemo(
    () => [...uiFeedbackFeed].reverse().find((e) => e.scope === "dice") ?? null,
    [uiFeedbackFeed],
  );

  // A fresh result arrived: kick the die into its tumble (state adjusted
  // during render, per the React "you might not need an effect" pattern).
  if (diceResult && diceResult.seq !== animatedSeq) {
    setAnimatedSeq(diceResult.seq);
    setPhase("rolling");
    setSettled(null);
  }

  // While tumbling, shuffle the shown number, then settle on the real roll.
  useEffect(() => {
    if (phase !== "rolling" || !diceResult) return;

    const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    const settle = () => {
      setDisplayRoll(diceResult.roll);
      setSettled(diceResult);
      setPhase(diceResult.outcome === "win" ? "won" : "lost");
      cooldownUntilRef.current = Date.now() + diceResult.cooldownMs;
      setCooldownLeft(diceResult.cooldownMs);
    };

    const shuffle = reducedMotion
      ? null
      : window.setInterval(() => {
          setDisplayRoll(1 + Math.floor(Math.random() * 100));
        }, ROLL_TICK_MS);
    const stop = window.setTimeout(settle, reducedMotion ? 0 : ROLL_DURATION_MS);
    return () => {
      if (shuffle !== null) window.clearInterval(shuffle);
      window.clearTimeout(stop);
    };
  }, [phase, diceResult]);

  // Tick the post-roll cooldown down so the Roll button re-enables itself.
  useEffect(() => {
    if (cooldownLeft <= 0) return;
    const timer = window.setInterval(() => {
      setCooldownLeft(Math.max(0, cooldownUntilRef.current - Date.now()));
    }, 250);
    return () => window.clearInterval(timer);
  }, [cooldownLeft]);

  const rolling = phase === "rolling";
  const coolingDown = cooldownLeft > 0;

  const placeBet = (amount: number) => {
    if (rolling) return;
    const bet = Math.min(maxBet, Math.max(minBet, Math.floor(amount)));
    setBetDraft(`${bet}`);
    onCommand(`gamble ${bet}`);
  };

  const quickBets = useMemo(() => {
    const steps = [minBet, minBet * 5, minBet * 10, maxBet];
    return [...new Set(steps.filter((b) => b >= minBet && b <= maxBet))];
  }, [minBet, maxBet]);

  return (
    <div className="dice-panel">
      <div className="panel-header">
        <span className="panel-title">Dice Table</span>
      </div>

      {activeFeedback && (
        <p className={`systems-local-message systems-local-message-${activeFeedback.type}`}>
          {activeFeedback.message}
        </p>
      )}

      <article className="systems-card">
        <div className="systems-card-header">
          <div>
            <p className="systems-card-label">High-Stakes d100</p>
            <h4>Roll {threshold} or less to win {multiplier}× your bet</h4>
          </div>
          <span className="systems-pill">{gold.toLocaleString()} gold</span>
        </div>

        <div className="dice-stage">
          <div className={`dice-die dice-die-${phase}`} aria-hidden="true">
            {displayRoll !== null ? (
              <span className="dice-die-number">{displayRoll}</span>
            ) : (
              <span className="dice-die-mystery">?</span>
            )}
            <span className="dice-die-pip dice-die-pip-tl" />
            <span className="dice-die-pip dice-die-pip-tr" />
            <span className="dice-die-pip dice-die-pip-bl" />
            <span className="dice-die-pip dice-die-pip-br" />
          </div>
          <p className="dice-outcome" aria-live="polite">
            {rolling && "The die clatters across the table…"}
            {(phase === "won" || phase === "lost") && settled && (
              <span className="sr-only">Rolled {settled.roll}, needed {settled.needed} or less. </span>
            )}
            {phase === "won" && settled && (
              <span className="dice-outcome-win">
                You win {settled.payout.toLocaleString()} gold! (net +{(settled.payout - settled.bet).toLocaleString()})
              </span>
            )}
            {phase === "lost" && settled && (
              <span className="dice-outcome-lose">
                Lost {settled.bet.toLocaleString()} gold. The house grins.
              </span>
            )}
            {phase === "idle" && `Place a bet between ${minBet.toLocaleString()} and ${maxBet.toLocaleString()} gold.`}
          </p>
        </div>

        <div className="systems-choice-list systems-choice-list-compact">
          {quickBets.map((amount) => (
            <button
              key={amount}
              type="button"
              className="systems-choice-card"
              disabled={rolling || coolingDown}
              onClick={() => placeBet(amount)}
            >
              <span className="systems-choice-title">{amount.toLocaleString()} gold</span>
              <span className="systems-choice-copy">wins {Math.floor(amount * multiplier).toLocaleString()}</span>
            </button>
          ))}
        </div>

        <div className="systems-action-row">
          <input
            type="number"
            min={minBet}
            max={maxBet}
            step={1}
            inputMode="numeric"
            className="systems-number-input"
            value={betDraft}
            onChange={(event) => setBetDraft(event.target.value)}
          />
          <button
            type="button"
            className="systems-primary-btn"
            disabled={rolling || coolingDown}
            onClick={() => {
              const bet = Number(betDraft);
              if (!Number.isSafeInteger(bet) || bet < 1) return;
              placeBet(bet);
            }}
          >
            {rolling
              ? "Rolling…"
              : coolingDown
                ? `Catch your breath (${Math.ceil(cooldownLeft / 1000)}s)`
                : "Roll the Dice"}
          </button>
        </div>
      </article>
    </div>
  );
}

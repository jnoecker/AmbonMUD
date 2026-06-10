import { useEffect, useMemo, useRef, useState } from "react";
import type { CSSProperties } from "react";
import type { DiceGambleResult, DiceRoll, LotteryInfo, UiFeedbackEntry } from "../../types";

interface DicePanelProps {
  diceResult: DiceGambleResult | null;
  lotteryInfo: LotteryInfo | null;
  uiFeedbackFeed: UiFeedbackEntry[];
  gold: number;
  /** Resolved global-asset URLs (dice sprites, max faces, coin sides). */
  assets: Record<string, string>;
  onCommand: (command: string) => void;
}

/**
 * Per-die cadence. Each child wobbles in the velvet — its face shuffling
 * through random numbers — then LANDS on its real value and holds, drops into
 * the green felt, and the velvet sits empty for a beat so the player can take
 * in the new running total before the next child rolls. Six dice ≈ 14s.
 */
const WOBBLE_MS = 1200; // shuffling/wobbling before it lands
const LAND_HOLD_MS = 700; // landed value held in the velvet before it drops
const GAP_MS = 500; // empty-velvet pause to register the new total
const DROP_AT_MS = WOBBLE_MS + LAND_HOLD_MS; // when the die falls to the tray
const PER_DIE_MS = DROP_AT_MS + GAP_MS; // full slot, next die starts after this
const WOBBLE_TICK_MS = 90; // how fast the shuffling face cycles
const COIN_FLIP_MS = 2000;

type Phase = "idle" | "rolling" | "done";
type CoinState = "none" | "flipping" | "won" | "lost";

/** Display name for one of Aineroia's children. */
function childName(kind: string): string {
  return kind ? kind.charAt(0).toUpperCase() + kind.slice(1) : "";
}

/** Large d20, medium d16, small d8 — a CSS hint that mirrors the roll order. */
function sizeClass(sides: number): string {
  if (sides >= 20) return "dice-die-lg";
  if (sides >= 16) return "dice-die-md";
  return "dice-die-sm";
}

function formatGold(n: number): string {
  return Math.round(n).toLocaleString();
}

/** Stable pseudo-random in [0,1) so firework bursts vary but never re-shuffle on re-render. */
function jitter(i: number, salt: number): number {
  const x = Math.sin((i + 1) * 12.9898 + salt * 78.233) * 43758.5453;
  return x - Math.floor(x);
}

/** Headline for the win toast, by payout tier. */
function celebrateTitle(tier: string): string {
  if (tier === "jackpot") return "LUNEQRAE'S BLESSING!";
  if (tier === "coin") return "MOONLIT FORTUNE!";
  return "YOU WIN!";
}

/**
 * Renders one die: the Arcanum sprite (and illustrated max face on a crowned
 * roll) when the art has shipped, otherwise a themed CSS render. The value and
 * d-size tag overlay either way.
 */
function Die({
  die,
  extraClass,
  assets,
  displayValue,
  suppressMax,
}: {
  die: DiceRoll;
  extraClass: string;
  assets: Record<string, string>;
  /** Override the shown number (the shuffling face while wobbling). */
  displayValue?: number;
  /** Hold back the crowned max face/glow until the die has landed. */
  suppressMax?: boolean;
}) {
  const showMax = die.isMax && !suppressMax;
  const sprite = assets[`dice_${die.kind}`];
  const maxArt = assets[`dice_${die.kind}_max`];
  const faceArt = showMax ? (maxArt ?? sprite) : sprite;
  const value = displayValue ?? die.value;
  const className =
    `dice-die dice-die-${die.kind} ${sizeClass(die.sides)} ${extraClass}` +
    (showMax ? " dice-die-max" : "") +
    (faceArt ? " dice-die-art" : "");
  return (
    <div
      className={className}
      title={`${childName(die.kind)} (d${die.sides}): ${die.value}${die.isMax ? " — max!" : ""}`}
      style={faceArt ? { backgroundImage: `url("${faceArt}")` } : undefined}
    >
      <span className="dice-die-value">{value}</span>
      <span className="dice-die-tag">d{die.sides}</span>
    </div>
  );
}

/**
 * Aineroia's Dice — reskinned onto the painted `dice_bg` frame. The six
 * children tumble big → small in the purple velvet, settling one by one into
 * the green felt as the running total climbs; the Luneqrae coin flips last when
 * three or more crown their max face. Values, the bet controls, and the result
 * banner overlay their painted slots.
 */
export function DicePanel({ diceResult, lotteryInfo, uiFeedbackFeed, gold, assets, onCommand }: DicePanelProps) {
  const minBet = lotteryInfo?.diceMinBet ?? 10;
  const maxBet = lotteryInfo?.diceMaxBet ?? 1000;
  const winMult = lotteryInfo?.diceWinMultiplier ?? 2;
  const target = lotteryInfo?.diceWinTarget ?? 45;
  const coinMult = lotteryInfo?.coinWinMultiplier ?? 10;
  const jackpotMult = lotteryInfo?.coinJackpotMultiplier ?? 12;

  const [betDraft, setBetDraft] = useState(`${minBet}`);
  const [phase, setPhase] = useState<Phase>("idle");
  const [animatedSeq, setAnimatedSeq] = useState(0);
  const [activeDie, setActiveDie] = useState<DiceRoll | null>(null);
  const [activeLanded, setActiveLanded] = useState(false);
  const [wobbleValue, setWobbleValue] = useState(1);
  const [settled, setSettled] = useState<DiceRoll[]>([]);
  const [runningTotal, setRunningTotal] = useState(0);
  const [coinState, setCoinState] = useState<CoinState>("none");
  const [finalResult, setFinalResult] = useState<DiceGambleResult | null>(null);
  const [celebrate, setCelebrate] = useState<{ seq: number; tier: string; payout: number } | null>(null);
  const [cooldownLeft, setCooldownLeft] = useState(0);
  const cooldownUntilRef = useRef(0);

  const activeFeedback = useMemo(
    () => [...uiFeedbackFeed].reverse().find((e) => e.scope === "dice") ?? null,
    [uiFeedbackFeed],
  );

  // A fresh result arrived: reset and kick off the tumble (state set during
  // render, per the React "you might not need an effect" pattern).
  if (diceResult && diceResult.seq !== animatedSeq) {
    setAnimatedSeq(diceResult.seq);
    setPhase("rolling");
    setActiveDie(null);
    setActiveLanded(false);
    setSettled([]);
    setRunningTotal(0);
    setCoinState("none");
    setFinalResult(null);
    setCelebrate(null);
  }

  // Drive the sequenced reveal: each die tumbles in the velvet, then drops into
  // the green tray as the total grows; the coin flips last if it fired.
  useEffect(() => {
    if (phase !== "rolling" || !diceResult) return;
    const reduced = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    const timers: number[] = [];
    const at = (delay: number, fn: () => void) => {
      timers.push(window.setTimeout(fn, delay));
    };
    const finish = (r: DiceGambleResult) => {
      setPhase("done");
      setFinalResult(r);
      cooldownUntilRef.current = Date.now() + r.cooldownMs;
      setCooldownLeft(r.cooldownMs);
      if (r.payout > 0) setCelebrate({ seq: r.seq, tier: r.outcome, payout: r.payout });
    };

    if (reduced) {
      at(0, () => {
        setSettled(diceResult.dice);
        setRunningTotal(diceResult.sum);
        setCoinState(diceResult.coinFired ? (diceResult.coinWon ? "won" : "lost") : "none");
        finish(diceResult);
      });
      return () => timers.forEach((t) => window.clearTimeout(t));
    }

    diceResult.dice.forEach((die, i) => {
      const base = i * PER_DIE_MS;
      // Appear and start wobbling (face shuffles via the interval below).
      at(base, () => {
        setActiveDie(die);
        setActiveLanded(false);
        setWobbleValue(1 + Math.floor(Math.random() * die.sides));
      });
      // Land on the real value (and reveal the crowned max face if any).
      at(base + WOBBLE_MS, () => setActiveLanded(true));
      // Drop into the green tray; the running total ticks up. The velvet then
      // sits empty for GAP_MS before the next child appears.
      at(base + DROP_AT_MS, () => {
        setActiveDie(null);
        setSettled((prev) => [...prev, die]);
        setRunningTotal((prev) => prev + die.value);
      });
    });

    const afterDice = diceResult.dice.length * PER_DIE_MS;
    if (diceResult.coinFired) {
      at(afterDice + 400, () => setCoinState("flipping"));
      at(afterDice + 400 + COIN_FLIP_MS, () => setCoinState(diceResult.coinWon ? "won" : "lost"));
      at(afterDice + 400 + COIN_FLIP_MS + 900, () => finish(diceResult));
    } else {
      at(afterDice + 500, () => finish(diceResult));
    }

    return () => timers.forEach((t) => window.clearTimeout(t));
  }, [phase, diceResult]);

  // While a die wobbles, cycle its shown face through random numbers so it
  // looks like it's tumbling to a stop. Stops the moment it lands.
  useEffect(() => {
    if (!activeDie || activeLanded) return;
    if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) return;
    const sides = activeDie.sides;
    const id = window.setInterval(() => {
      setWobbleValue(1 + Math.floor(Math.random() * sides));
    }, WOBBLE_TICK_MS);
    return () => window.clearInterval(id);
  }, [activeDie, activeLanded]);

  // Clear the win celebration once its burst has played out.
  useEffect(() => {
    if (!celebrate) return;
    const t = window.setTimeout(() => setCelebrate(null), 2800);
    return () => window.clearTimeout(t);
  }, [celebrate]);

  // Firework spark vectors — deterministic per win so they don't reshuffle on
  // re-render; richer bursts for the coin/jackpot tiers.
  const sparks = useMemo<CSSProperties[]>(() => {
    if (!celebrate) return [];
    const n = celebrate.tier === "jackpot" ? 30 : celebrate.tier === "coin" ? 24 : 16;
    const palette =
      celebrate.tier === "jackpot"
        ? [48, 0, 130, 280, 200, 340]
        : celebrate.tier === "coin"
          ? [205, 230, 280, 48]
          : [120, 90, 48, 160];
    return Array.from({ length: n }, (_, i) => {
      const angle = ((i / n) * 360 + jitter(i, 1) * 44) * (Math.PI / 180);
      const dist = 56 + jitter(i, 2) * 130;
      return {
        ["--tx" as string]: `${Math.cos(angle) * dist}px`,
        ["--ty" as string]: `${Math.sin(angle) * dist}px`,
        ["--hue" as string]: `${palette[i % palette.length]}`,
        animationDelay: `${jitter(i, 3) * 0.5}s`,
      } as CSSProperties;
    });
  }, [celebrate]);

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
  const bet = Math.min(maxBet, Math.max(minBet, Math.floor(Number(betDraft) || minBet)));

  const clampBet = (n: number) => Math.max(minBet, Math.min(maxBet, Math.floor(n)));
  const nudge = (delta: number) => setBetDraft(`${clampBet(bet + delta)}`);

  const quickBets = useMemo(() => {
    const candidates = [minBet, 50, 100, 250, 500, 1000, 5000, maxBet];
    return [...new Set(candidates.filter((b) => b >= minBet && b <= maxBet))].slice(0, 6);
  }, [minBet, maxBet]);

  const roll = () => {
    if (rolling || coolingDown) return;
    const b = clampBet(bet);
    setBetDraft(`${b}`);
    onCommand(`gamble ${b}`);
  };

  const totalOver = runningTotal > target;
  const showTotal = rolling || phase === "done";

  // Outcome banner copy for the green felt band.
  const banner = (() => {
    if (rolling) return { cls: "rolling", text: "The children tumble across the velvet…" };
    if (phase === "done" && finalResult) {
      const net = finalResult.payout - finalResult.bet;
      switch (finalResult.outcome) {
        case "jackpot":
          return { cls: "jackpot", text: `LUNEQRAE'S BLESSING — ${jackpotMult}× — you win ${formatGold(finalResult.payout)} gold! (+${formatGold(net)})` };
        case "coin":
          return { cls: "coin", text: `The coin rescues you — ${coinMult}× — ${formatGold(finalResult.payout)} gold! (+${formatGold(net)})` };
        case "win":
          return { cls: "win", text: `You WIN — ${formatGold(finalResult.payout)} gold! (+${formatGold(net)})` };
        default:
          return { cls: "lose", text: `Busted at ${finalResult.sum}. The house keeps your ${formatGold(finalResult.bet)} gold.` };
      }
    }
    return { cls: "idle", text: `Sum ${target} or less wins ${winMult}× your bet.` };
  })();

  return (
    <div className="dice-board">
      {/* Your Gold */}
      <div className="dice-gold">{formatGold(gold)}</div>

      {/* How to Play */}
      <div className="dice-howto">
        <p>Six dice — the children of Aineroia — roll big to small.</p>
        <p>Sum <strong>{target}</strong> or less and win <strong>{winMult}×</strong> your bet.</p>
        <p>Land <strong>3+</strong> max faces and the Luneqrae coin flips: <strong>{coinMult}×</strong> on a bust, <strong>{jackpotMult}×</strong> if your sum also held.</p>
      </div>

      {/* Stat slots */}
      <div className="dice-stat dice-stat-target">{target}<span className="dice-stat-sub">or less</span></div>
      <div className="dice-stat dice-stat-payout">{formatGold(bet * winMult)}</div>
      <div className="dice-stat dice-stat-bet">{formatGold(bet)}</div>

      {/* Velvet roll stage */}
      <div className="dice-velvet" role="status" aria-live="polite">
        {activeDie && (
          <Die
            die={activeDie}
            extraClass={activeLanded ? "dice-die-landing" : "dice-die-wobbling"}
            assets={assets}
            displayValue={activeLanded ? undefined : wobbleValue}
            suppressMax={!activeLanded}
          />
        )}
        {coinState !== "none" && (() => {
          // Moon side = the Luneqrae's blessing (win); dark-wind side = the loss.
          const coinArt = coinState === "lost" ? assets["coin_luneqrae_wind"] : assets["coin_luneqrae_moon"];
          return (
            <div
              className={`dice-coin dice-coin-${coinState}${coinArt ? " dice-coin-art" : ""}`}
              style={coinArt ? { backgroundImage: `url("${coinArt}")` } : undefined}
            >
              {!coinArt && (
                <span className="dice-coin-face">{coinState === "lost" ? "✦" : "☾"}</span>
              )}
            </div>
          );
        })()}
        {!activeDie && coinState === "none" && !rolling && phase !== "done" && (
          <p className="dice-velvet-hint">Place your bet and roll the dice.</p>
        )}
      </div>
      {showTotal && (
        <div className={`dice-total${totalOver ? " dice-total-over" : ""}`}>{runningTotal}</div>
      )}

      {/* Place your bet (parchment) */}
      <div className="dice-bet">
        <div className="dice-bet-title">Place Your Bet</div>
        <input
          type="number"
          min={minBet}
          max={maxBet}
          step={1}
          inputMode="numeric"
          className="dice-bet-input"
          value={betDraft}
          onChange={(e) => setBetDraft(e.target.value)}
          aria-label="Bet amount"
        />
        <div className="dice-bet-steppers">
          <button type="button" onClick={() => nudge(-10)} aria-label="Minus ten">−10</button>
          <button type="button" onClick={() => nudge(-1)} aria-label="Minus one">−1</button>
          <button type="button" onClick={() => nudge(1)} aria-label="Plus one">+1</button>
          <button type="button" onClick={() => nudge(10)} aria-label="Plus ten">+10</button>
        </div>
        <div className="dice-bet-quick">
          {quickBets.map((amount) => (
            <button
              key={amount}
              type="button"
              className={`dice-bet-chip${bet === amount ? " is-active" : ""}`}
              aria-pressed={bet === amount}
              aria-label={`Bet ${amount.toLocaleString()} gold`}
              onClick={() => setBetDraft(`${amount}`)}
            >
              {amount >= 1000 ? `${amount / 1000}K` : amount}
            </button>
          ))}
        </div>
        <button type="button" className="dice-roll-btn" disabled={rolling || coolingDown} onClick={roll}>
          {rolling ? "Rolling…" : coolingDown ? `Wait ${Math.ceil(cooldownLeft / 1000)}s` : "ROLL"}
        </button>
        {activeFeedback && (
          <p className={`dice-feedback dice-feedback-${activeFeedback.type}`} role="status" aria-live="polite">{activeFeedback.message}</p>
        )}
      </div>

      {/* Green felt — settled dice + result banner */}
      <div className="dice-felt">
        <div className="dice-tray">
          {settled.map((die, i) => (
            <Die key={`${die.kind}-${i}`} die={die} extraClass="dice-die-settled" assets={assets} />
          ))}
        </div>
        <p className={`dice-banner dice-banner-${banner.cls}`}>{banner.text}</p>
      </div>

      {/* Win celebration — a brief fireworks burst + toast. */}
      {celebrate && (
        <div className={`dice-celebrate dice-celebrate-${celebrate.tier}`} key={celebrate.seq} aria-hidden="true">
          <div className="dice-fireworks">
            {sparks.map((s, i) => (
              <span key={i} className="dice-spark" style={s} />
            ))}
          </div>
          <div className="dice-celebrate-toast">
            <span className="dice-celebrate-title">{celebrateTitle(celebrate.tier)}</span>
            <span className="dice-celebrate-amount">{formatGold(celebrate.payout)} gold</span>
          </div>
        </div>
      )}
    </div>
  );
}

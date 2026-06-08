import { useEffect, useRef, useState } from "react";
import type { PuzzleItem, PuzzleResult, PuzzleState } from "../types";

interface PuzzlePopoutProps {
  puzzle: PuzzleState;
  puzzleResult: PuzzleResult | null;
  serverAssets: Record<string, string>;
  onCommand: (command: string) => void;
}

function SequenceDots({ currentStep, totalSteps }: { currentStep: number; totalSteps: number }) {
  return (
    <div className="puzzle-seq-dots" aria-label={`Step ${currentStep} of ${totalSteps}`}>
      {Array.from({ length: totalSteps }, (_, i) => (
        <span
          key={i}
          className={`puzzle-seq-dot${i < currentStep ? " is-done" : ""}${i === currentStep ? " is-next" : ""}`}
        />
      ))}
    </div>
  );
}

function RiddleBlock({ puzzle }: { puzzle: PuzzleItem }) {
  return (
    <div className={`puzzle-riddle${puzzle.solved ? " is-solved" : ""}`}>
      <span className="puzzle-eyebrow">A Riddle</span>
      <p className="puzzle-riddle-text">
        {puzzle.question ?? "An ancient riddle awaits your answer."}
      </p>
      {puzzle.solved && <span className="puzzle-seal">Solved</span>}
    </div>
  );
}

function SequenceBlock({ puzzle }: { puzzle: PuzzleItem }) {
  return (
    <div className={`puzzle-riddle${puzzle.solved ? " is-solved" : ""}`}>
      <span className="puzzle-eyebrow">A Sequence</span>
      <p className="puzzle-riddle-text">Work the room's features in the right order.</p>
      {puzzle.totalSteps != null && puzzle.currentStep != null && (
        <SequenceDots currentStep={puzzle.currentStep} totalSteps={puzzle.totalSteps} />
      )}
      {puzzle.solved && <span className="puzzle-seal">Solved</span>}
    </div>
  );
}

export function PuzzlePopout({ puzzle, puzzleResult, serverAssets, onCommand }: PuzzlePopoutProps) {
  const [answer, setAnswer] = useState("");
  const [burn, setBurn] = useState<{ correct: boolean; text: string } | null>(null);
  const [verdict, setVerdict] = useState<{ correct: boolean; message: string } | null>(null);
  const [seenResult, setSeenResult] = useState<PuzzleResult | null>(puzzleResult);
  const [pendingText, setPendingText] = useState("");
  const inputRef = useRef<HTMLInputElement>(null);

  const hasArt =
    (puzzle.puzzles.find((p) => p.backgroundImage)?.backgroundImage ?? serverAssets["puzzle_bg"]) != null;
  const hasUnsolvedRiddle = puzzle.puzzles.some((p) => p.type === "riddle" && !p.solved);

  // A fresh Puzzle.Result ignites the ink: gold flare if correct, ash scatter if
  // not. Derived during render (the blessed "adjust state on prop change" form)
  // so we react the instant the result lands, showing what was just inscribed.
  // The flame (`burn`) is brief; the verdict message stays so it can be read.
  if (puzzleResult !== seenResult) {
    setSeenResult(puzzleResult);
    if (puzzleResult) {
      setBurn({ correct: puzzleResult.correct, text: pendingText });
      setVerdict({ correct: puzzleResult.correct, message: puzzleResult.message });
      setAnswer("");
    }
  }

  const submit = () => {
    const trimmed = answer.trim();
    if (trimmed.length === 0 || burn) return;
    setPendingText(trimmed);
    setVerdict(null);
    onCommand(`answer ${trimmed}`);
  };

  // Clear the flame once it's burned out; re-arm the page for another attempt
  // when the answer was wrong. The verdict message persists.
  useEffect(() => {
    if (!burn) return;
    const wasWrong = !burn.correct;
    const timer = setTimeout(() => {
      setBurn(null);
      if (wasWrong) inputRef.current?.focus();
    }, 1200);
    return () => clearTimeout(timer);
  }, [burn]);

  return (
    <div className="puzzle-tome">
      <div className={`puzzle-page${hasArt ? " on-art" : " on-css"}`}>
        {puzzle.puzzles.map((p) =>
          p.type === "riddle" ? (
            <RiddleBlock key={p.id} puzzle={p} />
          ) : (
            <SequenceBlock key={p.id} puzzle={p} />
          ),
        )}

        {(hasUnsolvedRiddle || burn) && (
          <form
            className="puzzle-inscribe"
            onSubmit={(e) => {
              e.preventDefault();
              submit();
            }}
          >
            <div className={`puzzle-inscribe-field${burn ? (burn.correct ? " is-success" : " is-fail") : ""}`}>
              <input
                ref={inputRef}
                id="puzzle-answer-input"
                type="text"
                className="puzzle-inscribe-input"
                placeholder="Inscribe your answer…"
                value={answer}
                onChange={(e) => setAnswer(e.target.value)}
                autoComplete="off"
                spellCheck={false}
                disabled={burn != null}
                aria-label="Your answer"
              />
              {burn && (
                <div className={`puzzle-burn${burn.correct ? " is-success" : " is-fail"}`} aria-hidden="true">
                  <span className="puzzle-burn-text">{burn.text}</span>
                  <span className="puzzle-flame" />
                  <span className="puzzle-flame puzzle-flame-2" />
                </div>
              )}
            </div>
            <button
              type="submit"
              className="puzzle-inscribe-submit"
              disabled={answer.trim().length === 0 || burn != null}
            >
              Inscribe
            </button>
          </form>
        )}

        {verdict && (
          <p className={`puzzle-verdict${verdict.correct ? " is-success" : " is-fail"}`} role="status">
            {verdict.message}
          </p>
        )}
      </div>
    </div>
  );
}

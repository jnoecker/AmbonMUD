import { useState } from "react";
import type { PuzzleState } from "../types";

interface PuzzlePopoutProps {
  puzzle: PuzzleState;
  onCommand: (command: string) => void;
}

export function PuzzlePopout({ puzzle, onCommand }: PuzzlePopoutProps) {
  const [answer, setAnswer] = useState("");

  const submit = () => {
    const trimmed = answer.trim();
    if (trimmed.length === 0) return;
    onCommand(`answer ${trimmed}`);
    setAnswer("");
  };

  return (
    <div className="puzzle-popout">
      {puzzle.puzzles.map((p) => {
        const isRiddle = p.type === "riddle";
        return (
          <article
            key={p.id}
            className={`puzzle-popout-card${p.solved ? " puzzle-popout-card-solved" : ""}`}
          >
            <header className="puzzle-popout-card-header">
              <span className="puzzle-popout-kind">{isRiddle ? "Riddle" : "Sequence Puzzle"}</span>
              {p.solved && <span className="puzzle-popout-solved-badge">Solved</span>}
            </header>

            {isRiddle ? (
              <p className="puzzle-popout-question">
                {p.question ?? "An ancient riddle awaits your answer."}
              </p>
            ) : (
              <p className="puzzle-popout-question">
                Interact with this room's features in the correct order to solve it.
                {p.totalSteps != null && p.currentStep != null && (
                  <>
                    {" "}
                    <span className="puzzle-popout-progress">
                      Progress: {p.currentStep} / {p.totalSteps}
                    </span>
                  </>
                )}
              </p>
            )}
          </article>
        );
      })}

      {puzzle.puzzles.some((p) => p.type === "riddle" && !p.solved) && (
        <form
          className="puzzle-popout-form"
          onSubmit={(e) => {
            e.preventDefault();
            submit();
          }}
        >
          <label htmlFor="puzzle-answer-input" className="sr-only">
            Your answer
          </label>
          <input
            id="puzzle-answer-input"
            type="text"
            className="puzzle-popout-input"
            placeholder="Your answer..."
            value={answer}
            onChange={(e) => setAnswer(e.target.value)}
            autoComplete="off"
            spellCheck={false}
          />
          <button
            type="submit"
            className="soft-button puzzle-popout-submit"
            disabled={answer.trim().length === 0}
          >
            Answer
          </button>
        </form>
      )}
    </div>
  );
}

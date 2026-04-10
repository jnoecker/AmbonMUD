import { useState } from "react";
import type { PuzzleItem, PuzzleState } from "../types";

interface PuzzlePopoutProps {
  puzzle: PuzzleState;
  onCommand: (command: string) => void;
}

function SequenceDots({ currentStep, totalSteps }: { currentStep: number; totalSteps: number }) {
  return (
    <div className="puzzle-sequence-dots" aria-label={`Step ${currentStep} of ${totalSteps}`}>
      {Array.from({ length: totalSteps }, (_, i) => (
        <span
          key={i}
          className={`puzzle-sequence-dot${i < currentStep ? " puzzle-sequence-dot-done" : ""}${i === currentStep ? " puzzle-sequence-dot-next" : ""}`}
        />
      ))}
    </div>
  );
}

function RiddleCard({ puzzle }: { puzzle: PuzzleItem }) {
  return (
    <article className={`puzzle-card puzzle-card-riddle${puzzle.solved ? " puzzle-card-solved" : ""}`}>
      <div className="puzzle-card-icon">
        <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
          <path
            d="M12 2C8.13 2 5 5.13 5 9c0 2.38 1.19 4.47 3 5.74V17a1 1 0 001 1h6a1 1 0 001-1v-2.26c1.81-1.27 3-3.36 3-5.74 0-3.87-3.13-7-7-7z"
            stroke="currentColor"
            strokeWidth="1.5"
            strokeLinecap="round"
            strokeLinejoin="round"
          />
          <path d="M9 21h6" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
          <path d="M10 17v4M14 17v4" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
        </svg>
      </div>
      <div className="puzzle-card-body">
        <div className="puzzle-card-top">
          <span className="puzzle-card-label">Riddle</span>
          {puzzle.solved && <span className="puzzle-card-badge">Solved</span>}
        </div>
        <p className="puzzle-card-question">
          {puzzle.question ?? "An ancient riddle awaits your answer."}
        </p>
      </div>
    </article>
  );
}

function SequenceCard({ puzzle }: { puzzle: PuzzleItem }) {
  return (
    <article className={`puzzle-card puzzle-card-sequence${puzzle.solved ? " puzzle-card-solved" : ""}`}>
      <div className="puzzle-card-icon puzzle-card-icon-sequence">
        <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
          <circle cx="5" cy="12" r="2" stroke="currentColor" strokeWidth="1.5" />
          <circle cx="12" cy="12" r="2" stroke="currentColor" strokeWidth="1.5" />
          <circle cx="19" cy="12" r="2" stroke="currentColor" strokeWidth="1.5" />
          <path d="M7 12h3M14 12h3" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
        </svg>
      </div>
      <div className="puzzle-card-body">
        <div className="puzzle-card-top">
          <span className="puzzle-card-label">Sequence</span>
          {puzzle.solved && <span className="puzzle-card-badge">Solved</span>}
        </div>
        <p className="puzzle-card-hint">
          Interact with features in the correct order.
        </p>
        {puzzle.totalSteps != null && puzzle.currentStep != null && (
          <SequenceDots currentStep={puzzle.currentStep} totalSteps={puzzle.totalSteps} />
        )}
      </div>
    </article>
  );
}

export function PuzzlePopout({ puzzle, onCommand }: PuzzlePopoutProps) {
  const [answer, setAnswer] = useState("");

  const submit = () => {
    const trimmed = answer.trim();
    if (trimmed.length === 0) return;
    onCommand(`answer ${trimmed}`);
    setAnswer("");
  };

  const hasUnsolvedRiddle = puzzle.puzzles.some((p) => p.type === "riddle" && !p.solved);

  return (
    <div className="puzzle-popout">
      {puzzle.puzzles.map((p) =>
        p.type === "riddle" ? (
          <RiddleCard key={p.id} puzzle={p} />
        ) : (
          <SequenceCard key={p.id} puzzle={p} />
        ),
      )}

      {hasUnsolvedRiddle && (
        <form
          className="puzzle-answer-bar"
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
            className="puzzle-answer-input"
            placeholder="Speak your answer..."
            value={answer}
            onChange={(e) => setAnswer(e.target.value)}
            autoComplete="off"
            spellCheck={false}
          />
          <button
            type="submit"
            className="puzzle-answer-submit"
            disabled={answer.trim().length === 0}
          >
            Answer
          </button>
        </form>
      )}
    </div>
  );
}

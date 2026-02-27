import { useCallback, useEffect, useRef } from "react";
import { COMMANDS, EMPTY_TAB, HISTORY_KEY, MAX_HISTORY } from "../constants";
import type { TabCycle } from "../types";

function readHistory(): string[] {
  try {
    const raw = window.localStorage.getItem(HISTORY_KEY);
    const parsed = raw ? JSON.parse(raw) : [];
    return Array.isArray(parsed)
      ? parsed.filter((entry): entry is string => typeof entry === "string")
      : [];
  } catch {
    return [];
  }
}

function nextCompletion(value: string, cycle: TabCycle): { value: string; cycle: TabCycle } | null {
  if (value.trimStart().length === 0) return null;
  const parts = value.split(" ");
  const first = parts[0].toLowerCase();

  let next = cycle;
  let nextIndex = 0;

  const advance =
    cycle.matches.length > 0 &&
    cycle.originalPrefix.length > 0 &&
    first !== cycle.originalPrefix &&
    cycle.matches.includes(first);

  if (advance) {
    nextIndex = (cycle.index + 1) % cycle.matches.length;
  } else {
    const matches = COMMANDS.filter((cmd) => cmd.startsWith(first) && cmd !== first);
    if (matches.length === 0) return null;
    next = { matches, index: 0, originalPrefix: first, args: parts.slice(1).join(" ") };
  }

  const command = next.matches[nextIndex];
  const nextValue = next.args.length > 0 ? `${command} ${next.args}` : command;
  return { value: nextValue, cycle: { ...next, index: nextIndex } };
}

export function useCommandHistory() {
  const historyRef = useRef<string[]>([]);
  const termHistoryIndexRef = useRef(-1);
  const termSavedInputRef = useRef("");
  const termTabCycleRef = useRef<TabCycle>(EMPTY_TAB);

  const composerHistoryIndexRef = useRef(-1);
  const composerSavedInputRef = useRef("");
  const composerTabCycleRef = useRef<TabCycle>(EMPTY_TAB);

  useEffect(() => {
    historyRef.current = readHistory();
  }, []);

  const persistHistory = useCallback(() => {
    try {
      window.localStorage.setItem(HISTORY_KEY, JSON.stringify(historyRef.current));
    } catch {
      // ignore localStorage failures
    }
  }, []);

  const pushHistory = useCallback(
    (command: string) => {
      const normalized = command.trim();
      if (normalized.length === 0) return;
      if (historyRef.current.at(-1) === normalized) return;
      historyRef.current.push(normalized);
      while (historyRef.current.length > MAX_HISTORY) historyRef.current.shift();
      persistHistory();
    },
    [persistHistory],
  );

  const resetTerminalTraversal = useCallback(() => {
    termHistoryIndexRef.current = -1;
    termSavedInputRef.current = "";
    termTabCycleRef.current = EMPTY_TAB;
  }, []);

  const resetComposerTraversal = useCallback(() => {
    composerHistoryIndexRef.current = -1;
    composerSavedInputRef.current = "";
    composerTabCycleRef.current = EMPTY_TAB;
  }, []);

  const resetTerminalCompletion = useCallback(() => {
    termTabCycleRef.current = EMPTY_TAB;
  }, []);

  const resetComposerCompletion = useCallback(() => {
    composerTabCycleRef.current = EMPTY_TAB;
  }, []);

  const applyTerminalHistoryUp = useCallback(
    (currentInput: string, replaceInput: (value: string) => void): boolean => {
      if (historyRef.current.length === 0) return false;
      if (termHistoryIndexRef.current === -1) {
        termSavedInputRef.current = currentInput;
        termHistoryIndexRef.current = historyRef.current.length - 1;
      } else if (termHistoryIndexRef.current > 0) {
        termHistoryIndexRef.current -= 1;
      }
      replaceInput(historyRef.current[termHistoryIndexRef.current] ?? "");
      return true;
    },
    [],
  );

  const applyTerminalHistoryDown = useCallback(
    (replaceInput: (value: string) => void): boolean => {
      if (termHistoryIndexRef.current === -1) return false;
      termHistoryIndexRef.current += 1;
      if (termHistoryIndexRef.current >= historyRef.current.length) {
        termHistoryIndexRef.current = -1;
        replaceInput(termSavedInputRef.current);
      } else {
        replaceInput(historyRef.current[termHistoryIndexRef.current] ?? "");
      }
      return true;
    },
    [],
  );

  const applyTerminalCompletion = useCallback(
    (currentInput: string, replaceInput: (value: string) => void): boolean => {
      const completion = nextCompletion(currentInput, termTabCycleRef.current);
      if (!completion) return false;
      replaceInput(completion.value);
      termTabCycleRef.current = completion.cycle;
      return true;
    },
    [],
  );

  const applyComposerHistoryUp = useCallback(
    (composerValue: string, setComposerValue: (value: string) => void): boolean => {
      if (historyRef.current.length === 0) return false;
      if (composerHistoryIndexRef.current === -1) {
        composerSavedInputRef.current = composerValue;
        composerHistoryIndexRef.current = historyRef.current.length - 1;
      } else if (composerHistoryIndexRef.current > 0) {
        composerHistoryIndexRef.current -= 1;
      }
      setComposerValue(historyRef.current[composerHistoryIndexRef.current] ?? "");
      return true;
    },
    [],
  );

  const applyComposerHistoryDown = useCallback(
    (setComposerValue: (value: string) => void): boolean => {
      if (composerHistoryIndexRef.current === -1) return false;
      composerHistoryIndexRef.current += 1;
      if (composerHistoryIndexRef.current >= historyRef.current.length) {
        composerHistoryIndexRef.current = -1;
        setComposerValue(composerSavedInputRef.current);
      } else {
        setComposerValue(historyRef.current[composerHistoryIndexRef.current] ?? "");
      }
      return true;
    },
    [],
  );

  const applyComposerCompletion = useCallback(
    (composerValue: string, setComposerValue: (value: string) => void): boolean => {
      const completion = nextCompletion(composerValue, composerTabCycleRef.current);
      if (!completion) return false;
      setComposerValue(completion.value);
      composerTabCycleRef.current = completion.cycle;
      return true;
    },
    [],
  );

  return {
    pushHistory,
    applyTerminalHistoryUp,
    applyTerminalHistoryDown,
    applyTerminalCompletion,
    applyComposerHistoryUp,
    applyComposerHistoryDown,
    applyComposerCompletion,
    resetTerminalTraversal,
    resetComposerTraversal,
    resetTerminalCompletion,
    resetComposerCompletion,
  };
}


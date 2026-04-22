import { useCallback, useState } from "react";

// Per-character, one-time UI hints for first-time players. Stored in localStorage
// under a single key so we can evolve the schema without adding new keys.
//
// Schema:
//   {
//     "<characterName>": { invHintDone?: true, equipHintDone?: true }
//   }
//
// A hint is "done" once the user has taken the triggering action (opened the
// inventory drawer / equipped the item). We only show hints when the character
// has at least one equipable-but-unequipped item in inventory.

const STORAGE_KEY = "ambonmud_onboarding_v1";

export interface OnboardingFlags {
  invHintDone?: boolean;
  equipHintDone?: boolean;
}

type OnboardingStore = Record<string, OnboardingFlags>;

function readStore(): OnboardingStore {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return {};
    const parsed = JSON.parse(raw) as OnboardingStore;
    return parsed && typeof parsed === "object" ? parsed : {};
  } catch {
    return {};
  }
}

function writeStore(store: OnboardingStore): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(store));
  } catch {
    /* ignore — localStorage might be unavailable in private mode */
  }
}

/**
 * Onboarding hints for a single character. Pass the empty string ("" / "-") before
 * a character is selected; hints remain hidden until a real name is provided.
 */
export function useOnboarding(characterName: string): {
  flags: OnboardingFlags;
  markInvHintDone: () => void;
  markEquipHintDone: () => void;
} {
  const active = characterName.length > 0 && characterName !== "-";

  // Track which character the current `flags` value corresponds to so we can
  // re-derive (without a setState-in-effect) on character switch.
  const [entry, setEntry] = useState<{ name: string; flags: OnboardingFlags }>(() => ({
    name: active ? characterName : "",
    flags: active ? readStore()[characterName] ?? {} : {},
  }));

  let flags: OnboardingFlags;
  if (entry.name !== (active ? characterName : "")) {
    // Character changed — derive fresh value inline and schedule a sync render.
    flags = active ? readStore()[characterName] ?? {} : {};
    setEntry({ name: active ? characterName : "", flags });
  } else {
    flags = entry.flags;
  }

  const setFlag = useCallback(
    (patch: OnboardingFlags) => {
      if (!active) return;
      setEntry((prev) => {
        const base = prev.name === characterName ? prev.flags : readStore()[characterName] ?? {};
        const next = { ...base, ...patch };
        const store = readStore();
        store[characterName] = next;
        writeStore(store);
        return { name: characterName, flags: next };
      });
    },
    [active, characterName],
  );

  const markInvHintDone = useCallback(() => setFlag({ invHintDone: true }), [setFlag]);
  const markEquipHintDone = useCallback(() => setFlag({ equipHintDone: true }), [setFlag]);

  return { flags, markInvHintDone, markEquipHintDone };
}

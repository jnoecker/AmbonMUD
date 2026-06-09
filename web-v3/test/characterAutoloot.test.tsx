import { describe, expect, test } from "bun:test";
import { renderToStaticMarkup } from "react-dom/server";
import { CharacterPanel } from "../src/components/panels/CharacterPanel";
import { DEFAULT_STATUS_VAR_LABELS, EMPTY_CHAR, EMPTY_VITALS } from "../src/constants";
import { applyGmcpPackage } from "../src/gmcp/applyGmcpPackage";
import type { CharacterInfo } from "../src/types";

const baseProps = {
  connected: true,
  hasCharacterProfile: true,
  canOpenEquipment: true,
  character: {
    ...EMPTY_CHAR,
    name: "Alice",
    gender: "female",
    race: "ELF",
    className: "MAGE",
    level: 10,
    autolootEnabled: false,
  },
  displayRace: "Elf",
  displayClassName: "Mage",
  vitals: {
    ...EMPTY_VITALS,
    hp: 42,
    maxHp: 50,
    mana: 27,
    maxMana: 30,
    level: 10,
    xpIntoLevel: 20,
    xpToNextLevel: 100,
    gold: 55,
  },
  statusVarLabels: DEFAULT_STATUS_VAR_LABELS,
  serverAssets: {},
  xpValue: 20,
  xpMax: 100,
  xpText: "20 / 100",
  effects: [],
  visibleEffects: [],
  hiddenEffectsCount: 0,
  achievements: { completed: [], inProgress: [] },
  quests: [],
  questNotifications: [],
  charStats: null,
  guildInfo: { name: null, tag: null, rank: null, motd: null, memberCount: 0, maxSize: 50 },
  groupInfo: { leader: null, members: [] },
  activeTitle: null,
  spriteList: { active: null, sprites: [] },
  currencies: [],
  factions: [],
  petState: null,
  prestigeInfo: null,
  onDismissQuestNotification: () => {},
  onAbandonQuest: () => {},
  onOpenInventory: () => {},
  onOpenEquipment: () => {},
  onCommand: () => {},
  onLogout: () => {},
};

describe("character auto-loot UI", () => {
  test("renders the auto-loot control in the off state", () => {
    const html = renderToStaticMarkup(<CharacterPanel {...baseProps} />);

    expect(html).toContain("Auto Loot");
    expect(html).toContain("Mob drops go straight into your pack after a kill.");
    // Off state: the switch is unchecked and carries no "is-on" modifier.
    expect(html).toContain('aria-checked="false"');
    expect(html).not.toContain("cc-ctl-toggle is-on");
  });

  test("renders the auto-loot control in the on state", () => {
    const html = renderToStaticMarkup(
      <CharacterPanel
        {...baseProps}
        character={{ ...baseProps.character, autolootEnabled: true }}
      />,
    );

    // Only auto-loot is enabled here, so the checked switch + "is-on" modifier
    // uniquely identify the auto-loot control.
    expect(html).toContain("Auto Loot");
    expect(html).toContain('aria-checked="true"');
    expect(html).toContain("cc-ctl-toggle is-on");
  });
});

describe("Char.Name auto-loot sync", () => {
  test("stores autolootEnabled from the GMCP payload", () => {
    let character: CharacterInfo = EMPTY_CHAR;
    const ctx = {
      setCharacter: (next: CharacterInfo | ((prev: CharacterInfo) => CharacterInfo)) => {
        character = typeof next === "function" ? next(character) : next;
      },
      setLoginPrompt: () => {},
      setLoginError: () => {},
    } as unknown as Parameters<typeof applyGmcpPackage>[2];

    applyGmcpPackage(
      "Char.Name",
      {
        name: "Alice",
        gender: "female",
        race: "ELF",
        class: "MAGE",
        level: 10,
        sprite: "/images/alice.webp",
        isStaff: false,
        autolootEnabled: true,
      },
      ctx,
    );

    expect(character.autolootEnabled).toBe(true);
  });
});

import { describe, expect, test } from "bun:test";
import {
  buildAdminCommand,
  requiresAdminConfirmation,
} from "../src/components/panels/AdminPanel.logic";

describe("AdminPanel logic", () => {
  test("builds commands for contextual admin actions", () => {
    expect(buildAdminCommand("goto", "test_zone:hub", "")).toBe("goto test_zone:hub");
    expect(buildAdminCommand("transfer", "Bob", "test_zone:outpost")).toBe("transfer Bob test_zone:outpost");
    expect(buildAdminCommand("spawn", "test_zone:grunt", "")).toBe("spawn test_zone:grunt");
    expect(buildAdminCommand("setlevel", "Bob", "10")).toBe("setlevel Bob 10");
    expect(buildAdminCommand("reload", "", "")).toBe("reload");
    expect(buildAdminCommand("reload", "all", "")).toBe("reload");
    expect(buildAdminCommand("reload", "world", "")).toBe("reload world");
  });

  test("rejects incomplete admin commands", () => {
    expect(buildAdminCommand("goto", "", "")).toBeNull();
    expect(buildAdminCommand("transfer", "Bob", "")).toBeNull();
    expect(buildAdminCommand("setlevel", "", "10")).toBeNull();
    expect(buildAdminCommand("broadcast", "", "")).toBeNull();
    expect(buildAdminCommand("possess", "", "")).toBeNull();
  });

  test("marks only high-impact actions for confirmation", () => {
    expect(requiresAdminConfirmation("goto")).toBe(false);
    expect(requiresAdminConfirmation("spawn")).toBe(false);
    expect(requiresAdminConfirmation("dispel")).toBe(false);
    expect(requiresAdminConfirmation("transfer")).toBe(true);
    expect(requiresAdminConfirmation("smite")).toBe(true);
    expect(requiresAdminConfirmation("kick")).toBe(true);
    expect(requiresAdminConfirmation("setlevel")).toBe(true);
    expect(requiresAdminConfirmation("reload")).toBe(true);
    expect(requiresAdminConfirmation("broadcast")).toBe(true);
    expect(requiresAdminConfirmation("shutdown")).toBe(true);
  });

});

# AmbonMUD Quest System - User Flow Plan

## Overview
A flexible quest system supporting NPC dialogue-based quests, quest boards, multi-stage objectives, and quest chains. Players can track multiple active quests, abandon anytime, and earn experience/gold/items/reputation.

---

## 1. QUEST DISCOVERY

### 1.1 NPC Quest Offering
- **Trigger:** Player talks to an NPC via `talk <npc-name>` or similar command
- **NPC Dialog Flow:**
  - NPC displays their available quests
  - Player can ask about each quest to see description/rewards
  - Player accepts quest or asks for more details
  - Quest is added to player's active quest log

### 1.2 Quest Board System
- **Trigger:** Player visits a quest board room (e.g., tavern, town center)
- **Board Interaction Flow:**
  - `look` or `board` command shows list of available quests posted on board
  - `board show <quest-name>` displays full quest description and rewards
  - `board accept <quest-name>` accepts quest from the board
  - Board quests can have different NPCs as quest-givers (for turn-in purposes)

### 1.3 Exploration-Triggered Quests
- Some quests may trigger automatically when:
  - Player enters a specific location
  - Player picks up a specific item
  - Player defeats a specific NPC
  - (Handled via Scheduler/event system)

---

## 2. QUEST ACCEPTANCE

### 2.1 Prerequisites & Gating
Before accepting a quest, system checks:
- **Level/Power Requirement:** "You must be level 5 to accept this quest"
- **Previous Quest Completion:** "You must complete 'Rat Extermination' first"
- **Class/Race Restrictions:** "Only warriors may accept this quest"

If any check fails, acceptance is denied with a clear reason.

### 2.2 Acceptance Confirmation
- Player sees quest summary with:
  - Quest name and giver
  - Objectives (what needs to be done)
  - Reward preview (XP, gold, items, reputation)
  - Any special notes or warnings
- Player confirms acceptance
- **On Acceptance:**
  - Quest added to active quest log
  - If quest requires items (for delivery), items are given to player
  - Quest objectives begin tracking
  - Optional: Auto-prompt or message "Started quest: [Quest Name]"

---

## 3. QUEST PROGRESS TRACKING

### 3.1 Active Quest Log
- **Command:** `quest log` or `quests`
- **Display Shows:**
  - List of all active quests with names and current stage
  - Brief status for each (e.g., "Kill 3/5 rats", "Deliver package to inn")
  - Quests sorted by acceptance order or priority

### 3.2 Detailed Quest Info
- **Command:** `quest info <quest-name>` or `quest details <quest-name>`
- **Display Shows:**
  - Full quest description/story
  - Current stage and detailed objectives
  - Quest-giver name
  - Rewards (XP, gold, items, reputation)
  - Any progress bar or percentage completion
  - Option to `quest abandon <quest-name>`

### 3.3 Automatic Progress Notifications
- **Kill objectives:** "You've killed 3 of 5 rats for 'Rat Extermination'"
- **Collect objectives:** "You've collected 2 of 3 herbs for 'Gather Herbs'"
- **Location objectives:** "You've visited 2 of 3 locations for 'Scout the Realm'"
- **Delivery objectives:** "You've delivered the package to the Inn for 'Urgent Delivery'"
- Notifications appear as system messages when progress changes

### 3.4 Multi-Stage Quests
- Quest progresses through stages in order
- Player sees current stage prominently in quest info
- Completing a stage automatically advances to next
- Final stage completion requires turn-in or triggers auto-completion

---

## 4. NPC BEHAVIOR DURING ACTIVE QUEST

### 4.1 Quest-Giver Interaction
- **If player talks to quest-giver while quest is active:**
  - NPC gives progress hint/reminder: "Don't forget, I need 5 rats killed"
  - Optionally offers to repeat the objective details
  - If quest is near completion, NPC might give encouraging message

### 4.2 Quest Completion Dialogue
- Some quests require returning to NPC for turn-in
  - NPC recognizes completion and congratulates player
  - NPC hands over rewards
  - Quest marked complete
- Other quests auto-complete when objectives done

---

## 5. QUEST COMPLETION

### 5.1 Completion Types

#### Auto-Completion
- Last objective completes → quest automatically done
- Rewards granted immediately
- System message: "Quest complete: [Name]! You've earned 100 XP, 50 gold, and [Item]"
- Quest moved to completed quest log

#### NPC Turn-In Completion
- Last objective completes → quest shows "Ready to turn in"
- Player must return to quest-giver NPC
- Use `talk` command or `quest turnin <quest-name>`
- NPC delivers rewards
- Quest moved to completed quest log

### 5.2 Rewards Granted
- **Experience:** Added to player XP pool
- **Gold:** Added to player inventory
- **Items:** Added to player inventory
  - If inventory full, items drop to ground (or held for pickup)
- **Reputation:** Added to faction/NPC reputation tracker
- **Story Progression:** Unlocks follow-up quests (if part of chain)

---

## 6. QUEST ABANDONMENT & FAILURE

### 6.1 Abandonment
- **Command:** `quest abandon <quest-name>`
- Player can abandon any active quest anytime
- **On Abandonment:**
  - Quest removed from active log
  - Quest progress reset (can accept again)
  - If quest gave items, player keeps them (or choice?)
  - No penalty, no cooldown
  - System message: "You've abandoned [Quest Name]"

### 6.2 Quest Failure
- Quests don't auto-fail
- However, specific failure conditions might exist:
  - Quest-giver NPC dies permanently (in that run)
  - Required NPC for delivery dies
  - Item given for delivery is lost/destroyed
  - (Future: time-limited quest expires)

---

## 7. QUEST CHAINS & DEPENDENCIES

### 7.1 Simple Sequential Chains
- Quest A must be completed to unlock Quest B
- On completion of Quest A, Quest B becomes available from same NPC or board
- System prevents offering/accepting Quest B until Quest A is done
- Clear messaging: "You've unlocked [Quest B]!"

### 7.2 Chain Progression
- Quest-givers can have multiple quests in a chain
- Completing one automatically opens next in chain
- Player can see chain structure in detailed quest info
- Optional: "This is part of the [Chain Name] story arc"

---

## 8. PLAYER COMMANDS & UI

### 8.1 Quest Management Commands
- `quest log` — List all active quests with status
- `quest info <name>` — Detailed info on specific quest
- `quest abandon <name>` — Abandon a quest
- `quest turnin <name>` — Turn in quest to NPC (shortcut if NPC not present)
- `quest history` or `quest completed` — View completed quests (optional)

### 8.2 NPC Dialogue Commands
- `talk <npc-name>` — Start dialogue; NPC mentions available quests
- `accept <quest-name>` — Accept quest during dialogue
- `quest info <name>` — Ask NPC for more details about their quest
- `leave` or `cancel` — Exit dialogue

### 8.3 Quest Board Commands
- `look` — Describe room with board
- `board show` or `board list` — See all available board quests
- `board show <quest-name>` — Detailed info on specific board quest
- `board accept <quest-name>` — Accept quest from board

---

## 9. QUEST STATE & PERSISTENCE

### 9.1 Player Data Storage
- Active quests stored in PlayerRecord
  - Quest name/ID
  - Current stage
  - Objective progress (e.g., "3/5 rats killed")
  - Timestamp accepted

### 9.2 Completed Quest Tracking
- Record of completed quests (for locking quest chains, preventing re-acceptance of story quests)
- Possible: Rep tracking per NPC/faction

### 9.3 Quest Content
- Quests defined in YAML world content (in `src/main/resources/world/`)
- Includes:
  - Quest name, description, lore
  - Objectives and stages
  - Prerequisites (level, previous quests, class/race)
  - Rewards (XP, gold, items, rep)
  - Quest-giver NPC reference
  - Completion type (auto vs NPC turn-in)

---

## 10. PLAYER FLOW SUMMARY (Happy Path)

1. Player explores world, finds NPC or quest board
2. Player sees available quests
3. Player accepts quest (checks pass)
4. Quest added to active log
5. Player works on objectives:
   - Kills mobs, collects items, visits locations, delivers packages
   - System tracks progress and sends notifications
6. Player can check progress with `quest log` and `quest info`
7. When complete:
   - Auto-complete path: rewards given immediately
   - NPC turn-in path: player returns to NPC, gets rewards
8. Quest moves to completed log
9. Any follow-up quests unlock (if chain)

---

## 11. EDGE CASES & QUESTIONS FOR LATER

- What if player dies during quest? Objectives stay?
- Can player have same quest multiple times (different instances)?
- Should there be a max cap on active quests?
- How do we handle quest items? Drop on abandon?
- Should NPCs give different dialogue if player has quest from them?
- How to display quest objectives on multiple lines in compact log view?
- Should we show estimated reward before accepting?
- Any "failed" vs "abandoned" distinction?

---

## Next Steps (Architectural)

Once user flow is approved:
1. Define quest data model (YAML schema & code structures)
2. Design QuestSystem subsystem in GameEngine
3. Plan command parsing & routing for quest commands
4. Plan NPC quest integration with dialogue system
5. Plan persistence changes (PlayerRecord + YAML storage)
6. Identify UI/rendering needs for quest display

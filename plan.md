# Group/Party System & Multi-Combatant Combat — Implementation Plan

## Design Decisions (from user input)

- **Heal targeting:** ALLY target type — Clerics can heal group members by name; self-cast if no target given; group members only.
- **Warrior taunt:** Both flat threat bonus AND passive threat multiplier. Warriors get a passive 1.5× threat multiplier on all damage. An active "taunt" ability adds a large flat threat amount + sets the Warrior to top threat + margin.
- **AoE scope:** Mage area spells hit all mobs currently engaged with any group member (doesn't pull new mobs).
- **Rewards:** Equal XP split among group members in the room, with a configurable group bonus (+10% per additional member). Gold goes to killer. Loot round-robin (items drop to room, tracked via round-robin order for fairness messaging).

---

## Phase 1: Group/Party State & Commands

### 1a. GroupSystem class — `src/main/kotlin/dev/ambon/engine/GroupSystem.kt`

New engine subsystem managing group state:

```kotlin
data class Group(
    val id: UUID,
    var leader: SessionId,
    val members: MutableList<SessionId>,        // Ordered; leader is always members[0]
    var lootRobinIndex: Int = 0,                // Round-robin loot tracking
)

class GroupSystem(
    private val players: PlayerRegistry,
    private val outbound: OutboundBus,
    private val maxGroupSize: Int = 5,
) {
    private val groupBySession = mutableMapOf<SessionId, Group>()
    private val pendingInvites = mutableMapOf<SessionId, PendingInvite>() // invitee → invite

    fun getGroup(sessionId: SessionId): Group?
    fun isGrouped(sessionId: SessionId): Boolean
    fun isLeader(sessionId: SessionId): Boolean
    fun groupMembers(sessionId: SessionId): List<SessionId>
    fun membersInRoom(sessionId: SessionId, roomId: RoomId): List<SessionId>

    suspend fun invite(inviterSid: SessionId, targetName: String): String?
    suspend fun accept(inviteeSid: SessionId): String?
    suspend fun leave(sessionId: SessionId): String?
    suspend fun kick(leaderSid: SessionId, targetName: String): String?
    suspend fun list(sessionId: SessionId): String?
    suspend fun gtell(sessionId: SessionId, message: String): String?
    fun onPlayerDisconnected(sessionId: SessionId)
    fun remapSession(oldSid: SessionId, newSid: SessionId)
}
```

**Invite flow:** Inviter must not be in combat. Target must be in same room, not already in a different group, and not already invited. Invites expire after 60s (cleaned on next invite/accept attempt). On accept, the accepter joins the inviter's group (creating one if needed).

**Leave/Kick flow:** If leader leaves, leadership passes to next member. If group drops to 1 member, group dissolves. Kick requires leader.

### 1b. Group Commands — `CommandParser.kt` + `CommandRouter.kt`

Add to `Command` sealed interface:
```kotlin
sealed interface GroupCmd : Command {
    data class Invite(val target: String) : GroupCmd
    data object Accept : GroupCmd
    data object Leave : GroupCmd
    data class Kick(val target: String) : GroupCmd
    data object List : GroupCmd
}
data class Gtell(val message: String) : Command
```

Parsing in `CommandParser.parse()`:
- `group invite <player>` / `group inv <player>`
- `group accept` / `group acc`
- `group leave`
- `group kick <player>`
- `group list` / `group` (bare) → list
- `gtell <message>` / `gt <message>`

Routing: Dispatch to `groupSystem.invite/accept/leave/kick/list/gtell`.

### 1c. Configuration — `AppConfig.kt` + `application.yaml`

Add to `EngineConfig`:
```kotlin
data class GroupConfig(
    val maxSize: Int = 5,
    val inviteTimeoutMs: Long = 60_000L,
    val xpBonusPerMember: Double = 0.10,   // +10% per additional member
)
```

Add validation: `maxSize in 2..20`, `inviteTimeoutMs > 0`, `xpBonusPerMember >= 0.0`.

### 1d. Wire into GameEngine

- Instantiate `GroupSystem` in `GameEngine`.
- Pass to `CommandRouter` and `CombatSystem`.
- Call `groupSystem.onPlayerDisconnected()` in disconnect handler.
- Call `groupSystem.remapSession()` in takeover handler.

### 1e. Tests — `src/test/kotlin/dev/ambon/engine/GroupSystemTest.kt`

- Invite + accept creates group
- Invite to self fails
- Invite when target not in room fails
- Invite when target already grouped fails
- Accept without invite fails
- Leave dissolves group when 2 members
- Leave transfers leadership
- Kick by non-leader fails
- Kick removes member
- gtell broadcasts to all group members
- Disconnected player removed from group
- Max group size enforced
- Invite timeout expiry

---

## Phase 2: Threat Table & N:M Combat Rework

### 2a. ThreatTable — `src/main/kotlin/dev/ambon/engine/ThreatTable.kt`

```kotlin
class ThreatTable {
    // Per-mob: tracks cumulative threat from each player
    private val tables = mutableMapOf<MobId, MutableMap<SessionId, Double>>()

    fun addThreat(mobId: MobId, sessionId: SessionId, amount: Double)
    fun topThreat(mobId: MobId): SessionId?
    fun getThreats(mobId: MobId): Map<SessionId, Double>
    fun removeMob(mobId: MobId)
    fun removePlayer(sessionId: SessionId)
    fun remapSession(oldSid: SessionId, newSid: SessionId)
    fun hasThreat(mobId: MobId, sessionId: SessionId): Boolean
    fun mobsThreatenedBy(sessionId: SessionId): Set<MobId>
    fun playersThreateningMob(mobId: MobId): Set<SessionId>
}
```

Threat sources:
- Melee damage dealt → 1.0 threat per damage point (× class multiplier)
- Spell damage dealt → 1.0 threat per damage point
- Healing done → 0.5 threat per HP healed (split across all mobs targeting group members in room)
- Taunt → flat amount (configurable, default 50) + set to max existing threat + margin

Class threat multipliers (configurable):
- WARRIOR: 1.5×
- Others: 1.0×

### 2b. CombatSystem rework — modify `CombatSystem.kt`

**Replace 1:1 data structures with N:M:**

Remove:
```kotlin
private data class Fight(val sessionId: SessionId, val mobId: MobId, var nextTickAtMs: Long)
private val fightsByPlayer = mutableMapOf<SessionId, Fight>()
private val fightsByMob = mutableMapOf<MobId, Fight>()
```

Add:
```kotlin
// Track which mobs are in combat, with per-mob tick timing
private data class MobCombatState(
    val mobId: MobId,
    var nextTickAtMs: Long,
)
private val activeMobs = mutableMapOf<MobId, MobCombatState>()

// Track which player is targeting which mob (player's chosen attack target)
private val playerTarget = mutableMapOf<SessionId, MobId>()

// Threat table for mob → player threat tracking
private val threatTable = ThreatTable()
```

**Key behavioral changes:**

1. `startCombat(sessionId, keyword)`:
   - Player selects a mob to attack. Multiple players can target the same mob.
   - A mob that's already in combat CAN be targeted by additional players (remove the "already fighting someone" restriction).
   - If mob not yet in activeMobs, add it with nextTickAtMs.
   - Set `playerTarget[sessionId] = mobId`.
   - Add initial threat (e.g., 1 point) so the mob knows about this attacker.

2. `isInCombat(sessionId)`: Check `playerTarget.containsKey(sessionId)`.

3. `currentTarget(sessionId)`: Return `playerTarget[sessionId]`.

4. `tick()` rework:
   - **Player attack phase:** For each player in `playerTarget`, if their target mob exists and is in the same room:
     - Calculate damage as before (roll + equip + STR bonus).
     - Apply class threat multiplier.
     - Add threat to `threatTable.addThreat(mobId, sessionId, damage * multiplier)`.
     - If mob dies → `handleMobDeath(mob)` (distribute rewards to group, see Phase 3).
   - **Mob attack phase:** For each mob in `activeMobs`, if due:
     - Pick target = `threatTable.topThreat(mobId)`.
     - If target not in same room, pick next highest threat in room.
     - If no valid target, end combat for this mob.
     - Calculate mob damage, apply to chosen target.
     - If target dies → player death handling.
   - **Cleanup:** Remove mobs/players with no valid engagements.

5. `flee(sessionId)`: Remove from `playerTarget`. Remove from all threat tables. If no players remain threatening a mob, mob exits combat.

6. `endCombatFor(sessionId)`: Same as flee minus messaging.

7. `onMobRemovedExternally(mobId)`: Notify all players that had this mob in their target or threat table.

8. `onPlayerDisconnected(sessionId)`: Remove from playerTarget, remove from all threat tables.

### 2c. Update AbilitySystem for threat

When a spell deals damage, add threat: `threatTable.addThreat(mobId, sessionId, damage)`.
When a spell heals, add healing threat to all mobs engaged with the group.

### 2d. Update BehaviorTreeSystem / AggroAction

`AggroAction` should work with the new multi-combatant system:
- When a mob aggros a player, it adds to `activeMobs` and the threat table.
- `startMobCombat()` now just means "this mob enters combat with this player" — sets player as target in threat table.

### 2e. Configuration additions

Add to `CombatEngineConfig`:
```kotlin
val threatMultiplierWarrior: Double = 1.5,
val threatMultiplierDefault: Double = 1.0,
val healingThreatMultiplier: Double = 0.5,
val tauntFlatThreat: Double = 50.0,
val tauntMargin: Double = 10.0,
```

### 2f. Tests — `src/test/kotlin/dev/ambon/engine/ThreatTableTest.kt` + update `CombatSystemTest.kt`

ThreatTable tests:
- Add threat, verify top threat
- Multiple players, highest threat wins
- Remove player clears their threat
- Remove mob clears its table

CombatSystem tests (update existing + add new):
- Two players can attack same mob
- Mob attacks highest-threat player
- Player flee removes them from threat
- Mob with no remaining threats exits combat
- Threat multiplier for Warriors
- Healing generates threat

---

## Phase 3: Group XP, Gold, and Loot Distribution

### 3a. Modify `CombatSystem.handleMobDeath()`

Currently: `grantKillXp(killerSessionId, mob)` and `grantKillGold(killerSessionId, mob)`.

New behavior:
- Get the group of the killer (if any).
- Find group members in the same room.
- **XP:** Base XP / N members in room, then apply group bonus (+10% per additional member). E.g., 3 members: each gets `baseXP / 3 * 1.2`. Solo: no change.
- **Gold:** Goes to the killer only (as specified).
- **Item drops:** Drop to room as normal. Round-robin order tracked in `Group.lootRobinIndex` for messaging ("It's Player2's turn to loot").
- **Quest/achievement callbacks:** Fire for all contributing group members (those with threat on the mob).

### 3b. Modify `grantKillXp()` → `grantGroupKillXp()`

```kotlin
private suspend fun grantGroupKillXp(
    killerSessionId: SessionId,
    mob: MobState,
    groupSystem: GroupSystem,
) {
    val baseReward = progression.killXpReward(mob)
    if (baseReward <= 0L) return

    val group = groupSystem.getGroup(killerSessionId)
    val mob_room = mob.roomId

    val recipients = if (group != null) {
        group.members.filter { sid ->
            val p = players.get(sid)
            p != null && p.roomId == mob_room
        }
    } else {
        listOf(killerSessionId)
    }

    val memberCount = recipients.size
    val groupBonus = if (memberCount > 1) 1.0 + (memberCount - 1) * groupXpBonusPerMember else 1.0
    val perPlayerXp = ((baseReward.toDouble() / memberCount) * groupBonus).toLong().coerceAtLeast(1L)

    for (sid in recipients) {
        val player = players.get(sid) ?: continue
        val equipCha = items.equipment(sid).values.sumOf { it.item.charisma }
        val reward = progression.applyCharismaXpBonus(player.charisma + equipCha, perPlayerXp)
        // ... grant XP, check level up, send messages
    }
}
```

### 3c. Tests

- Solo kill: unchanged XP
- 2-member group, both in room: XP split with 10% bonus
- 3-member group, 1 in different room: only 2 get XP
- Gold only goes to killer
- Quest callbacks fire for all group members with threat

---

## Phase 4: Ally Targeting (Heal Group Members)

### 4a. New target type — `AbilityDefinition.kt`

Add `ALLY` to `TargetType` enum:
```kotlin
enum class TargetType { ENEMY, SELF, ALLY }
```

Update `AppConfig.kt` validation to accept `"ALLY"`.

### 4b. AbilitySystem.cast() — ALLY handling

For `TargetType.ALLY`:
- If no keyword: self-heal (same as SELF).
- If keyword: find player by name in group AND in same room.
- Validate target is in caster's group.
- Apply effect (DirectHeal or ApplyStatus to target player).
- Generate healing threat: 0.5 threat per HP healed, distributed across all mobs engaged with the group in the room.

### 4c. AbilitySystem integration with ThreatTable

Pass `ThreatTable` (or a callback) to `AbilitySystem` so it can:
- Add damage threat when casting ENEMY spells.
- Add healing threat when casting ALLY/SELF heals.

### 4d. Configuration

Add `heal` ability definitions to `application.yaml` that use `targetType: ALLY`. Example:
```yaml
group_heal:
  displayName: "Healing Touch"
  manaCost: 15
  cooldownMs: 3000
  levelRequired: 3
  targetType: ALLY
  requiredClass: CLERIC
  effect:
    type: DIRECT_HEAL
    minHeal: 4
    maxHeal: 8
```

### 4e. Tests

- ALLY heal on self (no target) works
- ALLY heal on group member works
- ALLY heal on non-group-member fails
- ALLY heal on player not in room fails
- Healing generates threat on engaged mobs

---

## Phase 5: AoE Damage (Mage Area Spells)

### 5a. New effect type — `AbilityEffect.AreaDamage`

```kotlin
data class AreaDamage(val minDamage: Int, val maxDamage: Int) : AbilityEffect
```

### 5b. AbilitySystem.cast() — AREA_DAMAGE handling

For `TargetType.ENEMY` + `AbilityEffect.AreaDamage`:
- Find all mobs in the room that are engaged with any group member (i.e., exist in `activeMobs` AND have threat entries for any member of the caster's group).
- Roll damage once (or per-mob, design choice — per-mob is more interesting).
- Apply damage + INT bonus to each mob.
- Add threat for each mob damaged.
- Handle kills for any that die.

### 5c. Configuration

Add area damage ability definitions:
```yaml
fireball:
  displayName: "Fireball"
  manaCost: 25
  cooldownMs: 8000
  levelRequired: 5
  targetType: ENEMY
  requiredClass: MAGE
  effect:
    type: AREA_DAMAGE
    minDamage: 3
    maxDamage: 7
```

Update `AppConfig.kt` validation to accept `AREA_DAMAGE` effect type.

### 5d. Tests

- AoE hits all mobs in combat with group
- AoE doesn't hit mobs not in combat
- AoE generates threat on all hit mobs
- AoE can kill multiple mobs in one cast

---

## Phase 6: Taunt Ability (Warrior Threat)

### 6a. New effect type — `AbilityEffect.Taunt`

```kotlin
data class Taunt(val flatThreat: Double, val margin: Double) : AbilityEffect
```

### 6b. AbilitySystem.cast() — TAUNT handling

For `TargetType.ENEMY` + `AbilityEffect.Taunt`:
- Target must be a mob currently in combat.
- Set caster's threat to max existing threat on that mob + margin.
- Add flat threat on top.
- Send "mob turns to face you" message.

### 6c. Passive threat multiplier

In `CombatSystem`, when calculating threat from melee damage:
- Check player class.
- Multiply threat by class multiplier (WARRIOR: 1.5×, default: 1.0×).

### 6d. Configuration

```yaml
taunt:
  displayName: "Taunt"
  manaCost: 5
  cooldownMs: 10000
  levelRequired: 2
  targetType: ENEMY
  requiredClass: WARRIOR
  effect:
    type: TAUNT
```

### 6e. Tests

- Taunt sets warrior to top threat + margin
- Mob switches target to taunter
- Passive multiplier: warrior generates 1.5× threat from melee
- Non-warrior: default 1.0× threat multiplier

---

## Phase 7: GMCP & Polish

### 7a. GMCP Group package

Add `Group.Info` GMCP package:
```json
{
  "leader": "PlayerName",
  "members": [
    {"name": "Player1", "hp": 10, "maxHp": 20, "class": "WARRIOR"},
    {"name": "Player2", "hp": 15, "maxHp": 18, "class": "CLERIC"}
  ]
}
```

Push on: group join, leave, kick, member HP change.

### 7b. Update `score` command

Show group info in score output (leader, member count).

### 7c. Update `who` command

Show group indicators in who list (e.g., `[Group: Leader]`).

### 7d. Help text

Update help output to include group commands.

---

## File Change Summary

| File | Action | Description |
|------|--------|-------------|
| `engine/GroupSystem.kt` | **NEW** | Group state, invite/accept/leave/kick/list/gtell |
| `engine/ThreatTable.kt` | **NEW** | Per-mob threat tracking |
| `engine/CombatSystem.kt` | **MODIFY** | 1:1 → N:M combat, threat integration, group XP/loot |
| `engine/abilities/AbilitySystem.kt` | **MODIFY** | ALLY target, AREA_DAMAGE, TAUNT effects, threat callbacks |
| `engine/abilities/AbilityDefinition.kt` | **MODIFY** | Add TargetType.ALLY, AbilityEffect.AreaDamage, AbilityEffect.Taunt |
| `engine/commands/CommandParser.kt` | **MODIFY** | Parse group/gtell commands |
| `engine/commands/CommandRouter.kt` | **MODIFY** | Route group/gtell commands, pass GroupSystem |
| `engine/GameEngine.kt` | **MODIFY** | Wire GroupSystem, pass to subsystems |
| `engine/PlayerState.kt` | No change | Group membership is transient (not persisted) |
| `config/AppConfig.kt` | **MODIFY** | Add GroupConfig, threat config, validation |
| `resources/application.yaml` | **MODIFY** | Add group defaults, new ability definitions |
| `engine/GmcpEmitter.kt` | **MODIFY** | Add Group.Info package |
| `engine/EngineUtil.kt` | No change | broadcastToRoom still used |
| `engine/behavior/actions/AggroAction.kt` | **MODIFY** | Work with N:M combat |
| `test/.../GroupSystemTest.kt` | **NEW** | Group system tests |
| `test/.../ThreatTableTest.kt` | **NEW** | Threat table tests |
| `test/.../CombatSystemTest.kt` | **MODIFY** | Update for N:M, add group combat tests |
| `test/.../AbilitySystemTest.kt` | **MODIFY** | Add ALLY/AoE/Taunt tests |

---

## Implementation Order

1. **Phase 1** — GroupSystem + commands (standalone, no combat changes)
2. **Phase 2** — ThreatTable + CombatSystem N:M rework (biggest change, core risk)
3. **Phase 3** — Group XP/gold/loot distribution
4. **Phase 4** — ALLY targeting for heals
5. **Phase 5** — AoE damage
6. **Phase 6** — Taunt ability + passive threat multiplier
7. **Phase 7** — GMCP, score/who updates, help text

Each phase builds on the previous. Phase 2 is the riskiest (largest refactor of existing code) and should be tested thoroughly before proceeding.

**Run `ktlintCheck test` after each phase to verify.**

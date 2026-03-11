# Friends & Mail

Two social systems for player-to-player interaction: a **friends list** with online/offline notifications, and an **in-game mail** system for asynchronous messaging.

---

## Friends

Track other players and receive notifications when they log in or out.

### Commands

| Command | Description |
|---------|-------------|
| `friend` / `friend list` / `friends` | Show your friends list with online status |
| `friend add <player>` | Add a player to your friends list |
| `friend remove <player>` | Remove a player (`rem`, `del`, `delete` also work) |

### Behavior

- **Online status:** The friends list shows each friend's online/offline status. Online friends also show their current level and zone.
- **Login notifications:** When a friend logs in, you receive a notification. This is **one-directional** — you only get notified about players on *your* list, regardless of whether they have you on theirs.
- **Logout notifications:** Same one-directional behavior for logouts.
- **Case-insensitive:** Names are matched case-insensitively for add/remove but displayed with their original casing.

### Limits

| Setting | Default | Config Key |
|---------|---------|------------|
| Max friends per player | 50 | `ambonmud.engine.friends.maxFriends` |

### Validation

- Cannot add yourself
- Cannot add a player who doesn't exist (must have a registered account)
- Cannot add duplicates
- Cannot exceed the max friends limit

### GMCP Packages

| Package | When Sent | Payload |
|---------|-----------|---------|
| `Friends.List` | On login, on `friend list` | Array of `{name, online, level?, zone?}` |
| `Friends.Online` | When a friend logs in | `{name, level}` |
| `Friends.Offline` | When a friend logs out | `{name}` |

### Persistence

Friends are stored as a set of lowercase player names per player:

- **YAML backend:** `friendsList` field in player YAML
- **PostgreSQL:** `friends_list TEXT` column, JSON array (migration `V16__add_player_friends.sql`)

---

## Mail

Send messages to other players, delivered instantly if online or stored for pickup when they next log in.

### Commands

| Command | Description |
|---------|-------------|
| `mail` / `mail list` | Show your inbox with unread markers |
| `mail read <n>` | Read message number *n* (1-based) and mark as read |
| `mail delete <n>` | Delete message number *n* (`del` also works) |
| `mail send <player>` | Begin composing a message to *player* |
| `mail abort` | Cancel an in-progress composition |

### Compose Flow

1. Type `mail send Alice` to enter compose mode
2. Type your message — each line is accumulated
3. Type `.` (a single period) on its own line to send
4. Or type `mail abort` to cancel

While in compose mode, all input is captured as message lines (bypassing normal command parsing). Empty messages are rejected.

### Message Structure

Each mail message contains:

| Field | Description |
|-------|-------------|
| `id` | Unique UUID |
| `fromName` | Sender's name (preserves original casing) |
| `body` | Message text (multiline, lines joined with `\n`) |
| `sentAtEpochMs` | Timestamp when sent |
| `read` | Whether the recipient has read it |

### Delivery

- **Online recipient:** Message appears in their inbox immediately; they receive a notification.
- **Offline recipient:** Message is saved to their player record; they'll see it on next login.
- **Unknown recipient:** Sender gets an error; message is not sent.

### Inbox Display

`mail list` shows messages in order with:
- Index number (1-based)
- `[NEW]` marker for unread messages
- Sender name
- Relative timestamp

### Limits

There are currently no enforced limits on inbox size, message length, or message TTL.

### Persistence

Mail is stored as a JSON array of message objects per player:

- **YAML backend:** `inbox` field in player YAML
- **PostgreSQL:** `mail_inbox TEXT` column, JSON array (migration `V9__add_player_mail.sql`)

Compose state (`mailCompose`) is runtime-only and cleared on logout.

---

## Key Source Files

| File | Purpose |
|------|---------|
| `engine/FriendsSystem.kt` | Friends logic, online notifications |
| `engine/commands/handlers/FriendsHandler.kt` | Friend command routing |
| `engine/commands/handlers/MailHandler.kt` | Mail command routing, compose mode |
| `domain/mail/MailMessage.kt` | Mail message data model |
| `engine/GmcpEmitter.kt` | Friends GMCP emissions |

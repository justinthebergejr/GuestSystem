# GuestSystem

A Paper plugin that lets players bring friends onto a private or members-only server as guests.

It was originally made for a university Minecraft server, but it works for any server where only certain players (students, a community, a friend group) should have full access.

A player sponsors a friend with `/guest add`. The guest can play whenever their sponsor is online, and gets a limited amount of playtime when they're not.

## Features

- Players sponsor guests with `/guest add <name>`
- Guests play as long as they want while their sponsor is online
- But have solo time limit for when the sponsor is offline (resets at midnight)
- Short grace period and a warning before being disconnected
- LuckPerms groups for guests and regular players, so each can sync to its own Discord role
- Works with EssentialsX DiscordLink
- Logs guest adds/removes to Discord through EssentialsX Discord

## Requirements

- Paper 26.2
- Java 25
- [LuckPerms](https://luckperms.net/)

Optional:

- EssentialsX DiscordLink, for account linking and role sync

## Installation

1. Download the jar from the releases page, or build it yourself
2. Put it in your server's `plugins/` folder
3. Restart the server
4. Edit `plugins/GuestSystem/config.yml` and run `/guest reload`

### Discord roles (optional)

To give guests and regular players separate Discord roles, map the LuckPerms groups in EssentialsDiscordLink's config:

```yaml
role-sync:
  groups:
    member: YOUR_MEMBER_ROLE_ID
    guest: YOUR_GUEST_ROLE_ID
```

Only put these under `groups`, not `roles`, or DiscordLink will remove them.

To send guest logs to a specific channel, add this under `message-types` in EssentialsDiscord's config:

```yaml
guests: staff
```

## Commands

| Command | Description |
| --- | --- |
| `/guest add <name>` | Sponsor a friend as your guest |
| `/guest remove <name>` | Remove one of your guests |
| `/guest list` | See your guests and their status |
| `/guest time` | Guests: check how much solo time is left today |
| `/guest list all` | Staff: list every guest |
| `/guest info <name>` | Staff: see who sponsored a guest |
| `/guest reload` | Staff: reload the config |

## Permissions

| Permission | Description | Default |
| --- | --- | --- |
| `guestsystem.sponsor` | Sponsor guests | everyone |
| `guestsystem.member` | Counts as a full member when the member gate is on | no one |
| `guestsystem.admin` | Manage all guests and reload | op |

## Configuration

The main options in `config.yml`:

```yaml
max-guests-per-sponsor: 2
sponsor-leave-grace-seconds: 60

solo-time:
  enabled: true
  minutes-per-day: 60
  timezone: America/New_York
  warn-at-minutes: [10, 5, 1]

guest-group: guest
member-group: member

member-gate:
  enabled: false

discord-log: true
```

Every message players see is under `messages:` and supports `&` color codes.

## Building

```
mvn package
```

jar file will be in `target/`.

package zone.justi.guestsystem;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public final class GuestSystem extends JavaPlugin implements Listener, TabExecutor {
    private static final Pattern NAME = Pattern.compile("^\\.?[A-Za-z0-9_]{1,16}$");
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private GuestStore store;
    private DiscordHook discord;
    private LuckPermsHook luckPerms;

    private final Set<UUID> online = ConcurrentHashMap.newKeySet();
    private final Map<UUID, BukkitTask> pendingKicks = new ConcurrentHashMap<>();
    private BukkitTask soloTicker;
    private int ticksSinceSave = 0;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        store = new GuestStore(getDataFolder(), getLogger());
        store.load();

        if (Bukkit.getPluginManager().isPluginEnabled("LuckPerms")) {
            luckPerms = LuckPermsHook.create(getLogger());
            ensureGroups();
        } else {
            getLogger().warning("LuckPerms not found: guests won't get the guest group/prefix, and member-gate can't work.");
        }

        if (Bukkit.getPluginManager().isPluginEnabled("EssentialsDiscord")
                || Bukkit.getPluginManager().isPluginEnabled("EssentialsDiscordLink")) {
            discord = DiscordHook.create(this);
            getLogger().info("EssentialsX Discord hook: " + (discord.hasDiscord() ? "logging on" : "logging off")
                    + ", DiscordLink: " + (discord.hasLink() ? "found" : "not found"));
        }

        for (Player p : Bukkit.getOnlinePlayers()) online.add(p.getUniqueId());

        getServer().getPluginManager().registerEvents(this, this);
        soloTicker = Bukkit.getScheduler().runTaskTimer(this, this::tickSolo, 20L, 20L);
        var cmd = getCommand("guest");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
        getLogger().info("Loaded " + store.all().size() + " guest(s). Member gate: "
                + (memberGate() ? "ON" : "off"));
    }

    @Override
    public void onDisable() {
        pendingKicks.values().forEach(BukkitTask::cancel);
        pendingKicks.clear();
        if (soloTicker != null) soloTicker.cancel();
        if (store != null) store.save();
    }

    private String guestGroup() {
        return getConfig().getString("guest-group", "guest");
    }

    private String memberGroup() {
        if (getConfig().isSet("member-group")) return getConfig().getString("member-group", "");
        if (getConfig().isSet("student-group")) return getConfig().getString("student-group", "");
        return "member";
    }

    private void ensureGroups() {
        if (luckPerms == null) return;
        luckPerms.ensureGroup(guestGroup(), getConfig().getString("guest-prefix"));
        if (!memberGroup().isEmpty()) luckPerms.ensureGroup(memberGroup(), "");
    }

    private void applyGroups(UUID uuid, boolean isGuest) {
        if (luckPerms == null) return;
        String member = memberGroup();
        if (isGuest) {
            luckPerms.addGroup(uuid, guestGroup());
            if (!member.isEmpty()) luckPerms.removeGroup(uuid, member);
        } else {
            luckPerms.removeGroup(uuid, guestGroup());
            if (!member.isEmpty()) luckPerms.addGroup(uuid, member);
        }
    }

    private boolean soloEnabled() {
        return getConfig().getBoolean("solo-time.enabled", true);
    }

    private long soloLimitSeconds() {
        return Math.max(0, getConfig().getLong("solo-time.minutes-per-day", 60)) * 60L;
    }

    private String today() {
        ZoneId zone;
        try {
            zone = ZoneId.of(getConfig().getString("solo-time.timezone", "America/New_York"));
        } catch (Exception e) {
            zone = ZoneId.systemDefault();
        }
        return LocalDate.now(zone).toString();
    }

    private void rollDay(GuestStore.Guest g) {
        String today = today();
        if (!today.equals(g.soloDay)) {
            g.soloDay = today;
            g.soloUsed = 0;
        }
    }

    private long soloRemaining(GuestStore.Guest g) {
        if (!soloEnabled()) return 0;
        rollDay(g);
        return Math.max(0, soloLimitSeconds() - g.soloUsed);
    }

    private static String fmt(long seconds) {
        if (seconds < 60) return seconds + "s";
        long h = seconds / 3600, m = (seconds % 3600) / 60;
        return h > 0 ? h + "h " + m + "m" : m + "m";
    }

    private void tickSolo() {
        if (!soloEnabled()) return;
        long limit = soloLimitSeconds();
        List<Integer> warnAt = getConfig().getIntegerList("solo-time.warn-at-minutes");
        boolean changed = false;

        for (GuestStore.Guest g : store.all()) {
            if (g.uuid == null || online.contains(g.sponsor)) continue;
            Player p = Bukkit.getPlayer(g.uuid);
            if (p == null) continue;
            rollDay(g);

            if (g.soloUsed >= limit) {
                if (!pendingKicks.containsKey(g.sponsor)) {
                    p.kick(msg("solo-kick", "sponsor", g.sponsorName, "reset", "midnight"));
                }
                continue;
            }

            g.soloUsed++;
            changed = true;
            long left = limit - g.soloUsed;
            if (left <= 0) {
                p.kick(msg("solo-kick", "sponsor", g.sponsorName, "reset", "midnight"));
            } else if (left % 60 == 0 && warnAt.contains((int) (left / 60))) {
                p.sendMessage(msg("solo-warning", "time", fmt(left)));
            }
        }

        if (changed && ++ticksSinceSave >= 60) {
            ticksSinceSave = 0;
            store.save();
        }
    }

    private boolean memberGate() {
        return getConfig().getBoolean("member-gate.enabled", false);
    }

    private int maxGuests() {
        return Math.max(0, getConfig().getInt("max-guests-per-sponsor", 2));
    }

    private int graceSeconds() {
        return Math.max(0, getConfig().getInt("sponsor-leave-grace-seconds", 60));
    }

    private String raw(String key, String... kv) {
        String s = getConfig().getString("messages." + key);
        if (s == null) s = key;
        for (int i = 0; i + 1 < kv.length; i += 2) s = s.replace("{" + kv[i] + "}", kv[i + 1]);
        return s;
    }

    private Component msg(String key, String... kv) {
        return LEGACY.deserialize(raw(key, kv).replace("\\n", "\n"));
    }

    private void discordLog(String key, String... kv) {
        if (discord != null && getConfig().getBoolean("discord-log", true)) discord.log(raw(key, kv));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;

        UUID uuid = event.getUniqueId();
        String name = event.getName();

        GuestStore.Guest guest = store.find(uuid, name);
        if (guest != null) {
            if (guest.uuid == null || !guest.name.equals(name)) {
                guest.uuid = uuid;
                guest.name = name;
                store.save();
            }
            if (!online.contains(guest.sponsor)) {
                if (soloEnabled()) {
                    if (soloRemaining(guest) <= 0) {
                        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                                msg("solo-used-up", "sponsor", guest.sponsorName, "reset", "midnight"));
                    }
                } else {
                    event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                            msg("sponsor-offline", "sponsor", guest.sponsorName));
                }
            }
            return;
        }

        if (memberGate() && !isMember(uuid)) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, msg("not-a-member"));
        }
    }

    private boolean isMember(UUID uuid) {
        if (Bukkit.getOfflinePlayer(uuid).isOp()) return true;
        if (luckPerms == null) return true;
        return luckPerms.hasPermissionAsync(uuid, "guestsystem.member")
                || luckPerms.hasPermissionAsync(uuid, "guestsystem.admin");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        UUID uuid = p.getUniqueId();
        online.add(uuid);

        BukkitTask pending = pendingKicks.remove(uuid);
        if (pending != null) pending.cancel();
        if (soloEnabled()) {
            for (Player g : onlineGuestsOf(uuid)) g.sendMessage(msg("solo-paused", "sponsor", p.getName()));
        }
        List<GuestStore.Guest> mine = store.ofSponsor(uuid);
        if (!mine.isEmpty() && mine.stream().anyMatch(g -> !g.sponsorName.equals(p.getName()))) {
            mine.forEach(g -> g.sponsorName = p.getName());
            store.save();
        }

        GuestStore.Guest guest = store.find(uuid, p.getName());
        applyGroups(uuid, guest != null);
        if (guest != null) {
            if (!online.contains(guest.sponsor)) {
                long left = soloRemaining(guest);
                if (left > 0) {
                    Bukkit.getScheduler().runTaskLater(this, () -> p.sendMessage(
                            msg("solo-started", "sponsor", guest.sponsorName, "time", fmt(left))), 20L);
                } else {
                    Bukkit.getScheduler().runTask(this, () ->
                            p.kick(msg("sponsor-offline", "sponsor", guest.sponsorName)));
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        online.remove(uuid);
        if (store.find(uuid, event.getPlayer().getName()) != null) store.save();

        List<Player> onlineGuests = new ArrayList<>();
        for (Player g : onlineGuestsOf(uuid)) {
            GuestStore.Guest guest = store.find(g.getUniqueId(), g.getName());
            long left = guest == null ? 0 : soloRemaining(guest);
            if (left > 0) {
                g.sendMessage(msg("solo-started", "sponsor", event.getPlayer().getName(), "time", fmt(left)));
            } else {
                onlineGuests.add(g);
            }
        }
        if (onlineGuests.isEmpty()) return;

        String sponsorName = event.getPlayer().getName();
        int grace = graceSeconds();
        if (grace == 0) {
            onlineGuests.forEach(g -> g.kick(msg("sponsor-left-kick", "sponsor", sponsorName)));
            return;
        }
        onlineGuests.forEach(g -> g.sendMessage(msg("sponsor-left-warning",
                "sponsor", sponsorName, "seconds", String.valueOf(grace))));

        BukkitTask old = pendingKicks.put(uuid, Bukkit.getScheduler().runTaskLater(this, () -> {
            pendingKicks.remove(uuid);
            if (online.contains(uuid)) return;
            for (Player g : onlineGuestsOf(uuid)) {
                GuestStore.Guest guest = store.find(g.getUniqueId(), g.getName());
                if (guest == null || soloRemaining(guest) <= 0) {
                    g.kick(msg("sponsor-left-kick", "sponsor", sponsorName));
                }
            }
        }, grace * 20L));
        if (old != null) old.cancel();
    }

    private List<Player> onlineGuestsOf(UUID sponsor) {
        List<Player> out = new ArrayList<>();
        for (GuestStore.Guest g : store.ofSponsor(sponsor)) {
            if (g.uuid == null) continue;
            Player p = Bukkit.getPlayer(g.uuid);
            if (p != null) out.add(p);
        }
        return out;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) return help(sender, label);
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "add" -> add(sender, args);
            case "remove", "rm", "del" -> remove(sender, args);
            case "list" -> list(sender, args);
            case "info" -> info(sender, args);
            case "time" -> time(sender, args);
            case "reload" -> reload(sender);
            default -> help(sender, label);
        }
        return true;
    }

    private boolean help(CommandSender sender, String label) {
        sender.sendMessage(LEGACY.deserialize(
                "&6/" + label + " add <name> &7- sponsor a friend (Bedrock: &f.Name&7)\n"
                        + "&6/" + label + " remove <name> &7- remove one of your guests\n"
                        + "&6/" + label + " list &7- see your guests\n"
                        + "&6/" + label + " time &7- guests: check your solo time left today"
                        + (sender.hasPermission("guestsystem.admin")
                        ? "\n&6/" + label + " list all|<sponsor> &7- staff: list guests\n"
                        + "&6/" + label + " info <guest> &7- staff: who sponsored them\n"
                        + "&6/" + label + " reload &7- staff: reload config" : "")));
        return true;
    }

    private void add(CommandSender sender, String[] args) {
        if (!(sender instanceof Player sponsor)) {
            sender.sendMessage(LEGACY.deserialize("&cOnly players can sponsor guests."));
            return;
        }
        if (!sponsor.hasPermission("guestsystem.sponsor")) {
            sender.sendMessage(LEGACY.deserialize("&cYou don't have permission to sponsor guests."));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(LEGACY.deserialize("&cUsage: /guest add <name>"));
            return;
        }
        String name = args[1];
        if (!NAME.matcher(name).matches()) {
            sender.sendMessage(msg("invalid-name"));
            return;
        }
        if (store.find(sponsor.getUniqueId(), sponsor.getName()) != null) {
            sender.sendMessage(msg("guests-cant-sponsor"));
            return;
        }
        if (name.equalsIgnoreCase(sponsor.getName())) {
            sender.sendMessage(msg("cant-add-self"));
            return;
        }
        GuestStore.Guest existing = store.byName(name);
        if (existing != null) {
            sender.sendMessage(msg("already-guest", "guest", existing.name, "sponsor", existing.sponsorName));
            return;
        }
        int max = maxGuests();
        if (!sponsor.hasPermission("guestsystem.admin") && store.ofSponsor(sponsor.getUniqueId()).size() >= max) {
            sender.sendMessage(msg("limit-reached", "max", String.valueOf(max)));
            return;
        }

        GuestStore.Guest added = store.add(name, sponsor.getUniqueId(), sponsor.getName());
        Player target = Bukkit.getPlayerExact(name);
        if (target != null) {
            added.uuid = target.getUniqueId();
            added.name = target.getName();
            store.save();
            applyGroups(target.getUniqueId(), true);
        }
        sender.sendMessage(msg("added", "guest", name));
        discordLog("discord-added", "sponsor", sponsor.getName(), "guest", name);
        getLogger().info(sponsor.getName() + " added guest " + name);
    }

    private void remove(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(LEGACY.deserialize("&cUsage: /guest remove <name>"));
            return;
        }
        GuestStore.Guest g = store.byName(args[1]);
        if (g == null) {
            for (GuestStore.Guest x : store.all()) if (x.name.equalsIgnoreCase(args[1])) g = x;
        }
        boolean admin = sender.hasPermission("guestsystem.admin");
        boolean own = sender instanceof Player p && g != null && g.sponsor.equals(p.getUniqueId());
        if (g == null || (!own && !admin)) {
            sender.sendMessage(msg("not-your-guest", "guest", args[1]));
            return;
        }

        store.remove(g);
        if (g.uuid != null) {
            if (luckPerms != null) luckPerms.removeGroup(g.uuid, guestGroup());
            Player online = Bukkit.getPlayer(g.uuid);
            if (online != null) online.kick(msg("guest-removed-kick", "sponsor", sender.getName()));
        }
        sender.sendMessage(msg("removed", "guest", g.name));
        discordLog("discord-removed", "sponsor", sender.getName(), "guest", g.name);
        getLogger().info(sender.getName() + " removed guest " + g.name);
    }

    private void list(CommandSender sender, String[] args) {
        boolean admin = sender.hasPermission("guestsystem.admin");
        List<GuestStore.Guest> guests;
        String title;

        if (args.length >= 2 && admin) {
            if (args[1].equalsIgnoreCase("all")) {
                guests = new ArrayList<>(store.all());
                title = "All guests";
            } else {
                OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(args[1]);
                if (target == null) {
                    sender.sendMessage(LEGACY.deserialize("&cNo player named " + args[1] + " has joined before."));
                    return;
                }
                guests = store.ofSponsor(target.getUniqueId());
                title = "Guests of " + args[1];
            }
        } else if (sender instanceof Player p) {
            guests = store.ofSponsor(p.getUniqueId());
            title = "Your guests (" + guests.size() + "/" + maxGuests() + ")";
        } else {
            sender.sendMessage(LEGACY.deserialize("&cUsage from console: /guest list all|<sponsor>"));
            return;
        }

        StringBuilder sb = new StringBuilder("&6" + title + ":");
        if (guests.isEmpty()) sb.append("\n&7  none");
        for (GuestStore.Guest g : guests) {
            sb.append("\n&7- &f").append(g.name).append(' ').append(status(g));
            if (args.length >= 2 && args[1].equalsIgnoreCase("all")) sb.append(" &8(by ").append(g.sponsorName).append(')');
        }
        sender.sendMessage(LEGACY.deserialize(sb.toString()));
    }

    private void time(CommandSender sender, String[] args) {
        GuestStore.Guest g = null;
        if (args.length >= 2 && sender.hasPermission("guestsystem.admin")) {
            g = store.byName(args[1]);
            if (g == null) {
                sender.sendMessage(LEGACY.deserialize("&c" + args[1] + " isn't a guest."));
                return;
            }
        } else if (sender instanceof Player p) {
            g = store.find(p.getUniqueId(), p.getName());
        }
        if (g == null) {
            sender.sendMessage(msg("not-a-guest"));
            return;
        }
        if (!soloEnabled()) {
            sender.sendMessage(LEGACY.deserialize("&7Solo time is turned off. Guests can only play while their sponsor is online."));
            return;
        }
        sender.sendMessage(msg("solo-status", "time", fmt(soloRemaining(g)), "sponsor", g.sponsorName));
    }

    private void info(CommandSender sender, String[] args) {
        if (!sender.hasPermission("guestsystem.admin")) {
            sender.sendMessage(LEGACY.deserialize("&cNo permission."));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(LEGACY.deserialize("&cUsage: /guest info <guest>"));
            return;
        }
        GuestStore.Guest g = store.byName(args[1]);
        if (g == null) {
            for (GuestStore.Guest x : store.all()) if (x.name.equalsIgnoreCase(args[1])) g = x;
        }
        if (g == null) {
            sender.sendMessage(LEGACY.deserialize("&c" + args[1] + " isn't a guest."));
            return;
        }
        String added = new SimpleDateFormat("MMM d, yyyy h:mm a").format(new Date(g.addedAt));
        sender.sendMessage(LEGACY.deserialize(
                "&6Guest &f" + g.name + " " + status(g)
                        + "\n&7Sponsor: &f" + g.sponsorName + (online.contains(g.sponsor) ? " &a(online)" : " &8(offline)")
                        + "\n&7Added: &f" + added
                        + (soloEnabled() ? "\n&7Solo time left today: &f" + fmt(soloRemaining(g)) : "")
                        + "\n&7UUID: &f" + (g.uuid == null ? "not joined yet" : g.uuid.toString())));
    }

    private String status(GuestStore.Guest g) {
        if (g.uuid == null) return "&8[hasn't joined yet]";
        if (online.contains(g.uuid)) return "&a[online]";
        Boolean linked = discord == null ? null : discord.isLinked(g.uuid);
        if (linked == null) return "&7[joined]";
        return linked ? "&a[linked]" : "&e[not linked yet]";
    }

    private void reload(CommandSender sender) {
        if (!sender.hasPermission("guestsystem.admin")) {
            sender.sendMessage(LEGACY.deserialize("&cNo permission."));
            return;
        }
        reloadConfig();
        store.load();
        ensureGroups();
        sender.sendMessage(LEGACY.deserialize("&aGuestSystem reloaded. &7Member gate: "
                + (memberGate() ? "&aON" : "&eoff")));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        boolean admin = sender.hasPermission("guestsystem.admin");
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            out.addAll(List.of("add", "remove", "list", "time"));
            if (admin) out.addAll(List.of("info", "reload"));
        } else if (args.length == 2) {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "remove", "rm", "del" -> {
                    if (admin) store.all().forEach(g -> out.add(g.name));
                    else if (sender instanceof Player p) store.ofSponsor(p.getUniqueId()).forEach(g -> out.add(g.name));
                }
                case "info", "time" -> { if (admin) store.all().forEach(g -> out.add(g.name)); }
                case "list" -> {
                    if (admin) {
                        out.add("all");
                        Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
                    }
                }
                default -> { }
            }
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(prefix));
        return out;
    }
}

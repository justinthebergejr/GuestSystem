package zone.justi.guestsystem;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public final class GuestStore {
    public static final class Guest {
        public final String key;
        public volatile String name;
        public volatile UUID uuid;
        public final UUID sponsor;
        public volatile String sponsorName;
        public final long addedAt;
        public volatile long soloUsed;
        public volatile String soloDay;

        Guest(String key, String name, UUID uuid, UUID sponsor, String sponsorName, long addedAt) {
            this.key = key;
            this.name = name;
            this.uuid = uuid;
            this.sponsor = sponsor;
            this.sponsorName = sponsorName;
            this.addedAt = addedAt;
        }
    }

    private final File file;
    private final Logger log;
    private final Map<String, Guest> byKey = new ConcurrentHashMap<>();

    public GuestStore(File dataFolder, Logger log) {
        this.file = new File(dataFolder, "guests.yml");
        this.log = log;
    }

    public static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    public synchronized void load() {
        byKey.clear();
        if (!file.exists()) return;
        YamlConfiguration yml = new YamlConfiguration();
        yml.options().pathSeparator('/');
        try {
            yml.load(file);
        } catch (Exception e) {
            log.severe("Could not read guests.yml: " + e.getMessage());
            return;
        }
        ConfigurationSection root = yml.getConfigurationSection("guests");
        if (root == null) return;
        for (String k : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(k);
            if (s == null) continue;
            try {
                String uuidStr = s.getString("uuid");
                byKey.put(k, new Guest(
                        k,
                        s.getString("name", k),
                        uuidStr == null || uuidStr.isEmpty() ? null : UUID.fromString(uuidStr),
                        UUID.fromString(s.getString("sponsor")),
                        s.getString("sponsor-name", "?"),
                        s.getLong("added", 0L)));
                Guest g = byKey.get(k);
                g.soloUsed = s.getLong("solo-used", 0L);
                g.soloDay = s.getString("solo-day", "");
            } catch (Exception e) {
                log.warning("Skipping bad guests.yml entry '" + k + "': " + e.getMessage());
            }
        }
    }

    public synchronized void save() {
        YamlConfiguration yml = new YamlConfiguration();
        yml.options().pathSeparator('/');
        for (Guest g : byKey.values()) {
            String p = "guests/" + g.key + "/";
            yml.set(p + "name", g.name);
            yml.set(p + "uuid", g.uuid == null ? "" : g.uuid.toString());
            yml.set(p + "sponsor", g.sponsor.toString());
            yml.set(p + "sponsor-name", g.sponsorName);
            yml.set(p + "added", g.addedAt);
            yml.set(p + "solo-used", g.soloUsed);
            yml.set(p + "solo-day", g.soloDay == null ? "" : g.soloDay);
        }
        try {
            yml.save(file);
        } catch (IOException e) {
            log.severe("Could not save guests.yml: " + e.getMessage());
        }
    }

    public Guest byName(String name) {
        return byKey.get(key(name));
    }

    public Guest find(UUID uuid, String name) {
        for (Guest g : byKey.values()) {
            if (uuid.equals(g.uuid)) return g;
        }
        Guest g = byKey.get(key(name));
        if (g != null && g.uuid == null) return g;
        return null;
    }

    public List<Guest> ofSponsor(UUID sponsor) {
        List<Guest> out = new ArrayList<>();
        for (Guest g : byKey.values()) if (g.sponsor.equals(sponsor)) out.add(g);
        return out;
    }

    public Collection<Guest> all() {
        return byKey.values();
    }

    public Guest add(String name, UUID sponsor, String sponsorName) {
        Guest g = new Guest(key(name), name, null, sponsor, sponsorName, System.currentTimeMillis());
        byKey.put(g.key, g);
        save();
        return g;
    }

    public void remove(Guest g) {
        byKey.remove(g.key);
        save();
    }
}

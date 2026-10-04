package zone.justi.guestsystem;

import net.essentialsx.api.v2.services.discord.DiscordService;
import net.essentialsx.api.v2.services.discord.MessageType;
import net.essentialsx.api.v2.services.discordlink.DiscordLinkService;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.UUID;

final class DiscordHook {
    static final MessageType GUESTS = new MessageType("guests");

    private final DiscordService discord;
    private final DiscordLinkService link;

    private DiscordHook(DiscordService discord, DiscordLinkService link) {
        this.discord = discord;
        this.link = link;
    }

    static DiscordHook create(Plugin plugin) {
        DiscordService discord = null;
        DiscordLinkService link = null;
        if (Bukkit.getPluginManager().isPluginEnabled("EssentialsDiscord")) {
            discord = Bukkit.getServicesManager().load(DiscordService.class);
            if (discord != null && !discord.isRegistered(GUESTS.getKey())) {
                try {
                    discord.registerMessageType(plugin, GUESTS);
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        if (Bukkit.getPluginManager().isPluginEnabled("EssentialsDiscordLink")) {
            link = Bukkit.getServicesManager().load(DiscordLinkService.class);
        }
        return new DiscordHook(discord, link);
    }

    boolean hasDiscord() {
        return discord != null;
    }

    boolean hasLink() {
        return link != null;
    }

    void log(String message) {
        if (discord != null) discord.sendMessage(GUESTS, message, false);
    }

    Boolean isLinked(UUID uuid) {
        if (link == null || uuid == null) return null;
        return link.isLinked(uuid);
    }
}

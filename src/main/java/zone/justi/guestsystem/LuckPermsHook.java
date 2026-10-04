package zone.justi.guestsystem;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.types.InheritanceNode;
import net.luckperms.api.node.types.PrefixNode;
import net.luckperms.api.util.Tristate;

import java.util.UUID;
import java.util.logging.Logger;

final class LuckPermsHook {
    private final LuckPerms lp;
    private final Logger log;

    private LuckPermsHook(LuckPerms lp, Logger log) {
        this.lp = lp;
        this.log = log;
    }

    static LuckPermsHook create(Logger log) {
        try {
            return new LuckPermsHook(LuckPermsProvider.get(), log);
        } catch (IllegalStateException e) {
            return null;
        }
    }

    void ensureGroup(String group, String prefix) {
        if (lp.getGroupManager().getGroup(group) != null) return;
        lp.getGroupManager().createAndLoadGroup(group).thenAccept(g -> {
            boolean hasPrefix = g.getNodes(NodeType.PREFIX).stream().findAny().isPresent();
            if (!hasPrefix && prefix != null && !prefix.isEmpty()) {
                g.data().add(PrefixNode.builder(prefix, 5).build());
                lp.getGroupManager().saveGroup(g);
            }
            log.info("Created LuckPerms group '" + group + "'");
        });
    }

    void addGroup(UUID uuid, String group) {
        lp.getUserManager().modifyUser(uuid, u -> u.data().add(InheritanceNode.builder(group).build()));
    }

    void removeGroup(UUID uuid, String group) {
        lp.getUserManager().modifyUser(uuid, u -> u.data().remove(InheritanceNode.builder(group).build()));
    }

    boolean hasPermissionAsync(UUID uuid, String permission) {
        User user = lp.getUserManager().getUser(uuid);
        if (user == null) user = lp.getUserManager().loadUser(uuid).join();
        return user.getCachedData().getPermissionData().checkPermission(permission) == Tristate.TRUE;
    }
}

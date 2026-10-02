package com.dogpound.prideperms;

import com.mojang.authlib.GameProfile;
import net.minecraftforge.server.permission.DefaultPermissionHandler;
import net.minecraftforge.server.permission.DefaultPermissionLevel;
import net.minecraftforge.server.permission.IPermissionHandler;
import net.minecraftforge.server.permission.PermissionAPI;
import net.minecraftforge.server.permission.context.IContext;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Forge's permission system. Mods register nodes with a default level (everyone / ops / nobody) and later ask
 * "may this player…?". A PridePerms rule wins; with no rule, the mod's own default applies — exactly as before.
 */
public final class Handler implements IPermissionHandler {
    static final Handler INSTANCE = new Handler();
    final Map<String, DefaultPermissionLevel> levels = new ConcurrentHashMap<>();
    final Map<String, String> descriptions = new ConcurrentHashMap<>();

    private Handler() {}

    static void install() {
        IPermissionHandler current = PermissionAPI.getPermissionHandler();
        if (!(current instanceof DefaultPermissionHandler)) {
            PridePerms.LOG.warn("Another mod already answers Forge permissions ({}); PridePerms leaves it alone", current.getClass().getName());
            return;
        }
        copyEarlyNodes(current);
        PermissionAPI.setPermissionHandler(INSTANCE);
        PridePerms.LOG.info("PridePerms now answers Forge permissions");
    }

    /** nodes registered before us (preInit order) — keep their levels */
    @SuppressWarnings("unchecked")
    private static void copyEarlyNodes(IPermissionHandler from) {
        try {
            for (String name : new String[]{"PERMISSION_LEVEL_MAP", "permissionLevelMap"}) {
                try {
                    Field f = DefaultPermissionHandler.class.getDeclaredField(name);
                    f.setAccessible(true);
                    INSTANCE.levels.putAll((Map<String, DefaultPermissionLevel>) f.get(from));
                    break;
                } catch (NoSuchFieldException ignored) {}
            }
            for (String n : from.getRegisteredNodes()) {
                INSTANCE.levels.putIfAbsent(n, DefaultPermissionLevel.OP);
                String d = from.getNodeDescription(n);
                if (d != null && !d.isEmpty()) INSTANCE.descriptions.put(n, d);
            }
        } catch (Throwable t) {
            PridePerms.LOG.warn("couldn't copy nodes registered before PridePerms: {}", t.toString());
        }
    }

    @Override
    public void registerNode(String node, DefaultPermissionLevel level, String desc) {
        levels.put(node, level);
        if (desc != null && !desc.isEmpty()) descriptions.put(node, desc);
        Known.seen(node);
    }

    @Override public Collection<String> getRegisteredNodes() { return Collections.unmodifiableSet(levels.keySet()); }

    @Override
    public boolean hasPermission(GameProfile profile, String node, @Nullable IContext context) {
        Boolean d = profile == null ? null : PridePerms.decide(profile.getId(), node);
        if (d != null) return d;
        DefaultPermissionLevel l = levels.getOrDefault(node, DefaultPermissionLevel.OP);
        if (l == DefaultPermissionLevel.ALL) return true;
        if (l == DefaultPermissionLevel.NONE) return false;
        return profile != null && PridePerms.isOp(profile.getId());
    }

    @Override public String getNodeDescription(String node) { return descriptions.getOrDefault(node, ""); }
}

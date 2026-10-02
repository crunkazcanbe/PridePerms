package com.dogpound.prideperms;

import net.minecraft.inventory.Container;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.ModContainer;

import java.io.File;
import java.net.URL;
import java.security.CodeSource;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Which mod a class (e.g. a machine's menu) comes from — found by the jar it was loaded from. */
final class Owners {
    private static final Map<Class<?>, String> byClass = new ConcurrentHashMap<>();
    private static volatile Map<String, String> byJar;             // absolute jar path → modid

    private Owners() {}

    /** "<modid>.open.<menu>", e.g. ic2.open.macerator; null when unknown */
    static String containerNode(Container c) {
        if (c == null) return null;
        String mod = modOf(c.getClass());
        String n = c.getClass().getSimpleName();
        if (n.startsWith("Container") && n.length() > 9) n = n.substring(9);
        else if (n.endsWith("Container") && n.length() > 9) n = n.substring(0, n.length() - 9);
        return mod + ".open." + Guard.clean(n.isEmpty() ? "menu" : n);
    }

    static String modOf(Class<?> k) {
        return byClass.computeIfAbsent(k, Owners::find);
    }

    private static String find(Class<?> k) {
        if (k.getName().startsWith("net.minecraft.")) return "minecraft";
        try {
            CodeSource cs = k.getProtectionDomain().getCodeSource();
            URL u = cs == null ? null : cs.getLocation();
            if (u != null) {
                String path = u.getPath();
                int bang = path.indexOf('!');                              // jar:file:/x.jar!/pkg/...
                if (bang > 0) path = path.substring(0, bang);
                if (path.startsWith("file:")) path = path.substring(5);
                String mod = jars().get(new File(java.net.URLDecoder.decode(path, "UTF-8")).getAbsolutePath());
                if (mod != null) return mod;
            }
        } catch (Throwable ignored) {}
        String p = k.getName();                                             // fallback: a package part that is a modid
        for (String part : p.split("\\.")) if (Loader.isModLoaded(part)) return part;
        return "unknown";
    }

    private static Map<String, String> jars() {
        Map<String, String> m = byJar;
        if (m == null) {
            m = new HashMap<>();
            for (ModContainer mc : Loader.instance().getActiveModList()) {
                File src = mc.getSource();
                if (src != null && src.isFile()) m.putIfAbsent(src.getAbsolutePath(), mc.getModId());
            }
            byJar = m;
        }
        return m;
    }
}

package com.dogpound.prideperms;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Every node anyone has asked about or registered — what /perms nodes and the menu list. */
final class Known {
    private static final Set<String> nodes = ConcurrentHashMap.newKeySet();

    private Known() {}

    static void seen(String node) { if (nodes.size() < 200_000) nodes.add(node); }

    static List<String> search(String filter) {
        String f = filter == null ? "" : filter.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String n : nodes) if (n.contains(f)) out.add(n);
        Collections.sort(out);
        return out;
    }
}

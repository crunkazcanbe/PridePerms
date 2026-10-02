package com.dogpound.prideperms;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;
import zone.rong.mixinbooter.IEarlyMixinLoader;

/** Loads the one early patch: "may this player use that command?" asks PridePerms first. */
@IFMLLoadingPlugin.Name("PridePerms")
@IFMLLoadingPlugin.MCVersion("1.12.2")
@IFMLLoadingPlugin.SortingIndex(1004)
public class PridePermsCore implements IFMLLoadingPlugin, IEarlyMixinLoader {
    @Override public List<String> getMixinConfigs() { return java.util.Arrays.asList("prideperms.mixins.json", "prideperms.bridge.mixins.json"); }
    @Override public void injectData(Map<String, Object> data) {}
    @Override public String[] getASMTransformerClass() { return new String[0]; }
    @Override public String getModContainerClass() { return null; }
    @Override public String getSetupClass() { return null; }
    @Override public String getAccessTransformerClass() { return null; }
}

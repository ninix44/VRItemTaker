package org.vmstudio.itemtaker.core.client;

import org.vmstudio.visor.api.VisorAPI;
import org.vmstudio.visor.api.common.addon.VisorAddon;
import org.vmstudio.itemtaker.core.client.overlays.VROverlayExample;
import org.vmstudio.itemtaker.core.common.ItemTaker;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class ItemTakerAddonClient implements VisorAddon {
    @Override
    public void onAddonLoad() {
        VisorAPI.addonManager().getRegistries()
                .overlays()
                .registerComponents(
                        List.of(
                                new VROverlayExample(
                                        this,
                                        VROverlayExample.ID
                                )
                        )
                );
    }

    @Override
    public @Nullable String getAddonPackagePath() {
        return "org.vmstudio.itemtaker.core.client";
    }

    @Override
    public @NotNull String getAddonId() {
        return ItemTaker.MOD_ID;
    }
    @Override
    public @NotNull Component getAddonName() {
        return Component.literal(ItemTaker.MOD_NAME);
    }
    @Override
    public String getModId() {
        return ItemTaker.MOD_ID;
    }
}

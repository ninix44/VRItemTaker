package org.vmstudio.itemtaker.core.server;

import org.vmstudio.visor.api.common.addon.VisorAddon;
import org.vmstudio.itemtaker.core.common.ItemTaker;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class ItemTakerAddonServer implements VisorAddon {
    @Override
    public void onAddonLoad() {

    }

    @Override
    public @Nullable String getAddonPackagePath() {
        return "org.vmstudio.itemtaker.core.server";
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

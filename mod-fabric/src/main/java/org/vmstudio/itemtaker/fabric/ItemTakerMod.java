package org.vmstudio.itemtaker.fabric;

import org.vmstudio.visor.api.ModLoader;
import org.vmstudio.visor.api.VisorAPI;
import org.vmstudio.itemtaker.core.client.ItemTakerAddonClient;
import org.vmstudio.itemtaker.core.server.ItemTakerAddonServer;
import net.fabricmc.api.ModInitializer;

public class ItemTakerMod implements ModInitializer {
    @Override
    public void onInitialize() {
        if(ModLoader.get().isDedicatedServer()){
            VisorAPI.registerAddon(
                    new ItemTakerAddonServer()
            );
        }else{
            VisorAPI.registerAddon(
                    new ItemTakerAddonClient()
            );
        }
    }
}

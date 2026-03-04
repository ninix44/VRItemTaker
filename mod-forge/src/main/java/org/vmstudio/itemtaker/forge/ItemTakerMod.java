package org.vmstudio.itemtaker.forge;

import org.vmstudio.visor.api.ModLoader;
import org.vmstudio.visor.api.VisorAPI;
import org.vmstudio.itemtaker.core.client.ItemTakerAddonClient;
import org.vmstudio.itemtaker.core.common.ItemTaker;
import org.vmstudio.itemtaker.core.server.ItemTakerAddonServer;
import net.minecraftforge.fml.common.Mod;

@Mod(ItemTaker.MOD_ID)
public class ItemTakerMod {
    public ItemTakerMod(){
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

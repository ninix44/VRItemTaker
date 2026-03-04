package org.vmstudio.itemtaker.fabric;

import io.netty.buffer.Unpooled;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import org.vmstudio.itemtaker.core.client.ItemTakerLogic;
import org.vmstudio.itemtaker.core.common.ItemTaker;
import org.vmstudio.visor.api.ModLoader;
import org.vmstudio.visor.api.VisorAPI;
import org.vmstudio.itemtaker.core.client.ItemTakerAddonClient;
import org.vmstudio.itemtaker.core.server.ItemTakerAddonServer;

public class ItemTakerMod implements ModInitializer {
    public static final ResourceLocation SYNC_ITEM = new ResourceLocation(ItemTaker.MOD_ID, "sync_item");

    @Override
    public void onInitialize() {
        ItemTakerLogic.bridge = (entity, x, y, z, vx, vy, vz, noGrav) -> {
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            buf.writeInt(entity.getId());
            buf.writeDouble(x); buf.writeDouble(y); buf.writeDouble(z);
            buf.writeDouble(vx); buf.writeDouble(vy); buf.writeDouble(vz);
            buf.writeBoolean(noGrav);
            ClientPlayNetworking.send(SYNC_ITEM, buf);
        };

        ServerPlayNetworking.registerGlobalReceiver(SYNC_ITEM, (server, player, handler, buf, responseSender) -> {
            int id = buf.readInt();
            double x = buf.readDouble(), y = buf.readDouble(), z = buf.readDouble();
            double vx = buf.readDouble(), vy = buf.readDouble(), vz = buf.readDouble();
            boolean noGrav = buf.readBoolean();

            server.execute(() -> {
                Entity entity = player.level().getEntity(id);
                if (entity != null) {
                    entity.setNoGravity(noGrav);
                    entity.teleportTo(x, y, z);
                    entity.setDeltaMovement(vx, vy, vz);
                    entity.hasImpulse = true;
                }
            });
        });

        if(ModLoader.get().isDedicatedServer()){
            VisorAPI.registerAddon(
                    new ItemTakerAddonServer()
            );
        }else{
            VisorAPI.registerAddon(
                    new ItemTakerAddonClient()
            );
            ClientTickEvents.END_CLIENT_TICK.register(client -> ItemTakerLogic.tick());
        }
    }
}

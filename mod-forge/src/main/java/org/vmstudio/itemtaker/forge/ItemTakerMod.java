package org.vmstudio.itemtaker.forge;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;
import org.vmstudio.itemtaker.core.client.ItemTakerAddonClient;
import org.vmstudio.itemtaker.core.client.ItemTakerLogic;
import org.vmstudio.itemtaker.core.common.ItemTaker;
import org.vmstudio.itemtaker.core.server.ItemTakerAddonServer;
import org.vmstudio.visor.api.ModLoader;
import org.vmstudio.visor.api.VisorAPI;

import java.util.function.Supplier;

@Mod(ItemTaker.MOD_ID)
public class ItemTakerMod {
    private static final String PROTOCOL = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
        new ResourceLocation(ItemTaker.MOD_ID, "main"),
        () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals
    );

    public ItemTakerMod(){
        CHANNEL.registerMessage(0, ItemSyncPacket.class, ItemSyncPacket::encode, ItemSyncPacket::decode, ItemSyncPacket::handle);

        if(!ModLoader.get().isDedicatedServer()){
            ItemTakerLogic.bridge = (e, x, y, z, vx, vy, vz, g) -> CHANNEL.sendToServer(new ItemSyncPacket(e.getId(), x, y, z, vx, vy, vz, g));
            VisorAPI.registerAddon(
                new ItemTakerAddonClient()
            );
            MinecraftForge.EVENT_BUS.addListener(this::onTick);
        }else{
            VisorAPI.registerAddon(new ItemTakerAddonServer());
        }
    }

    private void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) ItemTakerLogic.tick();
    }

    public static class ItemSyncPacket {
        private final int id;
        private final double x, y, z, vx, vy, vz;
        private final boolean noGrav;

        public ItemSyncPacket(int id, double x, double y, double z, double vx, double vy, double vz, boolean noGrav) {
            this.id = id; this.x = x; this.y = y; this.z = z; this.vx = vx; this.vy = vy; this.vz = vz; this.noGrav = noGrav;
        }

        public static void encode(ItemSyncPacket msg, FriendlyByteBuf buf) {
            buf.writeInt(msg.id); buf.writeDouble(msg.x); buf.writeDouble(msg.y); buf.writeDouble(msg.z);
            buf.writeDouble(msg.vx); buf.writeDouble(msg.vy); buf.writeDouble(msg.vz); buf.writeBoolean(msg.noGrav);
        }

        public static ItemSyncPacket decode(FriendlyByteBuf b) {
            return new ItemSyncPacket(b.readInt(), b.readDouble(), b.readDouble(), b.readDouble(), b.readDouble(), b.readDouble(), b.readDouble(), b.readBoolean());
        }

        public static void handle(ItemSyncPacket msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                Entity entity = ctx.get().getSender().level().getEntity(msg.id);
                if (entity != null) {
                    entity.setNoGravity(msg.noGrav);
                    entity.teleportTo(msg.x, msg.y, msg.z);
                    entity.setDeltaMovement(msg.vx, msg.vy, msg.vz);
                }
            });
            ctx.get().setPacketHandled(true);
        }
    }
}

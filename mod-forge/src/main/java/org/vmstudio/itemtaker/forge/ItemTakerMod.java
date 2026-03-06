package org.vmstudio.itemtaker.forge;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
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
        CHANNEL.registerMessage(1, ItemPickupPacket.class, ItemPickupPacket::encode, ItemPickupPacket::decode, ItemPickupPacket::handle);

        if(!ModLoader.get().isDedicatedServer()){
            ItemTakerLogic.bridge = new ItemTakerLogic.NetworkBridge() {
                @Override
                public void sendSync(Entity entity, double x, double y, double z, double vx, double vy, double vz, boolean noGravity) {
                    CHANNEL.sendToServer(new ItemSyncPacket(entity.getId(), x, y, z, vx, vy, vz, noGravity));
                }

                @Override
                public void sendPickup(Entity entity, boolean isMainHand) {
                    CHANNEL.sendToServer(new ItemPickupPacket(entity.getId(), isMainHand));
                }
            };

            VisorAPI.registerAddon(new ItemTakerAddonClient());
            MinecraftForge.EVENT_BUS.addListener(this::onTick);
        } else {
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
                if (entity instanceof ItemEntity itemEntity) {
                    itemEntity.setNoGravity(msg.noGrav);
                    itemEntity.teleportTo(msg.x, msg.y, msg.z);
                    itemEntity.setDeltaMovement(msg.vx, msg.vy, msg.vz);
                    itemEntity.hasImpulse = true;
                    if (!msg.noGrav) itemEntity.setPickUpDelay(0);
                }
            });
            ctx.get().setPacketHandled(true);
        }
    }

    public static class ItemPickupPacket {
        private final int id;
        private final boolean isMainHand;

        public ItemPickupPacket(int id, boolean isMainHand) {
            this.id = id;
            this.isMainHand = isMainHand;
        }

        public static void encode(ItemPickupPacket msg, FriendlyByteBuf buf) {
            buf.writeInt(msg.id);
            buf.writeBoolean(msg.isMainHand);
        }

        public static ItemPickupPacket decode(FriendlyByteBuf buf) {
            return new ItemPickupPacket(buf.readInt(), buf.readBoolean());
        }

        public static void handle(ItemPickupPacket msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                var player = ctx.get().getSender();
                Entity entity = player.level().getEntity(msg.id);

                if (entity instanceof ItemEntity itemEntity) {
                    ItemStack stack = itemEntity.getItem();
                    InteractionHand targetHand = msg.isMainHand ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;

                    if (player.getItemInHand(targetHand).isEmpty()) {
                        player.setItemInHand(targetHand, stack.copy());
                        stack.setCount(0);
                    }
                    else {
                        if (!player.getInventory().add(stack)) {
                            itemEntity.setNoGravity(false);
                            itemEntity.setPickUpDelay(0);
                            return;
                        }
                    }

                    player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                        SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS,
                        0.2F, (player.getRandom().nextFloat() - player.getRandom().nextFloat()) * 1.4F + 2.0F);

                    if (stack.isEmpty()) {
                        itemEntity.discard();
                    } else {
                        itemEntity.setItem(stack);
                    }
                }
            });
            ctx.get().setPacketHandled(true);
        }
    }
}

package org.vmstudio.itemtaker.fabric;

import io.netty.buffer.Unpooled;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.vmstudio.itemtaker.core.client.ItemTakerLogic;
import org.vmstudio.itemtaker.core.common.ItemTaker;
import org.vmstudio.visor.api.ModLoader;
import org.vmstudio.visor.api.VisorAPI;
import org.vmstudio.itemtaker.core.client.ItemTakerAddonClient;
import org.vmstudio.itemtaker.core.server.ItemTakerAddonServer;

public class ItemTakerMod implements ModInitializer {
    public static final ResourceLocation SYNC_ITEM = new ResourceLocation(ItemTaker.MOD_ID, "sync_item");
    public static final ResourceLocation PICKUP_ITEM = new ResourceLocation(ItemTaker.MOD_ID, "pickup_item");

    @Override
    public void onInitialize() {
        ItemTakerLogic.bridge = new ItemTakerLogic.NetworkBridge() {
            @Override
            public void sendSync(Entity entity, double x, double y, double z, double vx, double vy, double vz, boolean noGravity) {
                FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
                buf.writeInt(entity.getId());
                buf.writeDouble(x); buf.writeDouble(y); buf.writeDouble(z);
                buf.writeDouble(vx); buf.writeDouble(vy); buf.writeDouble(vz);
                buf.writeBoolean(noGravity);
                ClientPlayNetworking.send(SYNC_ITEM, buf);
            }

            @Override
            public void sendPickup(Entity entity, boolean isMainHand) {
                FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
                buf.writeInt(entity.getId());
                buf.writeBoolean(isMainHand);
                ClientPlayNetworking.send(PICKUP_ITEM, buf);
            }
        };

        ServerPlayNetworking.registerGlobalReceiver(SYNC_ITEM, (server, player, handler, buf, responseSender) -> {
            int id = buf.readInt();
            double x = buf.readDouble(), y = buf.readDouble(), z = buf.readDouble();
            double vx = buf.readDouble(), vy = buf.readDouble(), vz = buf.readDouble();
            boolean noGrav = buf.readBoolean();

            server.execute(() -> {
                Entity entity = player.level().getEntity(id);
                if (entity instanceof ItemEntity itemEntity) {
                    itemEntity.setNoGravity(noGrav);
                    itemEntity.teleportTo(x, y, z);
                    itemEntity.setDeltaMovement(vx, vy, vz);
                    itemEntity.hasImpulse = true;
                    if (!noGrav) itemEntity.setPickUpDelay(0);
                }
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(PICKUP_ITEM, (server, player, handler, buf, responseSender) -> {
            int id = buf.readInt();
            boolean isMainHand = buf.readBoolean();

            server.execute(() -> {
                Entity entity = player.level().getEntity(id);
                if (entity instanceof ItemEntity itemEntity) {
                    ItemStack stack = itemEntity.getItem();
                    InteractionHand targetHand = isMainHand ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;

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
        });

        if(ModLoader.get().isDedicatedServer()){
            VisorAPI.registerAddon(new ItemTakerAddonServer());
        } else {
            VisorAPI.registerAddon(new ItemTakerAddonClient());
            ClientTickEvents.END_CLIENT_TICK.register(client -> ItemTakerLogic.tick());
        }
    }
}

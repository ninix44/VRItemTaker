package org.vmstudio.itemtaker.core.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.vmstudio.visor.api.VisorAPI;
import org.vmstudio.visor.api.client.player.VRLocalPlayer;
import org.vmstudio.visor.api.client.player.pose.PlayerPoseType;
import org.vmstudio.visor.api.client.player.pose.RawController;
import org.vmstudio.visor.api.common.HandType;
import org.vmstudio.visor.api.common.player.PlayerPose;
import org.vmstudio.visor.api.common.player.VRPose;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ItemTakerLogic {
    public interface NetworkBridge {
        void sendSync(Entity entity, double x, double y, double z, double vx, double vy, double vz, boolean noGravity);
        void sendPickup(Entity entity, boolean isMainHand);
    }
    public static NetworkBridge bridge;

    private static final double RANGE = 7.0;
    private static final double PICKUP_DISTANCE = 0.6;
    private static final double GROUP_RADIUS = 1.5;

    private static final float FLICK_THRESHOLD = 0.25f;

    private static final List<PulledItem> pulledItems = new ArrayList<>();
    private static final Set<ItemEntity> currentGlowingItems = new HashSet<>();

    private static int syncTimer = 0;

    private static class PulledItem {
        ItemEntity item;
        HandType targetHand;

        PulledItem(ItemEntity item, HandType targetHand) {
            this.item = item;
            this.targetHand = targetHand;
        }
    }

    // todo: for some reason, when the HAND is empty, the item sometimes flies, either into the hand, or into the center of the cell in the hotbar, it works "RANDOM". Should I make additional checks
    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        VRLocalPlayer vrPlayer = VisorAPI.client().getVRLocalPlayer();
        if (vrPlayer == null) return;

        PlayerPose pose = vrPlayer.getPoseData(PlayerPoseType.TICK);

        if (!pulledItems.isEmpty()) {
            handlePulling(mc, pose);
        }

        Set<ItemEntity> newGlowingItems = new HashSet<>();
        handleHandInteraction(mc, vrPlayer, pose, HandType.MAIN, newGlowingItems);
        handleHandInteraction(mc, vrPlayer, pose, HandType.OFFHAND, newGlowingItems);

        for (ItemEntity oldItem : currentGlowingItems) {
            if (!newGlowingItems.contains(oldItem) && oldItem.isAlive()) {
                oldItem.setGlowingTag(false);
            }
        }
        currentGlowingItems.clear();
        currentGlowingItems.addAll(newGlowingItems);
    }

    private static void handleHandInteraction(Minecraft mc, VRLocalPlayer vrPlayer, PlayerPose pose, HandType handType, Set<ItemEntity> glowingItems) {
        VRPose handPose = pose.getHand(handType);
        RawController rawCtrl = vrPlayer.getRawController(handType);

        if (!rawCtrl.isTracking()) return;

        Vec3 handPos = handPose.getPositionVec3();
        Vec3 handForward = handPose.getDirectionVec3();

        AABB searchBox = mc.player.getBoundingBox().inflate(RANGE);
        List<ItemEntity> items = mc.level.getEntitiesOfClass(ItemEntity.class, searchBox);

        ItemEntity bestTarget = null;
        double bestAngle = -1.0;

        for (ItemEntity item : items) {
            if (isItemAlreadyPulled(item)) continue;

            Vec3 toItem = item.position().add(0, 0.2, 0).subtract(handPos).normalize();
            double dot = handForward.dot(toItem);

            double requiredAngle = currentGlowingItems.contains(item) ? 0.65 : 0.92;

            if (dot > requiredAngle && dot > bestAngle) {
                if (canFitInSimulatedInventory(mc, item.getItem())) {
                    bestAngle = dot;
                    bestTarget = item;
                }
            }
        }

        if (bestTarget != null) {
            bestTarget.setGlowingTag(true);
            glowingItems.add(bestTarget);

            if (mc.level.random.nextInt(2) == 0) {
                mc.level.addParticle(ParticleTypes.GLOW,
                    bestTarget.getX(), bestTarget.getY() + 0.2, bestTarget.getZ(),
                    0, 0.02, 0
                );
            }

            Vector3f netMove = rawCtrl.getPositionHistory().netMovement(0.15f);
            Vec3 moveVec = new Vec3(netMove.x(), netMove.y(), netMove.z());
            double moveLen = moveVec.length();

            Vec3 toItem = bestTarget.position().subtract(handPos).normalize();
            double dotTowardsItem = moveLen > 0 ? (moveVec.dot(toItem) / moveLen) : 0;

            if (moveLen > FLICK_THRESHOLD && (moveVec.y < -0.15 || dotTowardsItem < -0.25)) {
                captureItems(mc, bestTarget, handType);
            }
        }
    }

    private static void captureItems(Minecraft mc, ItemEntity target, HandType handType) {
        AABB groupZone = target.getBoundingBox().inflate(GROUP_RADIUS);
        List<ItemEntity> nearbyItems = mc.level.getEntitiesOfClass(ItemEntity.class, groupZone);

        for (ItemEntity groupItem : nearbyItems) {
            if (isItemAlreadyPulled(groupItem)) continue;

            if (canFitInSimulatedInventory(mc, groupItem.getItem())) {
                groupItem.setNoGravity(true);
                groupItem.setGlowingTag(false);
                groupItem.setPickUpDelay(10);
                pulledItems.add(new PulledItem(groupItem, handType));
            }
        }
    }

    private static void handlePulling(Minecraft mc, PlayerPose pose) {
        pulledItems.removeIf(pulled -> {
            ItemEntity item = pulled.item;
            if (!item.isAlive()) return true;

            Vec3 targetPos = pose.getHand(pulled.targetHand).getPositionVec3();
            double dist = item.position().distanceTo(targetPos);

            if (dist < PICKUP_DISTANCE) {
                if (bridge != null) {
                    bridge.sendPickup(item, pulled.targetHand == HandType.MAIN);
                }
                return true;
            }

            Vec3 motion = targetPos.subtract(item.position()).normalize().scale(0.8);
            item.setDeltaMovement(motion);
            item.hasImpulse = true;

            return false;
        });

        syncTimer++;
        if (syncTimer >= 2) {
            for (PulledItem pulled : pulledItems) {
                ItemEntity item = pulled.item;
                syncWithServer(item, item.position(), item.getDeltaMovement(), true);
            }
            syncTimer = 0;
        }
    }

    private static boolean isItemAlreadyPulled(ItemEntity item) {
        for (PulledItem pulled : pulledItems) {
            if (pulled.item.equals(item)) return true;
        }
        return false;
    }

    private static boolean canFitInSimulatedInventory(Minecraft mc, ItemStack newStack) {
        List<ItemStack> tempInv = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            tempInv.add(mc.player.getInventory().getItem(i).copy());
        }
        for (PulledItem flyingItem : pulledItems) {
            simulateAddItem(tempInv, flyingItem.item.getItem());
        }
        return simulateAddItem(tempInv, newStack);
    }

    private static boolean simulateAddItem(List<ItemStack> inv, ItemStack toAdd) {
        ItemStack stack = toAdd.copy();
        if (stack.isStackable()) {
            for (ItemStack slot : inv) {
                if (!slot.isEmpty() && ItemStack.isSameItemSameTags(slot, stack)) {
                    int space = slot.getMaxStackSize() - slot.getCount();
                    int canTake = Math.min(space, stack.getCount());
                    slot.grow(canTake);
                    stack.shrink(canTake);
                    if (stack.isEmpty()) return true;
                }
            }
        }
        for (int i = 0; i < inv.size(); i++) {
            if (inv.get(i).isEmpty()) {
                inv.set(i, stack);
                return true;
            }
        }
        return false;
    }

    private static void syncWithServer(Entity entity, Vec3 pos, Vec3 motion, boolean noGravity) {
        if (bridge != null) {
            bridge.sendSync(entity, pos.x, pos.y, pos.z, motion.x, motion.y, motion.z, noGravity);
        }
    }
}

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
import org.vmstudio.visor.api.common.player.PlayerPose;
import org.vmstudio.visor.api.common.player.VRPose;
import org.vmstudio.visor.api.common.utils.VRMathUtils;
import org.vmstudio.visor.api.common.utils.Vector3fHistory;

import java.util.ArrayList;
import java.util.List;

public class ItemTakerLogic {
    public interface NetworkBridge {
        void sendSync(Entity entity, double x, double y, double z, double vx, double vy, double vz, boolean noGravity);
    }
    public static NetworkBridge bridge;

    private static final double RANGE = 3.0;
    private static final double PICKUP_DISTANCE = 0.5;
    private static final double GROUP_RADIUS = 1.2;

    private static final float FLICK_THRESHOLD = 0.15f;

    private static final List<ItemEntity> pickedItems = new ArrayList<>();

    private static final Vector3fHistory mainHandHistory = new Vector3fHistory(20);
    private static final Vector3fHistory offHandHistory = new Vector3fHistory(20);

    private static int syncTimer = 0;

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        VRLocalPlayer vrPlayer = VisorAPI.client().getVRLocalPlayer();
        if (vrPlayer == null) return;

        PlayerPose pose = vrPlayer.getPoseData(PlayerPoseType.TICK);

        mainHandHistory.add(pose.getMainHand().getPosition());
        offHandHistory.add(pose.getOffhand().getPosition());

        if (!pickedItems.isEmpty()) {
            handlePulling(mc);
        }

        handleVRInteraction(mc, pose);
    }

    private static void handleVRInteraction(Minecraft mc, PlayerPose pose) {
        Vec3 eyePos = mc.player.getEyePosition();
        AABB searchBox = mc.player.getBoundingBox().inflate(RANGE);
        List<ItemEntity> items = mc.level.getEntitiesOfClass(ItemEntity.class, searchBox);

        ItemEntity bestTarget = null;
        double bestAngle = 0.99; // 98 maybe, test on VR
        Vec3 lookVec = mc.player.getViewVector(1.0F);

        for (ItemEntity item : items) {
            if (pickedItems.contains(item)) continue;

            Vec3 toItem = item.position().add(0, 0.2, 0).subtract(eyePos).normalize();
            double dot = lookVec.dot(toItem);
            if (dot > bestAngle) {
                if (canFitInSimulatedInventory(mc, item.getItem())) {
                    bestAngle = dot;
                    bestTarget = item;
                }
            }
        }

        if (bestTarget != null) {
            checkHand(mc, bestTarget, pose.getMainHand(), mainHandHistory);
            checkHand(mc, bestTarget, pose.getOffhand(), offHandHistory);
        }
    }

    private static void checkHand(Minecraft mc, ItemEntity target, VRPose handPose, Vector3fHistory history) {
        Vector3f handUp = VRMathUtils.extractUpDir(handPose.getRotation(), true);
        float upDot = handUp.dot(new Vector3f(0, 1, 0));

        if (upDot > 0.2f) {
            spawnHoverParticles(target);

            Vector3f netMove = history.netMovement(0.2f);
            if (netMove.y() > FLICK_THRESHOLD) {
                captureItems(mc, target);
            }
        }
    }

    private static void captureItems(Minecraft mc, ItemEntity target) {
        AABB groupZone = target.getBoundingBox().inflate(GROUP_RADIUS);
        List<ItemEntity> nearbyItems = mc.level.getEntitiesOfClass(ItemEntity.class, groupZone);

        for (ItemEntity groupItem : nearbyItems) {
            if (pickedItems.contains(groupItem)) continue;
            if (canFitInSimulatedInventory(mc, groupItem.getItem())) {
                groupItem.setNoGravity(true);
                pickedItems.add(groupItem);
            }
        }
    }

    private static void handlePulling(Minecraft mc) {
        Vec3 targetPos = mc.player.position().add(0, 0.8, 0);

        pickedItems.removeIf(item -> {
            if (!item.isAlive()) return true;

            double dist = item.position().distanceTo(targetPos);
            if (dist < PICKUP_DISTANCE) {
                item.setPos(targetPos.x, targetPos.y, targetPos.z);
                syncWithServer(item, targetPos, Vec3.ZERO, false);
                item.setNoGravity(false);
                return true;
            }

            Vec3 motion = targetPos.subtract(item.position()).normalize().scale(0.5); // 0.6 maybe, test on VR
            item.setDeltaMovement(motion);
            item.hasImpulse = true;

            if (mc.level.random.nextBoolean()) {
                mc.level.addParticle(ParticleTypes.CRIT, item.getX(), item.getY(), item.getZ(), 0, 0, 0);
            }
            return false;
        });

        syncTimer++;
        if (syncTimer >= 2) {
            for (ItemEntity item : pickedItems) {
                syncWithServer(item, item.position(), item.getDeltaMovement(), true);
            }
            syncTimer = 0;
        }
    }

    private static boolean canFitInSimulatedInventory(Minecraft mc, ItemStack newStack) {
        List<ItemStack> tempInv = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            tempInv.add(mc.player.getInventory().getItem(i).copy());
        }

        for (ItemEntity flyingItem : pickedItems) {
            simulateAddItem(tempInv, flyingItem.getItem());
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

    private static void spawnHoverParticles(ItemEntity item) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level.random.nextInt(2) == 0) {
            mc.level.addParticle(ParticleTypes.GLOW, item.getX(), item.getY() + 0.2, item.getZ(), 0, 0.02, 0);
        }
    }
}

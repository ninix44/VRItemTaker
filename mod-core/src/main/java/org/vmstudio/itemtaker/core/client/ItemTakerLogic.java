package org.vmstudio.itemtaker.core.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
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

import java.util.*;

public class ItemTakerLogic {
    public interface NetworkBridge {
        void sendSync(Entity entity, double x, double y, double z, double vx, double vy, double vz, boolean noGravity);
        void sendPickup(Entity entity, boolean isMainHand);
    }
    public static NetworkBridge bridge;

    private static final double RANGE = 7.0;
    private static final double PICKUP_DISTANCE = 0.7;
    private static final double GROUP_RADIUS = 1.7;

    private static final double REQUIRED_ANGLE_NEW = 0.96;
    private static final double REQUIRED_ANGLE_STICKY = 0.94;

    private static final float FLICK_THRESHOLD = 0.08f;
    private static final float FLICK_UP_THRESHOLD = 0.04f;

    private static final int DROP_COOLDOWN_TICKS = 40;
    private static final int TARGETING_DELAY_TICKS = 20;

    private static final List<PulledItem> pulledItems = new ArrayList<>();
    private static final Set<ItemEntity> currentGlowingItems = new HashSet<>();
    private static final Map<UUID, Long> targetingStartTicks = new HashMap<>();

    private static int syncTimer = 0;
    private static long lastAutoSwitchTick = 0;

    private static class PulledItem {
        ItemEntity item;
        HandType targetHand;
        PulledItem(ItemEntity item, HandType targetHand) {
            this.item = item;
            this.targetHand = targetHand;
        }
    }

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        VRLocalPlayer vrPlayer = VisorAPI.client().getVRLocalPlayer();
        if (vrPlayer == null) return;

        PlayerPose pose = vrPlayer.getPoseData(PlayerPoseType.TICK);
        long currentGameTime = mc.level.getGameTime();

        if (!pulledItems.isEmpty()) {
            handlePulling(mc, pose);
        }

        Set<ItemEntity> itemsLookedAt = new HashSet<>();

        handleHandInteraction(mc, vrPlayer, pose, HandType.MAIN, itemsLookedAt, currentGameTime);

        currentGlowingItems.removeIf(item -> {
            if (!itemsLookedAt.contains(item) || !item.isAlive()) {
                item.setGlowingTag(false);
                return true;
            }
            return false;
        });

        for (ItemEntity looked : itemsLookedAt) {
            if (isFocused(looked, currentGameTime)) {
                looked.setGlowingTag(true);
                currentGlowingItems.add(looked);
            }
        }

        targetingStartTicks.keySet().removeIf(uuid -> {
            for (ItemEntity e : itemsLookedAt) {
                if (e.getUUID().equals(uuid)) return false;
            }
            return true;
        });
    }

    private static boolean isFocused(ItemEntity item, long currentTime) {
        Long start = targetingStartTicks.get(item.getUUID());
        return start != null && (currentTime - start) >= TARGETING_DELAY_TICKS;
    }

    private static void handleHandInteraction(Minecraft mc, VRLocalPlayer vrPlayer, PlayerPose pose, HandType handType, Set<ItemEntity> itemsLookedAt, long currentGameTime) {
        VRPose handPose = pose.getHand(handType);
        RawController rawCtrl = vrPlayer.getRawController(handType);

        if (!rawCtrl.isTracking()) return;

        Vec3 handPos = handPose.getPositionVec3();
        Vec3 headPos = pose.getHmd().getPositionVec3();
        Vec3 headForward = pose.getHmd().getDirectionVec3();

        AABB searchBox = mc.player.getBoundingBox().inflate(RANGE);
        List<ItemEntity> items = mc.level.getEntitiesOfClass(ItemEntity.class, searchBox);

        ItemEntity bestTarget = null;
        double bestAngle = -1.0;

        for (ItemEntity item : items) {
            if (isItemAlreadyPulled(item)) continue;
            if (item.tickCount < DROP_COOLDOWN_TICKS) continue;

            Vec3 headToItem = item.position().add(0, 0.25, 0).subtract(headPos).normalize();
            double dot = headForward.dot(headToItem);

            double requiredAngle = currentGlowingItems.contains(item) ? REQUIRED_ANGLE_STICKY : REQUIRED_ANGLE_NEW;

            if (dot > requiredAngle && dot > bestAngle) {
                if (item.position().distanceTo(handPos) <= RANGE) {
                    if (canFitInSimulatedInventory(mc, item.getItem())) {
                        bestAngle = dot;
                        bestTarget = item;
                    }
                }
            }
        }

        if (bestTarget != null) {
            UUID targetUUID = bestTarget.getUUID();
            itemsLookedAt.add(bestTarget);

            if (!targetingStartTicks.containsKey(targetUUID)) {
                targetingStartTicks.put(targetUUID, currentGameTime);
            }

            if (isFocused(bestTarget, currentGameTime)) {
                if (mc.level.random.nextInt(3) == 0) {
                    mc.level.addParticle(ParticleTypes.GLOW,
                        bestTarget.getX(), bestTarget.getY() + 0.3, bestTarget.getZ(),
                        0, 0.03, 0);
                }

                Vector3f netMoveF = rawCtrl.getPositionHistory().netMovement(0.15f);
                Vec3 moveVec = new Vec3(netMoveF.x(), netMoveF.y(), netMoveF.z());
                double moveLen = moveVec.length();

                if (moveLen > FLICK_THRESHOLD) {
                    boolean flickUp = moveVec.y > FLICK_UP_THRESHOLD;

                    Vector3f rawHand = rawCtrl.getAimPosition();
                    Vector3f rawHead = vrPlayer.getRawHmd().getHeadsetPosition();
                    Vec3 toHead = new Vec3(rawHead.x() - rawHand.x(), rawHead.y() - rawHand.y(), rawHead.z() - rawHand.z()).normalize();

                    boolean flickTowardsPlayer = moveVec.normalize().dot(toHead) > 0.3;

                    if (flickUp || flickTowardsPlayer) {
                        captureItems(mc, bestTarget, handType, currentGameTime);
                    }
                }
            }
        }
    }

    private static void captureItems(Minecraft mc, ItemEntity target, HandType handType, long currentGameTime) {
        AABB groupZone = target.getBoundingBox().inflate(GROUP_RADIUS);
        List<ItemEntity> nearbyItems = mc.level.getEntitiesOfClass(ItemEntity.class, groupZone);

        boolean switchedSlot = false;

        for (ItemEntity groupItem : nearbyItems) {
            if (isItemAlreadyPulled(groupItem)) continue;
            if (groupItem.tickCount < DROP_COOLDOWN_TICKS) continue;

            if (canFitInSimulatedInventory(mc, groupItem.getItem())) {

                if (!switchedSlot && handType == HandType.MAIN && mc.player.getMainHandItem().isEmpty()) {
                    if (currentGameTime - lastAutoSwitchTick > 10) {
                        int predictedSlot = predictTargetHotbarSlot(mc.player.getInventory(), groupItem.getItem());
                        int currentSlot = mc.player.getInventory().selected;

                        if (predictedSlot != -1 && predictedSlot != currentSlot) {
                            mc.player.getInventory().selected = predictedSlot;
                            if (mc.getConnection() != null) {
                                mc.getConnection().send(new ServerboundSetCarriedItemPacket(predictedSlot));
                            }

                            lastAutoSwitchTick = currentGameTime;
                            switchedSlot = true;
                        }
                    }
                }

                groupItem.setNoGravity(true);
                groupItem.setGlowingTag(false);

                currentGlowingItems.remove(groupItem);
                targetingStartTicks.remove(groupItem.getUUID());

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

            Vec3 motion = targetPos.subtract(item.position()).normalize().scale(1.0);
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

    private static int predictTargetHotbarSlot(Inventory inv, ItemStack pickupStack) {
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack slotStack = inv.items.get(i);
            if (!slotStack.isEmpty() && ItemStack.isSameItemSameTags(slotStack, pickupStack)) {
                if (slotStack.getCount() < slotStack.getMaxStackSize()) {
                    return (i < 9) ? i : -1;
                }
            }
        }

        for (int i = 0; i < inv.items.size(); i++) {
            if (inv.items.get(i).isEmpty()) {
                return (i < 9) ? i : -1;
            }
        }

        return -1;
    }

    private static boolean isItemAlreadyPulled(ItemEntity item) {
        for (PulledItem pulled : pulledItems) {
            if (pulled.item.getUUID().equals(item.getUUID())) return true;
        }
        return false;
    }

    private static boolean canFitInSimulatedInventory(Minecraft mc, ItemStack newStack) {
        if (mc.player.isCreative()) return true;
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

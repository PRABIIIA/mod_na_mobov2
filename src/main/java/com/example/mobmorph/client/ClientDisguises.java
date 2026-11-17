package com.example.mobmorph.client;

import com.example.mobmorph.MorphState;
import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.EndermanEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.SlimeEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Клиентские «подставные» мобы, которых рисуем вместо игроков. */
public final class ClientDisguises {
    /** true — над мобом виден ник игрока; false — моб полностью «настоящий». */
    public static final boolean SHOW_NAME = false;

    private static final class Entry {
        Identifier type;
        int carried;
        LivingEntity entity;
    }

    private static final Map<UUID, Entry> MAP = new HashMap<>();

    private ClientDisguises() {}

    public static void set(UUID id, Identifier type, int carried) {
        if (type == null) {
            MAP.remove(id);
            MorphState.CLIENT.remove(id);
            return;
        }
        MorphState.CLIENT.put(id, type);
        Entry e = MAP.get(id);
        if (e == null || !e.type.equals(type)) {
            e = new Entry();
            e.type = type;
            MAP.put(id, e);
        }
        e.carried = carried;
        applyCarried(e);
    }

    public static void clear() {
        MAP.clear();
        MorphState.CLIENT.clear();
    }

    public static LivingEntity get(AbstractClientPlayerEntity player) {
        Entry e = MAP.get(player.getUuid());
        if (e == null) return null;
        if (e.entity == null || e.entity.getWorld() != player.getWorld()) {
            Entity created = Registries.ENTITY_TYPE.get(e.type).create(player.getWorld());
            if (!(created instanceof LivingEntity living)) {
                MAP.remove(player.getUuid());
                return null;
            }
            e.entity = living;
            living.setSilent(true);
            if (living instanceof SlimeEntity s) s.setSize(MorphState.SLIME_SIZE, false);
            if (SHOW_NAME) {
                living.setCustomName(player.getName());
                living.setCustomNameVisible(true);
            }
            applyCarried(e);
        }
        return e.entity;
    }

    private static void applyCarried(Entry e) {
        if (e.entity instanceof EndermanEntity enderman) {
            enderman.setCarriedBlock(e.carried == 0 ? null : Block.getStateFromRawId(e.carried));
        }
    }

    /** Копируем позу/анимации игрока на моба. Тело всегда повёрнуто туда же, куда смотрит голова. */
    public static void sync(PlayerEntity p, LivingEntity f) {
        f.setPos(p.getX(), p.getY(), p.getZ());
        f.prevX = p.prevX; f.prevY = p.prevY; f.prevZ = p.prevZ;
        f.lastRenderX = p.lastRenderX; f.lastRenderY = p.lastRenderY; f.lastRenderZ = p.lastRenderZ;
        f.setYaw(p.headYaw); f.prevYaw = p.prevHeadYaw;
        f.setPitch(p.getPitch()); f.prevPitch = p.prevPitch;
        f.bodyYaw = p.headYaw; f.prevBodyYaw = p.prevHeadYaw;
        f.headYaw = p.headYaw; f.prevHeadYaw = p.prevHeadYaw;
        f.age = p.age;
        f.hurtTime = p.hurtTime;
        f.deathTime = p.deathTime;
        f.handSwinging = p.handSwinging;
        f.handSwingProgress = p.handSwingProgress;
        f.lastHandSwingProgress = p.lastHandSwingProgress;
        f.setOnGround(p.isOnGround());
        f.setInvisible(p.isInvisible());
        if (f instanceof MobEntity m) m.setAttacking(p.handSwinging);
    }

    /** Раз в тик: анимация ног, броня и предметы игрока на мобе, пересчёт хитбокса. */
    public static void tick(MinecraftClient client) {
        if (client.world == null) return;
        for (Map.Entry<UUID, Entry> en : MAP.entrySet()) {
            PlayerEntity p = client.world.getPlayerByUuid(en.getKey());
            if (p == null) continue;
            EntityDimensions d = p.getDimensions(p.getPose());
            if (Math.abs(d.width - p.getWidth()) > 0.01f || Math.abs(d.height - p.getHeight()) > 0.01f)
                p.calculateDimensions();
            LivingEntity f = en.getValue().entity;
            if (f == null) continue;
            sync(p, f);
            f.updateLimbs(false);
            for (EquipmentSlot slot : EquipmentSlot.values()) f.equipStack(slot, p.getEquippedStack(slot));
        }
    }
}

package com.example.mobmorph;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.attribute.AttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.SlimeEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Общее состояние (и на сервере, и на клиенте): кто в кого превращён + характеристики мобов. */
public final class MorphState {
    /** Размер слайма / магмового куба при превращении (4 — как «большой», но он 2×2 блока). */
    public static final int SLIME_SIZE = 2;

    public static final Map<UUID, Identifier> SERVER = new ConcurrentHashMap<>();
    public static final Map<UUID, Identifier> CLIENT = new ConcurrentHashMap<>();
    private static final Map<EntityType<?>, Optional<Profile>> CACHE = new ConcurrentHashMap<>();

    public record Profile(float width, float height, float eye, double maxHealth,
                          boolean hasDamage, double damage, double armor, double knockback,
                          boolean hasSpeed, double speed) {}

    private MorphState() {}

    public static EntityType<?> typeOf(Identifier id) {
        return Registries.ENTITY_TYPE.getOrEmpty(id).orElse(null);
    }

    public static Identifier get(Entity e) {
        if (!(e instanceof PlayerEntity) || e.getWorld() == null || e.getUuid() == null) return null;
        return (e.getWorld().isClient ? CLIENT : SERVER).get(e.getUuid());
    }

    public static EntityType<?> type(Entity e) {
        Identifier id = get(e);
        return id == null ? null : typeOf(id);
    }

    public static Profile profileOf(Entity e) {
        EntityType<?> t = type(e);
        return t == null ? null : profile(t, e.getWorld());
    }

    /** Реальные размеры, здоровье и атрибуты моба (null — в этого моба превращаться нельзя). */
    public static Profile profile(EntityType<?> t, World w) {
        Optional<Profile> cached = CACHE.get(t);
        if (cached != null) return cached.orElse(null);
        Profile pr = null;
        try {
            Entity e = t.create(w);
            if (e instanceof MobEntity m) {
                if (m instanceof SlimeEntity s) s.setSize(SLIME_SIZE, true);
                EntityDimensions d = m.getDimensions(EntityPose.STANDING);
                AttributeContainer a = m.getAttributes();
                boolean hasDmg = a.hasAttribute(EntityAttributes.GENERIC_ATTACK_DAMAGE);
                boolean hasSpd = a.hasAttribute(EntityAttributes.GENERIC_MOVEMENT_SPEED);
                pr = new Profile(d.width, d.height, m.getEyeHeight(EntityPose.STANDING),
                        m.getMaxHealth(),
                        hasDmg, hasDmg ? a.getValue(EntityAttributes.GENERIC_ATTACK_DAMAGE) : 1.0,
                        a.hasAttribute(EntityAttributes.GENERIC_ARMOR) ? a.getValue(EntityAttributes.GENERIC_ARMOR) : 0.0,
                        a.hasAttribute(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE)
                                ? a.getValue(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE) : 0.0,
                        hasSpd, hasSpd ? a.getValue(EntityAttributes.GENERIC_MOVEMENT_SPEED) : 0.1);
            }
            if (e != null) e.discard();
        } catch (Throwable ignored) {
            pr = null;
        }
        CACHE.put(t, Optional.ofNullable(pr));
        return pr;
    }
}

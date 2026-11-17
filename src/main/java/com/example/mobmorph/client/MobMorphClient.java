package com.example.mobmorph.client;

import com.example.mobmorph.MobAbilities;
import com.example.mobmorph.MobMorph;
import com.example.mobmorph.MorphState;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.player.PlayerAbilities;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.util.UUID;

public class MobMorphClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        KeyBinding ability = key("key.mobmorph.ability", GLFW.GLFW_KEY_R);
        KeyBinding second = key("key.mobmorph.grab", GLFW.GLFW_KEY_G);
        KeyBinding fly = key("key.mobmorph.fly", GLFW.GLFW_KEY_V);
        KeyBinding menu = key("key.mobmorph.menu", GLFW.GLFW_KEY_H);

        ClientPlayNetworking.registerGlobalReceiver(MobMorph.SYNC, (client, handler, buf, sender) -> {
            UUID id = buf.readUuid();
            boolean has = buf.readBoolean();
            Identifier type = has ? buf.readIdentifier() : null;
            int carried = has ? buf.readInt() : 0;
            client.execute(() -> {
                ClientDisguises.set(id, type, carried);
                if (client.world != null) {
                    PlayerEntity pl = client.world.getPlayerByUuid(id);
                    if (pl != null) pl.calculateDimensions();
                }
            });
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (ability.wasPressed()) sendAbility(false);
            while (second.wasPressed()) sendAbility(true);
            while (fly.wasPressed()) toggleFlight(client);
            while (menu.wasPressed()) {
                if (client.player != null && client.currentScreen == null) client.setScreen(new MorphScreen());
            }
            ClientDisguises.tick(client);
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ClientDisguises.clear());
    }

    private static KeyBinding key(String name, int code) {
        return KeyBindingHelper.registerKeyBinding(
                new KeyBinding(name, InputUtil.Type.KEYSYM, code, "category.mobmorph"));
    }

    private static void sendAbility(boolean secondary) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBoolean(secondary);
        ClientPlayNetworking.send(MobMorph.ABILITY, buf);
    }

    /** Один клик V — взлёт (Пробел вверх, Shift вниз); второй клик — полёт выключен. */
    private static void toggleFlight(MinecraftClient client) {
        ClientPlayerEntity pl = client.player;
        if (pl == null) return;
        Identifier id = MorphState.CLIENT.get(pl.getUuid());
        EntityType<?> t = id == null ? null : MorphState.typeOf(id);
        float speed = t == null ? 0f : MobAbilities.flySpeed(t);
        if (speed <= 0f) {
            pl.sendMessage(Text.literal("Этот моб не умеет летать"), true);
            return;
        }
        PlayerAbilities ab = pl.getAbilities();
        ab.allowFlying = true;
        ab.setFlySpeed(speed);
        if (ab.flying) {
            ab.flying = false;
            pl.sendMessage(Text.literal("Полёт выключен"), true);
        } else {
            ab.flying = true;
            pl.setOnGround(false);
            Vec3d v = pl.getVelocity();
            pl.setVelocity(v.x, 0.3, v.z);
            pl.sendMessage(Text.literal("Полёт включён: Пробел — вверх, Shift — вниз"), true);
        }
        pl.sendAbilitiesUpdate();
    }
}

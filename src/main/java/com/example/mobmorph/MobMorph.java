package com.example.mobmorph;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.command.argument.IdentifierArgumentType;
import net.minecraft.command.suggestion.SuggestionProviders;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;

import java.util.Collection;
import java.util.List;

public class MobMorph implements ModInitializer {
    public static final String MOD_ID = "mobmorph";
    /** сервер -> клиенты: кто в кого превращён */
    public static final Identifier SYNC = new Identifier(MOD_ID, "sync");
    /** клиент -> сервер: нажата способность (R / G) */
    public static final Identifier ABILITY = new Identifier(MOD_ID, "ability");
    /** клиент -> сервер: выбор в меню (H) */
    public static final Identifier MORPH_REQUEST = new Identifier(MOD_ID, "morph_request");

    /** true — меню (H) работает только у операторов; false — у всех игроков. */
    public static final boolean MENU_REQUIRES_OP = false;

    private static final SimpleCommandExceptionType BAD_MOB =
            new SimpleCommandExceptionType(Text.literal("Этот моб не подходит для превращения"));

    @Override
    public void onInitialize() {
        ServerPlayNetworking.registerGlobalReceiver(ABILITY, (server, player, handler, buf, sender) -> {
            boolean secondary = buf.readBoolean();
            server.execute(() -> MorphManager.useAbility(player, secondary));
        });

        ServerPlayNetworking.registerGlobalReceiver(MORPH_REQUEST, (server, player, handler, buf, sender) -> {
            boolean on = buf.readBoolean();
            Identifier id = on ? buf.readIdentifier() : null;
            server.execute(() -> {
                if (MENU_REQUIRES_OP && !player.hasPermissionLevel(2)) {
                    player.sendMessage(Text.literal("Меню превращения доступно только операторам"), true);
                    return;
                }
                if (!on) MorphManager.clear(player);
                else if (MorphManager.valid(id, player.getWorld())) MorphManager.set(player, id);
            });
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> MorphManager.onJoin(handler.player));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> MorphManager.clear(handler.player));
        ServerTickEvents.END_SERVER_TICK.register(MorphManager::tick);
        ServerPlayerEvents.AFTER_RESPAWN.register((oldP, newP, alive) -> MorphManager.onRespawn(newP));

        // моб-кошка, лягушка, летающие и т.д. не получают урона от падения
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (entity instanceof ServerPlayerEntity sp && source.isOf(DamageTypes.FALL)) {
                EntityType<?> t = MorphState.type(sp);
                if (t != null && MobAbilities.NO_FALL_DAMAGE.contains(t)) return false;
            }
            return true;
        });

        // эффекты обычного удара: яд паука, голод кадавра, иссушение и т.д.
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (!world.isClient && entity instanceof LivingEntity target) {
                EntityType<?> t = MorphState.type(player);
                if (t != null) MobAbilities.onHit(t, target);
            }
            return ActionResult.PASS;
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, env) ->
                dispatcher.register(CommandManager.literal("morph")
                        .requires(s -> s.hasPermissionLevel(2))
                        .then(CommandManager.literal("off")
                                .executes(c -> off(c.getSource(), List.of(c.getSource().getPlayerOrThrow())))
                                .then(CommandManager.argument("targets", EntityArgumentType.players())
                                        .executes(c -> off(c.getSource(), EntityArgumentType.getPlayers(c, "targets")))))
                        .then(CommandManager.argument("mob", IdentifierArgumentType.identifier())
                                .suggests(SuggestionProviders.SUMMONABLE_ENTITIES)
                                .executes(c -> morph(c.getSource(),
                                        IdentifierArgumentType.getIdentifier(c, "mob"),
                                        List.of(c.getSource().getPlayerOrThrow())))
                                .then(CommandManager.argument("targets", EntityArgumentType.players())
                                        .executes(c -> morph(c.getSource(),
                                                IdentifierArgumentType.getIdentifier(c, "mob"),
                                                EntityArgumentType.getPlayers(c, "targets")))))));
    }

    private static int morph(ServerCommandSource src, Identifier id, Collection<ServerPlayerEntity> targets)
            throws CommandSyntaxException {
        if (!MorphManager.valid(id, src.getWorld())) throw BAD_MOB.create();
        for (ServerPlayerEntity p : targets) MorphManager.set(p, id);
        src.sendFeedback(() -> Text.literal("Превращено игроков: " + targets.size() + " → " + id), true);
        return targets.size();
    }

    private static int off(ServerCommandSource src, Collection<ServerPlayerEntity> targets) {
        for (ServerPlayerEntity p : targets) MorphManager.clear(p);
        src.sendFeedback(() -> Text.literal("Превращение снято: " + targets.size()), true);
        return targets.size();
    }
}

package com.example.mobmorph.client;

import com.example.mobmorph.MobAbilities;
import com.example.mobmorph.MobMorph;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.SlimeEntity;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Меню выбора моба (клавиша H): сетка со всеми мобами, поиск, страницы. */
public class MorphScreen extends Screen {
    private static final int CW = 80, CH = 92, GAP = 6;

    private record Card(EntityType<?> type, Identifier id, LivingEntity entity, String name) {}

    private final List<Card> cards = new ArrayList<>();
    private final List<Card> filtered = new ArrayList<>();
    private TextFieldWidget search;
    private int page = 0, cols = 1, rows = 1, perPage = 1, left = 0;
    private static final int TOP = 48;

    public MorphScreen() {
        super(Text.literal("Превращение в моба"));
    }

    private void buildCards() {
        if (client == null || client.world == null) return;
        for (EntityType<?> t : Registries.ENTITY_TYPE) {
            if (t == EntityType.PLAYER) continue;
            Entity e;
            try { e = t.create(client.world); } catch (Throwable ex) { continue; }
            if (e instanceof MobEntity m) {
                if (m instanceof SlimeEntity s) s.setSize(com.example.mobmorph.MorphState.SLIME_SIZE, false);
                cards.add(new Card(t, Registries.ENTITY_TYPE.getId(t), m, t.getName().getString()));
            } else if (e != null) {
                e.discard();
            }
        }
        cards.sort(Comparator.comparing(c -> c.name().toLowerCase(Locale.ROOT)));
    }

    private void refilter(String q) {
        filtered.clear();
        String s = q.toLowerCase(Locale.ROOT).trim();
        for (Card c : cards) {
            if (s.isEmpty() || c.name().toLowerCase(Locale.ROOT).contains(s) || c.id().getPath().contains(s))
                filtered.add(c);
        }
    }

    @Override
    protected void init() {
        if (cards.isEmpty()) buildCards();
        String prev = search == null ? "" : search.getText();

        search = new TextFieldWidget(textRenderer, width / 2 - 110, 20, 220, 18, Text.literal(""));
        search.setMaxLength(40);
        search.setText(prev);
        search.setSuggestion(prev.isEmpty() ? "Поиск моба..." : "");
        search.setChangedListener(s -> {
            search.setSuggestion(s.isEmpty() ? "Поиск моба..." : "");
            page = 0;
            refilter(s);
        });
        addDrawableChild(search);
        setInitialFocus(search);

        int by = height - 24;
        addDrawableChild(ButtonWidget.builder(Text.literal("<"), b -> page--).dimensions(width / 2 - 70, by, 30, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal(">"), b -> page++).dimensions(width / 2 + 40, by, 30, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Обычный вид"), b -> { send(null); close(); })
                .dimensions(10, by, 110, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Закрыть"), b -> close())
                .dimensions(width - 120, by, 110, 20).build());

        cols = Math.max(1, (width - 20 + GAP) / (CW + GAP));
        rows = Math.max(1, (height - TOP - 34 + GAP) / (CH + GAP));
        perPage = cols * rows;
        left = (width - (cols * (CW + GAP) - GAP)) / 2;
        refilter(prev);
    }

    private int pages() { return Math.max(1, (filtered.size() + perPage - 1) / perPage); }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        renderBackground(ctx);
        page = MathHelper.clamp(page, 0, pages() - 1);
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("Выбери моба (" + cards.size() + ")"), width / 2, 6, 0xFFFFFF);
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal((page + 1) + " / " + pages()), width / 2, height - 19, 0xFFFFFF);

        Card hover = null;
        for (int i = 0; i < perPage; i++) {
            int idx = page * perPage + i;
            if (idx >= filtered.size()) break;
            Card c = filtered.get(idx);
            int x = left + (i % cols) * (CW + GAP);
            int y = TOP + (i / cols) * (CH + GAP);
            boolean over = mx >= x && mx < x + CW && my >= y && my < y + CH;
            if (over) hover = c;

            ctx.fill(x, y, x + CW, y + CH, over ? 0xCC3A5F9A : 0xCC1E1E1E);
            ctx.drawBorder(x, y, CW, CH, over ? 0xFFFFFFFF : 0xFF555555);

            LivingEntity e = c.entity();
            float big = Math.max(e.getHeight(), e.getWidth() * 0.9f);
            int size = (int) MathHelper.clamp(40f / Math.max(0.3f, big), 3, 42);
            ctx.enableScissor(x + 1, y + 1, x + CW - 1, y + CH - 14);
            InventoryScreen.drawEntity(ctx, x + CW / 2, y + CH - 20, size,
                    (x + CW / 2f) - mx, (y + CH / 3f) - my, e);
            ctx.disableScissor();

            String name = textRenderer.trimToWidth(c.name(), CW - 4);
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(name), x + CW / 2, y + CH - 11, 0xFFFFFF);
        }
        super.render(ctx, mx, my, delta);

        if (hover != null) {
            List<Text> tip = new ArrayList<>();
            tip.add(Text.literal(hover.name()).formatted(Formatting.YELLOW));
            for (String line : MobAbilities.describe(hover.type()).split(" \\| "))
                tip.add(Text.literal(line).formatted(Formatting.GRAY));
            ctx.drawTooltip(textRenderer, tip, mx, my);
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            for (int i = 0; i < perPage; i++) {
                int idx = page * perPage + i;
                if (idx >= filtered.size()) break;
                int x = left + (i % cols) * (CW + GAP);
                int y = TOP + (i / cols) * (CH + GAP);
                if (mx >= x && mx < x + CW && my >= y && my < y + CH) {
                    send(filtered.get(idx).id());
                    close();
                    return true;
                }
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double amount) {
        page = MathHelper.clamp(page + (amount < 0 ? 1 : -1), 0, pages() - 1);
        return true;
    }

    private static void send(Identifier id) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBoolean(id != null);
        if (id != null) buf.writeIdentifier(id);
        ClientPlayNetworking.send(MobMorph.MORPH_REQUEST, buf);
    }

    @Override
    public boolean shouldPause() { return false; }
}

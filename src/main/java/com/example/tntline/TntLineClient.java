package com.example.tntline;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

public class TntLineClient implements ClientModInitializer {
    // Khoảng cách giữa 2 TNT: 1 = sát nhau, 2 = cách 1 ô trống, 3 = cách 2 ô...
    private int spacing = 2;
    // Số TNT tối đa trong 1 đường
    private int count = 20;
    // Delay giữa 2 lần đặt (tick)
    private int delay = 2;
    // Tự đi tới: vừa đi vừa để lại đường TNT phía sau lưng
    private boolean autoWalk = false;

    private static final double REACH = 4.5;

    private KeyBinding toggleKey, spacingUpKey, spacingDownKey, autoWalkKey;

    private boolean active = false;
    private BlockPos anchor;
    private Direction dir;
    private final Set<Integer> placed = new HashSet<>();
    private int cooldown = 0;
    private int prevSlot = -1;

    @Override
    public void onInitializeClient() {
        loadConfig();

        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.tntline.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_R, "category.tntline"));
        spacingUpKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.tntline.spacing_up", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_BRACKET, "category.tntline"));
        spacingDownKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.tntline.spacing_down", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_BRACKET, "category.tntline"));

        autoWalkKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.tntline.autowalk", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_G, "category.tntline"));

        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("tntline")
                        .then(ClientCommandManager.literal("spacing")
                                .then(ClientCommandManager.argument("n", IntegerArgumentType.integer(1, 64))
                                        .executes(ctx -> {
                                            setSpacing(IntegerArgumentType.getInteger(ctx, "n"));
                                            ctx.getSource().sendFeedback(Text.literal("TntLine: khoảng cách = " + spacing));
                                            return 1;
                                        })))
                        .then(ClientCommandManager.literal("count")
                                .then(ClientCommandManager.argument("n", IntegerArgumentType.integer(1, 512))
                                        .executes(ctx -> {
                                            count = IntegerArgumentType.getInteger(ctx, "n");
                                            saveConfig();
                                            ctx.getSource().sendFeedback(Text.literal("TntLine: số TNT = " + count));
                                            return 1;
                                        })))
                        .then(ClientCommandManager.literal("autowalk")
                                .then(ClientCommandManager.argument("on", BoolArgumentType.bool())
                                        .executes(ctx -> {
                                            autoWalk = BoolArgumentType.getBool(ctx, "on");
                                            saveConfig();
                                            ctx.getSource().sendFeedback(Text.literal("TntLine: tự đi tới = " + (autoWalk ? "BẬT" : "TẮT")));
                                            return 1;
                                        })))
                        .then(ClientCommandManager.literal("delay")
                                .then(ClientCommandManager.argument("ticks", IntegerArgumentType.integer(0, 40))
                                        .executes(ctx -> {
                                            delay = IntegerArgumentType.getInteger(ctx, "ticks");
                                            saveConfig();
                                            ctx.getSource().sendFeedback(Text.literal("TntLine: delay = " + delay + " tick"));
                                            return 1;
                                        })))));
    }

    private void onTick(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null || mc.interactionManager == null) {
            active = false;
            return;
        }

        while (toggleKey.wasPressed()) {
            if (active) stop(p, "Đã dừng");
            else start(p);
        }
        while (spacingUpKey.wasPressed()) {
            setSpacing(spacing + 1);
            p.sendMessage(Text.literal("TntLine: khoảng cách = " + spacing), true);
        }
        while (spacingDownKey.wasPressed()) {
            setSpacing(spacing - 1);
            p.sendMessage(Text.literal("TntLine: khoảng cách = " + spacing), true);
        }

        while (autoWalkKey.wasPressed()) {
            autoWalk = !autoWalk;
            saveConfig();
            p.sendMessage(Text.literal("TntLine: tự đi tới = " + (autoWalk ? "BẬT" : "TẮT")), true);
        }

        if (!active || mc.currentScreen != null) return;

        // Tự đi tới: giữ phím W, TNT được đặt phía sau lưng nên không cản đường
        if (autoWalk) mc.options.forwardKey.setPressed(true);
        if (cooldown > 0) {
            cooldown--;
            return;
        }

        int slot = findTnt(p);
        if (slot < 0) {
            stop(p, "Hết TNT trong hotbar");
            return;
        }

        boolean allDone = true;
        int from = autoWalk ? 0 : 1;
        int to = autoWalk ? count - 1 : count;
        for (int i = from; i <= to; i++) {
            if (placed.contains(i)) continue;
            BlockPos target = anchor.offset(dir, i * spacing);
            BlockState st = mc.world.getBlockState(target);
            if (st.isOf(Blocks.TNT)) {
                placed.add(i);
                continue;
            }
            allDone = false;
            if (!st.isReplaceable()) continue;

            if (autoWalk) {
                // chỉ đặt ô nằm phía sau người chơi (theo hướng đi)
                int along = (target.getX() - p.getBlockX()) * dir.getOffsetX()
                        + (target.getZ() - p.getBlockZ()) * dir.getOffsetZ();
                if (along > -1) continue;
            }

            BlockPos below = target.down();
            if (!mc.world.getBlockState(below).isSideSolidFullSquare(mc.world, below, Direction.UP)) continue;
            if (p.getEyePos().distanceTo(Vec3d.ofCenter(target)) > REACH) continue;
            if (p.getBoundingBox().intersects(new Box(target))) continue;

            if (prevSlot < 0) prevSlot = p.getInventory().selectedSlot;
            p.getInventory().selectedSlot = slot;

            BlockHitResult hit = new BlockHitResult(
                    Vec3d.ofCenter(below).add(0, 0.5, 0), Direction.UP, below, false);
            if (mc.interactionManager.interactBlock(p, Hand.MAIN_HAND, hit).isAccepted()) {
                p.swingHand(Hand.MAIN_HAND);
                placed.add(i);
                cooldown = delay;
            }
            break;
        }

        if (allDone) stop(p, "Xong! Đã đặt " + placed.size() + " TNT");
    }

    private void start(ClientPlayerEntity p) {
        anchor = p.getBlockPos();
        dir = p.getHorizontalFacing();
        placed.clear();
        cooldown = 0;
        prevSlot = -1;
        active = true;
        p.sendMessage(Text.literal("TntLine: BẬT (hướng " + dir.asString() + ", cách " + spacing
                + ", tối đa " + count + (autoWalk ? ", tự đi tới)" : "). Đi tới dọc theo đường để đặt tiếp.")), true);
    }

    private void stop(ClientPlayerEntity p, String msg) {
        active = false;
        KeyBinding.updatePressedStates(); // nhả phím W do mod giữ
        if (prevSlot >= 0) {
            p.getInventory().selectedSlot = prevSlot;
            prevSlot = -1;
        }
        if (msg != null) p.sendMessage(Text.literal("TntLine: " + msg), true);
    }

    private int findTnt(ClientPlayerEntity p) {
        for (int i = 0; i < 9; i++) {
            if (p.getInventory().getStack(i).isOf(Items.TNT)) return i;
        }
        return -1;
    }

    private void setSpacing(int n) {
        spacing = Math.max(1, Math.min(64, n));
        placed.clear(); // vị trí đổi -> kiểm tra lại (TNT đã có sẽ tự được đánh dấu)
        saveConfig();
    }

    // ---- config đơn giản: .minecraft/config/tntline.properties ----
    private Path cfgPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("tntline.properties");
    }

    private void loadConfig() {
        Path f = cfgPath();
        if (!Files.exists(f)) return;
        try (InputStream in = Files.newInputStream(f)) {
            Properties pr = new Properties();
            pr.load(in);
            spacing = Integer.parseInt(pr.getProperty("spacing", "2"));
            count = Integer.parseInt(pr.getProperty("count", "20"));
            delay = Integer.parseInt(pr.getProperty("delay", "2"));
            autoWalk = Boolean.parseBoolean(pr.getProperty("autowalk", "false"));
        } catch (IOException | NumberFormatException ignored) {
        }
    }

    private void saveConfig() {
        try (OutputStream out = Files.newOutputStream(cfgPath())) {
            Properties pr = new Properties();
            pr.setProperty("spacing", String.valueOf(spacing));
            pr.setProperty("count", String.valueOf(count));
            pr.setProperty("delay", String.valueOf(delay));
            pr.setProperty("autowalk", String.valueOf(autoWalk));
            pr.store(out, "TntLine config");
        } catch (IOException ignored) {
        }
    }
              }

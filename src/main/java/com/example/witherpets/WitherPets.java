package com.example;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * Cerberus and Pluto: two named Withers you boss around with
 *   /cerberus <action>   /pluto <action>   /allwi <action>
 */
public class WitherPets implements ModInitializer {

    enum Mode { GUARD, FOLLOW, STAY, ATTACK }

    static final class State {
        Mode mode = Mode.GUARD;
        UUID subject; // the player to follow, or the player to attack
    }

    record Pet(String id, String name, double spawnOffsetX) {
        String tag() { return "wither_pets_" + id; }
    }

    static final Pet CERBERUS = new Pet("cerberus", "Cerberus", -3);
    static final Pet PLUTO = new Pet("pluto", "Pluto", 3);
    static final List<Pet> ALL = List.of(CERBERUS, PLUTO);

    /** Extra regeneration: heal HEAL_AMOUNT health every HEAL_INTERVAL_TICKS ticks (20 ticks = 1 second). */
    static final int HEAL_INTERVAL_TICKS = 20;
    static final float HEAL_AMOUNT = 5.0f; // a Wither has 300 max health, so this is ~1 minute for a full heal

    static final Map<String, State> STATES = new HashMap<>();
    static final Map<String, List<WitherBoss>> CACHE = new HashMap<>();

    @FunctionalInterface
    interface Action {
        boolean apply(CommandSourceStack src, Pet pet) throws CommandSyntaxException;
    }

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(build("cerberus", List.of(CERBERUS)));
            dispatcher.register(build("pluto", List.of(PLUTO)));
            dispatcher.register(build("allwi", ALL));
        });
        ServerTickEvents.END_SERVER_TICK.register(WitherPets::tick);
    }

    // ---------------------------------------------------------------- commands

    private static LiteralArgumentBuilder<CommandSourceStack> build(String root, List<Pet> pets) {
        return Commands.literal(root)
            .then(Commands.literal("spawn").executes(c -> each(c, pets, WitherPets::spawn)))
            .then(Commands.literal("follow").executes(c -> each(c, pets, (s, p) -> setMode(s, p, Mode.FOLLOW, "I follow."))))
            .then(Commands.literal("stay").executes(c -> each(c, pets, (s, p) -> setMode(s, p, Mode.STAY, "I will hold this ground."))))
            .then(Commands.literal("guard").executes(c -> each(c, pets, (s, p) -> setMode(s, p, Mode.GUARD, "I shall guard this place."))))
            .then(Commands.literal("come").executes(c -> each(c, pets, WitherPets::come)))
            .then(Commands.literal("status").executes(c -> each(c, pets, WitherPets::status)))
            .then(Commands.literal("dismiss").executes(c -> each(c, pets, WitherPets::dismiss)))
            .then(Commands.literal("attack")
                .then(Commands.argument("target", EntityArgument.player())
                    .executes(c -> {
                        ServerPlayer target = EntityArgument.getPlayer(c, "target");
                        return each(c, pets, (s, p) -> attack(s, p, target));
                    })))
            .then(Commands.literal("say")
                .then(Commands.argument("message", StringArgumentType.greedyString())
                    .executes(c -> {
                        String msg = StringArgumentType.getString(c, "message");
                        return each(c, pets, (s, p) -> {
                            if (find(s.getServer(), p).isEmpty()) return notFound(s, p);
                            speak(s.getServer(), p, msg);
                            return true;
                        });
                    })));
    }

    private static int each(CommandContext<CommandSourceStack> ctx, List<Pet> pets, Action action)
            throws CommandSyntaxException {
        int ok = 0;
        for (Pet pet : pets) {
            if (action.apply(ctx.getSource(), pet)) ok++;
        }
        return ok;
    }

    // ----------------------------------------------------------------- actions

    private static boolean spawn(CommandSourceStack src, Pet pet) throws CommandSyntaxException {
        MinecraftServer server = src.getServer();
        if (!find(server, pet).isEmpty()) {
            src.sendFailure(Component.literal(pet.name() + " already exists. Use /" + pet.id() + " dismiss first."));
            return false;
        }
        ServerPlayer player = src.getPlayerOrException();
        ServerLevel level = src.getLevel();
        Vec3 pos = player.position().add(pet.spawnOffsetX(), 3, 0);

        WitherBoss wither = EntityType.WITHER.create(level, EntitySpawnReason.COMMAND);
        if (wither == null) return false;
        wither.setPos(pos.x, pos.y, pos.z);
        wither.setCustomName(Component.literal(pet.name()));
        wither.setCustomNameVisible(true);
        wither.setPersistenceRequired();
        wither.addTag(pet.tag());
        level.addFreshEntity(wither);

        State st = new State();
        st.mode = Mode.FOLLOW;
        st.subject = player.getUUID();
        STATES.put(pet.id(), st);
        CACHE.put(pet.id(), find(server, pet));

        speak(server, pet, "I have awakened. I follow " + player.getName().getString() + ".");
        return true;
    }

    private static boolean setMode(CommandSourceStack src, Pet pet, Mode mode, String reply)
            throws CommandSyntaxException {
        if (find(src.getServer(), pet).isEmpty()) return notFound(src, pet);
        State st = state(pet);
        st.mode = mode;
        st.subject = (mode == Mode.FOLLOW) ? src.getPlayerOrException().getUUID() : null;
        speak(src.getServer(), pet, reply);
        return true;
    }

    private static boolean attack(CommandSourceStack src, Pet pet, ServerPlayer target) {
        if (find(src.getServer(), pet).isEmpty()) return notFound(src, pet);
        State st = state(pet);
        st.mode = Mode.ATTACK;
        st.subject = target.getUUID();
        speak(src.getServer(), pet, "Prepare yourself, " + target.getName().getString() + ".");
        return true;
    }

    private static boolean come(CommandSourceStack src, Pet pet) throws CommandSyntaxException {
        List<WitherBoss> list = find(src.getServer(), pet);
        if (list.isEmpty()) return notFound(src, pet);
        ServerPlayer p = src.getPlayerOrException();
        for (WitherBoss w : list) {
            if (w.level() != p.level()) continue; // cross-dimension travel not handled
            w.teleportTo(p.getX() + pet.spawnOffsetX(), p.getY() + 2, p.getZ());
        }
        speak(src.getServer(), pet, "I am here.");
        return true;
    }

    private static boolean status(CommandSourceStack src, Pet pet) {
        List<WitherBoss> list = find(src.getServer(), pet);
        if (list.isEmpty()) return notFound(src, pet);
        WitherBoss w = list.get(0);
        State st = state(pet);
        src.sendSuccess(() -> Component.literal(String.format(
            "%s: %s, health %.0f/%.0f at %.0f %.0f %.0f",
            pet.name(), st.mode, w.getHealth(), w.getMaxHealth(), w.getX(), w.getY(), w.getZ())), false);
        return true;
    }

    private static boolean dismiss(CommandSourceStack src, Pet pet) {
        List<WitherBoss> list = find(src.getServer(), pet);
        if (list.isEmpty()) return notFound(src, pet);
        speak(src.getServer(), pet, "Farewell.");
        list.forEach(WitherBoss::discard);
        STATES.remove(pet.id());
        CACHE.remove(pet.id());
        return true;
    }

    private static boolean notFound(CommandSourceStack src, Pet pet) {
        src.sendFailure(Component.literal(pet.name() + " isn't around (use /" + pet.id()
            + " spawn, or he may be in an unloaded chunk)."));
        return false;
    }

    // ------------------------------------------------------------------ helpers

    private static State state(Pet pet) {
        return STATES.computeIfAbsent(pet.id(), k -> new State());
    }

    private static void speak(MinecraftServer server, Pet pet, String text) {
        server.getPlayerList().broadcastSystemMessage(Component.literal("<" + pet.name() + "> " + text), false);
    }

    private static List<WitherBoss> find(MinecraftServer server, Pet pet) {
        List<WitherBoss> out = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            out.addAll(level.getEntities(EntityTypeTest.forClass(WitherBoss.class),
                w -> w.isAlive() && w.getTags().contains(pet.tag())));
        }
        return out;
    }

    // --------------------------------------------------------------------- AI

    private static void tick(MinecraftServer server) {
        boolean refresh = server.getTickCount() % 20 == 0;
        for (Pet pet : ALL) {
            if (refresh) CACHE.put(pet.id(), find(server, pet));
            State st = state(pet);
            for (WitherBoss w : CACHE.getOrDefault(pet.id(), List.of())) {
                if (w.isRemoved() || !w.isAlive()) continue;
                control(server, w, st);
            }
        }
    }

    private static void control(MinecraftServer server, WitherBoss w, State st) {
        // Heal over time
        if (server.getTickCount() % HEAL_INTERVAL_TICKS == 0 && w.getHealth() < w.getMaxHealth()) {
            w.heal(HEAL_AMOUNT);
        }

        ServerPlayer subject = st.subject == null ? null : server.getPlayerList().getPlayer(st.subject);

        // Withers normally attack players. Only allow that in ATTACK mode.
        if (st.mode == Mode.ATTACK) {
            if (subject != null && subject.isAlive() && !subject.isSpectator() && subject.level() == w.level()) {
                w.setTarget(subject);
            } else {
                st.mode = Mode.GUARD;
                st.subject = null;
            }
        } else if (w.getTarget() instanceof Player) {
            w.setTarget(null);
        }

        switch (st.mode) {
            case FOLLOW -> {
                if (subject == null || subject.level() != w.level()) return;
                double dist = w.distanceTo(subject);
                if (dist > 64) {
                    w.teleportTo(subject.getX(), subject.getY() + 2, subject.getZ());
                } else if (dist > 6) {
                    w.getMoveControl().setWantedPosition(subject.getX(), subject.getY() + 2, subject.getZ(), 1.4);
                }
            }
            case STAY -> {
                w.getNavigation().stop();
                w.setDeltaMovement(Vec3.ZERO);
            }
            default -> { }
        }
    }
}

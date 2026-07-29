package com.froggylord.constellation.constellation;

import com.froggylord.constellation.ConstellationClient;
import com.froggylord.constellation.config.HerculesConfig;
import com.froggylord.constellation.core.LocationManager;
import com.froggylord.constellation.render.WorldRenderer;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.*;

// ported from SkyHanni (LGPL-3.0-or-later): features/garden/farming/lane/FarmingLane.kt
// ported from SkyHanni (LGPL-3.0-or-later): features/garden/farming/lane/FarmingLaneApi.kt
// ported from SkyHanni (LGPL-3.0-or-later): features/garden/farming/lane/FarmingLaneCreator.kt
// ported from SkyHanni (LGPL-3.0-or-later): features/garden/farming/lane/FarmingLaneFeatures.kt
public final class HerculesFarmingLanes {
    public record Row(String label, String value, int color) {}
    private enum Direction { NORTH_SOUTH, EAST_WEST }
    private enum Movement { PAUSED("Paused"), TOO_SLOW("Too slow"), CALCULATING("Calculating"), NORMAL(""); final String label; Movement(String label) { this.label = label; } }
    private record Lane(Direction direction, double min, double max) {}

    private static HerculesConfig cfg;
    private static boolean detecting;
    private static Vec3 detectionStart;
    private static Vec3 detectionLast;
    private static Vec3 potentialEnd;
    private static HerculesGardenTracker.Crop detectionCrop;
    private static double maxDistance;
    private static HerculesGardenTracker.Crop currentCrop;
    private static Lane currentLane;
    private static final Map<String, Double> manualStarts = new HashMap<>();
    private static double previousAxis = Double.NaN;
    private static long previousAt;
    private static int lastDirection;
    private static double distance;
    private static double seconds;
    private static double lastRoundedSpeed = -1;
    private static int stableSpeedTicks;
    private static Movement movement = Movement.CALCULATING;
    private static boolean warned;
    private static long lastSound;
    private static long lastMissingWarning;

    private HerculesFarmingLanes() {}

    public static void init(HerculesConfig config) {
        cfg = config;
        maps();
        HerculesGardenTracker.registerHarvestListener(HerculesFarmingLanes::onHarvest);
        ConstellationClient.tick().every(1, "hercules-farming-lanes", HerculesFarmingLanes::tick);
        ClientPlayConnectionEvents.JOIN.register((a, b, c) -> resetTransient());
        ClientPlayConnectionEvents.DISCONNECT.register((a, b) -> resetTransient());
    }

    public static void registerCommands(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(LiteralArgumentBuilder.<FabricClientCommandSource>literal("farmlane")
            .executes(ctx -> status())
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("status").executes(ctx -> status()))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("detect").executes(ctx -> toggleDetection()))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("stop").executes(ctx -> stopDetection()))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("clear")
                .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("crop", StringArgumentType.word())
                    .executes(ctx -> clear(StringArgumentType.getString(ctx, "crop")))))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("ignore")
                .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("crop", StringArgumentType.word())
                    .executes(ctx -> ignore(StringArgumentType.getString(ctx, "crop")))))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("set")
                .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("crop", StringArgumentType.word())
                    .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("direction", StringArgumentType.word())
                        .then(RequiredArgumentBuilder.<FabricClientCommandSource, Double>argument("min", DoubleArgumentType.doubleArg(-30_000_000, 30_000_000))
                            .then(RequiredArgumentBuilder.<FabricClientCommandSource, Double>argument("max", DoubleArgumentType.doubleArg(-30_000_000, 30_000_000))
                                .executes(ctx -> set(StringArgumentType.getString(ctx, "crop"), StringArgumentType.getString(ctx, "direction"),
                                    DoubleArgumentType.getDouble(ctx, "min"), DoubleArgumentType.getDouble(ctx, "max"))))))))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("start")
                .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("crop", StringArgumentType.word())
                    .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("direction", StringArgumentType.word())
                        .executes(ctx -> manualStart(StringArgumentType.getString(ctx, "crop"), StringArgumentType.getString(ctx, "direction"))))))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("end")
                .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("crop", StringArgumentType.word())
                    .executes(ctx -> manualEnd(StringArgumentType.getString(ctx, "crop")))))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("option")
                .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("name", StringArgumentType.word())
                    .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("state", StringArgumentType.word())
                        .executes(ctx -> option(StringArgumentType.getString(ctx, "name"), StringArgumentType.getString(ctx, "state")))))));
    }

    public static boolean hudVisible() {
        return active() && cfg.farmingLaneDistanceDisplay && currentLane != null && movementDataValid();
    }

    public static List<Row> hudRows() {
        if (!hudVisible()) return List.of();
        List<Row> rows = new ArrayList<>();
        if (cfg.farmingLaneShowCrop && currentCrop != null) rows.add(new Row("Crop", currentCrop.display(), 0xFFFFFF55));
        if (cfg.farmingLaneShowDistance) rows.add(new Row("Distance", decimal(distance, cfg.farmingLaneDistancePrecision) + "m", 0xFFFFFF55));
        if (cfg.farmingLaneShowTime) rows.add(new Row("Time", movement == Movement.NORMAL ? time(seconds) : movement.label, movement == Movement.NORMAL ? 0xFF55FFFF : 0xFFFFAA00));
        if (cfg.farmingLaneShowSpeed) rows.add(new Row("Speed", decimal(speed(), 2) + " m/s", movement == Movement.NORMAL ? 0xFF55FF55 : 0xFFFFAA00));
        return rows;
    }

    public static void draw(WorldRenderer.Ctx ctx) {
        if (!active()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (detecting) {
            waypoint(ctx, detectionStart, "Lane Start", cfg.farmingLaneDetectionColor);
            waypoint(ctx, potentialEnd, "Potential End", cfg.farmingLaneDetectionColor);
            return;
        }
        if (!cfg.farmingLaneCornerWaypoints) return;
        if (currentLane == null) return;
        Vec3 player = mc.player.position();
        Vec3 first = setAxis(player, currentLane.direction, currentLane.min);
        Vec3 second = setAxis(player, currentLane.direction, currentLane.max);
        waypoint(ctx, first, "Lane Corner", cfg.farmingLaneCornerColor);
        waypoint(ctx, second, "Lane Corner", cfg.farmingLaneCornerColor);
    }

    private static void onHarvest(HerculesGardenTracker.Harvest harvest) {
        if (!inGarden()) return;
        currentCrop = harvest.crop();
        Lane lane = lane(currentCrop);
        if (!Objects.equals(lane, currentLane)) {
            currentLane = lane;
            resetMovement();
        }
        if (detecting) detect(harvest.crop());
        else if (lane == null) warnMissing(harvest.crop());
    }

    private static void detect(HerculesGardenTracker.Crop crop) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        Vec3 location = mc.player.position();
        if (detectionLast == null) {
            detectionStart = location;
            detectionLast = location;
            detectionCrop = crop;
            maxDistance = 0;
            potentialEnd = null;
            return;
        }
        if (crop != detectionCrop && !(flower(crop) && flower(detectionCrop))) {
            local("A different crop was broken; lane detection stopped.");
            clearDetection();
            return;
        }
        if (detectionLast.distanceTo(location) < .5) return;
        detectionLast = location;
        double travelled = detectionStart.distanceTo(location);
        if (travelled > maxDistance) {
            maxDistance = travelled;
            potentialEnd = null;
            return;
        }
        if (potentialEnd == null) {
            potentialEnd = location;
            return;
        }
        if (potentialEnd.distanceTo(location) <= Math.max(.5, cfg.farmingLaneDetectionTurnDistance)) return;
        saveDetected(detectionStart, potentialEnd, detectionCrop);
    }

    private static void saveDetected(Vec3 first, Vec3 second, HerculesGardenTracker.Crop crop) {
        double x = Math.abs(first.x - second.x), z = Math.abs(first.z - second.z);
        Direction direction = z > x ? Direction.NORTH_SOUTH : Direction.EAST_WEST;
        double a = axis(first, direction), b = axis(second, direction);
        Lane lane = new Lane(direction, Math.min(a, b), Math.max(a, b));
        put(crop, lane);
        if (flower(crop)) {
            put(HerculesGardenTracker.Crop.SUNFLOWER, lane);
            put(HerculesGardenTracker.Crop.MOONFLOWER, lane);
        }
        currentCrop = crop;
        currentLane = lane;
        ConstellationClient.saveConfig();
        local(crop.display() + " lane saved from " + decimal(lane.min, 1) + " to " + decimal(lane.max, 1) + ".");
        clearDetection();
    }

    private static void tick() {
        if (!active()) { resetMovement(); return; }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        HerculesGardenTracker.Crop held = HerculesGardenTracker.cropInHand(mc.player.getMainHandItem());
        if (held != null && held != currentCrop) {
            currentCrop = held;
            currentLane = lane(held);
            resetMovement();
        }
        if (currentLane == null || HerculesGardenTracker.rates() == null) { resetMovementDataOnly(); return; }
        double position = axis(mc.player.position(), currentLane.direction);
        if (position < currentLane.min || position > currentLane.max) { resetMovementDataOnly(); return; }
        long now = System.currentTimeMillis();
        if (!Double.isFinite(previousAxis) || previousAt == 0) {
            previousAxis = position;
            previousAt = now;
            return;
        }
        double delta = position - previousAxis;
        double elapsed = Math.max(.001, (now - previousAt) / 1000.0);
        previousAxis = position;
        previousAt = now;
        int direction = delta < 0 ? 1 : delta > 0 ? -1 : 0;
        if (direction != 0 && direction != lastDirection) {
            lastDirection = direction;
            warned = false;
            stableSpeedTicks = 0;
        }
        if (direction == 1) distance = Math.abs(position - currentLane.min);
        else if (direction == -1) distance = Math.abs(currentLane.max - position);
        double rawSpeed = Math.abs(delta) / elapsed;
        double rounded = Math.round(rawSpeed * 100) / 100.0;
        if (lastRoundedSpeed < 0 || Math.abs(rounded - lastRoundedSpeed) > .05) stableSpeedTicks = 0;
        else stableSpeedTicks++;
        lastRoundedSpeed = rounded;
        movement = rounded == 0 && stableSpeedTicks > 1 ? Movement.PAUSED
            : rounded < Math.max(.01, cfg.farmingLaneSlowSpeedHundredths / 100.0) && stableSpeedTicks > 5 ? Movement.TOO_SLOW
            : stableSpeedTicks < Math.max(1, cfg.farmingLaneStableTicks) ? Movement.CALCULATING : Movement.NORMAL;
        seconds = movement == Movement.NORMAL && rounded > 0 ? distance / rounded : Double.NaN;
        notifyApproach(now);
    }

    private static void notifyApproach(long now) {
        if (!cfg.farmingLaneNotification || movement != Movement.NORMAL || !Double.isFinite(seconds)) return;
        double threshold = Math.clamp(cfg.farmingLaneSecondsBefore, 1, 30);
        if (seconds >= threshold) { warned = false; return; }
        if (!warned) {
            warned = true;
            String text = template(cfg.farmingLaneMessage);
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                if (cfg.farmingLaneNotificationTitle) {
                    mc.gui.hud.resetTitleTimes();
                    mc.gui.hud.setTitle(Component.literal(text.replace('&', '§')));
                }
                if (cfg.farmingLaneNotificationChat) local(text.replace('&', '§'));
            }
        }
        long repeat = Math.max(1, cfg.farmingLaneSoundRepeatTicks) * 50L;
        if (cfg.farmingLaneNotificationSound && now - lastSound >= repeat) {
            lastSound = now;
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) mc.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 1, Math.clamp(cfg.farmingLaneSoundPitchHundredths / 100f, .5f, 2f));
        }
    }

    private static void warnMissing(HerculesGardenTracker.Crop crop) {
        if (!cfg.farmingLaneMissingWarning || ignored(crop) || detecting
            || !cfg.farmingLaneDistanceDisplay && !cfg.farmingLaneNotification) return;
        long now = System.currentTimeMillis();
        if (now - lastMissingWarning < Math.max(5, cfg.farmingLaneMissingRepeatSeconds) * 1000L) return;
        lastMissingWarning = now;
        local("No " + crop.display() + " lane is saved. Run /farmlane detect and farm two layers.");
    }

    private static int toggleDetection() {
        if (!inGarden()) { local("Enter the Garden first."); return 0; }
        detecting = !detecting;
        if (detecting) {
            detectionStart = null; detectionLast = null; potentialEnd = null; detectionCrop = null; maxDistance = 0;
            local("Lane detection started. Farm two layers so the turnaround can be measured.");
        } else {
            clearDetection();
            local("Lane detection stopped.");
        }
        return 1;
    }
    private static int stopDetection() { clearDetection(); local("Lane detection stopped."); return 1; }

    private static int set(String cropName, String directionName, double min, double max) {
        HerculesGardenTracker.Crop crop = crop(cropName);
        Direction direction = direction(directionName);
        if (crop == null || direction == null || !Double.isFinite(min) || !Double.isFinite(max) || min == max) {
            local("Use a valid crop, direction northsouth/eastwest, and two different finite bounds.");
            return 0;
        }
        put(crop, new Lane(direction, Math.min(min, max), Math.max(min, max)));
        ConstellationClient.saveConfig();
        return status();
    }
    private static int manualStart(String cropName, String directionName) {
        HerculesGardenTracker.Crop crop = crop(cropName);
        Direction direction = direction(directionName);
        Minecraft mc = Minecraft.getInstance();
        if (crop == null || direction == null || mc.player == null || !inGarden()) {
            local("Enter the Garden and use a valid crop and direction northsouth/eastwest.");
            return 0;
        }
        manualStarts.put(key(crop), axis(mc.player.position(), direction));
        manualStarts.put(key(crop) + "|direction", (double) direction.ordinal());
        local(crop.display() + " lane start saved here. Stand at the other end and run /farmlane end " + commandCrop(crop) + ".");
        return 1;
    }
    private static int manualEnd(String cropName) {
        HerculesGardenTracker.Crop crop = crop(cropName);
        Minecraft mc = Minecraft.getInstance();
        Double start = crop == null ? null : manualStarts.get(key(crop));
        Double directionValue = crop == null ? null : manualStarts.get(key(crop) + "|direction");
        if (crop == null || mc.player == null || !inGarden() || start == null || directionValue == null) {
            local("Set this crop's first point with /farmlane start <crop> <direction>.");
            return 0;
        }
        Direction direction = Direction.values()[Math.clamp(directionValue.intValue(), 0, Direction.values().length - 1)];
        double end = axis(mc.player.position(), direction);
        if (Math.abs(end - start) < .5) {
            local("Move to the other end of the lane before setting its end.");
            return 0;
        }
        manualStarts.remove(key(crop));
        manualStarts.remove(key(crop) + "|direction");
        return set(crop.name(), direction.name(), start, end);
    }
    private static int clear(String cropName) {
        HerculesGardenTracker.Crop crop = crop(cropName);
        if (crop == null) { local("Unknown crop."); return 0; }
        cfg.farmingLanes.remove(key(crop));
        if (crop == currentCrop) { currentLane = null; resetMovement(); }
        ConstellationClient.saveConfig();
        return status();
    }
    private static int ignore(String cropName) {
        HerculesGardenTracker.Crop crop = crop(cropName);
        if (crop == null) { local("Unknown crop."); return 0; }
        Set<String> ignored = ignored();
        String name = crop.name();
        if (!ignored.add(name)) ignored.remove(name);
        cfg.farmingLaneIgnoredCrops = String.join(",", ignored);
        ConstellationClient.saveConfig();
        local(crop.display() + " missing-lane warnings " + (ignored.contains(name) ? "ignored" : "enabled") + ".");
        return 1;
    }

    private static int option(String name, String state) {
        Boolean value = bool(state);
        if (value == null) { local("Use on or off."); return 0; }
        switch (name.toLowerCase(Locale.ROOT)) {
            case "enabled", "display" -> cfg.farmingLaneDistanceDisplay = value;
            case "notification" -> cfg.farmingLaneNotification = value;
            case "title" -> cfg.farmingLaneNotificationTitle = value;
            case "chat" -> cfg.farmingLaneNotificationChat = value;
            case "sound" -> cfg.farmingLaneNotificationSound = value;
            case "corners" -> cfg.farmingLaneCornerWaypoints = value;
            case "missing" -> cfg.farmingLaneMissingWarning = value;
            case "crop" -> cfg.farmingLaneShowCrop = value;
            case "distance" -> cfg.farmingLaneShowDistance = value;
            case "time" -> cfg.farmingLaneShowTime = value;
            case "speed" -> cfg.farmingLaneShowSpeed = value;
            default -> { local("Options: display, notification, title, chat, sound, corners, missing, crop, distance, time, speed."); return 0; }
        }
        ConstellationClient.saveConfig();
        return status();
    }

    private static int status() {
        Lane lane = currentCrop == null ? null : lane(currentCrop);
        if (lane == null) local("Farming lanes: display " + on(cfg.farmingLaneDistanceDisplay) + ", notification " + on(cfg.farmingLaneNotification) + ". No held-crop lane.");
        else local(currentCrop.display() + " " + lane.direction.name().toLowerCase(Locale.ROOT) + " lane "
            + decimal(lane.min, 1) + " to " + decimal(lane.max, 1) + ".");
        return 1;
    }

    private static void waypoint(WorldRenderer.Ctx ctx, Vec3 pos, String label, int color) {
        if (pos == null) return;
        Vec3 point = new Vec3(pos.x, Math.min(pos.y, 76), pos.z);
        ctx.highlight(new AABB(point.x - .4, point.y, point.z - .4, point.x + .4, point.y + 1, point.z + .4), color, cfg.farmingLaneCornerThroughWalls);
        ctx.beam(point.x, point.y, point.z, color, Math.clamp(cfg.farmingLaneCornerBeamHeight, 2, 100), cfg.farmingLaneCornerThroughWalls);
        ctx.label(point.add(0, 1.4, 0), label, color, cfg.farmingLaneCornerThroughWalls);
    }

    private static Lane lane(HerculesGardenTracker.Crop crop) {
        if (crop == null) return null;
        String value = cfg.farmingLanes.get(key(crop));
        if (value == null) return null;
        try {
            String[] parts = value.split(",");
            Lane lane = new Lane(Direction.valueOf(parts[0]), Double.parseDouble(parts[1]), Double.parseDouble(parts[2]));
            return Double.isFinite(lane.min) && Double.isFinite(lane.max) && lane.min < lane.max ? lane : null;
        } catch (Exception ignored) { return null; }
    }
    private static void put(HerculesGardenTracker.Crop crop, Lane lane) { cfg.farmingLanes.put(key(crop), lane.direction.name() + "," + lane.min + "," + lane.max); }
    private static String key(HerculesGardenTracker.Crop crop) { return profile() + "|" + crop.name(); }
    private static String profile() { String value = LyraStorageValue.currentProfileKey(); return value == null || value.isBlank() ? "unknown" : value.toLowerCase(Locale.ROOT); }
    private static double axis(Vec3 pos, Direction direction) { return direction == Direction.NORTH_SOUTH ? pos.z : pos.x; }
    private static Vec3 setAxis(Vec3 pos, Direction direction, double value) { return direction == Direction.NORTH_SOUTH ? new Vec3(pos.x, pos.y, value) : new Vec3(value, pos.y, pos.z); }
    private static double speed() { return movement == Movement.NORMAL ? Math.max(0, lastRoundedSpeed) : 0; }
    private static boolean movementDataValid() { return currentLane != null && Double.isFinite(distance); }
    private static String template(String raw) { return (raw == null ? "Lane switch incoming." : raw).replace("{crop}", currentCrop == null ? "Crop" : currentCrop.display()).replace("{distance}", decimal(distance, 1)).replace("{time}", Double.isFinite(seconds) ? time(seconds) : "soon"); }
    private static String time(double raw) { long total = Math.max(0, (long)Math.ceil(raw)); return total >= 60 ? total / 60 + "m " + total % 60 + "s" : total + "s"; }
    private static String decimal(double value, int precision) { return String.format(Locale.ROOT, "%." + Math.clamp(precision, 0, 4) + "f", value); }
    private static boolean flower(HerculesGardenTracker.Crop crop) { return crop == HerculesGardenTracker.Crop.SUNFLOWER || crop == HerculesGardenTracker.Crop.MOONFLOWER; }
    private static Direction direction(String raw) { String value = raw.replace("_", "").replace("-", "").toLowerCase(Locale.ROOT); return value.equals("northsouth") || value.equals("ns") || value.equals("z") ? Direction.NORTH_SOUTH : value.equals("eastwest") || value.equals("ew") || value.equals("x") ? Direction.EAST_WEST : null; }
    private static HerculesGardenTracker.Crop crop(String raw) { if (raw == null) return null; String value = raw.replace("_", "").replace("-", "").replace(" ", "").toLowerCase(Locale.ROOT); for (HerculesGardenTracker.Crop crop : HerculesGardenTracker.Crop.values()) if (crop.name().replace("_", "").toLowerCase(Locale.ROOT).equals(value) || crop.display().replace(" ", "").toLowerCase(Locale.ROOT).equals(value)) return crop; return null; }
    private static String commandCrop(HerculesGardenTracker.Crop crop) { return crop.name().toLowerCase(Locale.ROOT); }
    private static Set<String> ignored() { Set<String> values = new LinkedHashSet<>(); if (cfg.farmingLaneIgnoredCrops != null) for (String value : cfg.farmingLaneIgnoredCrops.split(",")) if (!value.isBlank()) values.add(value.trim().toUpperCase(Locale.ROOT)); return values; }
    private static boolean ignored(HerculesGardenTracker.Crop crop) { return ignored().contains(crop.name()); }
    private static boolean active() { return cfg != null && cfg.enabled && inGarden() && (cfg.farmingLaneDistanceDisplay || cfg.farmingLaneNotification || cfg.farmingLaneCornerWaypoints || detecting); }
    private static boolean inGarden() { return ConstellationClient.loc().area() == LocationManager.SkyblockArea.GARDEN; }
    private static Boolean bool(String value) { return switch (value.toLowerCase(Locale.ROOT)) { case "on", "true", "yes", "1" -> true; case "off", "false", "no", "0" -> false; default -> null; }; }
    private static String on(boolean value) { return value ? "on" : "off"; }
    private static void local(String text) { Minecraft mc = Minecraft.getInstance(); if (mc.player != null) mc.player.sendSystemMessage(Component.literal("§2[Farm Lane] §f" + text)); }
    private static void clearDetection() { detecting = false; detectionStart = null; detectionLast = null; potentialEnd = null; detectionCrop = null; maxDistance = 0; }
    private static void resetMovementDataOnly() { previousAxis = Double.NaN; previousAt = 0; distance = Double.NaN; seconds = Double.NaN; movement = Movement.CALCULATING; stableSpeedTicks = 0; lastRoundedSpeed = -1; }
    private static void resetMovement() { resetMovementDataOnly(); lastDirection = 0; warned = false; lastSound = 0; }
    private static void resetTransient() { clearDetection(); manualStarts.clear(); currentCrop = null; currentLane = null; resetMovement(); lastMissingWarning = 0; }
    private static void maps() { if (cfg.farmingLanes == null) cfg.farmingLanes = new HashMap<>(); }
}

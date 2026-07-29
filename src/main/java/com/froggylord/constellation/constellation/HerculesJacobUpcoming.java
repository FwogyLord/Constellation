package com.froggylord.constellation.constellation;

import com.froggylord.constellation.ConstellationClient;
import com.froggylord.constellation.config.HerculesConfig;
import com.froggylord.constellation.core.LocationManager;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import org.lwjgl.glfw.GLFW;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// ported from SkyHanni (LGPL-3.0-or-later): features/garden/GardenNextJacobContest.kt
// ported from SkyHanni (LGPL-3.0-or-later): api/EliteDevApi.kt fetchUpcomingContests
// ported from SkyHanni (LGPL-3.0-or-later): data/jsonobjects/elitedev/EliteContestJson.kt
public final class HerculesJacobUpcoming {
    public record Row(String label, String value, int color) {}
    private record Contest(long start, List<HerculesGardenTracker.Crop> crops, HerculesGardenTracker.Crop boosted) {
        long end() { return start + 20 * 60_000L; }
        boolean active(long now) { return start <= now && end() > now; }
    }

    private static final String URL = "https://api.eliteskyblock.com/contests/at/now";
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "constellation-jacob-contests");
        thread.setDaemon(true);
        return thread;
    });
    private static final HttpClient HTTP = HttpClient.newBuilder().executor(IO).connectTimeout(Duration.ofSeconds(8)).build();
    private static HerculesConfig cfg;
    private static List<Contest> contests = List.of();
    private static boolean fetching;
    private static long lastAttempt;
    private static long warnedStart;
    private static HerculesGardenTracker.Crop boostedFromTab;
    private static long boostedSeenAt;

    private HerculesJacobUpcoming() {}

    public static void init(HerculesConfig config) {
        cfg = config;
        maps();
        load();
        ConstellationClient.tick().every(20, "hercules-jacob-upcoming", HerculesJacobUpcoming::tick);
        ClientPlayConnectionEvents.JOIN.register((a,b,c) -> { warnedStart=0;boostedFromTab=null;load(); });
        ClientPlayConnectionEvents.DISCONNECT.register((a,b) -> { warnedStart=0;boostedFromTab=null; });
    }

    private static void tick() {
        if (!active()) return;
        readBoosted();
        if (cfg.jacobUpcomingFetchAutomatically) fetch(false);
        warn();
    }

    public static boolean hudVisible() {
        if (!active() || !cfg.jacobUpcomingDisplay) return false;
        if (!inGarden() && !cfg.jacobUpcomingOutsideGarden) return false;
        return fetching || next() != null;
    }

    public static List<Row> hudRows() {
        if (!hudVisible()) return List.of();
        if (fetching && contests.isEmpty()) return List.of(new Row("Status", "Fetching contests", 0xFFFFAA00));
        Contest next = next();
        if (next == null) return List.of(new Row("Status", "Open calendar or refresh", 0xFFFF5555));
        long now = System.currentTimeMillis();
        List<Row> rows = new ArrayList<>();
        boolean active = next.active(now);
        long remaining = (active ? next.end() : next.start) - now;
        rows.add(new Row(active ? "Active" : "Next", cropText(next), active ? 0xFF55FF55 : 0xFFFFFF55));
        rows.add(new Row(active ? "Ends in" : "Starts in", time(remaining), remaining <= cfg.jacobUpcomingWarningSeconds * 1000L ? 0xFFFFAA00 : 0xFF55FFFF));
        if (cfg.jacobUpcomingShowBoosted && boosted(next) != null) rows.add(new Row("Boosted", boosted(next).display(), 0xFFFFAA00));
        if (cfg.jacobUpcomingShowFollowing) {
            int shown = 0;
            for (Contest contest : contests) {
                if (contest.start <= next.start || contest.end() <= now) continue;
                rows.add(new Row("Following " + (shown + 1), cropText(contest) + " (" + time(contest.start - now) + ")", 0xFFAAAAAA));
                if (++shown >= Math.clamp(cfg.jacobUpcomingFollowingCount, 1, 5)) break;
            }
        }
        if (cfg.jacobUpcomingShowSource) rows.add(new Row("Schedule", cfg.jacobUpcomingYear > 0 ? "Year " + cfg.jacobUpcomingYear : "Cached", 0xFFAAAAAA));
        return rows;
    }

    private static void fetch(boolean force) {
        long now = System.currentTimeMillis();
        long interval = Math.max(1, cfg.jacobUpcomingFetchMinutes) * 60_000L;
        if (fetching || !force && now - Math.max(lastAttempt, cfg.jacobUpcomingFetchedAt) < interval) return;
        fetching = true;
        lastAttempt = now;
        HttpRequest request = HttpRequest.newBuilder(URI.create(URL)).timeout(Duration.ofSeconds(12))
            .header("Accept", "application/json").header("User-Agent", "Constellation/" + version()).GET().build();
        HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString()).whenComplete((response,error) ->
            Minecraft.getInstance().execute(() -> finishFetch(response,error)));
    }

    private static void finishFetch(HttpResponse<String> response, Throwable error) {
        fetching = false;
        if (error != null || response == null || response.statusCode() / 100 != 2) {
            if (contests.isEmpty()) local("Contest schedule fetch failed. Existing cached data was kept.");
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            JsonObject data = root.has("data") && root.get("data").isJsonObject() ? root.getAsJsonObject("data") : root;
            JsonObject schedule = data.getAsJsonObject("contests");
            List<Contest> parsed = new ArrayList<>();
            for (Map.Entry<String,JsonElement> entry : schedule.entrySet()) {
                long start = Long.parseLong(entry.getKey()) * 1000L;
                List<HerculesGardenTracker.Crop> crops = new ArrayList<>();
                for (JsonElement value : entry.getValue().getAsJsonArray()) {
                    HerculesGardenTracker.Crop crop = crop(value.getAsString());
                    if (crop != null) crops.add(crop);
                }
                if (crops.size() == 3) parsed.add(new Contest(start,List.copyOf(crops),null));
            }
            parsed.sort(Comparator.comparingLong(Contest::start));
            if (parsed.isEmpty()) throw new IllegalStateException("empty schedule");
            contests = List.copyOf(parsed);
            cfg.jacobUpcomingYear = data.has("year") ? data.get("year").getAsInt() : 0;
            cfg.jacobUpcomingFetchedAt = System.currentTimeMillis();
            saveFuture();
            ConstellationClient.saveConfig();
        } catch (Exception ignored) {
            if (contests.isEmpty()) local("Contest schedule response was not valid. Existing cached data was kept.");
        }
    }

    private static void saveFuture() {
        long cutoff = System.currentTimeMillis() - 20 * 60_000L;
        Map<String,String> saved = new LinkedHashMap<>();
        for (Contest contest : contests) {
            if (contest.start < cutoff) continue;
            saved.put(Long.toString(contest.start), String.join(",", contest.crops.stream().map(Enum::name).toList()));
            if (saved.size() >= 40) break;
        }
        cfg.jacobUpcomingContests = saved;
    }

    private static void load() {
        maps();
        List<Contest> loaded = new ArrayList<>();
        for (Map.Entry<String,String> entry : cfg.jacobUpcomingContests.entrySet()) {
            try {
                long start = Long.parseLong(entry.getKey());
                List<HerculesGardenTracker.Crop> crops = Arrays.stream(entry.getValue().split(",")).map(HerculesJacobUpcoming::crop).filter(Objects::nonNull).toList();
                if (crops.size() == 3 && start + 20 * 60_000L > System.currentTimeMillis()) loaded.add(new Contest(start,crops,null));
            } catch (Exception ignored) {}
        }
        loaded.sort(Comparator.comparingLong(Contest::start));
        contests = List.copyOf(loaded);
    }

    private static void readBoosted() {
        if (!inGarden()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) return;
        for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
            Component display = info.getTabListDisplayName();
            if (display == null) continue;
            String line = display.getString();
            if (!line.contains("\u2618")) continue;
            HerculesGardenTracker.Crop crop = crop(line.replaceAll("\u00a7.", "").replace("\u2618", "").replace("\u25cb", "").trim());
            if (crop != null) { boostedFromTab=crop;boostedSeenAt=System.currentTimeMillis();return; }
        }
        if (System.currentTimeMillis()-boostedSeenAt>5*60_000L) boostedFromTab=null;
    }

    private static void warn() {
        if (!cfg.jacobUpcomingWarning || !inGarden() && !cfg.jacobUpcomingOutsideGarden) return;
        Contest contest = next();
        if (contest == null || contest.active(System.currentTimeMillis()) || contest.start == warnedStart) return;
        long left = contest.start - System.currentTimeMillis();
        if (left < 0 || left > Math.clamp(cfg.jacobUpcomingWarningSeconds,10,300)*1000L || contest.crops.stream().noneMatch(HerculesJacobUpcoming::warnCrop)) return;
        warnedStart = contest.start;
        String text = template(cfg.jacobUpcomingMessage,contest,left);
        Minecraft mc = Minecraft.getInstance();
        if (cfg.jacobUpcomingWarningChat) local(text);
        if (mc.player != null && cfg.jacobUpcomingWarningTitle) {
            mc.gui.hud.resetTitleTimes();
            mc.gui.hud.setTimes(0,Math.clamp(cfg.jacobUpcomingTitleTicks,10,300),10);
            mc.gui.hud.setTitle(Component.literal(text).withColor(0xFFFF55));
        }
        if (mc.player != null && cfg.jacobUpcomingWarningSound) mc.player.playSound(SoundEvents.NOTE_BLOCK_PLING.value(),1,1);
        if (cfg.jacobUpcomingWarningAttention && !mc.isWindowActive()) GLFW.glfwRequestWindowAttention(mc.getWindow().handle());
    }

    public static void registerCommands(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(LiteralArgumentBuilder.<FabricClientCommandSource>literal("nextcontest")
            .executes(c->status())
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("refresh").executes(c->{fetch(true);local("Refreshing the contest schedule.");return 1;}))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("clear").executes(c->{cfg.jacobUpcomingContests.clear();contests=List.of();cfg.jacobUpcomingFetchedAt=0;save();return status();}))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("warning").then(RequiredArgumentBuilder.<FabricClientCommandSource,Integer>argument("seconds",IntegerArgumentType.integer(10,300)).executes(c->{cfg.jacobUpcomingWarningSeconds=IntegerArgumentType.getInteger(c,"seconds");save();return status();})))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("warncrop").then(RequiredArgumentBuilder.<FabricClientCommandSource,String>argument("crop",StringArgumentType.word()).executes(c->toggleCrop(StringArgumentType.getString(c,"crop")))))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("message").then(RequiredArgumentBuilder.<FabricClientCommandSource,String>argument("text",StringArgumentType.greedyString()).executes(c->message(StringArgumentType.getString(c,"text")))))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("option").then(RequiredArgumentBuilder.<FabricClientCommandSource,String>argument("name",StringArgumentType.word()).then(RequiredArgumentBuilder.<FabricClientCommandSource,String>argument("state",StringArgumentType.word()).executes(c->option(StringArgumentType.getString(c,"name"),StringArgumentType.getString(c,"state")))))));
    }

    private static int option(String name,String raw){Boolean value=bool(raw);if(value==null){local("Use on or off.");return 0;}switch(name.toLowerCase(Locale.ROOT)){case"display"->cfg.jacobUpcomingDisplay=value;case"outside"->cfg.jacobUpcomingOutsideGarden=value;case"fetch"->cfg.jacobUpcomingFetchAutomatically=value;case"warning"->cfg.jacobUpcomingWarning=value;case"title"->cfg.jacobUpcomingWarningTitle=value;case"chat"->cfg.jacobUpcomingWarningChat=value;case"sound"->cfg.jacobUpcomingWarningSound=value;case"attention"->cfg.jacobUpcomingWarningAttention=value;case"boosted"->cfg.jacobUpcomingShowBoosted=value;case"following"->cfg.jacobUpcomingShowFollowing=value;case"source"->cfg.jacobUpcomingShowSource=value;default->{local("Unknown next-contest option.");return 0;}}save();return status();}
    private static int toggleCrop(String raw){HerculesGardenTracker.Crop crop=crop(raw);if(crop==null){local("Unknown crop.");return 0;}Set<String> set=warnCrops();if(!set.add(crop.name()))set.remove(crop.name());cfg.jacobUpcomingWarnCrops=String.join(",",set);save();local(crop.display()+" warnings "+(set.contains(crop.name())?"enabled.":"disabled."));return 1;}
    private static int message(String raw){String value=raw.replace('\n',' ').replace('\r',' ').trim();if(value.isBlank()||value.length()>160){local("Message must contain 1-160 characters.");return 0;}cfg.jacobUpcomingMessage=value;save();return status();}
    private static int status(){Contest contest=next();local("Upcoming contest display "+on(cfg.jacobUpcomingDisplay)+", fetch "+on(cfg.jacobUpcomingFetchAutomatically)+", warning "+on(cfg.jacobUpcomingWarning)+".");local(contest==null?"No upcoming contest cached.":(contest.active(System.currentTimeMillis())?"Active: ":"Next: ")+cropText(contest)+" in "+time((contest.active(System.currentTimeMillis())?contest.end():contest.start)-System.currentTimeMillis())+".");return 1;}
    private static Contest next(){long now=System.currentTimeMillis();for(Contest contest:contests)if(contest.end()>now)return contest;return null;}
    private static HerculesGardenTracker.Crop boosted(Contest contest){return boostedFromTab!=null&&contest.crops.contains(boostedFromTab)&&Math.abs(contest.start-System.currentTimeMillis())<40*60_000L?boostedFromTab:contest.boosted;}
    private static String cropText(Contest contest){HerculesGardenTracker.Crop boosted=boosted(contest);return String.join(", ",contest.crops.stream().map(c->(c==boosted?"*":"")+c.display()).toList());}
    private static String template(String raw,Contest contest,long left){return(raw==null?"Farming Contest soon: {crops} in {time}":raw).replace("{crops}",cropText(contest)).replace("{time}",time(left)).replace("{boosted}",boosted(contest)==null?"None":boosted(contest).display());}
    private static HerculesGardenTracker.Crop crop(String raw){if(raw==null)return null;String value=raw.replaceAll("\u00a7.","").replace("_","").replace("-","").replace(" ","").toLowerCase(Locale.ROOT);for(HerculesGardenTracker.Crop crop:HerculesGardenTracker.Crop.values())if(crop.name().replace("_","").toLowerCase(Locale.ROOT).equals(value)||crop.display().replace(" ","").toLowerCase(Locale.ROOT).equals(value)||(crop==HerculesGardenTracker.Crop.COCOA&&value.equals("cocoabeans")))return crop;return null;}
    private static Set<String> warnCrops(){Set<String> out=new LinkedHashSet<>();if(cfg.jacobUpcomingWarnCrops!=null)for(String value:cfg.jacobUpcomingWarnCrops.split(","))if(!value.isBlank())out.add(value.trim().toUpperCase(Locale.ROOT));return out;}
    private static boolean warnCrop(HerculesGardenTracker.Crop crop){return warnCrops().contains(crop.name());}
    private static String time(long millis){long s=Math.max(0,millis/1000);if(s>=3600)return s/3600+"h "+s/60%60+"m";if(s>=60)return s/60+"m "+s%60+"s";return s+"s";}
    private static boolean active(){return cfg!=null&&cfg.enabled&&ConstellationClient.loc().onHypixel()&&(cfg.jacobUpcomingDisplay||cfg.jacobUpcomingWarning||cfg.jacobUpcomingFetchAutomatically);}
    private static boolean inGarden(){return ConstellationClient.loc().area()==LocationManager.SkyblockArea.GARDEN;}
    private static Boolean bool(String raw){return switch(raw.toLowerCase(Locale.ROOT)){case"on","true","yes","1"->true;case"off","false","no","0"->false;default->null;};}
    private static String on(boolean value){return value?"on":"off";}
    private static String version(){return"0.9.666";}
    private static void maps(){if(cfg.jacobUpcomingContests==null)cfg.jacobUpcomingContests=new HashMap<>();}
    private static void save(){ConstellationClient.saveConfig();}
    private static void local(String text){Minecraft mc=Minecraft.getInstance();if(mc.player!=null)mc.player.sendSystemMessage(Component.literal("§2[Jacob] §f"+text));}
}

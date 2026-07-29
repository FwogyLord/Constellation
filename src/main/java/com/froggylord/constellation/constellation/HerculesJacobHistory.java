package com.froggylord.constellation.constellation;

import com.froggylord.constellation.ConstellationClient;
import com.froggylord.constellation.config.HerculesConfig;
import com.froggylord.constellation.core.LocationManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// ported from SkyHanni (LGPL-3.0-or-later): features/garden/contest/FarmingContestApi.kt
// ported from SkyHanni (LGPL-3.0-or-later): features/garden/contest/ContestBracket.kt
// ported from SkyHanni (LGPL-3.0-or-later): features/garden/contest/JacobContestTimeNeeded.kt
// ported from SkyHanni (LGPL-3.0-or-later): features/garden/contest/JacobContestFFNeededDisplay.kt
// ported from SkyHanni (LGPL-3.0-or-later): features/garden/contest/JacobContestStatsSummary.kt
// ported from SkyHanni (LGPL-3.0-or-later): features/garden/contest/FarmingPersonalBestGain.kt
public final class HerculesJacobHistory {
    public record Row(String label,String value,int color){}
    private enum Bracket { DIAMOND(0xFF55FFFF), PLATINUM(0xFF00AAAA), GOLD(0xFFFFAA00), SILVER(0xFFFFFFFF), BRONZE(0xFFFF5555); final int color; Bracket(int color){this.color=color;} }
    private record Record(String id,HerculesGardenTracker.Crop crop,EnumMap<Bracket,Long> thresholds,long order){}

    private static final Pattern CROP = Pattern.compile("(?i)^(.+?) Contest$");
    private static final Pattern BRACKET = Pattern.compile("(?i)^(DIAMOND|PLATINUM|GOLD|SILVER|BRONZE).*?:\\s*([\\d,]+)$");
    private static final Pattern PB_NEW = Pattern.compile("(?i)^\\[NPC] Jacob: You collected ([\\d,]+) items! PERSONAL BEST!$");
    private static final Pattern PB_OLD = Pattern.compile("(?i)^\\[NPC] Jacob: Your previous Personal Best was ([\\d,]+)\\.$");
    private static final Pattern PB_FF = Pattern.compile("(?i)^\\[NPC] Jacob: Your Personal Bests perk is now granting you \\+([\\d,.]+)\\D*\\s+(.+?) Fortune!$");
    private static final Pattern DATE = Pattern.compile("(?i)^(Early |Late )?(Spring|Summer|Autumn|Winter)\\s+(\\d+)(?:st|nd|rd|th), Year (\\d+)$");
    private static final Map<HerculesGardenTracker.Crop,Integer> PB_INCREMENT = Map.ofEntries(
        Map.entry(HerculesGardenTracker.Crop.WHEAT,100),Map.entry(HerculesGardenTracker.Crop.CARROT,300),
        Map.entry(HerculesGardenTracker.Crop.POTATO,300),Map.entry(HerculesGardenTracker.Crop.NETHER_WART,300),
        Map.entry(HerculesGardenTracker.Crop.PUMPKIN,100),Map.entry(HerculesGardenTracker.Crop.MELON,500),
        Map.entry(HerculesGardenTracker.Crop.COCOA,300),Map.entry(HerculesGardenTracker.Crop.SUGAR_CANE,200),
        Map.entry(HerculesGardenTracker.Crop.CACTUS,200),Map.entry(HerculesGardenTracker.Crop.MUSHROOM,100),
        Map.entry(HerculesGardenTracker.Crop.SUNFLOWER,200),Map.entry(HerculesGardenTracker.Crop.MOONFLOWER,200),
        Map.entry(HerculesGardenTracker.Crop.WILD_ROSE,200)
    );
    private static HerculesConfig cfg;
    private static boolean validMenu;
    private static Record hovered;
    private static HerculesGardenTracker.Crop summaryCrop;
    private static long summaryStarted;
    private static long summaryBlocks;
    private static long summaryCollected;
    private static long lastHarvest;
    private static double pbNew=-1,pbOld=-1,pbFf=-1;
    private static HerculesGardenTracker.Crop pbCrop;
    private static boolean dirty;

    private HerculesJacobHistory(){}

    public static void init(HerculesConfig config){
        cfg=config;maps();
        HerculesGardenTracker.registerHarvestListener(HerculesJacobHistory::harvest);
        ConstellationClient.tick().every(5,"hercules-jacob-history",HerculesJacobHistory::tick);
        ClientReceiveMessageEvents.GAME.register((message,overlay)->{if(!overlay)chat(clean(message.getString()));});
    }

    private static void harvest(HerculesGardenTracker.Harvest harvest){
        if(!activeGarden())return;
        var contest=HerculesGardenTracker.contest();
        if(contest==null||!contest.crop().equalsIgnoreCase(harvest.crop().display()))return;
        if(summaryCrop!=harvest.crop()){summaryCrop=harvest.crop();summaryStarted=System.currentTimeMillis();summaryBlocks=0;}
        summaryBlocks++;lastHarvest=System.currentTimeMillis();
        var rates=HerculesGardenTracker.rates();
        if(rates!=null&&rates.averageBps()>0)cfg.jacobContestLatestBps.put(profile()+"|"+harvest.crop().name(),rates.averageBps());
    }

    private static void tick(){
        if(!active())return;
        if(dirty){dirty=false;ConstellationClient.saveConfig();}
        if(!activeGarden())return;
        var contest=HerculesGardenTracker.contest();
        if(contest!=null){
            summaryCollected=contest.collected();
            HerculesGardenTracker.Crop crop=crop(contest.crop());
            if(crop!=null&&summaryCrop==null){summaryCrop=crop;summaryStarted=System.currentTimeMillis();summaryBlocks=0;if(cfg.jacobContestSummary&&cfg.jacobContestSummaryStartMessage)local("Started tracking the "+crop.display()+" contest.");}
            else if(crop!=null&&crop!=summaryCrop){finishSummary();summaryCrop=crop;summaryStarted=System.currentTimeMillis();summaryBlocks=0;}
        }else if(summaryCrop!=null&&System.currentTimeMillis()-lastHarvest>2500)finishSummary();
    }

    private static void finishSummary(){
        if(summaryCrop==null)return;
        long elapsed=Math.max(1,System.currentTimeMillis()-summaryStarted);
        if(cfg.jacobContestSummary&&(!cfg.jacobContestSummaryHideZero||summaryBlocks>0)){
            List<String> parts=new ArrayList<>();
            if(cfg.jacobContestSummaryShowBlocks)parts.add(format(summaryBlocks)+" blocks");
            if(cfg.jacobContestSummaryShowBps)parts.add(decimal(summaryBlocks/(elapsed/1000.0),2)+" BPS");
            if(cfg.jacobContestSummaryShowTime)parts.add(time(elapsed));
            if(cfg.jacobContestSummaryShowCrops)parts.add(format(summaryCollected)+" crops");
            local(summaryCrop.display()+" contest: "+String.join(", ",parts)+".");
        }
        summaryCrop=null;summaryStarted=0;summaryBlocks=0;summaryCollected=0;lastHarvest=0;
    }

    public static void drawSlot(GuiGraphicsExtractor graphics,AbstractContainerScreen<?> screen,Slot slot,int mouseX,int mouseY){
        if(!active()||slot==null)return;
        if(!title(screen).equals("Your Contests")){validMenu=false;hovered=null;return;}
        if(slot.index==0)hovered=null;
        if(slot.index==50&&!slot.getItem().isEmpty())validMenu=lore(slot.getItem()).stream().anyMatch(line->line.toLowerCase(Locale.ROOT).contains("claim multiple farming contest"));
        if(!validMenu||slot.getItem().isEmpty())return;
        Record record=parse(slot.getItem());
        if(record!=null){
            store(record);
            if(mouseX>=slot.x&&mouseX<slot.x+16&&mouseY>=slot.y&&mouseY<slot.y+16)hovered=record;
        }
    }

    private static Record parse(ItemStack stack){
        HerculesGardenTracker.Crop crop=null;EnumMap<Bracket,Long> thresholds=new EnumMap<>(Bracket.class);
        for(String line:lore(stack)){
            Matcher cropMatcher=CROP.matcher(line);if(cropMatcher.matches()&&crop==null)crop=crop(cropMatcher.group(1));
            Matcher bracket=BRACKET.matcher(line);if(bracket.matches())thresholds.put(Bracket.valueOf(bracket.group(1).toUpperCase(Locale.ROOT)),number(bracket.group(2)));
        }
        if(crop==null||thresholds.isEmpty())return null;
        String id=clean(stack.getHoverName().getString());
        return new Record(id,crop,thresholds,dateOrder(id));
    }

    private static void store(Record record){
        String key=profile()+"|"+record.id;String value=encode(record);
        if(!Objects.equals(cfg.jacobContestRecords.put(key,value),value))dirty=true;
    }

    public static boolean hudVisible(){return active()&&validMenu&&(cfg.jacobContestTimeNeeded||cfg.jacobContestFfNeeded);}
    public static List<Row> hudRows(){
        if(!hudVisible())return List.of();
        if(hovered!=null&&cfg.jacobContestFfNeeded)return detail(hovered);
        List<Row> rows=new ArrayList<>();
        Bracket target=target();
        rows.add(new Row("Target",pretty(target),target.color));
        for(HerculesGardenTracker.Crop crop:HerculesGardenTracker.Crop.values()){
            long threshold=average(crop,target);
            Double fortune=HerculesFortune.latest(crop);
            double bps=bps(crop);
            if(threshold<=0||fortune==null||bps<=0){if(!cfg.jacobContestPlannerHideMissing)rows.add(new Row(crop.display(),"Missing history/FF",0xFFFF5555));continue;}
            double seconds=threshold/Math.max(.01,fortune*base(crop)*bps/100.0);
            boolean possible=seconds<=20*60;
            if(!possible&&!cfg.jacobContestPlannerShowImpossible)continue;
            String value=possible?time((long)(seconds*1000)):"Not possible";
            if(cfg.jacobContestPlannerShowFf)value+=" | "+format((long)Math.ceil(ffNeeded(crop,threshold,bps)))+" FF";
            rows.add(new Row(crop.display(),value,possible?0xFF55FFFF:0xFFFF5555));
        }
        return rows;
    }

    private static List<Row> detail(Record record){
        List<Row> rows=new ArrayList<>();double bps=bps(record.crop);Double fortune=HerculesFortune.latest(record.crop);
        rows.add(new Row("Contest",record.crop.display(),0xFFFFFF55));
        for(Bracket bracket:Bracket.values()){
            Long threshold=record.thresholds.get(bracket);if(threshold==null)continue;
            String value=cfg.jacobContestPlannerShowThreshold?format(threshold)+" crops":"";
            if(cfg.jacobContestPlannerShowFf)value+=(value.isEmpty()?"":" | ")+format((long)Math.ceil(ffNeeded(record.crop,threshold,bps)))+" FF";
            rows.add(new Row(pretty(bracket),value,bracket.color));
        }
        if(fortune!=null)rows.add(new Row("Latest FF",format(Math.round(fortune)),0xFFFFFF55));
        rows.add(new Row("Blocks/sec",decimal(bps,2),0xFF55FFFF));
        return rows;
    }

    private static void chat(String line){
        if(!cfg.jacobContestPersonalBestGain||!activeGarden())return;
        Matcher matcher=PB_NEW.matcher(line);if(matcher.matches())pbNew=number(matcher.group(1));
        matcher=PB_OLD.matcher(line);if(matcher.matches())pbOld=number(matcher.group(1));
        matcher=PB_FF.matcher(line);if(matcher.matches()){pbFf=doubleNumber(matcher.group(1));pbCrop=crop(matcher.group(2));}
        if(pbNew<0||pbOld<0||pbFf<0||pbCrop==null)return;
        int increment=PB_INCREMENT.getOrDefault(pbCrop,100);
        double oldFf=pbOld/(increment*100.0),overflow=pbNew/(increment*100.0);
        double difference=oldFf<100&&!cfg.jacobContestPersonalBestOverflow?pbFf-oldFf:overflow-oldFf;
        local("Personal Best gained "+decimal(difference,2)+" "+pbCrop.display()+" Fortune.");
        pbNew=pbOld=pbFf=-1;pbCrop=null;
    }

    public static void registerCommands(CommandDispatcher<FabricClientCommandSource> dispatcher){
        dispatcher.register(LiteralArgumentBuilder.<FabricClientCommandSource>literal("jacobhistory")
            .executes(c->status())
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("clear").executes(c->{cfg.jacobContestRecords.entrySet().removeIf(e->e.getKey().startsWith(profile()+"|"));save();return status();}))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("bracket").then(RequiredArgumentBuilder.<FabricClientCommandSource,String>argument("name",StringArgumentType.word()).executes(c->bracket(StringArgumentType.getString(c,"name")))))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("samples").then(RequiredArgumentBuilder.<FabricClientCommandSource,Integer>argument("count",IntegerArgumentType.integer(1,50)).executes(c->{cfg.jacobContestHistorySamples=IntegerArgumentType.getInteger(c,"count");save();return status();})))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("bps").then(RequiredArgumentBuilder.<FabricClientCommandSource,String>argument("value",StringArgumentType.word()).executes(c->customBps(StringArgumentType.getString(c,"value")))))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("option").then(RequiredArgumentBuilder.<FabricClientCommandSource,String>argument("name",StringArgumentType.word()).then(RequiredArgumentBuilder.<FabricClientCommandSource,String>argument("state",StringArgumentType.word()).executes(c->option(StringArgumentType.getString(c,"name"),StringArgumentType.getString(c,"state")))))));
    }

    private static int option(String name,String raw){Boolean value=bool(raw);if(value==null){local("Use on or off.");return 0;}switch(name.toLowerCase(Locale.ROOT)){case"enabled","history"->cfg.jacobContestHistory=value;case"time"->cfg.jacobContestTimeNeeded=value;case"ff"->cfg.jacobContestFfNeeded=value;case"summary"->cfg.jacobContestSummary=value;case"hidezero"->cfg.jacobContestSummaryHideZero=value;case"start"->cfg.jacobContestSummaryStartMessage=value;case"blocks"->cfg.jacobContestSummaryShowBlocks=value;case"summarybps"->cfg.jacobContestSummaryShowBps=value;case"summarytime"->cfg.jacobContestSummaryShowTime=value;case"crops"->cfg.jacobContestSummaryShowCrops=value;case"pb"->cfg.jacobContestPersonalBestGain=value;case"overflow"->cfg.jacobContestPersonalBestOverflow=value;case"custombps"->cfg.jacobContestCustomBps=value;case"plannerff"->cfg.jacobContestPlannerShowFf=value;case"threshold"->cfg.jacobContestPlannerShowThreshold=value;case"impossible"->cfg.jacobContestPlannerShowImpossible=value;case"hidemissing"->cfg.jacobContestPlannerHideMissing=value;default->{local("Unknown Jacob history option.");return 0;}}save();return status();}
    private static int bracket(String raw){try{cfg.jacobContestTargetBracket=Bracket.valueOf(raw.toUpperCase(Locale.ROOT)).name();save();return status();}catch(Exception ignored){local("Bracket must be diamond, platinum, gold, silver or bronze.");return 0;}}
    private static int customBps(String raw){try{double value=Double.parseDouble(raw);if(value<1||value>20)throw new NumberFormatException();cfg.jacobContestCustomBpsHundredths=(int)Math.round(value*100);save();return status();}catch(Exception ignored){local("BPS must be between 1 and 20.");return 0;}}
    private static int status(){local("Contest history "+on(cfg.jacobContestHistory)+", time planner "+on(cfg.jacobContestTimeNeeded)+", FF planner "+on(cfg.jacobContestFfNeeded)+", summary "+on(cfg.jacobContestSummary)+".");local("Target "+pretty(target())+", "+records().size()+" contest records for this profile.");return 1;}
    private static List<Record> records(){List<Record> out=new ArrayList<>();for(Map.Entry<String,String> entry:cfg.jacobContestRecords.entrySet())if(entry.getKey().startsWith(profile()+"|")){Record record=decode(entry.getKey().substring((profile()+"|").length()),entry.getValue());if(record!=null)out.add(record);}out.sort(Comparator.comparingLong(Record::order).reversed());return out;}
    private static long average(HerculesGardenTracker.Crop crop,Bracket bracket){List<Long> values=records().stream().filter(r->r.crop==crop&&r.thresholds.containsKey(bracket)).map(r->r.thresholds.get(bracket)).limit(Math.max(1,cfg.jacobContestHistorySamples)).toList();return values.isEmpty()?0:Math.round(values.stream().mapToLong(Long::longValue).average().orElse(0));}
    private static double bps(HerculesGardenTracker.Crop crop){return cfg.jacobContestCustomBps?Math.clamp(cfg.jacobContestCustomBpsHundredths/100.0,1,20):cfg.jacobContestLatestBps.getOrDefault(profile()+"|"+crop.name(),19.9);}
    private static double ffNeeded(HerculesGardenTracker.Crop crop,long threshold,double bps){return Math.max(0,threshold/Math.max(1,bps)/1200.0*100.0/base(crop));}
    private static double base(HerculesGardenTracker.Crop crop){return switch(crop){case CARROT,POTATO,COCOA->3;case NETHER_WART->2.5;case MELON->5;case SUGAR_CANE,CACTUS,SUNFLOWER,MOONFLOWER,WILD_ROSE->2;default->1;};}
    private static String encode(Record record){StringBuilder out=new StringBuilder(record.crop.name());for(Bracket bracket:Bracket.values())out.append(',').append(record.thresholds.getOrDefault(bracket,0L));return out.toString();}
    private static Record decode(String id,String raw){try{String[] p=raw.split(",");HerculesGardenTracker.Crop crop=HerculesGardenTracker.Crop.valueOf(p[0]);EnumMap<Bracket,Long> map=new EnumMap<>(Bracket.class);for(int i=0;i<Bracket.values().length&&i+1<p.length;i++){long value=Long.parseLong(p[i+1]);if(value>0)map.put(Bracket.values()[i],value);}return map.isEmpty()?null:new Record(id,crop,map,dateOrder(id));}catch(Exception ignored){return null;}}
    private static long dateOrder(String id){Matcher matcher=DATE.matcher(id);if(!matcher.matches())return id.hashCode()&0xffffffffL;int season=switch(matcher.group(2).toLowerCase(Locale.ROOT)){case"spring"->0;case"summer"->3;case"autumn"->6;default->9;};season+=matcher.group(1)==null?1:matcher.group(1).equalsIgnoreCase("Late ")?2:0;int day=(int)number(matcher.group(3));long year=number(matcher.group(4));return year*372L+season*31L+day;}
    private static HerculesGardenTracker.Crop crop(String raw){if(raw==null)return null;String value=raw.replace("_","").replace("-","").replace(" ","").toLowerCase(Locale.ROOT);for(var crop:HerculesGardenTracker.Crop.values())if(crop.name().replace("_","").toLowerCase(Locale.ROOT).equals(value)||crop.display().replace(" ","").toLowerCase(Locale.ROOT).equals(value)||(crop==HerculesGardenTracker.Crop.COCOA&&value.equals("cocoabeans")))return crop;return null;}
    private static Bracket target(){try{return Bracket.valueOf(cfg.jacobContestTargetBracket);}catch(Exception ignored){return Bracket.GOLD;}}
    private static String pretty(Bracket bracket){String name=bracket.name().toLowerCase(Locale.ROOT);return Character.toUpperCase(name.charAt(0))+name.substring(1);}
    private static List<String> lore(ItemStack stack){ItemLore lore=stack.get(DataComponents.LORE);if(lore==null)return List.of();return lore.lines().stream().map(c->clean(c.getString())).toList();}
    private static String title(AbstractContainerScreen<?> screen){return clean(screen.getTitle().getString());}
    private static String clean(String raw){String value=ChatFormatting.stripFormatting(raw);return value==null?"":value.trim();}
    private static long number(String raw){try{return Long.parseLong(raw.replace(",",""));}catch(Exception ignored){return 0;}}
    private static double doubleNumber(String raw){try{return Double.parseDouble(raw.replace(",",""));}catch(Exception ignored){return 0;}}
    private static String format(long value){return String.format(Locale.ROOT,"%,d",value);}
    private static String decimal(double value,int places){return String.format(Locale.ROOT,"%."+places+"f",value);}
    private static String time(long millis){long s=Math.max(0,millis/1000);return s>=60?s/60+"m "+s%60+"s":s+"s";}
    private static String profile(){String value=LyraStorageValue.currentProfileKey();return value==null||value.isBlank()?"unknown":value.toLowerCase(Locale.ROOT);}
    private static boolean active(){return cfg!=null&&cfg.enabled&&cfg.jacobContestHistory&&ConstellationClient.loc().onHypixel();}
    private static boolean activeGarden(){return active()&&ConstellationClient.loc().area()==LocationManager.SkyblockArea.GARDEN;}
    private static Boolean bool(String raw){return switch(raw.toLowerCase(Locale.ROOT)){case"on","true","yes","1"->true;case"off","false","no","0"->false;default->null;};}
    private static String on(boolean value){return value?"on":"off";}
    private static void maps(){if(cfg.jacobContestRecords==null)cfg.jacobContestRecords=new HashMap<>();if(cfg.jacobContestLatestBps==null)cfg.jacobContestLatestBps=new HashMap<>();}
    private static void save(){ConstellationClient.saveConfig();}
    private static void local(String text){Minecraft mc=Minecraft.getInstance();if(mc.player!=null)mc.player.sendSystemMessage(Component.literal("§2[Jacob] §f"+text));}
}

package com.froggylord.constellation.constellation;

import com.froggylord.constellation.ConstellationClient;
import com.froggylord.constellation.api.BazaarApi;
import com.froggylord.constellation.api.PriceProvider;
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
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.*;
import java.util.regex.Pattern;

// ported from SkyHanni (LGPL-3.0-or-later): features/garden/tracker/RareCropTracker.kt
// ported from SkyHanni (LGPL-3.0-or-later): data/jsonobjects/repo/RareCropDropsJson.kt
public final class HerculesRareCropTracker {
    public enum Drop {
        CROPIE("Cropie",0xFF55FF55),SQUASH("Squash",0xFF5555FF),FERMENTO("Fermento",0xFFFF55FF),HELIANTHUS("Helianthus",0xFFFFAA00),
        SEASONING("Seasoning",0xFF00AA00),CORNUCOPIA("Cornucopia",0xFF55FF55),CARROT_ZEST("Carrot Zest",0xFF55FF55),
        DEEPFRIES("Deepfries",0xFF55FF55),AGGOURDIAN("Aggourdian",0xFF55FF55),CANE_KNOT("Cane Knot",0xFF55FF55),
        MELON_JUICE("Melon Juice",0xFF55FF55),CACTUS_FLOWER("Cactus Flower",0xFF55FF55),
        DESIGNER_COFFEE_BEANS("Designer Coffee Beans",0xFF55FF55),FEASTFUNGUS("Feastfungus",0xFF55FF55),
        BOTROOT("Botroot",0xFF55FF55),SALTED_SUNFLOWER_SEEDS("Salted Sunflower Seeds",0xFF55FF55),
        CRYSTALIZED_MOONLIGHT("Crystalized Moonlight",0xFF55FF55),FLORAL_GELATIN("Floral Gelatin",0xFF55FF55),
        RAREFINDER_CHIP("Rarefinder Chip",0xFF5555FF),BURROWING_SPORES("Burrowing Spores",0xFF5555FF),WARTY("Warty",0xFFFF55FF);
        final String display;final int color;final Pattern pattern;
        Drop(String display,int color){this.display=display;this.color=color;this.pattern=Pattern.compile("^(?:VERY )?RARE CROP! "+Pattern.quote(display)+"(?: .*)?$",Pattern.CASE_INSENSITIVE);}
        public String display(){return display;}public int color(){return color;}
    }
    public record Row(Drop drop,long amount,double value){}
    public record Stats(List<Row> rows,long totalDrops,double profit,double profitPerHour,long activeMillis,Drop recent,long recentAt){}
    private static final EnumMap<Drop,Long> SESSION=new EnumMap<>(Drop.class);
    private static HerculesConfig cfg;
    private static String sessionProfile="";
    private static long activeMillis,lastTick,lastActivity;
    private static Drop recent;
    private static long recentAt;

    private HerculesRareCropTracker(){}

    public static void init(HerculesConfig config){
        cfg=config;
        if(cfg.rareCropPersistentDrops==null)cfg.rareCropPersistentDrops=new HashMap<>();
        ClientReceiveMessageEvents.ALLOW_GAME.register((message,overlay)->{
            if(overlay)return true;
            Drop drop=drop(clean(message.getString()));
            if(drop==null)return true;
            if(activeGarden())add(drop);
            return !(activeGarden()&&cfg.rareCropHideChat);
        });
        ConstellationClient.tick().every(1,"hercules-rare-crops",HerculesRareCropTracker::tick);
        ConstellationClient.tick().every(100,"hercules-rare-crop-prices",BazaarApi::ensureFresh);
    }

    private static void tick(){
        String profile=profile();
        if(!profile.equals(sessionProfile)){resetSession();sessionProfile=profile;}
        long now=System.currentTimeMillis();
        if(!activeGarden()){lastTick=0;return;}
        var rates=HerculesGardenTracker.rates();
        if(rates!=null&&rates.instantBps()>1)lastActivity=now;
        if(lastTick>0&&now-lastActivity<=Math.max(5,cfg.rareCropAfkSeconds)*1000L)activeMillis+=Math.min(100,now-lastTick);
        lastTick=now;
    }

    private static void add(Drop drop){
        SESSION.merge(drop,1L,Long::sum);
        cfg.rareCropPersistentDrops.merge(key(drop),1L,Long::sum);
        recent=drop;recentAt=lastActivity=System.currentTimeMillis();
        ConstellationClient.saveConfig();
    }

    public static Stats stats(){
        if(!activeGarden())return null;
        List<Row> rows=new ArrayList<>();long total=0;double profit=0;
        for(Drop drop:Drop.values()){
            long amount=count(drop);if(amount<=0)continue;
            double value=price(drop)*amount;
            total+=amount;profit+=value;rows.add(new Row(drop,amount,value));
        }
        Comparator<Row> order=cfg.rareCropSortByValue
            ?Comparator.comparingDouble(Row::value).reversed().thenComparing(r->r.drop.display)
            :Comparator.comparingLong(Row::amount).reversed().thenComparing(r->r.drop.display);
        rows.sort(order);
        int max=Math.clamp(cfg.rareCropMaxLines,0,Drop.values().length);
        if(max>0&&rows.size()>max)rows=new ArrayList<>(rows.subList(0,max));
        Drop shown=cfg.rareCropShowRecent&&recent!=null&&System.currentTimeMillis()-recentAt<=Math.max(1,cfg.rareCropRecentSeconds)*1000L?recent:null;
        return new Stats(List.copyOf(rows),total,profit,activeMillis<=0?0:profit*3_600_000.0/activeMillis,activeMillis,shown,recentAt);
    }

    public static boolean visible(){
        if(!activeGarden())return false;
        Minecraft mc=Minecraft.getInstance();
        return !cfg.rareCropOnlyWithTool||mc.player!=null&&HerculesGardenTracker.cropInHand(mc.player.getMainHandItem())!=null;
    }

    public static double dropsPerHour(HerculesGardenTracker.Crop crop,double bps){
        if(cfg==null||!cfg.cropMoneyIncludeRareCrops||crop==null||bps<=0)return 0;
        String drop=special(crop),required=requiredArmor(drop);
        if(required.isEmpty())return 0;
        int pieces=0;
        Minecraft mc=Minecraft.getInstance();
        if(mc.player==null)return 0;
        for(EquipmentSlot slot:List.of(EquipmentSlot.HEAD,EquipmentSlot.CHEST,EquipmentSlot.LEGS,EquipmentSlot.FEET)){
            String id=id(mc.player.getItemBySlot(slot));
            if(armorTier(id)>=armorTier(required))pieces++;
        }
        if(pieces<=0)return 0;
        double chance=chance(drop,Math.clamp(pieces,1,4));
        return bps*chance*3600/100.0;
    }

    public static String special(HerculesGardenTracker.Crop crop){
        return switch(crop){
            case WHEAT,CARROT,POTATO->"CROPIE";
            case PUMPKIN,MELON,COCOA->"SQUASH";
            case NETHER_WART,SUGAR_CANE,CACTUS,MUSHROOM->"FERMENTO";
            case SUNFLOWER,MOONFLOWER,WILD_ROSE->"HELIANTHUS";
        };
    }
    public static double priceForCrop(HerculesGardenTracker.Crop crop,boolean purchase){
        return crop==null?0:(purchase?PriceProvider.purchaseValue(special(crop)):PriceProvider.sellValue(special(crop)));
    }

    public static void registerCommands(CommandDispatcher<FabricClientCommandSource> dispatcher){
        dispatcher.register(LiteralArgumentBuilder.<FabricClientCommandSource>literal("rarecrops")
            .executes(c->status())
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("reset")
                .executes(c->{resetSession();local("Rare Crop session reset.");return status();}))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("clear")
                .executes(c->{String prefix=profile()+"|";cfg.rareCropPersistentDrops.keySet().removeIf(k->k.startsWith(prefix));resetSession();save();return status();}))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("lines")
                .then(RequiredArgumentBuilder.<FabricClientCommandSource,Integer>argument("amount",IntegerArgumentType.integer(0,21))
                    .executes(c->{cfg.rareCropMaxLines=IntegerArgumentType.getInteger(c,"amount");save();return status();})))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("afk")
                .then(RequiredArgumentBuilder.<FabricClientCommandSource,Integer>argument("seconds",IntegerArgumentType.integer(5,600))
                    .executes(c->{cfg.rareCropAfkSeconds=IntegerArgumentType.getInteger(c,"seconds");save();return status();})))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("recent")
                .then(RequiredArgumentBuilder.<FabricClientCommandSource,Integer>argument("seconds",IntegerArgumentType.integer(1,120))
                    .executes(c->{cfg.rareCropRecentSeconds=IntegerArgumentType.getInteger(c,"seconds");save();return status();})))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("add")
                .then(RequiredArgumentBuilder.<FabricClientCommandSource,String>argument("drop",StringArgumentType.word())
                    .then(RequiredArgumentBuilder.<FabricClientCommandSource,Integer>argument("amount",IntegerArgumentType.integer(1,10000))
                        .executes(c->manual(StringArgumentType.getString(c,"drop"),IntegerArgumentType.getInteger(c,"amount"))))))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("price")
                .then(RequiredArgumentBuilder.<FabricClientCommandSource,String>argument("source",StringArgumentType.word())
                    .executes(c->priceSource(StringArgumentType.getString(c,"source")))))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("option")
                .then(RequiredArgumentBuilder.<FabricClientCommandSource,String>argument("name",StringArgumentType.word())
                    .then(RequiredArgumentBuilder.<FabricClientCommandSource,String>argument("state",StringArgumentType.word())
                        .executes(c->option(StringArgumentType.getString(c,"name"),StringArgumentType.getString(c,"state")))))));
    }

    private static int option(String name,String raw){
        Boolean value=bool(raw);if(value==null){local("Use on or off.");return 0;}
        switch(name.toLowerCase(Locale.ROOT)){
            case"enabled"->cfg.rareCropTracker=value;case"hidechat"->cfg.rareCropHideChat=value;
            case"tool"->cfg.rareCropOnlyWithTool=value;case"session"->cfg.rareCropOnlySession=value;
            case"uptime"->cfg.rareCropShowUptime=value;case"profit"->cfg.rareCropShowProfit=value;
            case"profitperhour"->cfg.rareCropShowProfitPerHour=value;case"recent"->cfg.rareCropShowRecent=value;
            case"value"->cfg.rareCropSortByValue=value;default->{local("Unknown Rare Crop option.");return 0;}
        }
        save();return status();
    }
    private static int priceSource(String raw){
        String value=raw.toUpperCase(Locale.ROOT);
        if(!value.equals("PURCHASE")&&!value.equals("SELL")){local("Price source must be purchase or sell.");return 0;}
        cfg.rareCropPriceSource=value;save();return status();
    }
    private static int manual(String raw,int amount){
        Drop drop=dropByName(raw);if(drop==null){local("Unknown rare crop. Use its enum name, such as burrowing_spores.");return 0;}
        SESSION.merge(drop,(long)amount,Long::sum);cfg.rareCropPersistentDrops.merge(key(drop),(long)amount,Long::sum);
        recent=drop;recentAt=lastActivity=System.currentTimeMillis();save();return status();
    }
    private static int status(){Stats s=stats();local("Rare Crop Tracker "+on(cfg.rareCropTracker)+", "+(cfg.rareCropOnlySession?"session":"profile")+" view, "+(s==null?0:s.totalDrops)+" drops.");return 1;}

    private static long count(Drop drop){return cfg.rareCropOnlySession?SESSION.getOrDefault(drop,0L):cfg.rareCropPersistentDrops.getOrDefault(key(drop),0L);}
    private static double price(Drop drop){return cfg.rareCropPriceSource.equalsIgnoreCase("SELL")?PriceProvider.sellValue(drop.name()):PriceProvider.purchaseValue(drop.name());}
    private static Drop drop(String line){for(Drop drop:Drop.values())if(drop.pattern.matcher(line).matches())return drop;return null;}
    private static Drop dropByName(String raw){String value=raw.replace("-","_").replace(" ","_");for(Drop drop:Drop.values())if(drop.name().equalsIgnoreCase(value)||drop.display.replace(" ","_").equalsIgnoreCase(value))return drop;return null;}
    private static String requiredArmor(String drop){return switch(drop){case"CROPIE"->"MELON";case"SQUASH"->"CROPIE";case"FERMENTO"->"SQUASH";case"HELIANTHUS"->"FERMENTO";default->"";};}
    private static int armorTier(String id){if(id.contains("HELIANTHUS"))return 5;if(id.contains("FERMENTO"))return 4;if(id.contains("SQUASH"))return 3;if(id.contains("CROPIE"))return 2;if(id.contains("MELON"))return 1;return 0;}
    private static double chance(String drop,int pieces){double[] rates=switch(drop){case"CROPIE"->new double[]{0,.03,.04,.05};case"SQUASH"->new double[]{0,.01,.02,.03};case"FERMENTO"->new double[]{0,.005,.006,.007};case"HELIANTHUS"->new double[]{0,.002,.003,.004};default->new double[4];};return rates[Math.clamp(pieces-1,0,3)];}
    private static String id(ItemStack stack){if(stack==null||stack.isEmpty())return"";CustomData data=stack.get(DataComponents.CUSTOM_DATA);CompoundTag root=data==null?new CompoundTag():data.copyTag(),extra=root.getCompoundOrEmpty("ExtraAttributes");if(extra.isEmpty())extra=root;return extra.getStringOr("id","").toUpperCase(Locale.ROOT);}
    private static void resetSession(){SESSION.clear();activeMillis=0;lastTick=0;lastActivity=0;recent=null;recentAt=0;}
    private static String key(Drop drop){return profile()+"|"+drop.name();}
    private static String profile(){String value=LyraStorageValue.currentProfileKey();return value==null||value.isBlank()?"unknown":value.toLowerCase(Locale.ROOT);}
    private static boolean activeGarden(){return cfg!=null&&cfg.enabled&&cfg.rareCropTracker&&ConstellationClient.loc().area()==LocationManager.SkyblockArea.GARDEN;}
    private static String clean(String value){String clean=ChatFormatting.stripFormatting(value);return clean==null?"":clean.trim();}
    private static Boolean bool(String value){return switch(value.toLowerCase(Locale.ROOT)){case"on","true","yes","1"->true;case"off","false","no","0"->false;default->null;};}
    private static String on(boolean value){return value?"on":"off";}
    private static void local(String text){Minecraft mc=Minecraft.getInstance();if(mc.player!=null)mc.player.sendSystemMessage(Component.literal("§2[Rare Crops] §f"+text));}
    private static void save(){ConstellationClient.saveConfig();}
}

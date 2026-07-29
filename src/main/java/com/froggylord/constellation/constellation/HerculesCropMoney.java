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
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.*;

// ported from SkyHanni (LGPL-3.0-or-later): features/garden/farming/CropMoneyDisplay.kt
// ported from SkyHanni (LGPL-3.0-or-later): features/garden/farming/GardenCropSpeed.kt
public final class HerculesCropMoney {
    public record Price(double sellOffer,double instantSell,double npc) {}
    public record Row(HerculesGardenTracker.Crop crop,Price price,double bps,double fortune,boolean current,int rank) {}
    public record State(List<Row> rows,HerculesGardenTracker.Crop current,boolean pricesReady) {}
    private record Product(String id,int amount,double npcPerItem,int cropMultiplier,boolean replenish) {}

    private static final Map<HerculesGardenTracker.Crop,Product> PRODUCTS=new EnumMap<>(HerculesGardenTracker.Crop.class);
    private static HerculesConfig cfg;

    static {
        product(HerculesGardenTracker.Crop.WHEAT,"ENCHANTED_HAY_BLOCK",1296,7776,1,true);
        product(HerculesGardenTracker.Crop.CARROT,"ENCHANTED_CARROT",160,160,1,true);
        product(HerculesGardenTracker.Crop.POTATO,"ENCHANTED_POTATO",160,160,1,true);
        product(HerculesGardenTracker.Crop.NETHER_WART,"ENCHANTED_NETHER_STALK",160,480,1,true);
        product(HerculesGardenTracker.Crop.PUMPKIN,"ENCHANTED_PUMPKIN",160,640,1,false);
        product(HerculesGardenTracker.Crop.MELON,"ENCHANTED_MELON",160,80,1,false);
        product(HerculesGardenTracker.Crop.COCOA,"ENCHANTED_COCOA",160,480,1,true);
        product(HerculesGardenTracker.Crop.SUGAR_CANE,"ENCHANTED_SUGAR",160,320,2,false);
        product(HerculesGardenTracker.Crop.CACTUS,"ENCHANTED_CACTUS_GREEN",160,160,2,false);
        product(HerculesGardenTracker.Crop.MUSHROOM,"ENCHANTED_RED_MUSHROOM",160,160,1,false);
        product(HerculesGardenTracker.Crop.SUNFLOWER,"ENCHANTED_SUNFLOWER",160,0,1,false);
        product(HerculesGardenTracker.Crop.MOONFLOWER,"ENCHANTED_MOONFLOWER",160,0,1,false);
        product(HerculesGardenTracker.Crop.WILD_ROSE,"ENCHANTED_WILD_ROSE",160,0,1,false);
    }

    private HerculesCropMoney(){}

    public static void init(HerculesConfig config){
        cfg=config;
        if(cfg.cropMoneyLatestBps==null)cfg.cropMoneyLatestBps=new HashMap<>();
        if(cfg.cropMoneyPositions==null)cfg.cropMoneyPositions=new HashMap<>();
        HerculesGardenTracker.registerHarvestListener(h->{
            var rates=HerculesGardenTracker.rates();
            if(rates==null||rates.instantBps()<=1)return;
            double next=Math.min(20,rates.instantBps());
            Double old=cfg.cropMoneyLatestBps.get(h.crop().name());
            if(old==null||Math.abs(old-next)>=.05){
                cfg.cropMoneyLatestBps.put(h.crop().name(),next);
                ConstellationClient.saveConfig();
            }
        });
        ConstellationClient.tick().every(100,"hercules-crop-money-prices",BazaarApi::ensureFresh);
    }

    public static boolean visible(){
        if(cfg==null||!cfg.enabled||!cfg.cropMoneyDisplay||!inGarden())return false;
        return cfg.cropMoneyAlwaysOn||HerculesGardenTracker.rates()!=null;
    }

    public static State state(){
        if(!visible())return null;
        var rates=HerculesGardenTracker.rates();
        HerculesGardenTracker.Crop current=rates==null?null:crop(rates.crop());
        List<Row> all=new ArrayList<>();
        boolean ready=true;
        for(var crop:HerculesGardenTracker.Crop.values()){
            if(!cfg.cropMoneyIncludeRareCrops&&(crop==HerculesGardenTracker.Crop.SUNFLOWER||crop==HerculesGardenTracker.Crop.MOONFLOWER||crop==HerculesGardenTracker.Crop.WILD_ROSE))continue;
            Product product=PRODUCTS.get(crop);
            double bps=bps(crop,current,rates);
            Double fortune=HerculesFortune.latest(crop);
            if(bps<=0||fortune==null)continue;
            Price price=price(crop,product,bps,fortune);
            if(price.sellOffer<=0&&price.instantSell<=0&&price.npc<=0)ready=false;
            all.add(new Row(crop,price,bps,fortune,crop==current,0));
        }
        Comparator<Row> profit=Comparator.comparingDouble(HerculesCropMoney::selected).reversed();
        all.sort(cfg.cropMoneyManualOrder
            ? Comparator.comparingInt((Row r)->position(r.crop())).thenComparing(profit)
            : profit);
        List<Row> ranked=new ArrayList<>();
        for(int i=0;i<all.size();i++)ranked.add(new Row(all.get(i).crop,all.get(i).price,all.get(i).bps,all.get(i).fortune,all.get(i).current,i+1));
        int top=Math.clamp(cfg.cropMoneyShowBest,1,HerculesGardenTracker.Crop.values().length);
        List<Row> shown=new ArrayList<>();
        for(Row row:ranked)if(row.rank<=top||(cfg.cropMoneyShowCurrent&&row.current))shown.add(row);
        return new State(List.copyOf(shown),current,ready);
    }

    private static Price price(HerculesGardenTracker.Crop crop,Product p,double bps,double fortune){
        double basePerHour=Math.max(0,bps*3600*p.cropMultiplier*(1+fortune/100.0)-(p.replenish?bps*3600*p.cropMultiplier:0));
        double products=basePerHour/p.amount;
        double[] bazaar=BazaarApi.get(p.id);
        double instant=bazaar==null?0:bazaar[0]*products;
        double offer=bazaar==null?0:bazaar[1]*products;
        double npc=PriceProvider.npcValue(p.id)*products;
        if(npc<=0&&p.npcPerItem>0)npc=p.npcPerItem*products;
        if(cfg.cropMoneyMergeSeeds&&crop==HerculesGardenTracker.Crop.WHEAT){
            double seeds=Math.max(0,bps*3600*1.5-bps*3600)/160.0;
            double[] seedPrice=BazaarApi.get("ENCHANTED_SEEDS");
            instant+=seeds*(seedPrice==null?0:seedPrice[0]);
            offer+=seeds*(seedPrice==null?0:seedPrice[1]);
            npc+=seeds*160;
        }
        double extras=cfg.cropMoneyIncludeBountiful?basePerHour*.2:0;
        if(cfg.cropMoneyIncludeMooshroom&&crop!=HerculesGardenTracker.Crop.MUSHROOM){
            Product mushroom=PRODUCTS.get(HerculesGardenTracker.Crop.MUSHROOM);
            double mushroomItems=bps*3600*p.cropMultiplier*Math.clamp(cfg.cropMoneyMooshroomLevel,0,100)/100.0/mushroom.amount;
            double[] mp=BazaarApi.get(mushroom.id);
            instant+=mushroomItems*(mp==null?0:mp[0]);
            offer+=mushroomItems*(mp==null?0:mp[1]);
            npc+=mushroomItems*Math.max(mushroom.npcPerItem,PriceProvider.npcValue(mushroom.id));
        }
        return new Price(offer+extras,instant+extras,npc+extras);
    }

    public static void registerCommands(CommandDispatcher<FabricClientCommandSource> dispatcher){
        dispatcher.register(LiteralArgumentBuilder.<FabricClientCommandSource>literal("cropmoney")
            .executes(c->status())
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("option")
                .then(RequiredArgumentBuilder.<FabricClientCommandSource,String>argument("name",StringArgumentType.word())
                    .then(RequiredArgumentBuilder.<FabricClientCommandSource,String>argument("state",StringArgumentType.word())
                        .executes(c->option(StringArgumentType.getString(c,"name"),StringArgumentType.getString(c,"state"))))))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("top")
                .then(RequiredArgumentBuilder.<FabricClientCommandSource,Integer>argument("amount",IntegerArgumentType.integer(1,13))
                    .executes(c->{cfg.cropMoneyShowBest=IntegerArgumentType.getInteger(c,"amount");save();return status();})))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("bps")
                .then(RequiredArgumentBuilder.<FabricClientCommandSource,Integer>argument("hundredths",IntegerArgumentType.integer(100,2000))
                    .executes(c->{cfg.cropMoneyCustomBpsHundredths=IntegerArgumentType.getInteger(c,"hundredths");save();return status();})))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("position")
                .then(RequiredArgumentBuilder.<FabricClientCommandSource,String>argument("crop",StringArgumentType.word())
                    .then(RequiredArgumentBuilder.<FabricClientCommandSource,Integer>argument("position",IntegerArgumentType.integer(1,13))
                        .executes(c->position(StringArgumentType.getString(c,"crop"),IntegerArgumentType.getInteger(c,"position"))))))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("resetpositions")
                .executes(c->{cfg.cropMoneyPositions.clear();save();return status();})));
    }

    private static int option(String name,String raw){
        Boolean value=bool(raw);if(value==null){local("Use on or off.");return 0;}
        switch(name.toLowerCase(Locale.ROOT)){
            case"display"->cfg.cropMoneyDisplay=value;case"always"->cfg.cropMoneyAlwaysOn=value;
            case"current"->cfg.cropMoneyShowCurrent=value;case"compact"->cfg.cropMoneyCompact=value;
            case"compactprice"->cfg.cropMoneyCompactPrice=value;case"mergeseeds"->cfg.cropMoneyMergeSeeds=value;
            case"bountiful"->cfg.cropMoneyIncludeBountiful=value;case"mooshroom"->cfg.cropMoneyIncludeMooshroom=value;
            case"rarecrops"->cfg.cropMoneyIncludeRareCrops=value;case"offer"->cfg.cropMoneyShowSellOffer=value;
            case"instant"->cfg.cropMoneyShowInstantSell=value;case"npc"->cfg.cropMoneyShowNpc=value;
            case"custombps"->cfg.cropMoneyUseCustomBps=value;case"manual"->cfg.cropMoneyManualOrder=value;
            case"title"->cfg.cropMoneyHideTitle=!value;default->{local("Unknown crop money option.");return 0;}
        }
        save();return status();
    }

    private static int position(String name,int value){
        HerculesGardenTracker.Crop crop=crop(name);
        if(crop==null){local("Unknown crop. Use its name without spaces.");return 0;}
        cfg.cropMoneyPositions.put(crop.name(),value);
        cfg.cropMoneyManualOrder=true;save();
        local(crop.display()+" set to position "+value+".");return 1;
    }

    private static int status(){
        local("Crop money "+on(cfg.cropMoneyDisplay)+", "+(cfg.cropMoneyManualOrder?"manual":"profit")+" order, top "+cfg.cropMoneyShowBest+".");
        local("Use /cropmoney position <crop> <1-13> for a crop-specific position.");
        return 1;
    }

    private static double selected(Row r){
        if(cfg.cropMoneyShowSellOffer)return r.price.sellOffer;
        if(cfg.cropMoneyShowInstantSell)return r.price.instantSell;
        return r.price.npc;
    }
    private static double bps(HerculesGardenTracker.Crop crop,HerculesGardenTracker.Crop current,HerculesGardenTracker.Rates rates){
        if(cfg.cropMoneyUseCustomBps)return cfg.cropMoneyCustomBpsHundredths/100.0;
        if(crop==current&&rates!=null&&rates.instantBps()>1)return rates.instantBps();
        return cfg.cropMoneyLatestBps.getOrDefault(crop.name(),0.0);
    }
    private static int position(HerculesGardenTracker.Crop crop){return cfg.cropMoneyPositions.getOrDefault(crop.name(),100+crop.ordinal());}
    private static HerculesGardenTracker.Crop crop(String name){
        String clean=name.replace("_","").replace("-","").replace(" ","");
        for(var crop:HerculesGardenTracker.Crop.values())if(crop.name().replace("_","").equalsIgnoreCase(clean)||crop.display().replace(" ","").equalsIgnoreCase(clean))return crop;
        return null;
    }
    private static void product(HerculesGardenTracker.Crop crop,String id,int amount,double npc,int multiplier,boolean replenish){PRODUCTS.put(crop,new Product(id,amount,npc, multiplier,replenish));}
    private static boolean inGarden(){return ConstellationClient.loc().area()==LocationManager.SkyblockArea.GARDEN;}
    private static Boolean bool(String value){return switch(value.toLowerCase(Locale.ROOT)){case"on","true","yes","1"->true;case"off","false","no","0"->false;default->null;};}
    private static String on(boolean value){return value?"on":"off";}
    private static void local(String text){Minecraft mc=Minecraft.getInstance();if(mc.player!=null)mc.player.sendSystemMessage(Component.literal("\u00a72[Crop Money] \u00a7f"+text));}
    private static void save(){ConstellationClient.saveConfig();}
}

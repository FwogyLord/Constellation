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
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// ported from SkyHanni (LGPL-3.0-or-later): features/garden/GardenLevelDisplay.kt
// ported from SkyHanni (LGPL-3.0-or-later): features/garden/GardenApi.kt
public final class HerculesGardenLevel {
    public record State(int level,long totalXp,long levelXp,long neededXp,long overflowXp,double percentage,boolean maximum){}
    private static final long[] LEVEL_XP={0,70,70,140,240,600,1500,2000,2500,3000,10000,10000,10000,10000,10000};
    private static final long LEVEL_15_XP=60_120;
    private static final long OVERFLOW_LEVEL_XP=10_000;
    private static final Pattern LEVEL=Pattern.compile("(?i)^Garden Level ([IVXLCDM]+|\\d+)$");
    private static final Pattern PROGRESS=Pattern.compile("(?i)^Progress to Level ([IVXLCDM]+|\\d+):.*$");
    private static final Pattern FRACTION=Pattern.compile("(?<current>[\\d,.kKmMbB]+)\\s*/\\s*(?<needed>[\\d,.kKmMbB]+)");
    private static final Pattern OVERFLOW=Pattern.compile("(?i)^Overflow XP:?\\s*([\\d,.kKmMbB]+)?$");
    private static final Pattern REWARD=Pattern.compile("^\\+([\\d,]+) Garden Experience$",Pattern.CASE_INSENSITIVE);
    private static HerculesConfig cfg;
    private static long lastParsedAt;

    private HerculesGardenLevel(){}

    public static void init(HerculesConfig config){
        cfg=config;
        if(cfg.gardenLevelExperience==null)cfg.gardenLevelExperience=new HashMap<>();
        ConstellationClient.tick().every(5,"hercules-garden-level",HerculesGardenLevel::tick);
        ClientReceiveMessageEvents.GAME.register((message,overlay)->{
            if(!overlay&&inGarden())reward(clean(message.getString()));
        });
    }

    private static void tick(){
        if(cfg==null||!cfg.enabled||!inGarden())return;
        Minecraft mc=Minecraft.getInstance();
        if(!(mc.gui.screen() instanceof AbstractContainerScreen<?> screen))return;
        String title=clean(screen.getTitle().getString());
        int slot=title.equals("Desk")?4:title.equals("SkyBlock Menu")?10:-1;
        if(slot<0||screen.getMenu().slots.size()<=slot)return;
        ItemStack stack=screen.getMenu().getSlot(slot).getItem();
        if(!stack.isEmpty())parse(stack);
    }

    private static void parse(ItemStack stack){
        String name=clean(stack.getHoverName().getString());
        Matcher itemLevel=LEVEL.matcher(name);
        if(!name.equals("Garden Desk")&&!itemLevel.matches())return;
        List<String> lore=lore(stack);
        int displayedLevel=itemLevel.matches()?romanOrNumber(itemLevel.group(1)):-1;
        int progressLevel=-1;
        long progress=-1;
        long overflow=-1;
        boolean nextOverflow=false;
        boolean maximumSeen=false;
        for(String line:lore){
            if(line.equalsIgnoreCase("Max level reached!"))maximumSeen=true;
            Matcher level=PROGRESS.matcher(line);
            if(level.matches())progressLevel=Math.max(0,romanOrNumber(level.group(1))-1);
            Matcher fraction=FRACTION.matcher(line);
            if(fraction.find()&&progress<0)progress=amount(fraction.group("current"));
            Matcher over=OVERFLOW.matcher(line);
            if(over.matches()){
                if(over.group(1)!=null)overflow=amount(over.group(1));
                else nextOverflow=true;
                continue;
            }
            if(nextOverflow){
                Matcher number=Pattern.compile("([\\d,.kKmMbB]+)").matcher(line);
                if(number.find()){overflow=amount(number.group(1));nextOverflow=false;}
            }
            if(maximumSeen&&overflow<0&&line.matches("[\\d,.]+[kKmMbB]?"))overflow=amount(line);
        }
        long total=-1;
        if(overflow>=0)total=LEVEL_15_XP+overflow;
        else if(progressLevel>=0&&progress>=0)total=xpForLevel(progressLevel)+progress;
        else if(displayedLevel==15&&progress<0)total=LEVEL_15_XP;
        if(total<0)return;
        put(total);
        lastParsedAt=System.currentTimeMillis();
    }

    private static void reward(String line){
        Matcher matcher=REWARD.matcher(line);
        if(!matcher.matches())return;
        long old=xp();
        if(old<0)return;
        int oldLevel=level(old,true);
        long next=Math.max(0,old+amount(matcher.group(1)));
        put(next);
        int newLevel=level(next,true);
        if(cfg.gardenLevelOverflowChat&&newLevel>15&&newLevel==oldLevel+1)levelUp(oldLevel,newLevel);
    }

    private static void levelUp(int oldLevel,int newLevel){
        Minecraft mc=Minecraft.getInstance();if(mc.player==null)return;
        Component line=Component.literal("§b§lGARDEN LEVEL UP §8"+number(oldLevel)+" -> §b"+number(newLevel))
            .withStyle(style->style.withClickEvent(new ClickEvent.RunCommand("/gardenlevels"))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal("Open Garden Level progress and rewards"))));
        mc.player.sendSystemMessage(Component.empty());
        mc.player.sendSystemMessage(line);
        mc.player.sendSystemMessage(Component.literal("§7Overflow Garden XP: §2"+format(xp()-LEVEL_15_XP)));
        mc.player.sendSystemMessage(Component.empty());
    }

    public static boolean visible(){return cfg!=null&&cfg.enabled&&cfg.gardenLevelDisplay&&inGarden();}

    public static State state(){
        long xp=xp();if(xp<0)return null;
        int level=level(xp,cfg.gardenLevelOverflow);
        boolean maximum=!cfg.gardenLevelOverflow&&level>=15;
        long start=xpForLevel(level);
        long levelXp=Math.max(0,xp-start);
        long needed=maximum?0:xpForLevel(level+1)-start;
        double percent=needed<=0?100:Math.clamp(levelXp*100.0/needed,0,100);
        return new State(level,xp,levelXp,needed,Math.max(0,xp-LEVEL_15_XP),percent,maximum);
    }

    public static List<Component> appendTooltip(AbstractContainerScreen<?> screen,ItemStack stack,List<Component> original){
        if(original==null||stack==null||stack.isEmpty()||cfg==null||!cfg.enabled||!cfg.gardenLevelMenuTooltip||!inGarden())return original;
        String title=clean(screen.getTitle().getString());
        if(!title.equals("Desk")&&!title.equals("SkyBlock Menu"))return original;
        String name=clean(stack.getHoverName().getString());
        if(!name.equals("Garden Desk")&&!LEVEL.matcher(name).matches())return original;
        State state=state();if(state==null||state.level<15)return original;
        List<Component> out=new ArrayList<>(original);
        out.add(Component.empty());
        out.add(Component.literal("§b§lGARDEN LEVEL"));
        out.add(Component.literal("§7Level: §a"+number(state.level)));
        if(state.maximum)out.add(Component.literal("§7Overflow XP: §2"+format(state.overflowXp)));
        else{
            out.add(Component.literal("§7Next level: §e"+format(state.levelXp)+"§6/§e"+format(state.neededXp)));
            out.add(Component.literal("§7Progress: §e"+decimal(state.percentage)+"%"));
            if(state.level>=15)out.add(Component.literal("§7Total overflow: §2"+format(state.overflowXp)));
        }
        out.add(Component.literal("§8Synced from this menu "+(System.currentTimeMillis()-lastParsedAt<2000?"now":"previously")));
        return out;
    }

    public static void registerCommands(CommandDispatcher<FabricClientCommandSource> dispatcher){
        dispatcher.register(LiteralArgumentBuilder.<FabricClientCommandSource>literal("gardenlevel")
            .executes(c->status())
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("set")
                .then(RequiredArgumentBuilder.<FabricClientCommandSource,Integer>argument("level",IntegerArgumentType.integer(0,10000))
                    .then(RequiredArgumentBuilder.<FabricClientCommandSource,Integer>argument("progress",IntegerArgumentType.integer(0,1_000_000_000))
                        .executes(c->set(IntegerArgumentType.getInteger(c,"level"),IntegerArgumentType.getInteger(c,"progress"))))))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("clear")
                .executes(c->{cfg.gardenLevelExperience.remove(profile());save();return status();}))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("precision")
                .then(RequiredArgumentBuilder.<FabricClientCommandSource,Integer>argument("digits",IntegerArgumentType.integer(0,3))
                    .executes(c->{cfg.gardenLevelPrecision=IntegerArgumentType.getInteger(c,"digits");save();return status();})))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("option")
                .then(RequiredArgumentBuilder.<FabricClientCommandSource,String>argument("name",StringArgumentType.word())
                    .then(RequiredArgumentBuilder.<FabricClientCommandSource,String>argument("state",StringArgumentType.word())
                        .executes(c->option(StringArgumentType.getString(c,"name"),StringArgumentType.getString(c,"state")))))));
    }

    private static int set(int level,int progress){
        long needed=xpForLevel(level+1)-xpForLevel(level);
        if(progress>needed&&needed>0){local("Progress exceeds the "+format(needed)+" XP required for that level.");return 0;}
        put(xpForLevel(level)+progress);return status();
    }
    private static int option(String name,String raw){
        Boolean value=bool(raw);if(value==null){local("Use on or off.");return 0;}
        switch(name.toLowerCase(Locale.ROOT)){
            case"display"->cfg.gardenLevelDisplay=value;case"overflow"->cfg.gardenLevelOverflow=value;
            case"chat"->cfg.gardenLevelOverflowChat=value;case"progress"->cfg.gardenLevelShowProgress=value;
            case"percentage"->cfg.gardenLevelShowPercentage=value;case"total"->cfg.gardenLevelShowTotalXp=value;
            case"overflowxp"->cfg.gardenLevelShowOverflowXp=value;case"tooltip"->cfg.gardenLevelMenuTooltip=value;
            case"roman"->cfg.gardenLevelUseRomanNumerals=value;default->{local("Unknown Garden Level option.");return 0;}
        }
        save();return status();
    }
    private static int status(){
        State state=state();
        local("Garden Level display "+on(cfg.gardenLevelDisplay)+", overflow "+on(cfg.gardenLevelOverflow)+".");
        local(state==null?"Open the Desk or SkyBlock Menu to sync XP.":"Level "+number(state.level)+", "+format(state.totalXp)+" total XP.");
        return 1;
    }

    public static String number(int value){return cfg!=null&&cfg.gardenLevelUseRomanNumerals?roman(value):Integer.toString(value);}
    public static String format(long value){return String.format(Locale.ROOT,"%,d",value);}
    public static String decimal(double value){return String.format(Locale.ROOT,"%."+Math.clamp(cfg.gardenLevelPrecision,0,3)+"f",value);}
    private static long xp(){return cfg==null||cfg.gardenLevelExperience==null?-1:cfg.gardenLevelExperience.getOrDefault(profile(),-1L);}
    private static void put(long xp){long safe=Math.max(0,xp);Long old=cfg.gardenLevelExperience.put(profile(),safe);if(!Objects.equals(old,safe))save();}
    private static int level(long xp,boolean overflow){
        int level=0;long total=0;
        for(long needed:LEVEL_XP){total+=needed;if(total>xp)return level;level++;}
        if(!overflow)return 15;
        while(total+OVERFLOW_LEVEL_XP<=xp){total+=OVERFLOW_LEVEL_XP;level++;}
        return level;
    }
    private static long xpForLevel(int requested){
        long total=0;int level=0;
        for(long needed:LEVEL_XP){total+=needed;level++;if(level==requested)return total;}
        return total+Math.max(0,requested-level)*OVERFLOW_LEVEL_XP;
    }
    private static List<String> lore(ItemStack stack){ItemLore lore=stack.get(DataComponents.LORE);if(lore==null)return List.of();List<String> out=new ArrayList<>();for(Component line:lore.lines())out.add(clean(line.getString()));return out;}
    private static long amount(String raw){if(raw==null)return 0;String value=raw.replace(",","").trim().toLowerCase(Locale.ROOT);double multiplier=1;if(value.endsWith("k")){multiplier=1e3;value=value.substring(0,value.length()-1);}else if(value.endsWith("m")){multiplier=1e6;value=value.substring(0,value.length()-1);}else if(value.endsWith("b")){multiplier=1e9;value=value.substring(0,value.length()-1);}try{return Math.round(Double.parseDouble(value)*multiplier);}catch(Exception ignored){return 0;}}
    private static int romanOrNumber(String value){try{return Integer.parseInt(value);}catch(Exception ignored){}int total=0,last=0;for(int i=value.length()-1;i>=0;i--){int n=switch(Character.toUpperCase(value.charAt(i))){case'I'->1;case'V'->5;case'X'->10;case'L'->50;case'C'->100;case'D'->500;case'M'->1000;default->0;};total+=n<last?-n:n;last=Math.max(last,n);}return total;}
    private static String roman(int value){if(value<=0)return"0";int[] amounts={1000,900,500,400,100,90,50,40,10,9,5,4,1};String[] symbols={"M","CM","D","CD","C","XC","L","XL","X","IX","V","IV","I"};StringBuilder out=new StringBuilder();for(int i=0;i<amounts.length;i++)while(value>=amounts[i]){out.append(symbols[i]);value-=amounts[i];}return out.toString();}
    private static String profile(){String value=LyraStorageValue.currentProfileKey();return value==null||value.isBlank()?"unknown":value.toLowerCase(Locale.ROOT);}
    private static boolean inGarden(){return ConstellationClient.loc().area()==LocationManager.SkyblockArea.GARDEN;}
    private static String clean(String value){String clean=ChatFormatting.stripFormatting(value);return clean==null?"":clean.trim();}
    private static Boolean bool(String value){return switch(value.toLowerCase(Locale.ROOT)){case"on","true","yes","1"->true;case"off","false","no","0"->false;default->null;};}
    private static String on(boolean value){return value?"on":"off";}
    private static void local(String text){Minecraft mc=Minecraft.getInstance();if(mc.player!=null)mc.player.sendSystemMessage(Component.literal("§2[Garden Level] §f"+text));}
    private static void save(){ConstellationClient.saveConfig();}
}

package com.froggylord.constellation.hud;

import com.froggylord.constellation.constellation.HerculesRareCropTracker;
import com.froggylord.constellation.constellation.HerculesGardenTracker;

import java.util.*;
import java.util.function.BooleanSupplier;

// ported from SkyHanni (LGPL-3.0-or-later): features/garden/tracker/RareCropTracker.kt
public final class RareCropHudWidget extends ThemedHudWidget {
    private final BooleanSupplier gate;private HudPosition position;private boolean enabled=true;
    public RareCropHudWidget(HudPosition position,BooleanSupplier gate){this.position=position;this.gate=gate;}
    @Override public String id(){return"garden-rare-crops";}@Override public HudPosition position(){return position;}@Override public void setPosition(HudPosition p){position=p;}
    @Override public boolean isEnabled(){return enabled&&gate.getAsBoolean();}@Override public void setEnabled(boolean value){enabled=value;}
    @Override public boolean visibleNow(){return isEnabled()&&HerculesRareCropTracker.visible();}@Override public String editorLabel(){return"Rare Crop Tracker";}
    @Override protected String title(){return"Rare Crops";}
    @Override protected List<Row> rows(){
        var stats=HerculesRareCropTracker.stats();if(stats==null)return List.of();
        var cfg=HerculesGardenTracker.config();List<Row> rows=new ArrayList<>();
        for(var row:stats.rows())rows.add(new Row("",row.drop().display(),String.format(Locale.ROOT,"%,d",row.amount()),row.drop().color()));
        if(rows.isEmpty())rows.add(new Row("","Drops","None"));
        if(cfg.rareCropShowProfit)rows.add(new Row("","Profit",coins(stats.profit()),0xFFFFAA00));
        if(cfg.rareCropShowProfitPerHour)rows.add(new Row("","Profit/hour",coins(stats.profitPerHour()),0xFFFFAA00));
        if(cfg.rareCropShowUptime)rows.add(new Row("","Uptime",time(stats.activeMillis()),0xFF55FFFF));
        if(stats.recent()!=null)rows.add(new Row("","Recent",stats.recent().display(),stats.recent().color()));
        return rows;
    }
    @Override protected List<Row> previewRows(){return List.of(new Row("","Cropie","28",0xFF55FF55),new Row("","Squash","9",0xFF5555FF),new Row("","Fermento","2",0xFFFF55FF),new Row("","Profit","1.24m",0xFFFFAA00),new Row("","Uptime","1h 18m",0xFF55FFFF));}
    private static String coins(double value){if(value>=1e9)return String.format(Locale.ROOT,"%.2fb",value/1e9);if(value>=1e6)return String.format(Locale.ROOT,"%.2fm",value/1e6);if(value>=1e3)return String.format(Locale.ROOT,"%.1fk",value/1e3);return String.format(Locale.ROOT,"%.0f",value);}
    private static String time(long millis){long seconds=millis/1000,hours=seconds/3600;seconds%=3600;long minutes=seconds/60;return hours>0?hours+"h "+minutes+"m":minutes+"m "+seconds%60+"s";}
}

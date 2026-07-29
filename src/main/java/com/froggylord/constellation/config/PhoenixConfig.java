package com.froggylord.constellation.config;

public class PhoenixConfig extends BaseConfigGroup {

    { enabled = false; }
    @Override public int currentVersion() { return 0; }

    public boolean fullbright = true;
    public boolean noHurtCam = true;
    public boolean noViewBob = true;
    public boolean autoSprint = false;
    public boolean hideLightning = true;
    public boolean hideFallingBlocks = true;
    public boolean hideFireOverlay = true;
    public boolean hideUnderwaterBlur = false;
    public boolean hideStatusEffects = true;
    public boolean hidePlayersInDungeon = false;
    public boolean etherwarpOverlay = true;
    public boolean scrollableTooltips = true;
    public boolean preventPlacingWeapons = false;
    public boolean noDeathAnimation = false;
    public boolean disableVignette = false;
    public boolean disableFog = false;
    public boolean instantSneak = false;
    public boolean signCalculator = true;
    public boolean hideAttachedArrows = true;
    public boolean itemProtection = true;
    public boolean preventDroppingValuable = true;
    public boolean protectStarredItems = true;
    public boolean protectRecombobulatedItems = true;
    public boolean protectValuableConsumables = true;
    public boolean showProtectedItemMarker = true;
    public boolean itemProtectionNotifications = true;
    public java.util.Set<String> protectedItemUuids = new java.util.HashSet<>();
    public java.util.Set<String> protectedSkyblockIds = new java.util.HashSet<>();
    public boolean hotbarScrollLock = false;
    public boolean wardrobeKeybinds = false;
    public boolean wardrobeArmorSets = true;
    public boolean wardrobeEquipmentSets = true;
    public boolean wardrobePreventUnequip = true;
    public boolean wardrobeSound = false;
    public boolean wardrobeFeedback = true;
    public boolean wardrobeConsumeInvalidKeys = true;
    public boolean wardrobeSlotLabels = true;
    public boolean wardrobeLabelTopRight = false;
    public boolean wardrobeSwapEnabled = false;
    public boolean wardrobeOpenKeybind = true;
    public String wardrobeKeyStyle = "HOTBAR";
    public int wardrobeClickCooldownMillis = 250;
    public int wardrobeSwapSlotOne = 1;
    public int wardrobeSwapSlotTwo = 2;
    public int wardrobeLabelColor = 0xFF55AAFF;
    public boolean autoSaveReminder = true;
    public boolean hotbarLock = false;
    public boolean hotbarSwapHelper = false;
    public boolean viewmodelCustomize = false;
    public boolean nameTagShadows = true;
    public boolean disableNpcDialogue = true;
}

package com.froggylord.constellation.constellation;

import com.froggylord.constellation.ConstellationClient;
import com.froggylord.constellation.config.HerculesConfig;
import com.froggylord.constellation.core.LocationManager;
import com.froggylord.constellation.mixin.ContainerScreenAccessor;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.*;

// ported from SkyHanni (LGPL-3.0-or-later): features/garden/greenhouse/DnaAnalyzerSolver.kt
// ported from SkyHanni (LGPL-3.0-or-later): config/features/garden/DnaAnalyzerSolverConfig.kt
public final class HerculesDnaAnalyzer {
    private enum Color { RED, GREEN, BLUE, YELLOW }
    private record Cell(int column, int row) {}
    private record Swap(Cell first, Cell second) {}
    private record Solution(int swaps, List<Swap> steps) {}

    private static final int ROWS = 4;
    private static final int COLUMNS = 9;
    private static final int UNREACHABLE = 1_000;
    private static final List<int[]> PERMUTATIONS = permutations();
    private static HerculesConfig cfg;
    private static String signature = "";
    private static Solution solution;
    private static int invalidUpdates;

    private HerculesDnaAnalyzer() {}

    public static void init(HerculesConfig config) {
        cfg = config;
        ConstellationClient.tick().every(2, "hercules-dna-analyzer", HerculesDnaAnalyzer::tick);
    }

    public static void registerCommands(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(LiteralArgumentBuilder.<FabricClientCommandSource>literal("dnasolver")
            .executes(ctx -> status())
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("status").executes(ctx -> status()))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("option")
                .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("name", StringArgumentType.word())
                    .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("state", StringArgumentType.word())
                        .executes(ctx -> option(StringArgumentType.getString(ctx, "name"), StringArgumentType.getString(ctx, "state")))))));
    }

    public static void drawSlot(GuiGraphicsExtractor graphics, AbstractContainerScreen<?> screen, Slot slot) {
        if (!active(screen) || slot == null || solution == null || solution.steps.isEmpty()) return;
        Swap next = solution.steps.get(solution.steps.size() - 1);
        int index = slot.index;
        int first = slot(next.first), second = slot(next.second);
        boolean selected = index == first || index == second;
        if (cfg.dnaAnalyzerDarkenOthers && index >= 9 && index <= 44 && !selected)
            graphics.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, cfg.dnaAnalyzerOtherColor);
        if (!selected) return;
        int color = index == first ? cfg.dnaAnalyzerFirstColor : cfg.dnaAnalyzerSecondColor;
        graphics.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, color);
        if (cfg.dnaAnalyzerShowOrder) {
            String label = index == first ? "1" : "2";
            graphics.text(Minecraft.getInstance().font, label, slot.x + 1, slot.y + 1, 0xFFFFFFFF, true);
        }
    }

    public static boolean shouldBlockClick(AbstractContainerScreen<?> screen, Slot slot, int slotId, int button, ContainerInput input) {
        if (!activeMenu(screen)) return false;
        if (slotId == 49 && cfg.dnaAnalyzerBlockClose) {
            local("Close button blocked while the DNA Analyzer is open.");
            return true;
        }
        if (!cfg.dnaAnalyzerBlockWrongClicks || solution == null || solution.steps.isEmpty() || slot == null) return false;
        if (slotId < 9 || slotId > 44) return false;
        Swap next = solution.steps.get(solution.steps.size() - 1);
        boolean correct = slotId == slot(next.first) || slotId == slot(next.second);
        if (!correct && cfg.dnaAnalyzerWrongClickFeedback) local("That slot is not part of the next swap.");
        return !correct;
    }

    public static List<Component> appendTooltip(AbstractContainerScreen<?> screen, List<Component> current) {
        if (!activeMenu(screen) || !cfg.dnaAnalyzerHideTooltips) return current;
        Slot hovered = ((ContainerScreenAccessor)screen).constellation$hoveredSlot();
        return hovered != null && hovered.index >= 9 && hovered.index <= 44 ? List.of() : current;
    }

    public static String hudText() {
        if (cfg == null || !cfg.enabled || !cfg.dnaAnalyzerSolver || !cfg.dnaAnalyzerHud) return null;
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> screen) || !active(screen) || solution == null) return null;
        if (solution.steps.isEmpty()) return "Solved";
        return solution.swaps + (solution.swaps == 1 ? " swap" : " swaps");
    }

    private static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> screen) || !activeMenu(screen)) {
            reset();
            return;
        }
        if (!cfg.enabled || !cfg.dnaAnalyzerSolver) return;
        Color[][] board = read(screen);
        if (board == null) {
            invalidUpdates++;
            solution = null;
            return;
        }
        String nextSignature = signature(board);
        if (nextSignature.equals(signature)) return;
        signature = nextSignature;
        invalidUpdates = 0;
        solution = solve(board, cfg.dnaAnalyzerAllowEndColumns);
    }

    private static Color[][] read(AbstractContainerScreen<?> screen) {
        if (screen.getMenu().slots.size() <= 44) return null;
        Color[][] board = new Color[COLUMNS][ROWS];
        for (int index = 9; index <= 44; index++) {
            int row = index / 9 - 1, column = index % 9;
            Color color = color(screen.getMenu().getSlot(index).getItem());
            if (color == null) return null;
            board[column][row] = color;
        }
        for (Color[] column : board) if (new HashSet<>(Arrays.asList(column)).size() != ROWS) return null;
        return board;
    }

    private static Solution solve(Color[][] board, boolean allowEnds) {
        int firstMutable = allowEnds ? 0 : 1;
        int lastMutable = allowEnds ? COLUMNS - 1 : COLUMNS - 2;
        int mutableCount = lastMutable - firstMutable + 1;
        int[][] dp = new int[mutableCount][PERMUTATIONS.size()];
        int[][] parent = new int[mutableCount][PERMUTATIONS.size()];
        int[][] cost = new int[mutableCount][PERMUTATIONS.size()];
        @SuppressWarnings("unchecked")
        List<int[]>[][] swaps = new List[mutableCount][PERMUTATIONS.size()];
        for (int i = 0; i < mutableCount; i++) {
            Arrays.fill(dp[i], UNREACHABLE);
            Arrays.fill(parent[i], -1);
            int column = firstMutable + i;
            for (int p = 0; p < PERMUTATIONS.size(); p++) {
                Color[] target = permute(board[column], PERMUTATIONS.get(p));
                List<int[]> steps = minimumSwaps(board[column], target);
                cost[i][p] = steps.size();
                swaps[i][p] = steps;
            }
        }
        for (int p = 0; p < PERMUTATIONS.size(); p++) {
            Color[] candidate = permute(board[firstMutable], PERMUTATIONS.get(p));
            if (!allowEnds && !connect(board[0], candidate)) continue;
            dp[0][p] = cost[0][p];
        }
        for (int i = 1; i < mutableCount; i++) {
            for (int p = 0; p < PERMUTATIONS.size(); p++) {
                Color[] current = permute(board[firstMutable + i], PERMUTATIONS.get(p));
                for (int q = 0; q < PERMUTATIONS.size(); q++) {
                    if (dp[i - 1][q] == UNREACHABLE) continue;
                    Color[] previous = permute(board[firstMutable + i - 1], PERMUTATIONS.get(q));
                    if (!connect(previous, current)) continue;
                    int candidate = dp[i - 1][q] + cost[i][p];
                    if (candidate < dp[i][p]) {
                        dp[i][p] = candidate;
                        parent[i][p] = q;
                    }
                }
            }
        }
        int best = UNREACHABLE, last = -1;
        for (int p = 0; p < PERMUTATIONS.size(); p++) {
            Color[] candidate = permute(board[lastMutable], PERMUTATIONS.get(p));
            if (!allowEnds && !connect(candidate, board[COLUMNS - 1])) continue;
            if (dp[mutableCount - 1][p] < best) {
                best = dp[mutableCount - 1][p];
                last = p;
            }
        }
        if (last < 0) return null;
        List<Swap> result = new ArrayList<>();
        int permutation = last;
        for (int i = mutableCount - 1; i >= 0; i--) {
            int column = firstMutable + i;
            for (int[] pair : swaps[i][permutation])
                result.add(new Swap(new Cell(column, pair[0]), new Cell(column, pair[1])));
            permutation = parent[i][permutation];
        }
        return new Solution(best, List.copyOf(result));
    }

    private static List<int[]> minimumSwaps(Color[] from, Color[] to) {
        int[] positions = new int[ROWS];
        for (int i = 0; i < ROWS; i++) positions[indexOf(from, to[i])] = i;
        boolean[] visited = new boolean[ROWS];
        List<int[]> swaps = new ArrayList<>();
        for (int i = 0; i < ROWS; i++) {
            if (visited[i]) continue;
            int current = i;
            List<Integer> cycle = new ArrayList<>();
            while (!visited[current]) {
                visited[current] = true;
                cycle.add(current);
                current = positions[current];
            }
            for (int k = 1; k < cycle.size(); k++) swaps.add(new int[]{cycle.get(0), cycle.get(k)});
        }
        return swaps;
    }

    private static boolean connect(Color[] first, Color[] second) {
        for (int row = 0; row < ROWS; row++) {
            Color value = first[row];
            if (second[row] == value || row > 0 && second[row - 1] == value || row < ROWS - 1 && second[row + 1] == value) continue;
            return false;
        }
        return true;
    }

    private static Color color(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.getHoverName().getString().toLowerCase(Locale.ROOT).contains("dna")) return null;
        TextColor textColor = firstColor(stack.getHoverName());
        if (textColor == null) return null;
        int value = textColor.getValue() & 0xFFFFFF;
        if (value == 0xFF5555 || value == 0xAA0000) return Color.RED;
        if (value == 0xFFFF55 || value == 0xFFAA00) return Color.YELLOW;
        if (value == 0x5555FF || value == 0x0000AA) return Color.BLUE;
        if (value == 0x55FF55 || value == 0x00AA00) return Color.GREEN;
        return null;
    }

    private static TextColor firstColor(Component component) {
        if (component.getStyle().getColor() != null) return component.getStyle().getColor();
        for (Component sibling : component.getSiblings()) {
            TextColor color = firstColor(sibling);
            if (color != null) return color;
        }
        return null;
    }

    private static Color[] permute(Color[] source, int[] permutation) {
        Color[] result = new Color[ROWS];
        for (int i = 0; i < ROWS; i++) result[i] = source[permutation[i]];
        return result;
    }

    private static int indexOf(Color[] values, Color target) {
        for (int i = 0; i < values.length; i++) if (values[i] == target) return i;
        return -1;
    }

    private static int slot(Cell cell) { return cell.column + (cell.row + 1) * 9; }
    private static String signature(Color[][] board) {
        StringBuilder result = new StringBuilder(COLUMNS * ROWS);
        for (Color[] column : board) for (Color color : column) result.append((char)('0' + color.ordinal()));
        return result.toString();
    }

    private static List<int[]> permutations() {
        List<int[]> result = new ArrayList<>();
        generate(result, new int[]{0, 1, 2, 3}, 0);
        return List.copyOf(result);
    }
    private static void generate(List<int[]> result, int[] values, int index) {
        if (index == values.length) { result.add(values.clone()); return; }
        for (int i = index; i < values.length; i++) {
            int swap = values[index]; values[index] = values[i]; values[i] = swap;
            generate(result, values, index + 1);
            swap = values[index]; values[index] = values[i]; values[i] = swap;
        }
    }

    private static int status() {
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> screen) || !activeMenu(screen)) {
            local("DNA Analyzer solver " + on(cfg.dnaAnalyzerSolver) + ". Open a DNA Analyzer board to inspect it.");
        } else if (solution == null) local("Waiting for a valid four-color board.");
        else local(solution.steps.isEmpty() ? "Board solved." : solution.swaps + " swaps remain.");
        return 1;
    }

    private static int option(String name, String state) {
        Boolean value = bool(state);
        if (value == null) { local("Use on or off."); return 0; }
        switch (name.toLowerCase(Locale.ROOT)) {
            case "enabled", "solver" -> cfg.dnaAnalyzerSolver = value;
            case "close" -> cfg.dnaAnalyzerBlockClose = value;
            case "wrongclicks" -> cfg.dnaAnalyzerBlockWrongClicks = value;
            case "feedback" -> cfg.dnaAnalyzerWrongClickFeedback = value;
            case "tooltips" -> cfg.dnaAnalyzerHideTooltips = value;
            case "darken" -> cfg.dnaAnalyzerDarkenOthers = value;
            case "order" -> cfg.dnaAnalyzerShowOrder = value;
            case "hud" -> cfg.dnaAnalyzerHud = value;
            case "ends" -> { cfg.dnaAnalyzerAllowEndColumns = value; signature = ""; }
            default -> { local("Options: solver, close, wrongclicks, feedback, tooltips, darken, order, hud, ends."); return 0; }
        }
        ConstellationClient.saveConfig();
        return status();
    }

    private static boolean active(AbstractContainerScreen<?> screen) { return cfg != null && cfg.enabled && cfg.dnaAnalyzerSolver && activeMenu(screen) && solution != null; }
    private static boolean activeMenu(AbstractContainerScreen<?> screen) {
        return cfg != null && cfg.enabled && cfg.dnaAnalyzerSolver && inGarden() && screen != null && screen.getTitle().getString().endsWith(" DNA");
    }
    private static boolean inGarden() { return ConstellationClient.loc().area() == LocationManager.SkyblockArea.GARDEN; }
    private static Boolean bool(String value) { return switch (value.toLowerCase(Locale.ROOT)) { case "on", "true", "yes", "1" -> true; case "off", "false", "no", "0" -> false; default -> null; }; }
    private static String on(boolean value) { return value ? "on" : "off"; }
    private static void local(String text) { Minecraft mc = Minecraft.getInstance(); if (mc.player != null) mc.player.sendSystemMessage(Component.literal("§2[DNA Analyzer] §f" + text)); }
    private static void reset() { signature = ""; solution = null; invalidUpdates = 0; }
}

package dev.fpaddon.automine;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Thông số đọc từ {@code config.yml}, nạp một lần khi bật plugin. Giá trị mặc định giống các hằng số
 * của bản trước, nên không có config thì hành vi không đổi. Mọi giá trị đều được kẹp trong khoảng hợp lệ.
 */
final class Settings {

    static int throttleFullUnits = 6000;
    static int throttleExpandCost = 16;
    static int searchStepNodes = 400;
    static boolean asyncPathfinding = true;
    static int scanUnitsPerStep = 3000;
    /** Các bán kính quét ngang, tăng dần. Quét dừng ở bán kính đầu tiên tìm thấy block. */
    static int[] scanRadii = {8, 16, 24};
    static boolean veinMining = true;
    static int mobSweepTicks = 60;
    static double mobSweepRadius = 25.0;

    /** AI tắt mặc định. Khoá API không nằm trong config: plugin đọc từ biến môi trường {@link #aiKeyEnv}. */
    static boolean aiEnabled = false;
    static String aiModel = "claude-haiku-5-5";
    static String aiKeyEnv = "ANTHROPIC_API_KEY";
    static int aiIntervalTicks = 600;
    static int aiCallsPerHour = 120;
    static List<AiBot> aiBots = List.of();

    /** Cảnh báo khi nạp config: giá trị sai bị bỏ qua hoặc thay bằng mặc định, plugin vẫn chạy. */
    static final List<String> warnings = new ArrayList<>();

    static final int MAX_AI_BOTS = 8;
    private static final String DEFAULT_MODEL = "claude-haiku-5-5";
    private static final String DEFAULT_KEY_ENV = "ANTHROPIC_API_KEY";

    /**
     * Một bot AI tự spawn khi bật plugin. Toạ độ null nghĩa là dùng điểm spawn của thế giới.
     */
    record AiBot(String name, String world, Double x, Double y, Double z) {
    }

    private Settings() {
    }

    static void load(FileConfiguration c) {
        warnings.clear();
        throttleFullUnits = clamp(c.getInt("throttle.full-units", 6000), 500, 100_000);
        throttleExpandCost = clamp(c.getInt("throttle.expand-cost", 16), 1, 1000);
        searchStepNodes = clamp(c.getInt("search.step-nodes", 400), 10, 20_000);
        asyncPathfinding = c.getBoolean("search.async", true);
        scanUnitsPerStep = clamp(c.getInt("scan.units-per-step", 3000), 100, 100_000);
        scanRadii = parseRadii(c.getIntegerList("scan.radii"));
        veinMining = c.getBoolean("mining.vein", true);
        mobSweepTicks = clamp(c.getInt("mob-sweep.interval-ticks", 60), 1, 72_000);
        mobSweepRadius = Math.max(1.0, Math.min(64.0, c.getDouble("mob-sweep.radius", 25.0)));

        aiEnabled = c.getBoolean("ai.enabled", false);
        aiModel = parseModel(c.getString("ai.model", DEFAULT_MODEL));
        aiKeyEnv = parseEnvName(c.getString("ai.api-key-env", DEFAULT_KEY_ENV));
        aiIntervalTicks = clamp(c.getInt("ai.decision-interval-ticks", 600), 100, 72_000);
        aiCallsPerHour = clamp(c.getInt("ai.max-calls-per-hour", 120), 1, 3600);
        aiBots = parseBots(c.getMapList("ai.bots"));
    }

    /** Lọc và sắp các bán kính; nếu cấu hình rỗng hoặc sai thì dùng mặc định. */
    private static int[] parseRadii(List<Integer> raw) {
        int[] out = raw.stream()
                .filter(r -> r != null && r >= 1 && r <= 32)
                .mapToInt(Integer::intValue)
                .sorted()
                .distinct()
                .toArray();
        return out.length == 0 ? new int[] {8, 16, 24} : out;
    }

    private static String parseModel(String raw) {
        if (raw != null && raw.matches("[a-z0-9.-]{3,64}")) {
            return raw;
        }
        warnings.add("ai.model không hợp lệ, dùng " + DEFAULT_MODEL + ".");
        return DEFAULT_MODEL;
    }

    private static String parseEnvName(String raw) {
        if (raw != null && raw.matches("[A-Z_][A-Z0-9_]{0,63}")) {
            return raw;
        }
        warnings.add("ai.api-key-env không hợp lệ, dùng " + DEFAULT_KEY_ENV + ".");
        return DEFAULT_KEY_ENV;
    }

    /** Đọc danh sách bot AI. Bỏ qua mục sai tên, trùng tên, hoặc vượt {@link #MAX_AI_BOTS}. */
    private static List<AiBot> parseBots(List<Map<?, ?>> raw) {
        List<AiBot> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Map<?, ?> m : raw) {
            Object name = m.get("name");
            if (!(name instanceof String n) || !n.matches("[A-Za-z0-9_]{1,16}")) {
                warnings.add("ai.bots: bỏ qua mục có tên không hợp lệ: " + name);
                continue;
            }
            if (!seen.add(n.toLowerCase(java.util.Locale.ROOT))) {
                warnings.add("ai.bots: bỏ qua tên trùng: " + n);
                continue;
            }
            if (out.size() >= MAX_AI_BOTS) {
                warnings.add("ai.bots: chỉ dùng tối đa " + MAX_AI_BOTS + " bot.");
                break;
            }
            Double x = number(m.get("x"));
            Double y = number(m.get("y"));
            Double z = number(m.get("z"));
            boolean hasCoords = x != null && y != null && z != null;
            Object world = m.get("world");
            out.add(new AiBot(n,
                    world instanceof String w ? w : null,
                    hasCoords ? x : null,
                    hasCoords ? y : null,
                    hasCoords ? z : null));
        }
        return List.copyOf(out);
    }

    private static Double number(Object o) {
        return o instanceof Number n ? n.doubleValue() : null;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}

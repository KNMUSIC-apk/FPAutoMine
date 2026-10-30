package dev.fpaddon.automine;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;

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

    private Settings() {
    }

    static void load(FileConfiguration c) {
        throttleFullUnits = clamp(c.getInt("throttle.full-units", 6000), 500, 100_000);
        throttleExpandCost = clamp(c.getInt("throttle.expand-cost", 16), 1, 1000);
        searchStepNodes = clamp(c.getInt("search.step-nodes", 400), 10, 20_000);
        asyncPathfinding = c.getBoolean("search.async", true);
        scanUnitsPerStep = clamp(c.getInt("scan.units-per-step", 3000), 100, 100_000);
        scanRadii = parseRadii(c.getIntegerList("scan.radii"));
        veinMining = c.getBoolean("mining.vein", true);
        mobSweepTicks = clamp(c.getInt("mob-sweep.interval-ticks", 60), 1, 72_000);
        mobSweepRadius = Math.max(1.0, Math.min(64.0, c.getDouble("mob-sweep.radius", 25.0)));
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

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}

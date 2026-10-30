package dev.fpaddon.automine;

import org.bukkit.Bukkit;

/**
 * Ngân sách công việc nặng (mở rộng nút A*, quét block) dùng chung cho MỌI bot trong MỘT tick
 * của server, để số bot tăng thì tổng tải cũng bị chặn. Khi server đang chậm (thời gian mỗi tick cao),
 * ngân sách tự giảm để addon không làm tụt TPS thêm. Các con số lấy từ {@link Settings}.
 *
 * <p>Đơn vị: 1 đơn vị ≈ một lần tra cứu block. Một lần mở rộng nút A* tốn {@link #expandCost()} đơn vị.
 */
final class Throttle {

    private static long currentTick = -1;
    private static int used;
    private static int limit = Settings.throttleFullUnits;

    private Throttle() {
    }

    /** Số đơn vị một nút A* tốn. */
    static int expandCost() {
        return Settings.throttleExpandCost;
    }

    /** Xin tối đa {@code want} đơn vị trong tick này. Trả về số được cấp (có thể bằng 0). */
    static int take(int want) {
        sync();
        int give = Math.max(0, Math.min(want, limit - used));
        used += give;
        return give;
    }

    /** Trả lại đơn vị đã xin nhưng không dùng hết (để bot khác trong cùng tick dùng). */
    static void refund(int units) {
        if (units > 0) {
            used = Math.max(0, used - units);
        }
    }

    private static void sync() {
        long tick = Bukkit.getCurrentTick();
        if (tick != currentTick) {
            currentTick = tick;
            used = 0;
            limit = scaledLimit();
        }
    }

    /** Giảm ngân sách khi thời gian mỗi tick tăng (50 ms là 20 TPS). */
    private static int scaledLimit() {
        int full = Settings.throttleFullUnits;
        double ms = Bukkit.getAverageTickTime();
        if (ms <= 0 || ms < 40.0) {
            return full;
        }
        if (ms < 50.0) {
            return full / 2;
        }
        return full / 5;
    }
}

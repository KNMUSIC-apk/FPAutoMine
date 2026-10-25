package dev.fpaddon.automine;

import org.bukkit.Bukkit;

/**
 * Ngân sách công việc nặng (mở rộng nút A*, quét block) dùng chung cho MỌI bot trong MỘT tick
 * của server, để số bot tăng thì tổng tải cũng bị chặn. Khi server đang chậm (thời gian mỗi tick
 * cao), ngân sách tự giảm để addon không làm tụt TPS thêm.
 *
 * <p>Đơn vị: 1 đơn vị ≈ một lần tra cứu block. Một lần mở rộng nút A* tốn {@link #EXPAND_COST} đơn vị.
 */
final class Throttle {

    /** Ngân sách đầy đủ mỗi tick khi server khoẻ: khoảng 375 nút A* hoặc 6000 ô quét (ước chừng ~1–2 ms). */
    static final int FULL_UNITS = 6000;
    /** Một lần mở rộng nút A* tốn chừng này đơn vị (mỗi nút tra cứu ~20 ô và cấp phát bộ nhớ). */
    static final int EXPAND_COST = 16;

    private static long currentTick = -1;
    private static int used;
    private static int limit = FULL_UNITS;

    private Throttle() {
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
        double ms = Bukkit.getAverageTickTime();
        if (ms <= 0 || ms < 40.0) {
            return FULL_UNITS;
        }
        if (ms < 50.0) {
            return FULL_UNITS / 2;
        }
        return FULL_UNITS / 5;
    }
}

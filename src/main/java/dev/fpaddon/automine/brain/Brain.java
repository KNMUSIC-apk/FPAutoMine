package dev.fpaddon.automine.brain;

import java.util.List;

/**
 * Bộ chọn việc của bot: mỗi tick chọn task có điểm cao nhất.
 *
 * <p>Hai quy tắc để hành vi ổn định:
 * <ul>
 *   <li>Việc hiện tại được giữ lại nếu điểm của nó không kém việc tốt nhất quá {@link #SWITCH_MARGIN}.
 *       Nhờ vậy bot không đổi việc liên tục khi hai việc có điểm gần nhau.</li>
 *   <li>Khi có việc khẩn cấp (điểm từ {@link #EMERGENCY} trở lên), luôn chuyển ngay, không chờ.</li>
 * </ul>
 */
public final class Brain {

    /** Chênh lệch điểm tối thiểu để đổi việc khi không khẩn cấp. */
    public static final double SWITCH_MARGIN = 0.1;
    /** Điểm từ mức này trở lên được coi là khẩn cấp và chuyển ngay. */
    public static final double EMERGENCY = 0.9;

    private final List<Task> tasks;
    private Task current;

    public Brain(List<Task> tasks) {
        this.tasks = List.copyOf(tasks);
    }

    /** Việc đang làm (null nếu chưa có). */
    public Task current() {
        return current;
    }

    /**
     * Chọn việc cho trạng thái {@code s}. Trả về null nếu không việc nào có điểm dương.
     */
    public Task choose(Snapshot s) {
        Task best = null;
        double bestScore = 0.0;
        for (Task t : tasks) {
            double u = clamp01(t.utility(s));
            if (u > bestScore) {
                best = t;
                bestScore = u;
            }
        }
        if (best != null && bestScore >= EMERGENCY) {
            current = best;
            return best;
        }
        if (current != null && current != best) {
            double keep = clamp01(current.utility(s));
            if (keep > 0.0 && keep + SWITCH_MARGIN >= bestScore) {
                return current;
            }
        }
        current = best;
        return best;
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, v));
    }
}

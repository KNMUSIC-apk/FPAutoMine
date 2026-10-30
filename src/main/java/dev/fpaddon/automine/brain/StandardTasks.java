package dev.fpaddon.automine.brain;

import java.util.List;

/**
 * Các việc cơ bản của giai đoạn M1, chỉ có phần chấm điểm. Phần thực hiện (đi, đào, ăn, đánh)
 * sẽ được nối vào ở các giai đoạn sau.
 */
public final class StandardTasks {

    /** Đói dưới mức này và có đồ ăn thì ăn ngay. */
    static final int FOOD_CRITICAL = 6;
    /** Đói dưới mức này thì nên ăn khi rảnh. */
    static final int FOOD_LOW = 14;

    private StandardTasks() {
    }

    /** Bộ việc mặc định, theo thứ tự ưu tiên tự nhiên (không dùng để xếp hạng, chỉ để đọc cho dễ). */
    public static List<Task> defaults() {
        return List.of(new Flee(), new Eat(), new Fight(), new Sleep(), new Gather(), new Idle());
    }

    /** Chạy trốn khi đang nguy hiểm: khẩn cấp tuyệt đối. */
    public static final class Flee implements Task {
        @Override
        public String name() {
            return "chạy-trốn";
        }

        @Override
        public double utility(Snapshot s) {
            return s.inDanger() ? 1.0 : 0.0;
        }
    }

    /** Ăn khi đói. Đói nặng thì khẩn cấp; đói nhẹ thì chỉ là việc nên làm khi rảnh. */
    public static final class Eat implements Task {
        @Override
        public String name() {
            return "ăn";
        }

        @Override
        public double utility(Snapshot s) {
            if (!s.hasFood()) {
                return 0.0;
            }
            if (s.food() <= FOOD_CRITICAL) {
                return 0.95;
            }
            if (s.food() <= FOOD_LOW) {
                return 0.6;
            }
            return 0.0;
        }
    }

    /** Đánh quái khi có quái gần. Máu thấp thì giảm điểm để bot ưu tiên trốn. */
    public static final class Fight implements Task {
        @Override
        public String name() {
            return "chiến-đấu";
        }

        @Override
        public double utility(Snapshot s) {
            if (s.hostiles() <= 0) {
                return 0.0;
            }
            double base = Math.min(0.85, 0.3 + 0.15 * s.hostiles());
            return s.health() < s.maxHealth() * 0.3 ? base * 0.5 : base;
        }
    }

    /** Ngủ khi đêm xuống, có giường và không có quái gần. */
    public static final class Sleep implements Task {
        @Override
        public String name() {
            return "ngủ";
        }

        @Override
        public double utility(Snapshot s) {
            return s.night() && s.hasBed() && s.hostiles() == 0 ? 0.5 : 0.0;
        }
    }

    /** Thu thập tài nguyên, việc mặc định khi không có gì gấp hơn. Thiếu công cụ thì ít hấp dẫn hơn. */
    public static final class Gather implements Task {
        @Override
        public String name() {
            return "thu-thập";
        }

        @Override
        public double utility(Snapshot s) {
            return s.hasTool() ? 0.3 : 0.2;
        }
    }

    /** Đứng yên, luôn có điểm nhỏ để bot không bao giờ không có việc. */
    public static final class Idle implements Task {
        @Override
        public String name() {
            return "nghỉ";
        }

        @Override
        public double utility(Snapshot s) {
            return 0.05;
        }
    }
}

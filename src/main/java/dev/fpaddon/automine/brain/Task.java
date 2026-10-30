package dev.fpaddon.automine.brain;

/**
 * Một việc bot có thể làm. {@link #utility} chấm điểm từ 0 đến 1 dựa trên trạng thái hiện tại:
 * 0 nghĩa là không nên làm lúc này, 1 nghĩa là khẩn cấp nhất.
 */
public interface Task {

    /** Tên hiển thị, dùng trong log và lệnh trạng thái. */
    String name();

    /** Điểm ưu tiên của việc này với trạng thái {@code s}, trong khoảng 0 đến 1. */
    double utility(Snapshot s);
}

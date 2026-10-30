package dev.fpaddon.automine;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Đọc quyết định từ Claude. Chỉ chấp nhận các việc trong {@link #TASKS}; mọi thứ khác bị bỏ qua.
 * Không dùng thư viện JSON để giảm phụ thuộc: câu trả lời chỉ cần hai trường phẳng là task và block.
 */
final class DecisionParser {

    /** Các việc AI được phép chọn. Thêm việc mới ở đây chỉ khi đã có mã thực hiện việc đó. */
    static final Set<String> TASKS = Set.of("gather", "rest");

    /** Block AI được phép yêu cầu đào: gỗ, quặng và vài block tài nguyên thường gặp. */
    private static final Set<String> GATHER_EXTRA = Set.of(
            "STONE", "DEEPSLATE", "COBBLESTONE", "DIRT", "GRAVEL", "SAND", "CLAY", "NETHERRACK");

    private static final Pattern TASK = Pattern.compile("\"task\"\\s*:\\s*\"([a-z]+)\"");
    private static final Pattern BLOCK = Pattern.compile("\"block\"\\s*:\\s*\"([A-Za-z_]{2,40})\"");

    /** Quyết định hợp lệ. {@code block} chỉ có khi task là gather. */
    record Decision(String task, String block) {
    }

    private DecisionParser() {
    }

    /** Trả về quyết định hợp lệ, hoặc null nếu văn bản không đúng định dạng hoặc việc không được phép. */
    static Decision parse(String text) {
        if (text == null) {
            return null;
        }
        Matcher t = TASK.matcher(text);
        if (!t.find()) {
            return null;
        }
        String task = t.group(1);
        if (!TASKS.contains(task)) {
            return null;
        }
        if (!task.equals("gather")) {
            return new Decision(task, null);
        }
        Matcher b = BLOCK.matcher(text);
        if (!b.find()) {
            return null;
        }
        String block = b.group(1).toUpperCase(Locale.ROOT);
        return isGatherBlock(block) ? new Decision(task, block) : null;
    }

    /** Block có được phép đào theo lệnh AI không: mọi loại gỗ (_LOG), mọi quặng (_ORE) và danh sách ngắn. */
    static boolean isGatherBlock(String name) {
        if (name == null) {
            return false;
        }
        return name.endsWith("_LOG") || name.endsWith("_ORE") || GATHER_EXTRA.contains(name);
    }
}

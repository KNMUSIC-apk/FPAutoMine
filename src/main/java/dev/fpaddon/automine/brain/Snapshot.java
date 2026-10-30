package dev.fpaddon.automine.brain;

/**
 * Ảnh chụp trạng thái bot tại một thời điểm, đủ để chấm điểm các việc. Không chứa tham chiếu Bukkit,
 * nên lõi quyết định kiểm thử được bằng dữ liệu giả.
 *
 * @param food       điểm đói, 0 đến 20
 * @param health     máu hiện tại
 * @param maxHealth  máu tối đa
 * @param night      đang ban đêm
 * @param hostiles   số quái thù địch trong tầm nhìn gần bot
 * @param hasFood    trong túi có món ăn an toàn
 * @param hasBed     biết vị trí giường an toàn
 * @param hasTool    có công cụ đào phù hợp trong túi
 * @param inDanger   đang trong tình huống nguy hiểm (chất lỏng, lửa, ngạt, void)
 */
public record Snapshot(
        int food,
        double health,
        double maxHealth,
        boolean night,
        int hostiles,
        boolean hasFood,
        boolean hasBed,
        boolean hasTool,
        boolean inDanger) {
}

package dev.fpaddon.automine;

import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Tìm đường A* trên lưới block (không dùng API của Paper để đi đường).
 * Một {@link Pos} là toạ độ block mà CHÂN bot đứng trong đó; đầu nằm ở y+1,
 * và mặt đất đỡ chân nằm ở y-1.
 *
 * <p>Có hai chế độ: đi bộ thuần ({@link #find}) và chế độ build ({@link #findBuild}),
 * trong đó được phép phá block chắn đường và đặt block lấp chỗ hổng dưới chân.
 *
 * <p>Thuật toán đọc block qua {@link BlockView}, nên cùng một mã có thể chạy trên thread chính
 * (đọc thế giới) hoặc trên thread phụ (đọc ảnh chụp chunk).
 */
final class Pathfinder {

    static final int MAX_NODES = 6000;
    static final int MAX_BUILD_NODES = 12000;
    static final int MAX_RADIUS = 48;
    /** Giới hạn số ô duyệt khi đích ở xa (tăng theo khoảng cách, không vượt quá). */
    static final int MAX_BUDGET = 60000;
    static final int MAX_DY = 16;
    /** Chiều cao mắt của người chơi (block). */
    static final double EYE_HEIGHT = 1.62;
    /** Chi phí phụ khi phải phá một block chắn đường. */
    static final double CLEAR_COST = 2.0;
    /** Chi phí phụ khi phải đặt một block lấp đất đỡ. */
    static final double PLACE_COST = 2.0;

    private static final int[] DX = {1, -1, 0, 0};
    private static final int[] DZ = {0, 0, 1, -1};

    /** Block nguy hiểm: không đứng trong/trên/cạnh chúng. */
    static final Set<Material> HAZARDS = EnumSet.of(
            Material.WATER, Material.LAVA, Material.FIRE, Material.SOUL_FIRE,
            Material.CACTUS, Material.MAGMA_BLOCK, Material.SWEET_BERRY_BUSH,
            Material.POWDER_SNOW, Material.BUBBLE_COLUMN, Material.CAMPFIRE,
            Material.SOUL_CAMPFIRE, Material.WITHER_ROSE);

    /** Block không bao giờ được phá khi mở đường. */
    private static final Set<Material> UNBREAKABLE = EnumSet.of(
            Material.BEDROCK, Material.BARRIER, Material.COMMAND_BLOCK, Material.CHAIN_COMMAND_BLOCK,
            Material.REPEATING_COMMAND_BLOCK, Material.STRUCTURE_BLOCK, Material.JIGSAW,
            Material.END_PORTAL_FRAME, Material.END_GATEWAY, Material.REINFORCED_DEEPSLATE,
            Material.LIGHT, Material.SPAWNER);

    record Pos(int x, int y, int z) {
        Pos offset(int dx, int dy, int dz) {
            return new Pos(x + dx, y + dy, z + dz);
        }
    }

    interface Goal {
        boolean test(Pos pos);
    }

    record Result(List<Pos> path, boolean reached) {
    }

    private record Node(Pos pos, Node parent, double g, double f) {
    }

    private Pathfinder() {
    }

    static Pos feetCell(Location loc) {
        return new Pos(loc.getBlockX(), (int) Math.floor(loc.getY() + 0.5), loc.getBlockZ());
    }

    static Pos posOf(Block block) {
        return new Pos(block.getX(), block.getY(), block.getZ());
    }

    static Pos posAt(Location loc) {
        return new Pos(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    /**
     * Tìm đường đi bộ từ {@code start} tới một ô thoả {@code goal}.
     *
     * @param hint         điểm ước lượng để định hướng A* (có thể null)
     * @param bestEffort   nếu không tới được đích, trả về đường tới ô gần {@code hint} nhất
     */
    static Result find(World world, Pos start, Goal goal, Pos hint, int maxNodes, boolean bestEffort) {
        return search(BlockView.of(world), start, goal, hint, maxNodes, bestEffort, false, false, false);
    }

    /**
     * Đường thoát hiểm khi bot đang trong chất lỏng/lửa hoặc bị block đặc bóp: được phép đi qua
     * chất lỏng và đứng tạm trên chất lỏng, đích là ô đứng an toàn ({@link #walkable}).
     */
    static Result findEscape(World world, Pos start, Goal goal, int maxNodes, boolean canPlace) {
        return search(BlockView.of(world), start, goal, null, maxNodes, false, true, canPlace, true);
    }

    /**
     * Tìm đường cho phép phá block chắn và (nếu {@code canPlace}) đặt block lấp đất đỡ.
     * Dùng khi đường đi bộ thường không tới được đích.
     */
    static Result findBuild(World world, Pos start, Goal goal, Pos hint, int maxNodes, boolean canPlace) {
        return findBuild(world, start, goal, hint, maxNodes, canPlace, false);
    }

    /** Như {@link #findBuild(World, Pos, Goal, Pos, int, boolean)}; {@code bestEffort} trả về đường tới ô gần đích nhất. */
    static Result findBuild(World world, Pos start, Goal goal, Pos hint, int maxNodes, boolean canPlace,
                            boolean bestEffort) {
        return search(BlockView.of(world), start, goal, hint, maxNodes, bestEffort, true, canPlace, false);
    }

    /** Đọc hết một lần tìm đường trong một lượt (khi cần kết quả ngay: thoát hiểm, cất đồ). */
    private static Result search(BlockView view, Pos start, Goal goal, Pos hint, int maxNodes,
                                 boolean bestEffort, boolean build, boolean canPlace, boolean escape) {
        Search s = new Search(view, start, goal, hint, maxNodes, bestEffort, build, canPlace, escape);
        while (!s.done()) {
            s.step(Integer.MAX_VALUE);
        }
        return s.result();
    }

    /**
     * Một lần tìm đường có thể tạm dừng. Mỗi lần {@link #step} chỉ mở rộng một số nút được cấp,
     * nên việc tìm đường trải qua nhiều tick thay vì chặn server trong một tick.
     * Khi đọc từ {@link BlockView.SnapshotView} và goal không gọi API Bukkit, lần tìm có thể chạy trên thread phụ.
     */
    static final class Search {
        private final BlockView view;
        private final Pos start;
        private final Goal goal;
        private final Pos hint;
        private final boolean bestEffort;
        private final boolean build;
        private final boolean canPlace;
        private final boolean escape;
        private final int radX;
        private final int radY;
        private final int maxNodes;
        private final PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.f(), b.f()));
        private final Map<Pos, Double> bestG = new HashMap<>();
        /** Chi phí bước vào một ô không đổi trong một lần tìm: nhớ lại để khỏi tính lặp. */
        private final Map<Pos, Double> enterMemo = new HashMap<>();
        private final Set<Pos> closed = new HashSet<>();
        private final Node startNode;
        private Node closest;
        private int expanded;
        private Result result;

        Search(BlockView view, Pos start, Goal goal, Pos hint, int maxNodes, boolean bestEffort,
               boolean build, boolean canPlace, boolean escape) {
            this.view = view;
            this.start = start;
            this.goal = goal;
            this.hint = hint;
            this.bestEffort = bestEffort;
            this.build = build;
            this.canPlace = canPlace;
            this.escape = escape;
            // Đích ở xa (có hint): mở rộng vùng tìm và ngân sách ô theo khoảng cách.
            int rx = MAX_RADIUS;
            int ry = MAX_DY;
            int budget = maxNodes;
            if (hint != null) {
                int hx = Math.abs(hint.x() - start.x());
                int hz = Math.abs(hint.z() - start.z());
                int hy = Math.abs(hint.y() - start.y());
                rx = Math.max(MAX_RADIUS, Math.max(hx, hz) + 16);
                ry = Math.max(MAX_DY, hy + 8);
                budget = Math.max(maxNodes, Math.min(MAX_BUDGET, (hx + hy + hz) * 150));
            }
            this.radX = rx;
            this.radY = ry;
            this.maxNodes = budget;
            this.startNode = new Node(start, null, 0.0, heuristic(start, hint));
            this.closest = startNode;
            if (goal.test(start)) {
                this.result = new Result(Collections.emptyList(), true);
                return;
            }
            open.add(startNode);
            bestG.put(start, 0.0);
        }

        boolean done() {
            return result != null;
        }

        Result result() {
            return result;
        }

        /** Số nút đã mở rộng cho tới nay (để hiển thị tiến độ). */
        int expanded() {
            return expanded;
        }

        /**
         * Mở rộng tối đa {@code budget} nút rồi dừng. Trả về số nút đã mở rộng trong lượt này.
         * Gọi lại ở tick sau để tiếp tục, cho tới khi {@link #done()}.
         */
        int step(int budget) {
            if (result != null) {
                return 0;
            }
            int spent = 0;
            while (spent < budget) {
                if (open.isEmpty() || expanded >= maxNodes) {
                    finish();
                    return spent;
                }
                Node cur = open.poll();
                if (!closed.add(cur.pos())) {
                    continue;
                }
                expanded++;
                spent++;
                if (goal.test(cur.pos())) {
                    result = new Result(buildPath(cur), true);
                    return spent;
                }
                if (heuristic(cur.pos(), hint) < heuristic(closest.pos(), hint)) {
                    closest = cur;
                }
                expandNeighbours(cur);
            }
            return spent;
        }

        private void finish() {
            if (bestEffort && closest != startNode) {
                result = new Result(buildPath(closest), false);
            } else {
                result = new Result(Collections.emptyList(), false);
            }
        }

        private void expandNeighbours(Node cur) {
            for (int i = 0; i < 4; i++) {
                for (int step = -1; step <= 1; step++) {
                    Pos next = cur.pos().offset(DX[i], step, DZ[i]);
                    if (Math.abs(next.x() - start.x()) > radX
                            || Math.abs(next.z() - start.z()) > radX
                            || Math.abs(next.y() - start.y()) > radY) {
                        continue;
                    }
                    double extra = 0.0;
                    // Bước lên 1 block cần chỗ trống ở trên đầu vị trí hiện tại.
                    if (step == 1 && !passable(view, cur.pos().x(), cur.pos().y() + 2, cur.pos().z(), escape)) {
                        if (!build || !clearable(view, cur.pos().x(), cur.pos().y() + 2, cur.pos().z())) {
                            continue;
                        }
                        extra += CLEAR_COST;
                    }
                    if (closed.contains(next)) {
                        continue;
                    }
                    double enter = enterMemo.computeIfAbsent(next,
                            q -> enterCost(view, q, build, canPlace, escape));
                    if (enter < 0) {
                        continue;
                    }
                    double base = step == 0 ? 1.0 : (step == 1 ? 1.5 : 1.2);
                    double ng = cur.g() + base + extra + enter;
                    Double old = bestG.get(next);
                    if (old != null && old <= ng) {
                        continue;
                    }
                    bestG.put(next, ng);
                    open.add(new Node(next, cur, ng, ng + heuristic(next, hint)));
                }
            }
        }
    }

    private static double heuristic(Pos p, Pos hint) {
        if (hint == null) {
            return 0.0;
        }
        return Math.max(0.0, Math.abs(p.x() - hint.x()) + Math.abs(p.z() - hint.z()) - 2.0);
    }

    private static List<Pos> buildPath(Node end) {
        List<Pos> path = new ArrayList<>();
        for (Node n = end; n != null && n.parent() != null; n = n.parent()) {
            path.add(n.pos());
        }
        Collections.reverse(path);
        return path;
    }

    private static boolean inRange(BlockView v, int y) {
        return y >= v.minY() && y < v.maxY();
    }

    /**
     * Ô đứng đủ gần block (theo khoảng cách từ mắt bot tới tâm block), không xét tường chắn.
     * Đây là điều kiện đọc được trên thread phụ; đường tới đích được kiểm tra lại bằng
     * {@link #canMine} trên thread chính trước khi dùng.
     */
    static boolean inReachOf(Pos cell, Pos block, double reach) {
        double dx = block.x() + 0.5 - (cell.x() + 0.5);
        double dy = block.y() + 0.5 - (cell.y() + EYE_HEIGHT);
        double dz = block.z() + 0.5 - (cell.z() + 0.5);
        return Math.sqrt(dx * dx + dy * dy + dz * dz) <= reach;
    }

    /**
     * Xấp xỉ tia nhìn từ mắt bot đứng ở {@code cell} tới tâm {@code block}, chỉ đọc qua {@link BlockView}
     * (đọc được trên thread phụ). Không phải tia quét chính xác của thế giới; đường được chọn vẫn phải
     * qua {@link #canMine} trên thread chính trước khi dùng.
     */
    static boolean losClear(BlockView v, Pos cell, Pos block) {
        double sx = cell.x() + 0.5;
        double sy = cell.y() + EYE_HEIGHT;
        double sz = cell.z() + 0.5;
        double dx = block.x() + 0.5 - sx;
        double dy = block.y() + 0.5 - sy;
        double dz = block.z() + 0.5 - sz;
        int steps = Math.max(1, (int) Math.ceil(Math.sqrt(dx * dx + dy * dy + dz * dz) / 0.2));
        for (int i = 1; i < steps; i++) {
            double t = (double) i / steps;
            int bx = (int) Math.floor(sx + dx * t);
            int by = (int) Math.floor(sy + dy * t);
            int bz = (int) Math.floor(sz + dz * t);
            if (bx == block.x() && by == block.y() && bz == block.z()) {
                continue;
            }
            Material m = v.type(bx, by, bz);
            if (m == null || m.isSolid()) {
                return false;
            }
        }
        return true;
    }

    static boolean passable(World world, int x, int y, int z) {
        return passable(BlockView.of(world), x, y, z, false);
    }

    static boolean passable(World world, int x, int y, int z, boolean escape) {
        return passable(BlockView.of(world), x, y, z, escape);
    }

    /** Ô bot có thể chiếm (không phải block đặc). Khi thoát hiểm thì chất lỏng/lửa cũng được coi là chiếm được. */
    static boolean passable(BlockView v, int x, int y, int z, boolean escape) {
        if (!inRange(v, y)) {
            return false;
        }
        Material m = v.type(x, y, z);
        return m != null && v.passable(x, y, z) && (escape || !HAZARDS.contains(m));
    }

    static boolean supports(World world, int x, int y, int z) {
        return supports(BlockView.of(world), x, y, z);
    }

    static boolean supports(BlockView v, int x, int y, int z) {
        if (!inRange(v, y)) {
            return false;
        }
        Material m = v.type(x, y, z);
        return m != null && m.isSolid() && !HAZARDS.contains(m);
    }

    /**
     * Có thể phá block này để mở đường: là chất rắn, không phải chất nguy hiểm,
     * không bất khả phá và không phải vật chứa đồ (tránh làm mất đồ trong rương).
     */
    static boolean clearable(World world, int x, int y, int z) {
        return clearable(BlockView.of(world), x, y, z);
    }

    static boolean clearable(BlockView v, int x, int y, int z) {
        if (!inRange(v, y)) {
            return false;
        }
        Material m = v.type(x, y, z);
        return m != null && m.isSolid() && !HAZARDS.contains(m) && !UNBREAKABLE.contains(m) && !protectedBlock(m)
                && !Safety.fluidAdjacent(v, x, y, z);
    }

    /**
     * Có thể phá block này ngay để thoát ngạt: là block đặc, không bất khả phá, không phải vật chứa.
     * Không xét chất lỏng kề vì khi ngạt thì phải phá trước đã.
     */
    static boolean breakableNow(Block b) {
        Material m = b.getType();
        return m.isSolid() && !HAZARDS.contains(m) && !UNBREAKABLE.contains(m) && !protectedBlock(m);
    }

    /** Vật chứa đồ hoặc tile entity quan trọng: không phá block loại này. */
    static boolean protectedBlock(Material m) {
        String n = m.name();
        return n.endsWith("CHEST") || n.endsWith("SHULKER_BOX") || n.endsWith("FURNACE")
                || n.endsWith("DISPENSER") || n.endsWith("DROPPER")
                || n.equals("BARREL") || n.equals("HOPPER") || n.equals("BREWING_STAND")
                || n.equals("SMOKER") || n.equals("LECTERN") || n.equals("JUKEBOX")
                || n.equals("BEACON") || n.equals("ENCHANTING_TABLE");
    }

    /**
     * Chi phí để bot đứng vào ô {@code p} (chân và đầu thoáng, có đất đỡ).
     * Trả về -1 nếu không đi được; ở chế độ build, phần bị chắn/hổng được tính thêm chi phí.
     */
    private static double enterCost(BlockView v, Pos p, boolean build, boolean canPlace, boolean escape) {
        // Sát đáy thế giới: có thể rơi vào void, không đứng ở đó.
        if (p.y() < v.minY() + Safety.VOID_MARGIN) {
            return -1;
        }
        double cost = 0.0;
        if (!passable(v, p.x(), p.y(), p.z(), escape)) {
            if (!build || !clearable(v, p.x(), p.y(), p.z())) {
                return -1;
            }
            cost += CLEAR_COST;
        }
        if (!passable(v, p.x(), p.y() + 1, p.z(), escape)) {
            if (!build || !clearable(v, p.x(), p.y() + 1, p.z())) {
                return -1;
            }
            cost += CLEAR_COST;
        }
        if (!ground(v, p.x(), p.y() - 1, p.z(), escape)) {
            if (!build || !canPlace || !passable(v, p.x(), p.y() - 1, p.z(), false)) {
                return -1;
            }
            cost += PLACE_COST;
        }
        // Phạt theo nguy hiểm kề bên (dung nham, lửa, nước, block rơi) để lộ trình tránh xa chúng.
        return cost + Safety.hazardCost(v, p.x(), p.y(), p.z());
    }

    /** Có đất đỡ ở ô (x, y, z). Khi thoát hiểm, chất lỏng/block nóng cũng tạm coi là đỡ được. */
    private static boolean ground(BlockView v, int x, int y, int z, boolean escape) {
        if (supports(v, x, y, z)) {
            return true;
        }
        if (!escape || !inRange(v, y)) {
            return false;
        }
        Material m = v.type(x, y, z);
        return m != null && (m.isSolid() || HAZARDS.contains(m));
    }

    static boolean walkable(World world, Pos p) {
        return enterCost(BlockView.of(world), p, false, false, false) >= 0;
    }

    /** Ô đứng hợp lệ ngay lúc này, dùng để kiểm tra lại ô kế tiếp trước khi bước (thế giới có thể đã đổi). */
    static boolean enterable(World world, Pos p, boolean build, boolean canPlace, boolean escape) {
        return enterCost(BlockView.of(world), p, build, canPlace, escape) >= 0;
    }

    /**
     * Bot đứng ở {@code stand} có đào được {@code target} không: trong tầm với
     * và tia nhìn từ mắt tới tâm block trúng đúng block đó (không bị chắn).
     * Chỉ dùng trên thread chính (dùng tia quét của thế giới).
     */
    static boolean canMine(World world, Pos stand, Block target, double reach) {
        double ex = stand.x() + 0.5;
        double ey = stand.y() + EYE_HEIGHT;
        double ez = stand.z() + 0.5;
        double dx = target.getX() + 0.5 - ex;
        double dy = target.getY() + 0.5 - ey;
        double dz = target.getZ() + 0.5 - ez;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist > reach) {
            return false;
        }
        if (!Safety.breakSafe(BlockView.of(world), stand, target)) {
            return false;
        }
        if (!target.getType().isSolid()) {
            return true;
        }
        Vector dir = new Vector(dx, dy, dz).normalize();
        RayTraceResult hit = world.rayTraceBlocks(new Location(world, ex, ey, ez), dir, dist + 0.5,
                FluidCollisionMode.NEVER, true);
        if (hit == null || hit.getHitBlock() == null) {
            return false;
        }
        Block b = hit.getHitBlock();
        return b.getX() == target.getX() && b.getY() == target.getY() && b.getZ() == target.getZ();
    }

    /** Khoảng cách từ tâm ô đứng tới tâm block (3D). */
    static double distToCenter(Pos cell, Block block) {
        double dx = cell.x() + 0.5 - (block.getX() + 0.5);
        double dy = cell.y() + 0.5 - (block.getY() + 0.5);
        double dz = cell.z() + 0.5 - (block.getZ() + 0.5);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Ô đứng đủ gần một điểm rơi (item) để nhặt. */
    static boolean nearPoint(Pos cell, Location point) {
        double dx = cell.x() + 0.5 - point.getX();
        double dz = cell.z() + 0.5 - point.getZ();
        return Math.sqrt(dx * dx + dz * dz) <= 0.9 && Math.abs(cell.y() - point.getY()) <= 1.5;
    }

    /** Yaw của Minecraft cho hướng (dx, dz). */
    static float yawTo(double dx, double dz) {
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    /** Pitch của Minecraft cho hướng (dx, dy, dz); dương là nhìn xuống. */
    static float pitchTo(double dx, double dy, double dz) {
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        return (float) -Math.toDegrees(Math.atan2(dy, horizontal));
    }
}

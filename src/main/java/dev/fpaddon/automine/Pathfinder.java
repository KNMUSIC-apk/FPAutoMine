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
 */
final class Pathfinder {

    static final int MAX_NODES = 6000;
    static final int MAX_RADIUS = 48;
    static final int MAX_DY = 16;
    /** Chiều cao mắt của người chơi (block). */
    static final double EYE_HEIGHT = 1.62;

    private static final int[] DX = {1, -1, 0, 0};
    private static final int[] DZ = {0, 0, 1, -1};

    /** Block nguy hiểm: không đứng trong/trên/cạnh chúng. */
    private static final Set<Material> HAZARDS = EnumSet.of(
            Material.WATER, Material.LAVA, Material.FIRE, Material.SOUL_FIRE,
            Material.CACTUS, Material.MAGMA_BLOCK, Material.SWEET_BERRY_BUSH,
            Material.POWDER_SNOW, Material.BUBBLE_COLUMN, Material.CAMPFIRE,
            Material.SOUL_CAMPFIRE, Material.WITHER_ROSE);

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
     * Tìm đường từ {@code start} tới một ô thoả {@code goal}.
     *
     * @param hint         điểm ước lượng để định hướng A* (có thể null)
     * @param bestEffort   nếu không tới được đích, trả về đường tới ô gần {@code hint} nhất
     */
    static Result find(World world, Pos start, Goal goal, Pos hint, int maxNodes, boolean bestEffort) {
        if (goal.test(start)) {
            return new Result(Collections.emptyList(), true);
        }
        PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.f(), b.f()));
        Map<Pos, Double> bestG = new HashMap<>();
        Set<Pos> closed = new HashSet<>();
        Node startNode = new Node(start, null, 0.0, heuristic(start, hint));
        open.add(startNode);
        bestG.put(start, 0.0);
        Node closest = startNode;
        int expanded = 0;

        while (!open.isEmpty() && expanded < maxNodes) {
            Node cur = open.poll();
            if (!closed.add(cur.pos())) {
                continue;
            }
            expanded++;
            if (goal.test(cur.pos())) {
                return new Result(buildPath(cur), true);
            }
            if (heuristic(cur.pos(), hint) < heuristic(closest.pos(), hint)) {
                closest = cur;
            }
            for (int i = 0; i < 4; i++) {
                for (int step = -1; step <= 1; step++) {
                    Pos next = cur.pos().offset(DX[i], step, DZ[i]);
                    if (Math.abs(next.x() - start.x()) > MAX_RADIUS
                            || Math.abs(next.z() - start.z()) > MAX_RADIUS
                            || Math.abs(next.y() - start.y()) > MAX_DY) {
                        continue;
                    }
                    // Bước lên 1 block cần chỗ trống ở trên đầu vị trí hiện tại.
                    if (step == 1 && !passable(world, cur.pos().x(), cur.pos().y() + 2, cur.pos().z())) {
                        continue;
                    }
                    if (closed.contains(next) || !walkable(world, next)) {
                        continue;
                    }
                    double cost = step == 0 ? 1.0 : (step == 1 ? 1.5 : 1.2);
                    double ng = cur.g() + cost;
                    Double old = bestG.get(next);
                    if (old != null && old <= ng) {
                        continue;
                    }
                    bestG.put(next, ng);
                    open.add(new Node(next, cur, ng, ng + heuristic(next, hint)));
                }
            }
        }
        if (bestEffort && closest != startNode) {
            return new Result(buildPath(closest), false);
        }
        return new Result(Collections.emptyList(), false);
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

    private static boolean inRange(World world, int y) {
        return y >= world.getMinHeight() && y < world.getMaxHeight();
    }

    static boolean passable(World world, int x, int y, int z) {
        if (!inRange(world, y) || !world.isChunkLoaded(x >> 4, z >> 4)) {
            return false;
        }
        Block b = world.getBlockAt(x, y, z);
        return b.isPassable() && !HAZARDS.contains(b.getType());
    }

    static boolean supports(World world, int x, int y, int z) {
        if (!inRange(world, y) || !world.isChunkLoaded(x >> 4, z >> 4)) {
            return false;
        }
        Material m = world.getBlockAt(x, y, z).getType();
        return m.isSolid() && !HAZARDS.contains(m);
    }

    static boolean walkable(World world, Pos p) {
        return passable(world, p.x(), p.y(), p.z())
                && passable(world, p.x(), p.y() + 1, p.z())
                && supports(world, p.x(), p.y() - 1, p.z());
    }

    /**
     * Bot đứng ở {@code stand} có đào được {@code target} không: trong tầm với
     * và tia nhìn từ mắt tới tâm block trúng đúng block đó (không bị chắn).
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

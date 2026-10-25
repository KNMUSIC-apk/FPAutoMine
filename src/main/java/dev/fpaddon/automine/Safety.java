package dev.fpaddon.automine;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.Set;

/**
 * Các kiểm tra an toàn cho từng nước đi và từng lần đào: tránh rơi vào void, ngạt (đứng trong
 * block đặc), chết trong dung nham/lửa/nước, rơi quá cao, và block rơi (cát, sỏi) đè lên bot.
 * Pathfinder, lúc đi và lúc đào đều hỏi module này trước khi quyết định.
 */
final class Safety {

    /** Ô đứng phải cách đáy thế giới ít nhất chừng này block, để không rơi vào void. */
    static final int VOID_MARGIN = 4;
    /** Rơi không quá chừng này block thì không mất máu. */
    static final int SAFE_FALL = 3;

    private static final int[] DX = {1, -1, 0, 0, 0, 0};
    private static final int[] DY = {0, 0, 0, 0, 1, -1};
    private static final int[] DZ = {0, 0, 1, -1, 0, 0};

    /** Chất gây cháy: không được kề, và tuyệt đối không được đứng/đi vào. */
    private static final Set<Material> BURNING = Set.of(Material.LAVA, Material.FIRE, Material.SOUL_FIRE);

    private Safety() {
    }

    /** Loại block tại toạ độ; null nếu ngoài thế giới hoặc chunk chưa nạp (coi là chưa biết). */
    static Material type(World w, int x, int y, int z) {
        if (y < w.getMinHeight() || y >= w.getMaxHeight() || !w.isChunkLoaded(x >> 4, z >> 4)) {
            return null;
        }
        return w.getBlockAt(x, y, z).getType();
    }

    static boolean isFluid(Material m) {
        return m == Material.LAVA || m == Material.WATER;
    }

    /** Có chất lỏng ở một trong 6 ô kề với ô này (hoặc có ô kề chưa biết). Chất lỏng sẽ chảy vào chỗ trống. */
    static boolean fluidAdjacent(World w, int x, int y, int z) {
        for (int i = 0; i < 6; i++) {
            Material m = type(w, x + DX[i], y + DY[i], z + DZ[i]);
            if (m == null || isFluid(m)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Nếu ô (x, y, z) đang trống và một bot ở đó rơi xuống, trả về y của chân khi chạm đất.
     * Trả về -1 nếu rơi qua chất lỏng/lửa, chạm block nguy hiểm, rơi vào void, hoặc rơi quá xa.
     */
    static int landingY(World w, int x, int y, int z) {
        int yy = y;
        for (int fall = 0; fall <= SAFE_FALL + 1; fall++) {
            Material below = type(w, x, yy - 1, z);
            if (below == null) {
                return -1;
            }
            if (below.isSolid()) {
                return Pathfinder.HAZARDS.contains(below) ? -1 : yy;
            }
            if (isFluid(below) || Pathfinder.HAZARDS.contains(below)) {
                return -1;
            }
            yy--;
        }
        return -1;
    }

    /**
     * Có đào được block {@code x} khi bot đứng tại {@code stand} mà không gây nguy hiểm không.
     *
     * <ul>
     *   <li>Không đào block kề chất lỏng: chất lỏng sẽ chảy vào chỗ trống.</li>
     *   <li>Không đào khi có block rơi ngay trên x và bot ở cùng cột với x (block rơi đè lên bot).</li>
     *   <li>Không đào block ngay dưới chân nếu bot sẽ rơi vào chất lỏng, void, hoặc rơi quá
     *       {@link #SAFE_FALL} block.</li>
     * </ul>
     */
    static boolean breakSafe(World w, Pathfinder.Pos stand, Block x) {
        int bx = x.getX();
        int by = x.getY();
        int bz = x.getZ();
        if (fluidAdjacent(w, bx, by, bz)) {
            return false;
        }
        Material above = type(w, bx, by + 1, bz);
        if (above != null && above.hasGravity() && stand.x() == bx && stand.z() == bz && stand.y() <= by + 2) {
            return false;
        }
        if (bx == stand.x() && bz == stand.z() && by == stand.y() - 1) {
            int landing = landingY(w, bx, by, bz);
            if (landing < 0 || stand.y() - landing > SAFE_FALL || landing < w.getMinHeight() + VOID_MARGIN) {
                return false;
            }
            if (landing != by && fluidAdjacent(w, bx, landing, bz)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Bot đang gặp nguy hiểm ngay lúc này: chân hoặc đầu trong chất lỏng/lửa, đứng trên block nóng,
     * chân hoặc đầu nằm trong block đặc (ngạt), hoặc sát đáy thế giới.
     * Trả về false khi chưa biết trạng thái ô (chunk chưa nạp), để không hoảng loạn vô cớ.
     */
    static boolean inDanger(World w, Location loc) {
        Pathfinder.Pos c = Pathfinder.feetCell(loc);
        Material feet = type(w, c.x(), c.y(), c.z());
        Material head = type(w, c.x(), c.y() + 1, c.z());
        Material ground = type(w, c.x(), c.y() - 1, c.z());
        if (feet == null || head == null) {
            return false;
        }
        if (c.y() < w.getMinHeight() + 1) {
            return true;
        }
        if (Pathfinder.HAZARDS.contains(feet) || Pathfinder.HAZARDS.contains(head)) {
            return true;
        }
        // Ngạt: đầu/chân nằm trong khối đặc đầy đủ (không tính lớp tuyết, thảm... không gây ngạt).
        if (feet.isOccluding() || head.isOccluding()) {
            return true;
        }
        return ground != null && Pathfinder.HAZARDS.contains(ground);
    }

    /**
     * Chi phí phạt khi đứng ở ô này vì nguy hiểm kề bên: dung nham/lửa rất nặng, nước nhẹ hơn,
     * và block rơi phía trên đầu. Giúp lộ trình tránh sát dung nham, nước và chỗ có thể bị đè.
     */
    static double hazardCost(World w, int x, int y, int z) {
        double cost = 0.0;
        for (int i = 0; i < 6; i++) {
            Material m = type(w, x + DX[i], y + DY[i], z + DZ[i]);
            if (m == null) {
                continue;
            }
            if (BURNING.contains(m)) {
                cost += 10.0;
            } else if (m == Material.WATER) {
                cost += 3.0;
            }
        }
        Material overHead = type(w, x, y + 2, z);
        if (overHead != null && overHead.hasGravity()) {
            cost += 3.0;
        }
        return cost;
    }
}

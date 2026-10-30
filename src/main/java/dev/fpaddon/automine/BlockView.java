package dev.fpaddon.automine;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.ChunkSnapshot;

import java.util.Map;

/**
 * Nguồn đọc block cho thuật toán tìm đường và kiểm tra an toàn. Có hai cài đặt:
 * {@link WorldView} đọc trực tiếp thế giới (chỉ trên thread chính), và {@link SnapshotView} đọc từ
 * ảnh chụp chunk (an toàn trên thread phụ, dùng cho tìm đường bất đồng bộ).
 */
interface BlockView {

    /** Y thấp nhất đứng được (bao gồm). */
    int minY();

    /** Y cao nhất cộng một (không bao gồm). */
    int maxY();

    /** Loại block tại toạ độ; null nếu ngoài thế giới hoặc chưa biết (chunk chưa nạp/chưa chụp). */
    Material type(int x, int y, int z);

    /** Ô có cho bot đi xuyên qua (không phải block đặc) không. Chỉ gọi khi {@link #type} khác null. */
    boolean passable(int x, int y, int z);

    /** Khoá chunk dùng làm khoá map: cx và cz ghép vào một long. */
    static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xffffffffL);
    }

    /** Đọc trực tiếp thế giới trên thread chính. */
    static BlockView of(World world) {
        return new WorldView(world);
    }

    final class WorldView implements BlockView {
        private final World world;

        WorldView(World world) {
            this.world = world;
        }

        @Override
        public int minY() {
            return world.getMinHeight();
        }

        @Override
        public int maxY() {
            return world.getMaxHeight();
        }

        @Override
        public Material type(int x, int y, int z) {
            if (y < world.getMinHeight() || y >= world.getMaxHeight() || !world.isChunkLoaded(x >> 4, z >> 4)) {
                return null;
            }
            return world.getBlockAt(x, y, z).getType();
        }

        @Override
        public boolean passable(int x, int y, int z) {
            Block b = world.getBlockAt(x, y, z);
            return b.isPassable();
        }
    }

    /**
     * Đọc từ ảnh chụp chunk đã chụp sẵn (bất biến, an toàn trên thread phụ). Không có thông tin
     * {@code isPassable} đầy đủ như Block, nên dùng quy tắc gần đúng: block không phải chất rắn thì đi xuyên được.
     * Khi đi thật, mọi bước đều được kiểm tra lại bằng {@link WorldView} trên thread chính.
     */
    final class SnapshotView implements BlockView {
        private final Map<Long, ChunkSnapshot> chunks;
        private final int minY;
        private final int maxY;

        SnapshotView(Map<Long, ChunkSnapshot> chunks, int minY, int maxY) {
            this.chunks = chunks;
            this.minY = minY;
            this.maxY = maxY;
        }

        @Override
        public int minY() {
            return minY;
        }

        @Override
        public int maxY() {
            return maxY;
        }

        @Override
        public Material type(int x, int y, int z) {
            if (y < minY || y >= maxY) {
                return null;
            }
            ChunkSnapshot snap = chunks.get(chunkKey(x >> 4, z >> 4));
            if (snap == null) {
                return null;
            }
            return snap.getBlockType(x & 15, y, z & 15);
        }

        @Override
        public boolean passable(int x, int y, int z) {
            Material m = type(x, y, z);
            return m != null && !m.isSolid();
        }
    }
}

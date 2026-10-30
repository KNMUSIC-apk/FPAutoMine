package dev.fpaddon.automine;

import org.bukkit.Bukkit;
import org.bukkit.ChunkSnapshot;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tìm đường trên thread phụ. Thread chính chụp ảnh các chunk cần thiết (ảnh chụp được dùng lại
 * trong vài giây để không chụp lặp), rồi giao việc A* cho thread phụ. Kết quả được đọc lại ở tick
 * sau trên thread chính. Ảnh chụp đã bất biến nên thread phụ đọc an toàn.
 */
final class AsyncPlanner {

    /** Ảnh chụp dùng lại tối đa chừng này tick (2 giây), rồi phải chụp lại. */
    private static final long SNAPSHOT_TTL_TICKS = 40;
    /** Ảnh chụp quá cũ này sẽ bị bỏ khi bộ nhớ đệm đã lớn. */
    private static final long SNAPSHOT_DROP_TICKS = 200;
    private static final int CACHE_LIMIT = 400;

    private record Snap(ChunkSnapshot snapshot, long tick) {
    }

    /** Ảnh chụp theo thế giới, rồi theo khoá chunk. Chỉ đọc/ghi trên thread chính. */
    private static final Map<UUID, Map<Long, Snap>> CACHE = new HashMap<>();

    /** Một việc tìm đường đã giao cho thread phụ. */
    static final class Job {
        private volatile Pathfinder.Result result;
        private volatile boolean cancelled;

        /** Huỷ: thread phụ vẫn chạy nốt nhưng kết quả bị bỏ. */
        void cancel() {
            cancelled = true;
        }

        /** Kết quả khi đã xong, null nếu còn đang chạy. */
        Pathfinder.Result result() {
            return result;
        }
    }

    private AsyncPlanner() {
    }

    /**
     * Ảnh chụp các chunk trong hình vuông {@code radius} block quanh {@code center}, chỉ các chunk đã nạp.
     * Chỉ gọi trên thread chính.
     */
    static BlockView.SnapshotView viewAround(World world, Pathfinder.Pos center, int radius) {
        long now = Bukkit.getCurrentTick();
        Map<Long, Snap> byChunk = CACHE.computeIfAbsent(world.getUID(), k -> new HashMap<>());
        int minCx = (center.x() - radius) >> 4;
        int maxCx = (center.x() + radius) >> 4;
        int minCz = (center.z() - radius) >> 4;
        int maxCz = (center.z() + radius) >> 4;
        Map<Long, ChunkSnapshot> chunks = new HashMap<>();
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                long key = BlockView.chunkKey(cx, cz);
                Snap s = byChunk.get(key);
                if (s == null || now - s.tick() > SNAPSHOT_TTL_TICKS) {
                    if (!world.isChunkLoaded(cx, cz)) {
                        byChunk.remove(key);
                        continue;
                    }
                    s = new Snap(world.getChunkAt(cx, cz).getChunkSnapshot(false, false, false), now);
                    byChunk.put(key, s);
                }
                chunks.put(key, s.snapshot());
            }
        }
        if (byChunk.size() > CACHE_LIMIT) {
            byChunk.values().removeIf(v -> now - v.tick() > SNAPSHOT_DROP_TICKS);
        }
        return new BlockView.SnapshotView(chunks, world.getMinHeight(), world.getMaxHeight());
    }

    /**
     * Giao một lần tìm đường cho thread phụ. Lần tìm được chạy tới khi xong, không giới hạn số tick.
     * Chỉ dùng goal không gọi API Bukkit (chỉ đọc qua {@link BlockView}).
     */
    static Job submit(Plugin plugin, Pathfinder.Search search) {
        Job job = new Job();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Pathfinder.Result r = new Pathfinder.Result(new ArrayList<>(), false);
            try {
                while (!search.done() && !job.cancelled) {
                    search.step(2000);
                }
                if (search.done()) {
                    r = search.result();
                }
            } catch (RuntimeException e) {
                // Lỗi trên thread phụ: coi như không tới được, để bot thử cách khác thay vì treo.
                r = new Pathfinder.Result(List.of(), false);
            }
            job.result = r;
        });
        return job;
    }
}

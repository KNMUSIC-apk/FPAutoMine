package dev.fpaddon.automine;

import com.coderxi.plugin.fakeplayer.api.action.Action;
import com.coderxi.plugin.fakeplayer.api.entity.FakePlayer;
import com.coderxi.plugin.fakeplayer.api.model.PlayerDetail;
import com.coderxi.plugin.fakeplayer.api.nms.NMSServerPlayer;
import com.coderxi.plugin.fakeplayer.api.nms.NMSServerPlayer.BlockBreakActionType;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.entity.Bat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Phantom;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Trạng thái của MỘT lần auto-mine trên MỘT bot.
 *
 * <p>Vòng lặp mỗi tick: nếu đang đếm lùi (cooldown) thì đứng im; nếu không thì
 * chạy đúng một pha: PLAN (quyết định việc tiếp theo), WALK (đi theo đường đã
 * tính), MINE (đào block), VERIFY (kiểm tra block đã mất để tính số lượng).
 */
final class AutoMineSession {

    private static final int SEARCH_RADIUS_Y = 12;
    private static final int CHEST_RADIUS = 10;
    private static final int PICKUP_RADIUS = 6;
    private static final int MAX_EXPLORE = 60;
    /** cos của nửa góc 60°: block có hướng lệch không quá 60° so với hướng nhìn được coi là "phía trước". */
    private static final double FRONT_COS = 0.5;
    private static final int MAX_KNOWN_CHESTS = 16;
    private static final int MAX_STORE_TRIES = 4;
    private static final int MAX_BUILD_TARGETS = 2;
    private static final int ESCAPE_NODES = 3000;
    /** Đói dưới mức này thì tự ăn; dưới mức nguy kịch mà không có đồ ăn thì dừng. */
    private static final int FOOD_LOW = 14;
    private static final int FOOD_CRITICAL = 6;
    /** Món ăn có hại hoặc gây dịch chuyển (chorus fruit đưa bot đi ngẫu nhiên, có thể vào dung nham/void). */
    private static final Set<Material> NEVER_EAT = Set.of(
            Material.ROTTEN_FLESH, Material.SPIDER_EYE, Material.POISONOUS_POTATO,
            Material.PUFFERFISH, Material.CHICKEN, Material.CHORUS_FRUIT);
    private static final int MAX_WALK_TICKS = 600;
    private static final int BLOCK_COOLDOWN_TICKS = 4;
    private static final int MAX_TARGET_TRIES = 6;
    private static final int MAX_MINING_MISSES = 3;
    private static final int MAX_BREAK_FAILURES = 3;
    private static final double MAX_SCAN_DRIFT = 16.0;
    private static final double REACH_LIMIT = 4.2;

    private enum Phase { PLAN, WALK, MINE, VERIFY }

    private enum WalkKind { BLOCK, CHEST, ITEM, EXPLORE, ESCAPE }

    /** FULL: túi đầy giữa chừng; QUOTA: đủ chỉ tiêu; NOT_FOUND: khám phá hết lượt mà không thấy gì. */
    private enum StoreReason { FULL, QUOTA, NOT_FOUND }

    /** {@code build}: đường được tính ở chế độ build (có thể phải phá block). */
    private record Plan(Block block, List<Pathfinder.Pos> path, boolean build) {
    }

    private final Material material;
    /** Số block cần đào; -1 nghĩa là không giới hạn (đào tới khi túi đầy và không có rương). */
    private final int limit;

    private int mined;
    private int explored;
    private int cooldown;
    private Phase phase = Phase.PLAN;

    // --- đi bộ ---
    private List<Pathfinder.Pos> path = List.of();
    private int pathIndex;
    private WalkKind walkKind;
    private Block walkBlock;
    private Item walkItem;
    private int walkTicks;
    private int stuckCounter;
    private Location lastCheck;
    private StoreReason pendingStore;
    /** Đường hiện tại được tính ở chế độ build: có thể phải phá hoặc đặt block khi đi. */
    private boolean buildPath;
    /** Đang trên đường thoát hiểm (chất lỏng/lửa/ngạt): ưu tiên hơn mọi việc khác. */
    private boolean escaping;

    // --- đào ---
    private Block target;
    private Block breakingBlock;
    private boolean breaking;
    private float progress;
    private int misses;
    private Block verifying;

    // --- bộ nhớ tìm kiếm trong phiên ---
    private final Deque<Block> candidates = new ArrayDeque<>();
    private Location scanOrigin;
    private final Map<Pathfinder.Pos, Integer> breakFailures = new HashMap<>();
    private final Set<Pathfinder.Pos> blacklist = new HashSet<>();
    private final Set<Pathfinder.Pos> fullChests = new HashSet<>();
    private final Set<UUID> skippedItems = new HashSet<>();
    /** Rương được chỉ định cho phiên: rương gần bot lúc bắt đầu, hoặc rương đầu tiên bot dùng để cất đồ. */
    private Block homeChest;
    private boolean homeChecked;
    /** Mọi rương đã thấy trong phiên; chunk của chúng được giữ nạp để bot còn nhớ đường khi đi xa. */
    private final Map<Pathfinder.Pos, Block> knownChests = new LinkedHashMap<>();
    private final Set<Long> ticketChunks = new HashSet<>();
    /** Đếm lùi tới lần quét quái kế tiếp. Lệch ngẫu nhiên để các bot không cùng quét một tick. */
    private int mobSweepIn;

    // --- tìm đường/quét nhiều tick (không chặn server) ---
    /** Lần tìm đường đang dở; chỉ một lần tại một thời điểm, khoá bằng {@link #pendingTag}. */
    private Pathfinder.Search pendingSearch;
    private String pendingTag;
    /** Đang quét block dở qua nhiều tick (quét theo chỉ số ô). */
    private boolean scanning;
    private int scanIndex;
    private int scanBx;
    private int scanBy;
    private int scanBz;
    private int scanMinY;
    private int scanMaxY;
    private List<Block> scanFound;
    /** Block đang được xét để đi tới: đường đi bộ trước, rồi đường build. */
    private Block candidate;
    /** Giai đoạn xét block mục tiêu: 0 = đi bộ bất đồng bộ (xấp xỉ), 1 = đi bộ chính xác, 2 = đường build. */
    private int candidateStage;
    private int targetTries;
    private int targetBuilds;
    /** Điểm khám phá đang đi tới (chọn một lần, giữ nguyên cho tới khi tìm được đường). */
    private Pathfinder.Pos exploreHint;
    /** Bot đang chờ tính đường/quét ở tick này: đứng im và không làm việc khác. */
    private boolean waiting;
    /** Việc tìm đường đang giao cho thread phụ, và tag khoá của nó. */
    private AsyncPlanner.Job asyncJob;
    private String asyncTag;
    /** Quét theo bán kính tăng dần: chỉ số bán kính hiện tại, và bán kính của vòng trước (đã quét xong). */
    private int scanLevel;
    private int scanPrevRadius;

    AutoMineSession(Material material, int limit) {
        this.material = material;
        this.limit = limit;
        this.mobSweepIn = ThreadLocalRandom.current().nextInt(1, Settings.mobSweepTicks + 1);
    }

    String materialName() {
        return material.name().toLowerCase(Locale.ROOT);
    }

    /** Gọi mỗi tick khi bot đang chạy action này. */
    void tick(FakePlayer fp, Action action) {
        Player p = fp.getPlayer();
        if (!p.isValid() || p.isDead()) {
            finish(fp, action, "bot đã chết hoặc không còn hoạt động", false);
            return;
        }
        if (--mobSweepIn <= 0) {
            mobSweepIn = Settings.mobSweepTicks;
            sweepMobs(p);
        }
        NMSServerPlayer nms = fp.getNms();
        // Nguy hiểm trước hết: thoát ra ngay, không làm việc khác.
        if (!escaping && Safety.inDanger(p.getWorld(), p.getLocation())) {
            escape(fp, action);
            return;
        }
        if (!escaping && p.getFoodLevel() <= FOOD_LOW && eatFood(p)) {
            return;
        }
        if (!escaping && p.getFoodLevel() <= FOOD_CRITICAL && foodSlot(p.getInventory()) < 0) {
            finish(fp, action, "đói và không còn đồ ăn trong túi", false);
            return;
        }
        if (cooldown > 0) {
            cooldown--;
            halt(nms);
            return;
        }
        switch (phase) {
            case PLAN -> plan(fp, action);
            case WALK -> walk(fp, action);
            case MINE -> mine(fp, action);
            case VERIFY -> verify(p, nms);
        }
    }

    /** Gọi khi action bị dừng: huỷ thao tác đào đang dở và đứng im. */
    void abort(FakePlayer fp) {
        NMSServerPlayer nms = fp.getNms();
        abortBreak(nms);
        halt(nms);
        releaseTickets();
        resetSearch();
    }

    /** Bỏ mọi lần tìm đường/quét đang dở (khi dừng, hoặc khi cần tính lại từ đầu). */
    private void resetSearch() {
        pendingSearch = null;
        pendingTag = null;
        if (asyncJob != null) {
            asyncJob.cancel();
        }
        asyncJob = null;
        asyncTag = null;
        scanning = false;
        scanFound = null;
        candidate = null;
        candidateStage = 0;
        targetTries = 0;
        targetBuilds = 0;
        exploreHint = null;
    }

    /**
     * Chạy hoặc tiếp tục một lần tìm đường theo ngân sách chung của server. Trả về kết quả khi đã
     * xong; trả về null nếu chưa xong (tick sau gọi lại với cùng {@code tag} để tiếp tục).
     * Tag gồm cả vị trí đứng, nên nếu bot đã đi chỗ khác thì tính lại từ đầu.
     */
    private Pathfinder.Result resumable(String tag, Supplier<Pathfinder.Search> factory) {
        if (pendingSearch == null || !tag.equals(pendingTag)) {
            pendingSearch = factory.get();
            pendingTag = tag;
        }
        int units = Throttle.take(Settings.searchStepNodes * Throttle.expandCost());
        int spent = pendingSearch.step(units / Throttle.expandCost());
        Throttle.refund(units - spent * Throttle.expandCost());
        if (!pendingSearch.done()) {
            return null;
        }
        Pathfinder.Result r = pendingSearch.result();
        pendingSearch = null;
        pendingTag = null;
        return r;
    }

    /**
     * Tìm đường với goal chỉ dựa trên hình học. Khi bật {@code search.async} thì chạy trên thread phụ,
     * ngược lại chạy bằng {@link #resumable} trên thread chính. Trả về null nếu chưa xong.
     */
    private Pathfinder.Result planGeometric(World world, String tag, Pathfinder.Pos start, Pathfinder.Goal goal,
                                            Pathfinder.Pos hint, boolean build, boolean canPlace, boolean bestEffort) {
        if (!Settings.asyncPathfinding) {
            return resumable(tag, () -> new Pathfinder.Search(BlockView.of(world), start, goal, hint,
                    Pathfinder.MAX_NODES, bestEffort, build, canPlace, false));
        }
        return asyncSearch(world, tag, start, view -> new Pathfinder.Search(view, start, goal, hint,
                Pathfinder.MAX_NODES, bestEffort, build, canPlace, false));
    }

    /**
     * Giao một lần tìm đường cho thread phụ, hoặc lấy kết quả nếu đã xong. Tag gồm cả vị trí đứng: nếu bot
     * đã đi chỗ khác thì huỷ việc cũ và giao việc mới. Trả về null khi chưa có kết quả.
     */
    private Pathfinder.Result asyncSearch(World world, String tag, Pathfinder.Pos start,
                                          Function<BlockView, Pathfinder.Search> make) {
        if (asyncJob != null && tag.equals(asyncTag)) {
            Pathfinder.Result r = asyncJob.result();
            if (r == null) {
                return null;
            }
            asyncJob = null;
            asyncTag = null;
            return r;
        }
        if (asyncJob != null) {
            asyncJob.cancel();
        }
        // Chụp (hoặc dùng lại ảnh chụp gần đây của) các chunk trong phạm vi tìm đường, rồi giao việc cho thread phụ.
        BlockView view = AsyncPlanner.viewAround(world, start, Pathfinder.MAX_RADIUS);
        asyncJob = AsyncPlanner.submit(plugin(), make.apply(view));
        asyncTag = tag;
        return null;
    }

    /**
     * Đào theo mạch: sau khi đào xong, nếu một block cùng loại kề bên đào được ngay từ chỗ đứng
     * và túi còn chỗ cho đồ rơi thì đào luôn, không cần tìm đường. Trả về true nếu đã chọn block đó.
     */
    private boolean tryVein(Player p, NMSServerPlayer nms, Pathfinder.Pos from) {
        World world = p.getWorld();
        Pathfinder.Pos stand = Pathfinder.feetCell(p.getLocation());
        double reach = reach(nms);
        int[][] around = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] d : around) {
            int x = from.x() + d[0];
            int y = from.y() + d[1];
            int z = from.z() + d[2];
            if (y < world.getMinHeight() || y >= world.getMaxHeight() || !world.isChunkLoaded(x >> 4, z >> 4)) {
                continue;
            }
            Block nb = world.getBlockAt(x, y, z);
            if (nb.getType() != material || blacklist.contains(Pathfinder.posOf(nb))) {
                continue;
            }
            if (Pathfinder.canMine(world, stand, nb, reach) && canFitDrops(p, nb)) {
                target = nb;
                misses = 0;
                return true;
            }
        }
        return false;
    }

    /** Mô tả ngắn trạng thái hiện tại, cho lệnh {@code /fpautomine status}. */
    String describe() {
        StringBuilder sb = new StringBuilder("đang đào ").append(materialName())
                .append(", đã đào ").append(mined)
                .append(limit < 0 ? " (không giới hạn)" : " / " + limit);
        sb.append("; pha: ").append(switch (phase) {
            case PLAN -> "lập kế hoạch";
            case WALK -> "đi tới " + walkName();
            case MINE -> "đào";
            case VERIFY -> "kiểm tra";
        });
        if (escaping) {
            sb.append("; đang thoát hiểm");
        }
        if (scanning) {
            sb.append("; đang quét block (bán kính ").append(Settings.scanRadii[scanLevel]).append(")");
        }
        if (asyncJob != null || pendingSearch != null) {
            sb.append("; đang tính đường");
        }
        sb.append("; rương đã nhớ ").append(knownChests.size());
        sb.append("; khám phá ").append(explored).append(" / ").append(MAX_EXPLORE);
        return sb.toString();
    }

    private String walkName() {
        if (walkKind == null) {
            return "";
        }
        return switch (walkKind) {
            case BLOCK -> "block";
            case CHEST -> "rương";
            case ITEM -> "đồ rơi";
            case EXPLORE -> "khám phá";
            case ESCAPE -> "thoát hiểm";
        };
    }

    // ------------------------------------------------------------------ PLAN

    private void plan(FakePlayer fp, Action action) {
        Player p = fp.getPlayer();
        NMSServerPlayer nms = fp.getNms();
        World world = p.getWorld();
        Pathfinder.Pos stand = Pathfinder.feetCell(p.getLocation());

        // 0. Chỉ định rương của phiên: rương gần nhất lúc bắt đầu (nếu có).
        if (!homeChecked) {
            homeChecked = true;
            List<Block> near = nearbyChests(p);
            for (Block c : near) {
                rememberChest(c);
            }
            if (!near.isEmpty()) {
                homeChest = near.get(0);
            }
        }

        // 1. Nhặt đồ rơi gần đó trước (block vừa đào rơi xuống đất).
        Item drop = nearestDrop(p);
        if (drop != null) {
            Location dropLoc = drop.getLocation();
            Pathfinder.Result r = planGeometric(world, "drop:" + drop.getUniqueId() + "@" + stand, stand,
                    cell -> Pathfinder.nearPoint(cell, dropLoc), Pathfinder.posAt(dropLoc),
                    false, false, false);
            if (r == null) {
                halt(nms);
                return;
            }
            if (r.reached()) {
                startWalk(WalkKind.ITEM, null, drop, r.path());
            } else {
                skippedItems.add(drop.getUniqueId());
            }
            return;
        }

        // 2. Đủ chỉ tiêu -> cất đồ (nếu có rương) rồi đứng im.
        if (limit >= 0 && mined >= limit) {
            storeThenFinish(fp, action, StoreReason.QUOTA);
            return;
        }

        // 3. Tìm block mục tiêu gần nhất mà bot có thể đi tới và đào.
        Plan plan = findTarget(p, nms, stand);
        if (waiting) {
            halt(nms);
            return;
        }
        if (plan != null) {
            exploreHint = null;
            if (!canFitDrops(p, plan.block())) {
                storeThenFinish(fp, action, StoreReason.FULL);
                return;
            }
            target = plan.block();
            misses = 0;
            if (plan.path().isEmpty()) {
                phase = Phase.MINE;
            } else {
                startWalk(WalkKind.BLOCK, plan.block(), null, plan.path());
                buildPath = plan.build();
            }
            return;
        }

        // 4. Không thấy gì quanh đây -> đi khám phá chỗ khác.
        if (explored >= MAX_EXPLORE) {
            storeThenFinish(fp, action, StoreReason.NOT_FOUND);
            return;
        }
        // Điểm khám phá chọn một lần rồi giữ cho tới khi tìm được đường tới đó.
        if (exploreHint == null) {
            exploreHint = randomHint(stand);
        }
        Pathfinder.Pos hint = exploreHint;
        Pathfinder.Result r = planGeometric(world, "explore:" + hint + "@" + stand, stand,
                cell -> cell.equals(hint), hint, true, canPlaceBlocks(p), true);
        if (r == null) {
            halt(nms);
            return;
        }
        exploreHint = null;
        explored++;
        if (!r.path().isEmpty()) {
            startWalk(WalkKind.EXPLORE, null, null, r.path());
            buildPath = true;
        }
    }

    /**
     * Chọn block mục tiêu gần nhất đi tới được và đào được. Nhiều tick: có thể trả về null với
     * {@link #waiting} = true, nghĩa là đang quét hoặc tìm đường dở và sẽ tiếp tục tick sau.
     */
    private Plan findTarget(Player p, NMSServerPlayer nms, Pathfinder.Pos stand) {
        waiting = false;
        World world = p.getWorld();
        double reach = reach(nms);
        // Ưu tiên tuyệt đối block đào được ngay từ chỗ đứng (đang nhìn thấy, hoặc trong tầm với).
        Block nearby = nearbyTarget(p, reach);
        if (nearby != null) {
            return new Plan(nearby, List.of(), false);
        }
        if (!scanning && (candidates.isEmpty() || scanOrigin == null || scanOrigin.getWorld() != world
                || scanOrigin.distance(p.getLocation()) > MAX_SCAN_DRIFT)) {
            startScan(p);
        }
        if (scanning && !continueScan(p)) {
            waiting = true;
            return null;
        }
        while (true) {
            if (candidate == null) {
                if (candidates.isEmpty() || targetTries >= MAX_TARGET_TRIES) {
                    break;
                }
                Block next = candidates.pollFirst();
                if (blacklist.contains(Pathfinder.posOf(next)) || next.getType() != material) {
                    continue;
                }
                targetTries++;
                if (Pathfinder.canMine(world, stand, next, reach)) {
                    endTargetSearch();
                    return new Plan(next, List.of(), false);
                }
                candidate = next;
                candidateStage = 0;
            }
            Block b = candidate;
            Pathfinder.Pos key = Pathfinder.posOf(b);
            if (candidateStage == 0) {
                // Đi bộ bất đồng bộ tới ô trong tầm có tia nhìn xấp xỉ; đích được kiểm tra lại bằng canMine.
                if (Settings.asyncPathfinding) {
                    Pathfinder.Result r = asyncSearch(world, "walk:" + key + "@" + stand, stand,
                            view -> new Pathfinder.Search(view, stand,
                                    cell -> Pathfinder.inReachOf(cell, key, reach) && Pathfinder.losClear(view, cell, key),
                                    key, Pathfinder.MAX_NODES, false, false, false, false));
                    if (r == null) {
                        waiting = true;
                        return null;
                    }
                    Pathfinder.Pos end = r.path().isEmpty() ? stand : r.path().get(r.path().size() - 1);
                    if (r.reached() && Pathfinder.canMine(world, end, b, reach)) {
                        endTargetSearch();
                        return new Plan(b, r.path(), false);
                    }
                }
                candidateStage = 1;
            }
            if (candidateStage == 1) {
                // Đi bộ chính xác trên thread chính: tia nhìn thật được tính trong goal.
                Pathfinder.Result r = resumable("exact:" + key + "@" + stand,
                        () -> new Pathfinder.Search(BlockView.of(world), stand,
                                cell -> Pathfinder.canMine(world, cell, b, reach),
                                key, Pathfinder.MAX_NODES, false, false, false, false));
                if (r == null) {
                    waiting = true;
                    return null;
                }
                if (r.reached() && !r.path().isEmpty()) {
                    endTargetSearch();
                    return new Plan(b, r.path(), false);
                }
                candidateStage = 2;
            }
            // Không có đường đi bộ: được phép đào xuyên block để tới nguyên liệu.
            if (targetBuilds < MAX_BUILD_TARGETS) {
                Pathfinder.Result built = resumable("build:" + key + "@" + stand,
                        () -> new Pathfinder.Search(BlockView.of(world), stand,
                                cell -> Pathfinder.canMine(world, cell, b, reach),
                                key, Pathfinder.MAX_BUILD_NODES, false, true, canPlaceBlocks(p), false));
                if (built == null) {
                    waiting = true;
                    return null;
                }
                targetBuilds++;
                if (built.reached() && !built.path().isEmpty()) {
                    endTargetSearch();
                    return new Plan(b, built.path(), true);
                }
            }
            blacklist.add(key);
            candidate = null;
            candidateStage = 0;
        }
        endTargetSearch();
        return null;
    }

    /** Kết thúc một đợt chọn block mục tiêu: xoá trạng thái thử nghiệm dở. */
    private void endTargetSearch() {
        candidate = null;
        candidateStage = 0;
        targetTries = 0;
        targetBuilds = 0;
    }

    /**
     * Block đào được ngay từ chỗ đứng, không cần đi. Block phía trước mặt được ưu tiên trước
     * (kể cả block dưới chân ở phía trước); sau đó là các block còn lại theo thứ tự ưu tiên đào.
     */
    private Block nearbyTarget(Player p, double reach) {
        World world = p.getWorld();
        Location loc = p.getLocation();
        Pathfinder.Pos stand = Pathfinder.feetCell(loc);
        // Hướng nhìn theo mặt phẳng ngang (công thức yaw của Minecraft).
        double yaw = Math.toRadians(loc.getYaw());
        double fx = -Math.sin(yaw);
        double fz = Math.cos(yaw);

        int bx = loc.getBlockX();
        int by = loc.getBlockY();
        int bz = loc.getBlockZ();
        int r = (int) Math.ceil(reach) + 1;
        double maxSq = (reach + 1) * (reach + 1);
        List<Block> near = new ArrayList<>();
        for (int x = bx - r; x <= bx + r; x++) {
            for (int z = bz - r; z <= bz + r; z++) {
                if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                    continue;
                }
                for (int y = by - r; y <= by + r; y++) {
                    if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
                        continue;
                    }
                    // Loại ô ngoài hình cầu trước khi tra cứu block (tiết kiệm phần góc của khối lập phương).
                    double dx = x + 0.5 - loc.getX();
                    double dy = y + 0.5 - loc.getY();
                    double dz = z + 0.5 - loc.getZ();
                    if (dx * dx + dy * dy + dz * dz > maxSq) {
                        continue;
                    }
                    Block b = world.getBlockAt(x, y, z);
                    if (b.getType() == material && !blacklist.contains(Pathfinder.posOf(b))) {
                        near.add(b);
                    }
                }
            }
        }
        near.sort(Comparator
                .comparingInt((Block b) -> inFront(b, loc, fx, fz) ? 0 : 1)
                .thenComparing(priority(bx, by, bz)));
        for (Block b : near) {
            if (Pathfinder.canMine(world, stand, b, reach)) {
                return b;
            }
        }
        return null;
    }

    /** Block nằm trong góc 60° phía trước bot (theo mặt phẳng ngang), không tính block ngay dưới/trên chân. */
    private static boolean inFront(Block b, Location loc, double fx, double fz) {
        double vx = b.getX() + 0.5 - loc.getX();
        double vz = b.getZ() + 0.5 - loc.getZ();
        double len = Math.sqrt(vx * vx + vz * vz);
        return len > 0.3 && (vx * fx + vz * fz) / len >= FRONT_COS;
    }

    /** Thứ tự ưu tiên đào quanh (bx, by, bz): lớp gần trước; trong lớp trái→phải, trước→sau, trên→dưới. */
    private static Comparator<Block> priority(int bx, int by, int bz) {
        return Comparator
                .comparingInt((Block b) -> layer(b, bx, by, bz))
                .thenComparingInt(b -> b.getX() - bx)
                .thenComparingInt(b -> b.getZ() - bz)
                .thenComparingInt(b -> by - b.getY());
    }

    /** Bắt đầu quét lại các block cùng loại quanh bot. Việc quét chia nhiều tick (xem {@link #continueScan}). */
    private void startScan(Player p) {
        candidates.clear();
        Location loc = p.getLocation();
        World world = p.getWorld();
        scanBx = loc.getBlockX();
        scanBy = loc.getBlockY();
        scanBz = loc.getBlockZ();
        scanMinY = Math.max(world.getMinHeight(), scanBy - SEARCH_RADIUS_Y);
        scanMaxY = Math.min(world.getMaxHeight() - 1, scanBy + SEARCH_RADIUS_Y);
        scanIndex = 0;
        scanLevel = 0;
        scanPrevRadius = 0;
        scanFound = new ArrayList<>();
        scanOrigin = loc.clone();
        scanning = true;
    }

    /**
     * Quét tiếp các ô (chỉ chunk đã nạp) trong ngân sách còn lại của tick. Trả về true khi quét xong;
     * lúc đó các block được sắp theo thứ tự đào và đưa vào {@link #candidates}.
     */
    private boolean continueScan(Player p) {
        World world = p.getWorld();
        int r = Settings.scanRadii[scanLevel];
        int side = 2 * r + 1;
        int height = scanMaxY - scanMinY + 1;
        int total = side * side * height;
        int units = Throttle.take(Settings.scanUnitsPerStep);
        int before = scanIndex;
        int end = Math.min(total, scanIndex + units);
        for (; scanIndex < end; scanIndex++) {
            // Chỉ số ô -> (x, z, y), đi theo x ngoài cùng, rồi z, rồi y (giống vòng lặp lồng nhau).
            int x = scanBx - r + scanIndex / (side * height);
            int z = scanBz - r + (scanIndex / height) % side;
            int y = scanMinY + scanIndex % height;
            // Ô nằm trong vòng đã quét ở bán kính trước: bỏ qua, không quét lại.
            if (Math.max(Math.abs(x - scanBx), Math.abs(z - scanBz)) <= scanPrevRadius) {
                continue;
            }
            if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                continue;
            }
            Block b = world.getBlockAt(x, y, z);
            Material t = b.getType();
            if (t == material && !blacklist.contains(Pathfinder.posOf(b))) {
                scanFound.add(b);
            } else if (t == Material.CHEST || t == Material.TRAPPED_CHEST) {
                rememberChest(b);
            }
        }
        Throttle.refund(units - (scanIndex - before));
        if (scanIndex < total) {
            return false;
        }
        // Hết vòng bán kính này mà chưa thấy block nào: mở rộng sang bán kính lớn hơn.
        if (scanFound.isEmpty() && scanLevel + 1 < Settings.scanRadii.length) {
            scanPrevRadius = Settings.scanRadii[scanLevel];
            scanLevel++;
            scanIndex = 0;
            return false;
        }
        // Thứ tự đào: theo lớp (khoảng cách Chebyshev) tăng dần; trong cùng lớp theo
        // trái→phải (x), rồi trước→sau (z), rồi trên→dưới (y).
        scanFound.sort(priority(scanBx, scanBy, scanBz));
        candidates.addAll(scanFound);
        scanFound = null;
        scanning = false;
        return true;
    }

    private static int layer(Block b, int bx, int by, int bz) {
        return Math.max(Math.abs(b.getX() - bx), Math.max(Math.abs(b.getY() - by), Math.abs(b.getZ() - bz)));
    }

    /** Điểm ngẫu nhiên cách bot 16–32 block theo phương ngang, để khám phá vùng mới. */
    private static Pathfinder.Pos randomHint(Pathfinder.Pos start) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        double angle = rnd.nextDouble(0, Math.PI * 2);
        int dist = rnd.nextInt(16, 33);
        return new Pathfinder.Pos(
                (int) Math.round(start.x() + Math.cos(angle) * dist),
                start.y(),
                (int) Math.round(start.z() + Math.sin(angle) * dist));
    }

    // ------------------------------------------------------------------ WALK

    private void startWalk(WalkKind kind, Block block, Item item, List<Pathfinder.Pos> newPath) {
        walkKind = kind;
        walkBlock = block;
        walkItem = item;
        path = newPath;
        pathIndex = 0;
        walkTicks = 0;
        stuckCounter = 0;
        lastCheck = null;
        buildPath = false;
        phase = Phase.WALK;
    }

    private void walk(FakePlayer fp, Action action) {
        Player p = fp.getPlayer();
        NMSServerPlayer nms = fp.getNms();
        if (walkKind == WalkKind.BLOCK && (walkBlock == null || walkBlock.getType() != material)) {
            halt(nms);
            target = null;
            phase = Phase.PLAN;
            return;
        }
        if (walkKind == WalkKind.ITEM && (walkItem == null || !walkItem.isValid())) {
            halt(nms);
            phase = Phase.PLAN;
            return;
        }
        if (++walkTicks > MAX_WALK_TICKS) {
            walkFailed(fp, action);
            return;
        }
        if (pathIndex >= path.size()) {
            halt(nms);
            arrive(fp, action);
            return;
        }

        Pathfinder.Pos next = path.get(pathIndex);
        // Kiểm tra lại ô kế tiếp ngay trước khi bước: chất lỏng hoặc block có thể đã đổi từ lúc tính đường.
        if (!Pathfinder.enterable(p.getWorld(), next, buildPath, canPlaceBlocks(p), walkKind == WalkKind.ESCAPE)) {
            walkFailed(fp, action);
            return;
        }
        Location loc = p.getLocation();
        if (buildPath && !prepareStep(p, nms, Pathfinder.feetCell(loc), next)) {
            walkFailed(fp, action);
            return;
        }
        double cx = next.x() + 0.5;
        double cz = next.z() + 0.5;
        double dx = cx - loc.getX();
        double dz = cz - loc.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        boolean last = pathIndex == path.size() - 1;
        if (dist < (last ? 0.35 : 0.45) && Math.abs(next.y() - loc.getY()) < 1.2) {
            pathIndex++;
            return;
        }

        nms.setYRot(Pathfinder.yawTo(dx, dz));
        nms.setXxa(0f);
        nms.setZza(dist < 0.6 ? 0.4f : 1f);
        if (next.y() > Pathfinder.feetCell(loc).y() && nms.getOnGround()) {
            nms.jumpFromGround();
        }

        // Phát hiện kẹt: cứ 10 tick mà không đi được ~0.3 block thì dịch chuyển tới waypoint.
        if (walkTicks % 10 == 0) {
            if (lastCheck != null && lastCheck.getWorld() == loc.getWorld() && loc.distance(lastCheck) < 0.3) {
                if (++stuckCounter >= 2) {
                    p.teleport(new Location(loc.getWorld(), cx, next.y(), cz, loc.getYaw(), 0f));
                    stuckCounter = 0;
                }
            } else {
                stuckCounter = 0;
            }
            lastCheck = loc.clone();
        }
    }

    private void walkFailed(FakePlayer fp, Action action) {
        halt(fp.getNms());
        switch (walkKind) {
            case BLOCK -> {
                blacklist.add(Pathfinder.posOf(walkBlock));
                target = null;
                phase = Phase.PLAN;
            }
            case CHEST -> {
                fullChests.add(Pathfinder.posOf(walkBlock));
                storeThenFinish(fp, action, pendingStore == null ? StoreReason.FULL : pendingStore);
            }
            case ITEM -> {
                skippedItems.add(walkItem.getUniqueId());
                phase = Phase.PLAN;
            }
            case EXPLORE -> phase = Phase.PLAN;
            case ESCAPE -> {
                escaping = false;
                phase = Phase.PLAN;
            }
        }
    }

    private void arrive(FakePlayer fp, Action action) {
        switch (walkKind) {
            case BLOCK -> phase = Phase.MINE;
            case CHEST -> depositAt(fp, action);
            case ITEM -> {
                collectDrops(fp.getPlayer());
                // Vẫn còn trên đất (túi đầy?) thì bỏ qua để không quay lại nhặt mãi.
                if (walkItem != null && walkItem.isValid()) {
                    skippedItems.add(walkItem.getUniqueId());
                }
                phase = Phase.PLAN;
            }
            case EXPLORE -> phase = Phase.PLAN;
            case ESCAPE -> {
                escaping = false;
                phase = Phase.PLAN;
            }
        }
    }

    // -------------------------------------------- PHÁ / ĐẶT BLOCK KHI ĐI (build)

    /**
     * Chuẩn bị bước sang ô {@code next} trên đường build: phá block chắn ở chân và đầu,
     * phá block trên đầu bot khi phải nhảy lên, đặt block lấp đất đỡ nếu thiếu.
     * Trả về false nếu không làm được (không đủ block để đặt, hoặc block quá xa).
     */
    private boolean prepareStep(Player p, NMSServerPlayer nms, Pathfinder.Pos from, Pathfinder.Pos next) {
        World w = p.getWorld();
        if (next.y() > from.y() && !Pathfinder.passable(w, from.x(), from.y() + 2, from.z())
                && !clearBlock(p, nms, w.getBlockAt(from.x(), from.y() + 2, from.z()), from)) {
            return false;
        }
        if (!Pathfinder.passable(w, next.x(), next.y(), next.z())
                && !clearBlock(p, nms, w.getBlockAt(next.x(), next.y(), next.z()), from)) {
            return false;
        }
        if (!Pathfinder.passable(w, next.x(), next.y() + 1, next.z())
                && !clearBlock(p, nms, w.getBlockAt(next.x(), next.y() + 1, next.z()), from)) {
            return false;
        }
        if (!Pathfinder.supports(w, next.x(), next.y() - 1, next.z())) {
            return placeSupport(p, w.getBlockAt(next.x(), next.y() - 1, next.z()));
        }
        return true;
    }

    /**
     * Thoát hiểm ngay. Nếu đầu hoặc chân nằm trong block đặc (ngạt) thì phá block đó trước, không cần
     * kiểm tra chất lỏng kề vì ngạt là nguy hiểm tức thời. Nếu đang ở chất lỏng/lửa thì đi tới ô đứng
     * an toàn gần nhất, được phép đi qua chất lỏng để ra ngoài.
     */
    private void escape(FakePlayer fp, Action action) {
        Player p = fp.getPlayer();
        NMSServerPlayer nms = fp.getNms();
        World world = p.getWorld();
        abortBreak(nms);
        halt(nms);
        Pathfinder.Pos c = Pathfinder.feetCell(p.getLocation());
        for (int dy = 1; dy >= 0; dy--) {
            Block b = world.getBlockAt(c.x(), c.y() + dy, c.z());
            if (Pathfinder.breakableNow(b) && b.getLocation().distance(p.getLocation()) <= REACH_LIMIT + 1.0) {
                selectTool(p, nms, b);
                b.breakNaturally(p.getInventory().getItemInMainHand());
                return;
            }
        }
        Pathfinder.Result r = Pathfinder.findEscape(world, c, cell -> Pathfinder.walkable(world, cell),
                ESCAPE_NODES, canPlaceBlocks(p));
        if (!r.path().isEmpty()) {
            startWalk(WalkKind.ESCAPE, null, null, r.path());
            buildPath = true;
            escaping = true;
        }
    }

    /** Chỉ số ô trong túi có đồ ăn bot được phép ăn, hoặc -1. */
    private static int foodSlot(PlayerInventory inv) {
        for (int i = 0; i < 36; i++) {
            ItemStack it = inv.getItem(i);
            if (it != null && it.getType().isEdible() && !NEVER_EAT.contains(it.getType())) {
                return i;
            }
        }
        return -1;
    }

    /** Ăn một món khi đói (ngay trong tick). Trả về true nếu đã ăn. */
    private static boolean eatFood(Player p) {
        PlayerInventory inv = p.getInventory();
        int slot = foodSlot(inv);
        if (slot < 0) {
            return false;
        }
        ItemStack it = inv.getItem(slot);
        int nutrition = 4;
        float saturation = 2.4f;
        ItemMeta meta = it.getItemMeta();
        if (meta != null && meta.getFood() != null) {
            nutrition = meta.getFood().getNutrition();
            saturation = meta.getFood().getSaturation();
        }
        p.setFoodLevel(Math.min(20, p.getFoodLevel() + nutrition));
        p.setSaturation(Math.min(20f, p.getSaturation() + saturation));
        if (it.getAmount() <= 1) {
            inv.setItem(slot, null);
        } else {
            it.setAmount(it.getAmount() - 1);
            inv.setItem(slot, it);
        }
        return true;
    }

    /** Phá một block chắn bằng công cụ tốt nhất trong túi; block rơi ra được nhặt sau. Chỉ phá khi an toàn. */
    private boolean clearBlock(Player p, NMSServerPlayer nms, Block b, Pathfinder.Pos stand) {
        if (!Pathfinder.clearable(b.getWorld(), b.getX(), b.getY(), b.getZ())
                || b.getLocation().distance(p.getLocation()) > REACH_LIMIT + 1.0
                || !Safety.breakSafe(BlockView.of(b.getWorld()), stand, b)) {
            return false;
        }
        selectTool(p, nms, b);
        b.breakNaturally(p.getInventory().getItemInMainHand());
        return true;
    }

    /** Đặt một block từ túi vào ô đất đỡ bị hổng. */
    private static boolean placeSupport(Player p, Block support) {
        if (support.getLocation().distance(p.getLocation()) > REACH_LIMIT + 1.0) {
            return false;
        }
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getItem(i);
            if (s == null || !canPlaceAsSupport(s.getType())) {
                continue;
            }
            support.setType(s.getType(), true);
            if (s.getAmount() <= 1) {
                inv.setItem(i, null);
            } else {
                s.setAmount(s.getAmount() - 1);
                inv.setItem(i, s);
            }
            return true;
        }
        return false;
    }

    /** Block dùng để lấp hổng: chất rắn, không bị rơi (cát, sỏi), không phải vật chứa. */
    private static boolean canPlaceAsSupport(Material m) {
        return m.isBlock() && m.isSolid() && !m.hasGravity() && !Pathfinder.protectedBlock(m);
    }

    private static boolean canPlaceBlocks(Player p) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getItem(i);
            if (s != null && canPlaceAsSupport(s.getType())) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ MINE

    private void mine(FakePlayer fp, Action action) {
        Player p = fp.getPlayer();
        NMSServerPlayer nms = fp.getNms();
        halt(nms);
        Block t = target;
        if (t == null || t.getType() != material) {
            abortBreak(nms);
            target = null;
            phase = Phase.PLAN;
            return;
        }
        if (!Pathfinder.canMine(p.getWorld(), Pathfinder.feetCell(p.getLocation()), t, reach(nms))) {
            abortBreak(nms);
            if (++misses >= MAX_MINING_MISSES) {
                blacklist.add(Pathfinder.posOf(t));
                target = null;
            }
            phase = Phase.PLAN;
            return;
        }

        faceBlock(p, nms, t);
        p.swingMainHand();
        if (!breaking) {
            selectTool(p, nms, t);
            nms.doBlockBreakAction(t, BlockBreakActionType.START);
            breaking = true;
            breakingBlock = t;
            progress = 0f;
        }
        progress += nms.getDestroyProgress(t);
        if (progress >= 1.0f) {
            nms.doBlockBreakAction(t, BlockBreakActionType.STOP);
            breaking = false;
            breakingBlock = null;
            progress = 0f;
            verifying = t;
            target = null;
            phase = Phase.VERIFY;
        }
    }

    private void verify(Player p, NMSServerPlayer nms) {
        Block t = verifying;
        verifying = null;
        if (t == null) {
            phase = Phase.PLAN;
            return;
        }
        Pathfinder.Pos key = Pathfinder.posOf(t);
        if (t.getType() != material) {
            mined++;
            explored = 0;
            misses = 0;
            breakFailures.remove(key);
            cooldown = BLOCK_COOLDOWN_TICKS;
            // Đào theo mạch: block cùng loại kề bên đào được ngay thì đào luôn, khỏi tìm đường.
            if (Settings.veinMining && (limit < 0 || mined < limit) && tryVein(p, nms, key)) {
                phase = Phase.MINE;
                return;
            }
        } else if (breakFailures.merge(key, 1, Integer::sum) >= MAX_BREAK_FAILURES) {
            blacklist.add(key);
        }
        phase = Phase.PLAN;
    }

    private void abortBreak(NMSServerPlayer nms) {
        if (breaking && breakingBlock != null) {
            nms.doBlockBreakAction(breakingBlock, BlockBreakActionType.ABORT);
        }
        breaking = false;
        breakingBlock = null;
        progress = 0f;
    }

    private void selectTool(Player p, NMSServerPlayer nms, Block block) {
        Integer slot = nms.findBestToolSlot(block);
        if (slot != null && slot >= 0 && slot <= 8 && p.getInventory().getHeldItemSlot() != slot) {
            p.getInventory().setHeldItemSlot(slot);
        }
    }

    private static void faceBlock(Player p, NMSServerPlayer nms, Block block) {
        Location eye = p.getEyeLocation();
        double dx = block.getX() + 0.5 - eye.getX();
        double dy = block.getY() + 0.5 - eye.getY();
        double dz = block.getZ() + 0.5 - eye.getZ();
        nms.setYRot(Pathfinder.yawTo(dx, dz));
        nms.setXRot(Pathfinder.pitchTo(dx, dy, dz));
    }

    // ----------------------------------------------------- ĐỒ RƠI / TÚI ĐỒ

    private Item nearestDrop(Player p) {
        Location loc = p.getLocation();
        Item best = null;
        double bestDist = Double.MAX_VALUE;
        for (Entity e : p.getNearbyEntities(PICKUP_RADIUS, 3, PICKUP_RADIUS)) {
            if (!(e instanceof Item item) || !item.isValid() || skippedItems.contains(item.getUniqueId())) {
                continue;
            }
            double d = item.getLocation().distanceSquared(loc);
            if (d < bestDist) {
                best = item;
                bestDist = d;
            }
        }
        return best;
    }

    /** Nhặt thủ công các đồ rơi trong tầm với (không phụ thuộc cài đặt pickup và delay của item). */
    private void collectDrops(Player p) {
        for (Entity e : p.getNearbyEntities(1.5, 1.5, 1.5)) {
            if (!(e instanceof Item item) || !item.isValid()) {
                continue;
            }
            ItemStack stack = item.getItemStack();
            Map<Integer, ItemStack> left = p.getInventory().addItem(stack.clone());
            if (left.isEmpty()) {
                item.remove();
            } else {
                item.setItemStack(left.values().iterator().next());
                skippedItems.add(item.getUniqueId());
            }
        }
    }

    /** Kiểm tra toàn bộ đồ rơi của block có vừa vào túi không (mô phỏng cộng dồn). */
    private boolean canFitDrops(Player p, Block block) {
        PlayerInventory inv = p.getInventory();
        Collection<ItemStack> drops = block.getDrops(inv.getItemInMainHand(), p);
        ItemStack[] sim = new ItemStack[36];
        for (int i = 0; i < 36; i++) {
            ItemStack it = inv.getItem(i);
            sim[i] = (it == null || it.getType().isAir()) ? null : it.clone();
        }
        for (ItemStack drop : drops) {
            int remaining = drop.getAmount();
            for (int i = 0; i < 36 && remaining > 0; i++) {
                ItemStack s = sim[i];
                if (s == null) {
                    int put = Math.min(remaining, drop.getMaxStackSize());
                    ItemStack fresh = drop.clone();
                    fresh.setAmount(put);
                    sim[i] = fresh;
                    remaining -= put;
                } else if (s.isSimilar(drop) && s.getAmount() < s.getMaxStackSize()) {
                    int put = Math.min(remaining, s.getMaxStackSize() - s.getAmount());
                    s.setAmount(s.getAmount() + put);
                    remaining -= put;
                }
            }
            if (remaining > 0) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------- CẤT ĐỒ VÀO RƯƠNG

    /**
     * Cất đồ rồi kết thúc. Khi kết thúc nhiệm vụ (đủ chỉ tiêu, hoặc khám phá hết mà không thấy gì),
     * bot về rương đã chỉ định dù đang ở xa, cất toàn bộ đồ, rồi biến mất nếu đã cất hết.
     * Khi túi đầy giữa chừng, chỉ dùng rương trong {@link #CHEST_RADIUS} block.
     */
    private void storeThenFinish(FakePlayer fp, Action action, StoreReason reason) {
        Player p = fp.getPlayer();
        World world = p.getWorld();
        Pathfinder.Pos stand = Pathfinder.feetCell(p.getLocation());
        boolean ending = reason != StoreReason.FULL;
        if (ending && bagEmpty(p)) {
            finish(fp, action, endReason(reason) + ", không còn đồ để cất", true);
            return;
        }
        int tries = 0;
        if (ending && homeChest != null && usableChest(world, homeChest)) {
            tries++;
            if (startStoreWalk(p, stand, homeChest, reason)) {
                return;
            }
        }
        for (Block chest : storeCandidates(p, ending)) {
            if (tries++ >= MAX_STORE_TRIES) {
                break;
            }
            if (startStoreWalk(p, stand, chest, reason)) {
                return;
            }
        }
        pendingStore = null;
        String why = ending
                ? endReason(reason) + ", không tới được rương nên bot giữ đồ trong túi"
                : "túi đầy và không có rương trong " + CHEST_RADIUS + " block";
        finish(fp, action, why, false);
    }

    /** Rương có thể dùng để cất đồ: chunk đang nạp, vẫn là rương, và chưa bị đánh dấu đầy/không tới được. */
    private boolean usableChest(World world, Block b) {
        if (b.getWorld() != world || !world.isChunkLoaded(b.getX() >> 4, b.getZ() >> 4)) {
            return false;
        }
        return isChest(b) && !fullChests.contains(Pathfinder.posOf(b));
    }

    /**
     * Các rương ứng viên để cất đồ, gần bot trước. Khi kết thúc nhiệm vụ thì cộng thêm mọi rương đã nhớ
     * trong phiên, kể cả ở xa.
     */
    private List<Block> storeCandidates(Player p, boolean ending) {
        World world = p.getWorld();
        Location loc = p.getLocation();
        Map<Pathfinder.Pos, Block> byPos = new LinkedHashMap<>();
        if (ending) {
            for (Block c : knownChests.values()) {
                if (usableChest(world, c)) {
                    byPos.putIfAbsent(Pathfinder.posOf(c), c);
                }
            }
        }
        for (Block c : nearbyChests(p)) {
            if (usableChest(world, c)) {
                byPos.putIfAbsent(Pathfinder.posOf(c), c);
            }
        }
        List<Block> out = new ArrayList<>(byPos.values());
        out.sort(Comparator.comparingDouble(b -> distanceSquared(b, loc)));
        return out;
    }

    /** Nhớ một rương trong phiên và nạp chunk của nó (ticket của plugin) để bot còn tới được khi đi xa. */
    private void rememberChest(Block b) {
        Pathfinder.Pos key = Pathfinder.posOf(b);
        if (knownChests.containsKey(key) || knownChests.size() >= MAX_KNOWN_CHESTS) {
            return;
        }
        knownChests.put(key, b);
        int cx = b.getX() >> 4;
        int cz = b.getZ() >> 4;
        long chunkKey = ((long) cx << 32) ^ (cz & 0xffffffffL);
        if (ticketChunks.add(chunkKey)) {
            b.getWorld().addPluginChunkTicket(cx, cz, plugin());
        }
    }

    /** Trả lại mọi ticket chunk đã giữ cho các rương của phiên. */
    private void releaseTickets() {
        if (knownChests.isEmpty() && ticketChunks.isEmpty()) {
            return;
        }
        Plugin plugin = plugin();
        for (Block b : knownChests.values()) {
            b.getWorld().removePluginChunkTicket(b.getX() >> 4, b.getZ() >> 4, plugin);
        }
        knownChests.clear();
        ticketChunks.clear();
        homeChest = null;
    }

    private String endReason(StoreReason reason) {
        return reason == StoreReason.QUOTA
                ? "đủ " + limit + " block"
                : "không tìm thấy " + materialName() + " sau " + MAX_EXPLORE + " lần khám phá";
    }

    private static Plugin plugin() {
        return JavaPlugin.getProvidingPlugin(AutoMineSession.class);
    }

    /**
     * Xoá quái (và dơi) trong bán kính {@link #MOB_SWEEP_RADIUS} quanh bot. Bỏ qua mob đang ngồi
     * thuyền/xe hoặc đang chở mob khác.
     */
    private static void sweepMobs(Player p) {
        Location loc = p.getLocation();
        double r = Settings.mobSweepRadius;
        double maxSq = r * r;
        for (Entity e : p.getWorld().getNearbyEntities(loc, r, r, r)) {
            if (!(e instanceof Monster || e instanceof Bat || e instanceof Phantom)) {
                continue;
            }
            if (e.isInsideVehicle() || !e.getPassengers().isEmpty()) {
                continue;
            }
            if (e.getLocation().distanceSquared(loc) > maxSq) {
                continue;
            }
            e.remove();
        }
    }

    /**
     * Lập đường tới rương để cất đồ: đi bộ, hoặc phá/đặt block nếu cần. Nếu không tới được thì
     * đánh dấu rương và trả về false.
     */
    private boolean startStoreWalk(Player p, Pathfinder.Pos stand, Block chest, StoreReason reason) {
        World world = p.getWorld();
        Pathfinder.Pos key = Pathfinder.posOf(chest);
        Pathfinder.Result r = Pathfinder.find(world, stand,
                cell -> Pathfinder.distToCenter(cell, chest) <= 3.0, key, Pathfinder.MAX_NODES, false);
        if (r.reached()) {
            pendingStore = reason;
            startWalk(WalkKind.CHEST, chest, null, r.path());
            rememberHome(chest);
            return true;
        }
        // Không với tới bằng đường đi bộ: thử đường được phép phá và đặt block.
        Pathfinder.Result built = Pathfinder.findBuild(world, stand,
                cell -> Pathfinder.distToCenter(cell, chest) <= 3.0, key, Pathfinder.MAX_BUILD_NODES,
                canPlaceBlocks(p));
        if (built.reached()) {
            pendingStore = reason;
            startWalk(WalkKind.CHEST, chest, null, built.path());
            buildPath = true;
            rememberHome(chest);
            return true;
        }
        fullChests.add(key);
        return false;
    }

    /** Rương đầu tiên bot dùng để cất đồ trở thành rương đã chỉ định (nếu lúc bắt đầu chưa có). */
    private void rememberHome(Block chest) {
        rememberChest(chest);
        if (homeChest == null) {
            homeChest = chest;
        }
    }

    private static boolean isChest(Block b) {
        Material m = b.getType();
        return m == Material.CHEST || m == Material.TRAPPED_CHEST;
    }

    private List<Block> nearbyChests(Player p) {
        World world = p.getWorld();
        Location loc = p.getLocation();
        int bx = loc.getBlockX();
        int by = loc.getBlockY();
        int bz = loc.getBlockZ();
        double maxSq = CHEST_RADIUS * CHEST_RADIUS;
        List<Block> chests = new ArrayList<>();
        for (int x = bx - CHEST_RADIUS; x <= bx + CHEST_RADIUS; x++) {
            for (int z = bz - CHEST_RADIUS; z <= bz + CHEST_RADIUS; z++) {
                if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                    continue;
                }
                for (int y = by - CHEST_RADIUS; y <= by + CHEST_RADIUS; y++) {
                    if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
                        continue;
                    }
                    double dx = x + 0.5 - loc.getX();
                    double dy = y + 0.5 - loc.getY();
                    double dz = z + 0.5 - loc.getZ();
                    if (dx * dx + dy * dy + dz * dz > maxSq) {
                        continue;
                    }
                    Block b = world.getBlockAt(x, y, z);
                    Material m = b.getType();
                    if (m == Material.CHEST || m == Material.TRAPPED_CHEST) {
                        chests.add(b);
                    }
                }
            }
        }
        chests.sort(Comparator.comparingDouble(b -> distanceSquared(b, loc)));
        return chests;
    }

    private void depositAt(FakePlayer fp, Action action) {
        Player p = fp.getPlayer();
        Block chestBlock = walkBlock;
        StoreReason reason = pendingStore == null ? StoreReason.FULL : pendingStore;
        pendingStore = null;

        int moved = 0;
        BlockState state = chestBlock.getState();
        if (state instanceof Chest chest) {
            moved = transferToChest(p, chest.getInventory(), reason != StoreReason.FULL);
        }
        if (moved == 0) {
            fullChests.add(Pathfinder.posOf(chestBlock));
        } else {
            skippedItems.clear();
        }

        if (reason != StoreReason.FULL) {
            if (bagEmpty(p)) {
                finish(fp, action, endReason(reason) + ", đã cất toàn bộ đồ vào rương", true);
            } else {
                finish(fp, action, endReason(reason) + (moved > 0
                        ? ", phần đồ còn lại không vừa rương nên bot giữ lại"
                        : ", rương đầy nên bot giữ đồ"), false);
            }
            return;
        }
        phase = Phase.PLAN;
    }

    /**
     * Chuyển đồ trong túi vào rương. Khi {@code everything} là false thì giữ lại công cụ và đồ ăn
     * để bot tiếp tục đào. Trả về số lượng đã chuyển.
     */
    private static int transferToChest(Player p, Inventory chestInv, boolean everything) {
        PlayerInventory bag = p.getInventory();
        int moved = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack it = bag.getItem(i);
            if (it == null || it.getType().isAir() || (!everything && keepInBag(it.getType()))) {
                continue;
            }
            int amount = it.getAmount();
            Map<Integer, ItemStack> left = chestInv.addItem(it.clone());
            int leftAmount = 0;
            for (ItemStack rest : left.values()) {
                leftAmount += rest.getAmount();
            }
            if (leftAmount == 0) {
                bag.setItem(i, null);
            } else {
                ItemStack rest = it.clone();
                rest.setAmount(leftAmount);
                bag.setItem(i, rest);
            }
            moved += amount - leftAmount;
        }
        return moved;
    }

    private static boolean bagEmpty(Player p) {
        PlayerInventory bag = p.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack it = bag.getItem(i);
            if (it != null && !it.getType().isAir()) {
                return false;
            }
        }
        return true;
    }

    /** Công cụ và đồ ăn giữ lại trong túi bot khi đang đào (túi đầy giữa chừng). */
    private static boolean keepInBag(Material m) {
        if (m.isEdible()) {
            return true;
        }
        String n = m.name();
        return n.endsWith("_PICKAXE") || n.endsWith("_AXE") || n.endsWith("_SHOVEL")
                || n.endsWith("_HOE") || n.endsWith("_SWORD")
                || n.equals("SHEARS") || n.equals("SHIELD") || n.equals("BOW")
                || n.equals("CROSSBOW") || n.equals("ELYTRA");
    }

    // ------------------------------------------------------------ KẾT THÚC

    /**
     * Kết thúc phiên. Nếu {@code despawn} thì xoá bot (sau tick hiện tại), giống {@code /fp remove}.
     */
    private void finish(FakePlayer fp, Action action, String reason, boolean despawn) {
        abort(fp);
        notifyOwners(fp, "§a[AutoMine]§r " + fp.getName() + ": dừng đào " + materialName()
                + " (" + reason + "). Đã đào " + mined + " block.");
        fp.getActions().stop(action);
        if (despawn) {
            Bukkit.getScheduler().runTask(plugin(), () -> fp.quit("AutoMine: đã xong nhiệm vụ"));
        }
    }

    static void notifyOwners(FakePlayer fp, String message) {
        for (PlayerDetail owner : fp.getOwners()) {
            Player op = Bukkit.getPlayer(owner.getUuid());
            if (op != null) {
                op.sendMessage(message);
            }
        }
    }

    private void halt(NMSServerPlayer nms) {
        nms.setZza(0f);
        nms.setXxa(0f);
    }

    private static double reach(NMSServerPlayer nms) {
        return Math.min(REACH_LIMIT, nms.getBlockReachDistance() - 0.3);
    }

    private static double distanceSquared(Block b, Location loc) {
        double dx = b.getX() + 0.5 - loc.getX();
        double dy = b.getY() + 0.5 - loc.getY();
        double dz = b.getZ() + 0.5 - loc.getZ();
        return dx * dx + dy * dy + dz * dz;
    }
}

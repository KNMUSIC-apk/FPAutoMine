package dev.fpaddon.automine;

import com.coderxi.plugin.fakeplayer.api.action.Action;
import com.coderxi.plugin.fakeplayer.api.entity.FakePlayer;
import com.coderxi.plugin.fakeplayer.api.model.PlayerDetail;
import com.coderxi.plugin.fakeplayer.api.nms.NMSServerPlayer;
import com.coderxi.plugin.fakeplayer.api.nms.NMSServerPlayer.BlockBreakActionType;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.util.RayTraceResult;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Trạng thái của MỘT lần auto-mine trên MỘT bot.
 *
 * <p>Vòng lặp mỗi tick: nếu đang đếm lùi (cooldown) thì đứng im; nếu không thì
 * chạy đúng một pha: PLAN (quyết định việc tiếp theo), WALK (đi theo đường đã
 * tính), MINE (đào block), VERIFY (kiểm tra block đã mất để tính số lượng).
 */
final class AutoMineSession {

    private static final int SEARCH_RADIUS_XZ = 24;
    private static final int SEARCH_RADIUS_Y = 12;
    private static final int CHEST_RADIUS = 10;
    private static final int PICKUP_RADIUS = 6;
    private static final int MAX_EXPLORE = 30;
    private static final int MAX_WALK_TICKS = 600;
    private static final int BLOCK_COOLDOWN_TICKS = 4;
    private static final int MAX_TARGET_TRIES = 6;
    private static final int MAX_MINING_MISSES = 3;
    private static final int MAX_BREAK_FAILURES = 3;
    private static final double MAX_SCAN_DRIFT = 16.0;
    private static final double REACH_LIMIT = 4.2;

    private enum Phase { PLAN, WALK, MINE, VERIFY }

    private enum WalkKind { BLOCK, CHEST, ITEM, EXPLORE }

    private enum StoreReason { FULL, QUOTA }

    private record Plan(Block block, List<Pathfinder.Pos> path) {
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

    AutoMineSession(Material material, int limit) {
        this.material = material;
        this.limit = limit;
    }

    String materialName() {
        return material.name().toLowerCase(Locale.ROOT);
    }

    /** Gọi mỗi tick khi bot đang chạy action này. */
    void tick(FakePlayer fp, Action action) {
        Player p = fp.getPlayer();
        if (!p.isValid() || p.isDead()) {
            finish(fp, action, "bot đã chết hoặc không còn hoạt động");
            return;
        }
        NMSServerPlayer nms = fp.getNms();
        if (cooldown > 0) {
            cooldown--;
            halt(nms);
            return;
        }
        switch (phase) {
            case PLAN -> plan(fp, action);
            case WALK -> walk(fp, action);
            case MINE -> mine(fp, action);
            case VERIFY -> verify();
        }
    }

    /** Gọi khi action bị dừng: huỷ thao tác đào đang dở và đứng im. */
    void abort(FakePlayer fp) {
        NMSServerPlayer nms = fp.getNms();
        abortBreak(nms);
        halt(nms);
    }

    // ------------------------------------------------------------------ PLAN

    private void plan(FakePlayer fp, Action action) {
        Player p = fp.getPlayer();
        NMSServerPlayer nms = fp.getNms();
        World world = p.getWorld();
        Pathfinder.Pos stand = Pathfinder.feetCell(p.getLocation());

        // 1. Nhặt đồ rơi gần đó trước (block vừa đào rơi xuống đất).
        Item drop = nearestDrop(p);
        if (drop != null) {
            Location dropLoc = drop.getLocation();
            Pathfinder.Result r = Pathfinder.find(world, stand,
                    cell -> Pathfinder.nearPoint(cell, dropLoc), Pathfinder.posAt(dropLoc),
                    Pathfinder.MAX_NODES, false);
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
        if (plan != null) {
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
            }
            return;
        }

        // 4. Không thấy gì quanh đây -> đi khám phá chỗ khác.
        if (explored >= MAX_EXPLORE) {
            finish(fp, action, "không tìm thấy " + materialName() + " sau " + MAX_EXPLORE + " lần khám phá");
            return;
        }
        explored++;
        startExplore(p);
    }

    private Plan findTarget(Player p, NMSServerPlayer nms, Pathfinder.Pos stand) {
        World world = p.getWorld();
        double reach = reach(nms);
        // Ưu tiên tuyệt đối block đào được ngay từ chỗ đứng (đang nhìn thấy, hoặc trong tầm với).
        Block nearby = nearbyTarget(p, reach);
        if (nearby != null) {
            return new Plan(nearby, List.of());
        }
        if (candidates.isEmpty() || scanOrigin == null || scanOrigin.getWorld() != world
                || scanOrigin.distance(p.getLocation()) > MAX_SCAN_DRIFT) {
            scan(p);
        }
        int tries = 0;
        while (!candidates.isEmpty() && tries < MAX_TARGET_TRIES) {
            Block b = candidates.pollFirst();
            Pathfinder.Pos key = Pathfinder.posOf(b);
            if (blacklist.contains(key) || b.getType() != material) {
                continue;
            }
            tries++;
            if (Pathfinder.canMine(world, stand, b, reach)) {
                return new Plan(b, List.of());
            }
            Pathfinder.Result r = Pathfinder.find(world, stand,
                    cell -> Pathfinder.canMine(world, cell, b, reach), key, Pathfinder.MAX_NODES, false);
            if (r.reached() && !r.path().isEmpty()) {
                return new Plan(b, r.path());
            }
            blacklist.add(key);
        }
        return null;
    }

    /**
     * Block đào được ngay từ chỗ đứng, không cần đi: ưu tiên block bot đang nhìn thấy trước mặt,
     * sau đó là các block trong tầm với theo thứ tự ưu tiên đào.
     */
    private Block nearbyTarget(Player p, double reach) {
        World world = p.getWorld();
        Location loc = p.getLocation();
        Pathfinder.Pos stand = Pathfinder.feetCell(loc);
        Location eye = p.getEyeLocation();

        RayTraceResult look = world.rayTraceBlocks(eye, eye.getDirection(), reach,
                FluidCollisionMode.NEVER, true);
        if (look != null && look.getHitBlock() != null) {
            Block facing = look.getHitBlock();
            if (facing.getType() == material && !blacklist.contains(Pathfinder.posOf(facing))
                    && Pathfinder.canMine(world, stand, facing, reach)) {
                return facing;
            }
        }

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
                    Block b = world.getBlockAt(x, y, z);
                    if (b.getType() == material && !blacklist.contains(Pathfinder.posOf(b))
                            && distanceSquared(b, loc) <= maxSq) {
                        near.add(b);
                    }
                }
            }
        }
        near.sort(priority(bx, by, bz));
        for (Block b : near) {
            if (Pathfinder.canMine(world, stand, b, reach)) {
                return b;
            }
        }
        return null;
    }

    /** Thứ tự ưu tiên đào quanh (bx, by, bz): lớp gần trước; trong lớp trái→phải, trước→sau, trên→dưới. */
    private static Comparator<Block> priority(int bx, int by, int bz) {
        return Comparator
                .comparingInt((Block b) -> layer(b, bx, by, bz))
                .thenComparingInt(b -> b.getX() - bx)
                .thenComparingInt(b -> b.getZ() - bz)
                .thenComparingInt(b -> by - b.getY());
    }

    /** Quét các block cùng loại quanh bot (chỉ chunk đã load), sắp theo thứ tự ưu tiên đào. */
    private void scan(Player p) {
        candidates.clear();
        World world = p.getWorld();
        Location loc = p.getLocation();
        int bx = loc.getBlockX();
        int by = loc.getBlockY();
        int bz = loc.getBlockZ();
        int minY = Math.max(world.getMinHeight(), by - SEARCH_RADIUS_Y);
        int maxY = Math.min(world.getMaxHeight() - 1, by + SEARCH_RADIUS_Y);
        List<Block> found = new ArrayList<>();
        for (int x = bx - SEARCH_RADIUS_XZ; x <= bx + SEARCH_RADIUS_XZ; x++) {
            for (int z = bz - SEARCH_RADIUS_XZ; z <= bz + SEARCH_RADIUS_XZ; z++) {
                if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                    continue;
                }
                for (int y = minY; y <= maxY; y++) {
                    Block b = world.getBlockAt(x, y, z);
                    if (b.getType() == material && !blacklist.contains(Pathfinder.posOf(b))) {
                        found.add(b);
                    }
                }
            }
        }
        // Thứ tự đào: theo lớp (khoảng cách Chebyshev) tăng dần; trong cùng lớp theo
        // trái→phải (x), rồi trước→sau (z), rồi trên→dưới (y).
        found.sort(priority(bx, by, bz));
        candidates.addAll(found);
        scanOrigin = loc.clone();
    }

    private static int layer(Block b, int bx, int by, int bz) {
        return Math.max(Math.abs(b.getX() - bx), Math.max(Math.abs(b.getY() - by), Math.abs(b.getZ() - bz)));
    }

    private void startExplore(Player p) {
        World world = p.getWorld();
        Pathfinder.Pos start = Pathfinder.feetCell(p.getLocation());
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        double angle = rnd.nextDouble(0, Math.PI * 2);
        int dist = rnd.nextInt(16, 33);
        Pathfinder.Pos hint = new Pathfinder.Pos(
                (int) Math.round(start.x() + Math.cos(angle) * dist),
                start.y(),
                (int) Math.round(start.z() + Math.sin(angle) * dist));
        Pathfinder.Result r = Pathfinder.find(world, start, cell -> cell.equals(hint), hint,
                Pathfinder.MAX_NODES, true);
        if (!r.path().isEmpty()) {
            startWalk(WalkKind.EXPLORE, null, null, r.path());
        }
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
                && !clearBlock(p, nms, w.getBlockAt(from.x(), from.y() + 2, from.z()))) {
            return false;
        }
        if (!Pathfinder.passable(w, next.x(), next.y(), next.z())
                && !clearBlock(p, nms, w.getBlockAt(next.x(), next.y(), next.z()))) {
            return false;
        }
        if (!Pathfinder.passable(w, next.x(), next.y() + 1, next.z())
                && !clearBlock(p, nms, w.getBlockAt(next.x(), next.y() + 1, next.z()))) {
            return false;
        }
        if (!Pathfinder.supports(w, next.x(), next.y() - 1, next.z())) {
            return placeSupport(p, w.getBlockAt(next.x(), next.y() - 1, next.z()));
        }
        return true;
    }

    /** Phá một block chắn bằng công cụ tốt nhất trong túi; block rơi ra được nhặt sau. */
    private boolean clearBlock(Player p, NMSServerPlayer nms, Block b) {
        if (!Pathfinder.clearable(b.getWorld(), b.getX(), b.getY(), b.getZ())
                || b.getLocation().distance(p.getLocation()) > REACH_LIMIT + 1.0) {
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

    private void verify() {
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

    /** Đi tới rương gần nhất (trong {@link #CHEST_RADIUS} block) để cất đồ; không có thì kết thúc. */
    private void storeThenFinish(FakePlayer fp, Action action, StoreReason reason) {
        Player p = fp.getPlayer();
        World world = p.getWorld();
        Pathfinder.Pos stand = Pathfinder.feetCell(p.getLocation());
        for (Block chest : nearbyChests(p)) {
            Pathfinder.Pos key = Pathfinder.posOf(chest);
            if (fullChests.contains(key)) {
                continue;
            }
            Pathfinder.Result r = Pathfinder.find(world, stand,
                    cell -> Pathfinder.distToCenter(cell, chest) <= 3.0, key, Pathfinder.MAX_NODES, false);
            if (r.reached()) {
                pendingStore = reason;
                startWalk(WalkKind.CHEST, chest, null, r.path());
                return;
            }
            // Không với tới bằng đường đi bộ: thử đường được phép phá và đặt block.
            Pathfinder.Result built = Pathfinder.findBuild(world, stand,
                    cell -> Pathfinder.distToCenter(cell, chest) <= 3.0, key, Pathfinder.MAX_BUILD_NODES,
                    canPlaceBlocks(p));
            if (built.reached()) {
                pendingStore = reason;
                startWalk(WalkKind.CHEST, chest, null, built.path());
                buildPath = true;
                return;
            }
            fullChests.add(key);
        }
        pendingStore = null;
        String why = reason == StoreReason.QUOTA
                ? "đủ " + limit + " block"
                : "túi đầy và không có rương trong " + CHEST_RADIUS + " block";
        finish(fp, action, why);
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
                    Block b = world.getBlockAt(x, y, z);
                    Material m = b.getType();
                    if ((m == Material.CHEST || m == Material.TRAPPED_CHEST) && distanceSquared(b, loc) <= maxSq) {
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
            moved = transferToChest(p, chest.getInventory());
        }
        if (moved == 0) {
            fullChests.add(Pathfinder.posOf(chestBlock));
        } else {
            skippedItems.clear();
        }

        if (reason == StoreReason.QUOTA) {
            finish(fp, action, moved > 0
                    ? "đủ " + limit + " block, đã cất đồ vào rương"
                    : "đủ " + limit + " block, rương đầy nên đồ còn trong túi");
            return;
        }
        phase = Phase.PLAN;
    }

    /** Chuyển mọi đồ trong túi (trừ công cụ và đồ ăn) vào rương. Trả về số lượng đã chuyển. */
    private static int transferToChest(Player p, Inventory chestInv) {
        PlayerInventory bag = p.getInventory();
        int moved = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack it = bag.getItem(i);
            if (it == null || it.getType().isAir() || keepInBag(it.getType())) {
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

    /** Công cụ và đồ ăn giữ lại trong túi bot. */
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

    private void finish(FakePlayer fp, Action action, String reason) {
        abort(fp);
        notifyOwners(fp, "§a[AutoMine]§r " + fp.getName() + ": dừng đào " + materialName()
                + " (" + reason + "). Đã đào " + mined + " block.");
        fp.getActions().stop(action);
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

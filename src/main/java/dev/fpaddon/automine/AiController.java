package dev.fpaddon.automine;

import com.coderxi.plugin.fakeplayer.api.FakePlayerPlusPluginApi;
import com.coderxi.plugin.fakeplayer.api.action.ActionController;
import com.coderxi.plugin.fakeplayer.api.entity.FakePlayer;
import dev.fpaddon.automine.brain.Snapshot;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;

/**
 * Bot AI: tự spawn các bot trong {@code ai.bots} khi bật plugin, rồi định kỳ hỏi Claude việc nên làm.
 *
 * <p>Ràng buộc: AI chỉ chọn trong {@link DecisionParser#TASKS} và block trong danh sách cho phép, nên không
 * thể ra lệnh tấn công người chơi hay đặt/đập block ngoài việc khai thác. Khi bot đang gặp nguy hiểm
 * (trong nước, trong lửa) thì không hỏi AI. Mọi việc trên luồng chính, chỉ lời gọi HTTP chạy nền.
 */
final class AiController {

    /** Trễ trước khi spawn, để FakePlayerPlus và các plugin khác sẵn sàng. */
    private static final long SPAWN_DELAY_TICKS = 100L;
    private static final long PLACE_POLL_TICKS = 10L;
    private static final int PLACE_POLL_TRIES = 30;
    private static final double HOSTILE_RANGE = 16.0;
    private static final long HOUR_MS = 3_600_000L;
    private static final long DAY_START = 13_000L;
    private static final long DAY_END = 23_000L;

    private final JavaPlugin plugin;
    private final FakePlayerPlusPluginApi api;
    private final ClaudeClient client;

    /** Thời điểm các lần gọi Claude trong một giờ gần nhất (chỉ dùng trên luồng chính). */
    private final ArrayDeque<Long> callTimes = new ArrayDeque<>();
    /** Bot đang chờ trả lời từ Claude (chỉ dùng trên luồng chính). */
    private final Set<UUID> pending = new HashSet<>();
    /** Block mà AI đang yêu cầu đào trên mỗi bot, để không khởi động lại cùng một việc mỗi chu kỳ. */
    private final Map<UUID, String> aiBlock = new HashMap<>();

    AiController(JavaPlugin plugin, FakePlayerPlusPluginApi api) {
        this.plugin = plugin;
        this.api = api;
        this.client = new ClaudeClient(System.getenv(Settings.aiKeyEnv), Settings.aiModel);
    }

    /** Bật AI nếu cấu hình cho phép. Trả về false nếu không chạy, kèm lý do trong log. */
    boolean start() {
        if (!Settings.aiEnabled) {
            return false;
        }
        if (Settings.aiBots.isEmpty()) {
            plugin.getLogger().warning("ai.enabled: true nhưng ai.bots trống. AI không chạy.");
            return false;
        }
        if (!client.ready()) {
            plugin.getLogger().warning("AI không chạy: chưa đặt biến môi trường " + Settings.aiKeyEnv
                    + " trên máy chủ. Đặt khoá API vào biến đó rồi khởi động lại server.");
            return false;
        }
        Bukkit.getScheduler().runTaskLater(plugin, this::spawnConfigured, SPAWN_DELAY_TICKS);
        Bukkit.getScheduler().runTaskTimer(plugin, this::decisionTick,
                SPAWN_DELAY_TICKS + Settings.aiIntervalTicks, Settings.aiIntervalTicks);
        plugin.getLogger().info("AI bật: " + Settings.aiBots.size() + " bot, model " + Settings.aiModel
                + ". Đã đọc khoá API từ biến " + Settings.aiKeyEnv + ".");
        return true;
    }

    /** Spawn các bot chưa có, qua lệnh console {@code fp spawn}. Bot không có chủ, chỉ admin điều khiển. */
    private void spawnConfigured() {
        for (Settings.AiBot spec : Settings.aiBots) {
            if (api.getFakePlayerManager().get(spec.name()) != null) {
                continue;
            }
            boolean sent = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "fp spawn " + spec.name());
            if (!sent) {
                plugin.getLogger().warning("AI: không gửi được lệnh spawn cho bot " + spec.name() + ".");
                continue;
            }
            if (spec.x() != null) {
                placeWhenReady(spec, PLACE_POLL_TRIES);
            }
        }
    }

    /** Spawn là bất đồng bộ, nên chờ bot xuất hiện rồi mới dịch chuyển tới toạ độ cấu hình. */
    private void placeWhenReady(Settings.AiBot spec, int triesLeft) {
        FakePlayer bot = api.getFakePlayerManager().get(spec.name());
        if (bot != null) {
            Location loc = location(spec);
            if (loc != null) {
                bot.getPlayer().teleport(loc);
            }
            return;
        }
        if (triesLeft <= 0) {
            plugin.getLogger().warning("AI: bot " + spec.name() + " chưa xuất hiện sau khi spawn, bỏ qua dịch chuyển.");
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> placeWhenReady(spec, triesLeft - 1), PLACE_POLL_TICKS);
    }

    private Location location(Settings.AiBot spec) {
        World world = findWorld(spec.world());
        if (world == null) {
            plugin.getLogger().warning("AI: không tìm thấy thế giới " + spec.world() + " cho bot " + spec.name() + ".");
            return null;
        }
        return new Location(world, spec.x(), spec.y(), spec.z());
    }

    /** Tìm thế giới theo tên; tên null thì lấy thế giới đầu tiên. Duyệt danh sách để không phụ thuộc overload. */
    private static World findWorld(String name) {
        if (name == null) {
            return Bukkit.getWorlds().get(0);
        }
        for (World world : Bukkit.getWorlds()) {
            if (world.getName().equals(name)) {
                return world;
            }
        }
        return null;
    }

    /** Chu kỳ quyết định (luồng chính): chọn bot nào cần hỏi, giữ trong hạn mức số lần gọi mỗi giờ. */
    private void decisionTick() {
        long now = System.currentTimeMillis();
        while (!callTimes.isEmpty() && now - callTimes.peekFirst() > HOUR_MS) {
            callTimes.pollFirst();
        }
        for (Settings.AiBot spec : Settings.aiBots) {
            FakePlayer bot = api.getFakePlayerManager().get(spec.name());
            if (bot == null || pending.contains(bot.getUuid())) {
                continue;
            }
            if (callTimes.size() >= Settings.aiCallsPerHour) {
                return;
            }
            Player player = bot.getPlayer();
            Snapshot state = snapshot(player);
            if (state.inDanger()) {
                continue;
            }
            UUID uuid = bot.getUuid();
            String name = bot.getName();
            pending.add(uuid);
            callTimes.addLast(now);
            client.decide(describe(state)).whenComplete((decision, error) -> {
                try {
                    Bukkit.getScheduler().runTask(plugin, () -> apply(uuid, name, decision, error));
                } catch (IllegalPluginAccessException ignored) {
                    // Plugin đã tắt trong lúc chờ trả lời: không còn gì để áp dụng.
                }
            });
        }
    }

    /** Áp dụng quyết định đã kiểm tra vào AutoMine (luồng chính). */
    private void apply(UUID uuid, String name, DecisionParser.Decision decision, Throwable error) {
        pending.remove(uuid);
        if (error != null) {
            plugin.getLogger().warning("AI (" + name + "): lỗi khi gọi Claude: " + describeError(error));
            return;
        }
        FakePlayer bot = api.getFakePlayerManager().get(name);
        if (bot == null) {
            aiBlock.remove(uuid);
            return;
        }
        if (decision == null) {
            plugin.getLogger().fine("AI (" + name + "): câu trả lời không hợp lệ, bỏ qua.");
            return;
        }
        ActionController controller = bot.getActions();
        boolean mining = controller.getActiveActions().contains(AutoMineAction.class);
        if (decision.block() == null) {
            aiBlock.remove(uuid);
            if (mining) {
                controller.stop(new AutoMineAction());
            }
            return;
        }
        if (mining && decision.block().equals(aiBlock.get(uuid))) {
            return;
        }
        Material material = Material.matchMaterial(decision.block());
        if (material == null || !material.isBlock()) {
            return;
        }
        if (mining) {
            controller.stop(new AutoMineAction());
        }
        Map<String, String> params = new HashMap<>();
        params.put("block", material.name());
        params.put("amount", "");
        controller.execute(new AutoMineAction(), "continuous", params);
        aiBlock.put(uuid, material.name());
        plugin.getLogger().info("AI (" + name + "): đào " + material.name().toLowerCase(Locale.ROOT) + ".");
    }

    /** Ảnh chụp trạng thái bot từ Bukkit (luồng chính). Chỉ đọc số liệu, không đọc chat hay tên người chơi. */
    private static Snapshot snapshot(Player p) {
        World world = p.getWorld();
        long time = world.getTime();
        boolean night = time >= DAY_START && time < DAY_END;
        int hostiles = 0;
        for (Entity e : p.getNearbyEntities(HOSTILE_RANGE, HOSTILE_RANGE, HOSTILE_RANGE)) {
            if (e instanceof Monster) {
                hostiles++;
            }
        }
        boolean hasFood = false;
        boolean hasTool = false;
        for (ItemStack item : p.getInventory().getContents()) {
            if (item == null || item.getType().isAir()) {
                continue;
            }
            if (item.getType().isEdible()) {
                hasFood = true;
            }
            if (item.getType().name().endsWith("_PICKAXE")) {
                hasTool = true;
            }
        }
        AttributeInstance maxAttr = p.getAttribute(Attribute.MAX_HEALTH);
        double maxHealth = maxAttr == null ? 20.0 : maxAttr.getValue();
        return new Snapshot(p.getFoodLevel(), p.getHealth(), maxHealth, night, hostiles,
                hasFood, false, hasTool, inDanger(p));
    }

    private static boolean inDanger(Player p) {
        Block feet = p.getLocation().getBlock();
        Material type = feet.getType();
        return p.getFireTicks() > 0
                || feet.isLiquid()
                || type == Material.FIRE
                || type == Material.SOUL_FIRE;
    }

    /** Chuỗi số liệu gửi cho Claude: chỉ có số và cờ true/false. */
    private static String describe(Snapshot s) {
        return "food=" + s.food()
                + " health=" + s.health() + "/" + s.maxHealth()
                + " night=" + s.night()
                + " hostiles=" + s.hostiles()
                + " has_food=" + s.hasFood()
                + " has_pickaxe=" + s.hasTool()
                + " has_bed=" + s.hasBed();
    }

    /** Lấy loại lỗi và thông điệp gốc, không in toàn bộ stack trace hay nội dung phản hồi. */
    private static String describeError(Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null
                ? error.getCause()
                : error;
        return cause.getClass().getSimpleName() + (cause.getMessage() == null ? "" : ": " + cause.getMessage());
    }
}

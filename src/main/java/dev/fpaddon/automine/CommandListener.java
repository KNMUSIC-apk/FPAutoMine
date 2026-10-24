package dev.fpaddon.automine;

import com.coderxi.plugin.fakeplayer.api.FakePlayerPlusPluginApi;
import com.coderxi.plugin.fakeplayer.api.action.ActionController;
import com.coderxi.plugin.fakeplayer.api.entity.FakePlayer;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;

import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Bắt lệnh {@code /fp action start mine <block> [số lượng|-] [tên bot]} trước khi
 * FakePlayerPlus xử lý. Lệnh gốc không đổi: nếu thiếu tên block thì để nguyên cho
 * FakePlayerPlus mở GUI, và {@code /fp action stop mine} chỉ bị bắt khi bot đang auto-mine.
 */
final class CommandListener implements Listener {

    private static final String PREFIX = "§a[AutoMine]§r ";

    private final FakePlayerPlusPluginApi api;

    CommandListener(FakePlayerPlusPluginApi api) {
        this.api = api;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        if (handle(event.getPlayer(), event.getMessage())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onServerCommand(ServerCommandEvent event) {
        if (handle(event.getSender(), event.getCommand())) {
            event.setCancelled(true);
        }
    }

    /** @return true nếu lệnh đã được addon xử lý (và cần huỷ để không chạy tiếp). */
    private boolean handle(CommandSender sender, String raw) {
        if (raw == null) {
            return false;
        }
        String text = raw.startsWith("/") ? raw.substring(1) : raw;
        String[] t = text.trim().split("\\s+");
        if (t.length < 4) {
            return false;
        }
        if (!t[0].equalsIgnoreCase("fp") && !t[0].equalsIgnoreCase("fakeplayer")) {
            return false;
        }
        if (!t[1].equalsIgnoreCase("action") || !t[3].equalsIgnoreCase("mine")) {
            return false;
        }
        if (t[2].equalsIgnoreCase("start")) {
            return handleStart(sender, t);
        }
        if (t[2].equalsIgnoreCase("stop")) {
            return handleStop(sender, t);
        }
        return false;
    }

    private boolean handleStart(CommandSender sender, String[] t) {
        if (t.length < 5) {
            return false;
        }
        if (!sender.hasPermission("fakeplayer.action") || !sender.hasPermission("fakeplayer.action.mine")) {
            sender.sendMessage(PREFIX + "Bạn không có quyền dùng lệnh này.");
            return true;
        }
        Material material = parseBlock(t[4]);
        if (material == null) {
            sender.sendMessage(PREFIX + "Block không hợp lệ: " + t[4]);
            return true;
        }

        int index = 5;
        int limit = -1;
        if (t.length > index && isAmountToken(t[index])) {
            String token = t[index++];
            if (!token.equals("-") && !token.equals("*")) {
                try {
                    limit = Integer.parseInt(token);
                } catch (NumberFormatException e) {
                    limit = 0;
                }
                if (limit <= 0) {
                    sender.sendMessage(PREFIX + "Số lượng phải là số nguyên dương, hoặc '-' để không giới hạn.");
                    return true;
                }
            }
        }

        String botName = t.length > index ? t[index] : null;
        FakePlayer bot = resolveBot(sender, botName);
        if (bot == null) {
            return true;
        }

        ActionController controller = bot.getActions();
        // Đang chạy auto-mine rồi thì dừng phiên cũ trước, tránh để lại thao tác đào dở.
        if (controller.getActiveActions().contains(AutoMineAction.class)) {
            controller.stop(new AutoMineAction());
        }
        Map<String, String> params = new HashMap<>();
        params.put("block", material.name());
        params.put("amount", limit < 0 ? "" : Integer.toString(limit));
        controller.execute(new AutoMineAction(), "continuous", params);

        String blockName = material.name().toLowerCase(Locale.ROOT);
        sender.sendMessage(PREFIX + bot.getName() + " bắt đầu đào " + blockName
                + (limit < 0
                        ? " (không giới hạn: đầy túi thì cất vào rương trong 10 block nếu có)"
                        : " (mục tiêu " + limit + " block)") + ".");
        return true;
    }

    private boolean handleStop(CommandSender sender, String[] t) {
        if (t.length > 5) {
            return false;
        }
        String botName = t.length == 5 ? t[4] : null;
        FakePlayer bot = pickBot(sender, botName);
        if (bot == null) {
            return false;
        }
        ActionController controller = bot.getActions();
        if (!controller.getActiveActions().contains(AutoMineAction.class)) {
            return false;
        }
        controller.stop(new AutoMineAction());
        sender.sendMessage(PREFIX + "Đã dừng auto mine trên bot " + bot.getName() + ".");
        return true;
    }

    /** Chọn bot theo tên, hoặc bot duy nhất của người gửi nếu không có tên. Có gửi thông báo lỗi. */
    private FakePlayer resolveBot(CommandSender sender, String name) {
        FakePlayer bot = pickBot(sender, name);
        if (bot != null) {
            return bot;
        }
        if (name != null) {
            FakePlayer named = api.getFakePlayerManager().get(name);
            sender.sendMessage(PREFIX + (named == null
                    ? "Không tìm thấy bot tên: " + name
                    : "Bạn không có quyền điều khiển bot " + name + "."));
            return null;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(PREFIX + "Console cần chỉ định tên bot ở cuối lệnh.");
            return null;
        }
        Collection<FakePlayer> owned = api.getFakePlayerManager().fakeplayersByOwnerUuid(player.getUniqueId());
        if (owned.isEmpty()) {
            sender.sendMessage(PREFIX + "Bạn chưa có bot nào đang online.");
        } else {
            sender.sendMessage(PREFIX + "Bạn có nhiều bot, hãy thêm tên bot vào cuối lệnh.");
        }
        return null;
    }

    /** Không gửi thông báo; trả về null nếu không xác định được bot mà người gửi điều khiển được. */
    private FakePlayer pickBot(CommandSender sender, String name) {
        if (name != null) {
            FakePlayer bot = api.getFakePlayerManager().get(name);
            return bot != null && canControl(sender, bot) ? bot : null;
        }
        if (!(sender instanceof Player player)) {
            return null;
        }
        Collection<FakePlayer> owned = api.getFakePlayerManager().fakeplayersByOwnerUuid(player.getUniqueId());
        return owned.size() == 1 ? owned.iterator().next() : null;
    }

    private static boolean canControl(CommandSender sender, FakePlayer bot) {
        if (!(sender instanceof Player player)) {
            return true;
        }
        return bot.isOwnedBy(player.getUniqueId()) || player.hasPermission("fakeplayer.admin");
    }

    private static boolean isAmountToken(String token) {
        return token.equals("-") || token.equals("*") || token.matches("\\d+");
    }

    private static Material parseBlock(String input) {
        String name = input.toUpperCase(Locale.ROOT).replace("MINECRAFT:", "").trim();
        Material material = Material.matchMaterial(name);
        if (material == null || !material.isBlock() || material.isAir()) {
            return null;
        }
        return material;
    }
}

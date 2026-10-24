package dev.fpaddon.automine;

import com.coderxi.plugin.fakeplayer.api.action.ActionHandler;
import com.coderxi.plugin.fakeplayer.api.entity.FakePlayer;
import org.bukkit.Material;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handler của action "automine". Mỗi bot có tối đa một phiên, lưu theo UUID.
 * Tham số nhận từ FakePlayerPlus: {@code block} (tên Material) và {@code amount}
 * (chuỗi rỗng = không giới hạn).
 */
public final class AutoMineHandler implements ActionHandler<AutoMineAction> {

    private static final Logger LOGGER = Logger.getLogger("FPAutoMine");

    private final Map<UUID, AutoMineSession> sessions = new ConcurrentHashMap<>();

    @Override
    public void onTick(FakePlayer fakePlayer, AutoMineAction action, String mode, Map<String, String> parameters) {
        AutoMineSession session = sessions.get(fakePlayer.getUuid());
        if (session == null) {
            Material material = Material.matchMaterial(
                    parameters.getOrDefault("block", "").trim().toUpperCase(Locale.ROOT));
            if (material == null || !material.isBlock() || material.isAir()) {
                fakePlayer.getActions().stop(action);
                return;
            }
            session = new AutoMineSession(material, parseLimit(parameters.get("amount")));
            sessions.put(fakePlayer.getUuid(), session);
        }
        try {
            session.tick(fakePlayer, action);
        } catch (RuntimeException e) {
            // FakePlayerPlus bỏ qua ngoại lệ trong vòng tick của bot; ghi log và dừng phiên này
            // để không làm đứng các action khác của bot.
            sessions.remove(fakePlayer.getUuid());
            session.abort(fakePlayer);
            fakePlayer.getActions().stop(action);
            LOGGER.log(Level.SEVERE, "Auto mine lỗi trên bot " + fakePlayer.getName() + ", đã dừng phiên.", e);
        }
    }

    @Override
    public void onStop(FakePlayer fakePlayer, AutoMineAction action, String mode, Map<String, String> parameters) {
        AutoMineSession session = sessions.remove(fakePlayer.getUuid());
        if (session != null) {
            session.abort(fakePlayer);
        }
    }

    /** Chuỗi rỗng, null hoặc số không hợp lệ đều được coi là không giới hạn (-1). */
    static int parseLimit(String text) {
        if (text == null || text.isBlank()) {
            return -1;
        }
        try {
            int value = Integer.parseInt(text.trim());
            return value > 0 ? value : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}

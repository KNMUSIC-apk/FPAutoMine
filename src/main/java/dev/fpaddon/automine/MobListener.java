package dev.fpaddon.automine;

import com.coderxi.plugin.fakeplayer.api.FakePlayerPlusPluginApi;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;

/**
 * Bot được coi như NPC: quái không chọn bot làm mục tiêu. Việc huỷ sự kiện nhắm mục tiêu
 * chỉ áp dụng cho bot đang online (không truy vấn cơ sở dữ liệu trong luồng sự kiện).
 */
final class MobListener implements Listener {

    private final FakePlayerPlusPluginApi api;

    MobListener(FakePlayerPlusPluginApi api) {
        this.api = api;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTarget(EntityTargetLivingEntityEvent event) {
        LivingEntity target = event.getTarget();
        if (target instanceof Player player && api.getFakePlayerManager().isFake(player.getUniqueId(), false)) {
            event.setCancelled(true);
        }
    }
}

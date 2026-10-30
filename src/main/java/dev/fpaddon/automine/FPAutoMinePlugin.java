package dev.fpaddon.automine;

import com.coderxi.plugin.fakeplayer.api.FakePlayerPlusPluginApi;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/** Addon thêm action "automine" và lệnh {@code /fp action start mine ...} cho FakePlayerPlus. */
public final class FPAutoMinePlugin extends JavaPlugin {

    static final String ACTION_NAME = "automine";

    @Override
    public void onEnable() {
        if (isFolia()) {
            getLogger().severe("Addon này chưa hỗ trợ Folia (cần luồng chính để đọc block). Đang tắt addon.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        Plugin fpPlugin = getServer().getPluginManager().getPlugin("FakePlayerPlus");
        if (!(fpPlugin instanceof FakePlayerPlusPluginApi api)) {
            getLogger().severe("Không tìm thấy FakePlayerPlus đã bật. Đang tắt addon.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        saveDefaultConfig();
        Settings.load(getConfig());
        AutoMineHandler handler = new AutoMineHandler();
        api.getGlobalActionRegistry().register(
                AutoMineAction.class, ACTION_NAME, "continuous", handler);
        getServer().getPluginManager().registerEvents(new CommandListener(api, handler), this);
        getServer().getPluginManager().registerEvents(new MobListener(api), this);
        getLogger().info("Đã đăng ký action '" + ACTION_NAME + "'. Dùng: /fp action start mine <block> [số lượng|-] [tên bot]");
    }

    private static boolean isFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}

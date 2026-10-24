package dev.fpaddon.automine;

import com.coderxi.plugin.fakeplayer.api.action.Action;

/**
 * Action "automine". Trạng thái thực thi không nằm ở instance này mà được
 * {@link AutoMineHandler} giữ theo UUID của bot, vì FakePlayerPlus có thể gọi
 * onStop bằng một instance mới.
 */
public final class AutoMineAction implements Action {

    public AutoMineAction() {
    }
}

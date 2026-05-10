package lyjew.com.lyclaw.action;

import java.util.concurrent.CompletableFuture;

/**
 * Computer Use Agent —— 模拟人类操作计算机。
 *
 * <p>支持截图、点击、输入、滚动、按键等操作。</p>
 *
 * @since 2.0
 */
public interface ComputerUseAgent {

    CompletableFuture<String> takeScreenshot();

    CompletableFuture<Void> click(int x, int y);

    CompletableFuture<Void> type(String text);

    CompletableFuture<Void> scroll(int direction, int amount);

    CompletableFuture<Void> keyPress(String keyCombination);
}

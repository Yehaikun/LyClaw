package lyjew.com.lyclaw.action;

import java.util.concurrent.CompletableFuture;

public interface ComputerUseAgent {

    CompletableFuture<String> takeScreenshot();
    CompletableFuture<Void> click(int x, int y);
    CompletableFuture<Void> type(String text);
    CompletableFuture<Void> scroll(int direction, int amount);
    CompletableFuture<Void> keyPress(String keyCombination);
}

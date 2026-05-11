package lyjew.com.lyclaw.action;

import java.util.concurrent.CompletableFuture;

/**
 * 计算机操作智能体接口，定义对远程桌面或浏览器环境进行自动化操作的能力。
 *
 * <p>该接口抽象了截图、鼠标点击、键盘输入、滚动和组合键等基本桌面操作，
 * 所有方法均返回异步结果，适配远程执行环境的延迟特性。
 * 实现类通常对接沙箱环境或 VNC/RDP 远程桌面协议。
 */
public interface ComputerUseAgent {

    /**
     * 截取当前桌面屏幕并返回 Base64 编码的图片数据。
     *
     * @return 异步返回 Base64 编码的截图字符串
     */
    CompletableFuture<String> takeScreenshot();

    /**
     * 在指定坐标位置执行鼠标左键点击。
     *
     * @param x X 轴坐标（像素）
     * @param y Y 轴坐标（像素）
     * @return 异步操作完成信号
     */
    CompletableFuture<Void> click(int x, int y);

    /**
     * 模拟键盘输入文本。
     *
     * @param text 要输入的文本内容
     * @return 异步操作完成信号
     */
    CompletableFuture<Void> type(String text);

    /**
     * 滚动屏幕。
     *
     * @param direction 滚动方向（正值为向下，负值为向上）
     * @param amount    滚动量（像素或行数，取决于实现）
     * @return 异步操作完成信号
     */
    CompletableFuture<Void> scroll(int direction, int amount);

    /**
     * 模拟按下组合键。
     *
     * @param keyCombination 组合键描述（如 "ctrl+c"、"alt+tab"）
     * @return 异步操作完成信号
     */
    CompletableFuture<Void> keyPress(String keyCombination);
}

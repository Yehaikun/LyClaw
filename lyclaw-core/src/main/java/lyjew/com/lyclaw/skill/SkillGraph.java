package lyjew.com.lyclaw.skill;

import java.util.List;

/**
 * 技能依赖关系图接口 —— 维护技能之间的 DAG 依赖关系。
 *
 * <p>技能之间的依赖关系构成一个有向无环图（DAG）。
 * 例如：技能 A 依赖技能 B 和技能 C，技能 B 依赖技能 D。
 * SkillGraph 提供添加/移除依赖、查询依赖和依赖者、拓扑排序、环检测等功能。</p>
 *
 * <p><b>设计动机</b>：如果依赖关系管理分散在技能内部，难以统一检测循环依赖。
 * SkillGraph 将依赖关系集中管理，使用 DFS 三色标记法检测环，
 * 使用 DFS 后序遍历进行拓扑排序。</p>
 *
 * <p><b>拓扑排序算法</b>：DFS + 后序遍历 + 逆序输出。
 * 从任意一个节点开始 DFS，遍历完所有邻接节点后将该节点加入结果列表，
 * 最后反转列表得到拓扑排序结果。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see SkillRegistry
 */
public interface SkillGraph {

    /**
     * 添加依赖关系：from 依赖 to（from 的执行依赖于 to 先执行完成）。
     *
     * @param fromSkillId 依赖于其他技能的技能 ID
     * @param toSkillId   被依赖的技能 ID
     */
    void addDependency(String fromSkillId, String toSkillId);

    /**
     * 移除依赖关系。
     *
     * @param fromSkillId 依赖方技能 ID
     * @param toSkillId   被依赖方技能 ID
     */
    void removeDependency(String fromSkillId, String toSkillId);

    /**
     * 获取指定技能的直接依赖。
     *
     * @param skillId 技能 ID
     * @return 被依赖的技能 ID 列表（直接依赖，不含传递依赖）
     */
    List<String> getDependencies(String skillId);

    /**
     * 获取直接依赖当前技能的其他技能。
     *
     * @param skillId 技能 ID
     * @return 依赖当前技能的其他技能 ID 列表
     */
    List<String> getDependents(String skillId);

    /**
     * 拓扑排序 —— DFS + 后序遍历，返回按执行顺序排列的技能 ID 列表。
     * 依赖在前、被依赖在后。
     *
     * @return 按执行顺序排列的技能 ID 列表
     * @throws IllegalStateException 如果图中存在环
     */
    List<String> getExecutionOrder();

    /**
     * 检测是否有环。使用 DFS 三色标记法：
     * <ul>
     *   <li>白色：未访问</li>
     *   <li>灰色：正在访问（当前 DFS 路径中）</li>
     *   <li>黑色：访问完成</li>
     * </ul>
     * 如果访问到灰色节点，说明存在环。
     *
     * @return true 表示存在环
     */
    boolean hasCycle();
}
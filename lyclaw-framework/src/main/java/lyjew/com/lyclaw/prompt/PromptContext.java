package lyjew.com.lyclaw.prompt;

import java.util.HashMap;
import java.util.Map;

/**
 * 提示词构建上下文，携带构建系统提示词所需的全部参数。
 */
public class PromptContext {

    private String taskDescription;
    private String availableTools;
    private String constraints;
    private String outputFormat;
    private final Map<String, Object> extras = new HashMap<>();

    public String getTaskDescription() { return taskDescription; }
    public void setTaskDescription(String taskDescription) { this.taskDescription = taskDescription; }
    public String getAvailableTools() { return availableTools; }
    public void setAvailableTools(String availableTools) { this.availableTools = availableTools; }
    public String getConstraints() { return constraints; }
    public void setConstraints(String constraints) { this.constraints = constraints; }
    public String getOutputFormat() { return outputFormat; }
    public void setOutputFormat(String outputFormat) { this.outputFormat = outputFormat; }
    public Map<String, Object> getExtras() { return extras; }
    public void setExtra(String key, Object value) { extras.put(key, value); }
    public Object getExtra(String key) { return extras.get(key); }
}

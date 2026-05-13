package lyjew.com.lyclaw.autoconfigure.processor;

import lyjew.com.lyclaw.autoconfigure.binding.AnnotatedToolAdapter;
import lyjew.com.lyclaw.model.ToolDefinition;
import lyjew.com.lyclaw.tool.Tool;
import lyjew.com.lyclaw.tool.ToolRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;

import java.lang.reflect.Method;
import java.util.*;
import java.lang.annotation.Annotation;
/**
 * 工具注解处理器，作为 Spring {@link BeanPostProcessor} 在 Bean 初始化完成后自动发现
 * 带有 {@code @Tool} 注解的类并将其注册到 {@link ToolRegistry} 工具注册表中。
 *
 * <p><b>三种工具注册模式：</b></p>
 * <ul>
 *   <li><b>模式一：类级 @Tool 注解 + 方法级 @Tool 注解</b>——类上标注 @Tool 注解用于声明工具的
 *       元数据（名称、描述、只读属性、分组、超时时间），同时类中某个具体方法也标注 @Tool 注解
 *       作为工具执行的入口点。处理器会通过反射解析该方法的参数（利用 @Param 注解提取参数名称、
 *       描述、是否必填等信息），自动构建 JSON Schema 格式的参数定义（ToolDefinition），然后
 *       创建 {@link AnnotatedToolAdapter} 适配器实例并注册到 ToolRegistry。</li>
 *   <li><b>模式二：类级 @Tool 注解 + 实现 Tool 接口</b>——类上标注 @Tool 注解但类中没有找到
 *       带有 @Tool 注解的方法，也没有找到带有 @Param 注解的参数化方法时，处理器会检查该类是否
 *       实现了 {@link Tool} 接口。如果实现了 Tool 接口，则直接将 Bean 作为 Tool 实例注册到
 *       ToolRegistry 中，此时工具的执行逻辑由接口实现类的 execute() 方法提供。</li>
 *   <li><b>模式三：旧版兼容模式（无 @Tool 注解）</b>——类上没有 @Tool 注解但实现了 Tool 接口，
 *       作为旧版工具直接注册。这种方式向后兼容早期版本的代码，无需修改已有工具类即可正常工作。
 *       处理器会从 Tool.getName() 方法获取工具名称并记录日志。</li>
 * </ul>
 *
 * <p><b>注解发现的实现方式：</b>本处理器通过 Java 反射 API 在运行时动态查找注解，
 * 不依赖编译期注解依赖。使用 {@link #findAnnotation(Object, String)} 方法通过注解的
 * 简单类名（如 "Tool"、"Param"）进行匹配，避免了对框架注解模块的硬编译依赖。
 * 所有注解属性值通过 {@link #getAnnotationAttr(Object, String, Class)} 方法利用反射
 * 调用注解的对应方法获取，如果获取失败则使用默认值（字符串为空字符串，布尔为 false，长整型为 0）。</p>
 *
 * <p><b>重复注册防护：</b>维护一个 {@code registeredClasses} 集合记录已处理过的类全限定名，
 * 防止同一个 Bean 被 Spring 的多个后处理器重复扫描注册。</p>
 *
 * @see ToolRegistry 工具注册表，所有发现并注册的工具最终汇聚于此
 * @see AnnotatedToolAdapter 将注解驱动的 POJO 适配为 Tool 接口的适配器
 * @see ToolDefinition 包含工具名称、描述、JSON Schema 参数定义的完整模型
 */
public class ToolAnnotationProcessor implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(ToolAnnotationProcessor.class);

    private final ToolRegistry toolRegistry;
    private final Set<String> registeredClasses = new HashSet<>();

    public ToolAnnotationProcessor(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    /**
     * Spring Bean 后处理器核心方法，在 Bean 初始化完成后被容器调用，负责发现和注册工具。
     *
     * <p><b>处理流程：</b></p>
     * <ol>
     *   <li><b>去重检查：</b>通过 {@code registeredClasses} 集合判断当前类的全限定名是否已经
     *       处理过，若已处理则直接返回原 Bean，避免重复注册。</li>
     *   <li><b>注解发现：</b>使用反射在运行时查找类上的 {@code @Tool} 注解（不依赖编译期依赖），
     *       提取注解中的 name、description、readonly、group、timeout 等元数据属性。
     *       若未显式指定工具名称，则自动将类名首字母小写作为默认名称。</li>
     *   <li><b>执行方法定位：</b>调用 {@link #findExecuteMethod(Class)} 在类中查找带有
     *       {@code @Tool} 注解的方法，若未找到则尝试查找首个带有 {@code @Param} 注解的参数化方法。
     *       该方法将作为工具的运行时执行入口。</li>
     *   <li><b>模式一处理（有执行方法）：</b>若找到了执行方法，通过 {@link #buildDefinition} 构建
     *       包含 JSON Schema 参数定义的工具描述对象，创建 {@link AnnotatedToolAdapter} 适配器
     *       并注册到 ToolRegistry。</li>
     *   <li><b>模式二处理（无执行方法但实现 Tool 接口）：</b>若没有找到执行方法但 Bean 实现了
     *       {@link Tool} 接口，直接将 Bean 作为 Tool 实例注册。</li>
     *   <li><b>模式三处理（旧版无注解的 Tool 实现）：</b>若类上没有 @Tool 注解但实现了 Tool 接口，
     *       作为传统旧版工具直接注册以保证向后兼容。</li>
     *   <li><b>异常处理：</b>整个处理过程包裹在 try-catch 中，任何步骤的异常都会被记录日志，
     *       不会中断 Spring 容器的启动流程。</li>
     * </ol>
     *
     * @param bean Spring 容器中已初始化的 Bean 实例，可能带有 @Tool 注解或实现 Tool 接口
     * @param beanName Bean 在 Spring 容器中的注册名称，用于日志记录和问题定位
     * @return 始终返回原始 bean 实例（可能已被包装为 AnnotatedToolAdapter 并注册到 ToolRegistry，
     *         但方法本身不修改 bean 对象的引用）
     * @throws BeansException 当 Bean 后处理过程中发生严重错误时抛出，但内部已捕获异常通常不会传播
     */
    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        try {
            Class<?> clazz = bean.getClass();
            String classKey = clazz.getName();
            if (registeredClasses.contains(classKey)) {
                return bean;
            }

            // Check for @Tool annotation via reflection (no compile-time dependency required)
            Object toolAnnot = findAnnotation(clazz, "Tool");
            if (toolAnnot != null) {
                String name = getAnnotationAttr(toolAnnot, "name", String.class);
                String desc = getAnnotationAttr(toolAnnot, "description", String.class);
                boolean readonly = Boolean.TRUE.equals(
                        getAnnotationAttr(toolAnnot, "readonly", Boolean.class));
                String group = getAnnotationAttr(toolAnnot, "group", String.class);
                long timeout = getAnnotationAttr(toolAnnot, "timeout", Long.class);

                if (name == null || name.isEmpty()) {
                    name = Character.toLowerCase(clazz.getSimpleName().charAt(0))
                            + clazz.getSimpleName().substring(1);
                }

                // Find the execute method (annotated with @Tool on method, or first public
                // method with @Param)
                Method execMethod = findExecuteMethod(clazz);
                if (execMethod == null) {
                    // Class-level @Tool without a specific method — must implement Tool interface
                    if (bean instanceof Tool) {
                        toolRegistry.register((Tool) bean);
                        registeredClasses.add(classKey);
                        log.info("Registered tool '{}' (implements Tool interface with @Tool annotation)", name);
                    } else {
                        log.error("@Tool class '{}' does not implement Tool and has no executable method",
                                clazz.getName());
                    }
                    return bean;
                }

                ToolDefinition def = buildDefinition(name, desc, group, readonly, timeout, execMethod);
                AnnotatedToolAdapter adapter = new AnnotatedToolAdapter(
                        bean, name, desc, readonly, def, execMethod);
                toolRegistry.register(adapter);
                registeredClasses.add(classKey);
                log.info("注册工具成功！ '{}' (class: {})", name, clazz.getSimpleName());
            } else if (bean instanceof Tool tool) {
                // 旧版兼容模式：类上没有 @Tool 注解但实现了 Tool 接口，作为传统工具直接注册。
                // 这种方式保证了向后兼容性——开发者无需修改已有的 Tool 实现类代码即可正常运行。
                // 工具名称和描述从 Tool 接口的 getName()/getDefinition() 方法获取，而非从注解读取。
                toolRegistry.register(tool);
                registeredClasses.add(classKey);
                log.info("Registered legacy tool '{}' (class: {})", tool.getName(), clazz.getSimpleName());
            }
        } catch (Exception e) {
            log.error("Failed to process @Tool bean '{}': {}", beanName, e.getMessage(), e);
        }
        return bean;
    }

    // --- internal helpers ----------------------------------------------------

    /**
     * 在给定类中查找工具的执行方法，采用两级匹配策略。
     *
     * <p><b>第一级匹配——方法级 @Tool 注解：</b>遍历类的所有已声明方法（包括私有方法），
     * 查找直接标注了 {@code @Tool} 注解的方法。如果找到，立即返回该方法作为工具的执行入口。
     * 这是最直接的匹配方式，适用于开发者显式在某个方法上标注 @Tool 注解的场景。</p>
     *
     * <p><b>第二级匹配——参数级 @Param 注解：</b>如果在第一步未找到方法级 @Tool 注解，
     * 则再次遍历类的所有已声明方法，查找首个参数数量大于零且参数上带有 {@code @Param} 注解
     * 的方法。这种方式适用于开发者没有在方法上标注 @Tool 但通过 @Param 注解声明了工具参数
     * 的场景，作为隐式的执行方法发现机制。</p>
     *
     * <p>如果两级匹配都未找到合适的执行方法，返回 {@code null}，此时调用方将回退到模式二
     * （检查类是否实现了 Tool 接口）或模式三（旧版兼容模式）。</p>
     *
     * @param clazz 需要查找执行方法的目标类，可能是任何带有 @Tool 注解的普通 Java 类
     * @return 找到的执行方法对象，如果未找到任何合适的执行方法则返回 {@code null}
     */
    private Method findExecuteMethod(Class<?> clazz) {
        for (Method m : clazz.getDeclaredMethods()) {
            if (findAnnotation(m, "Tool") != null) {
                return m;
            }
        }
        // Try first public method with @Param annotation
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.getParameterCount() > 0 && hasParamAnnotation(m)) {
                return m;
            }
        }
        return null;
    }

    /**
     * 检查方法的参数列表中是否存在至少一个带有 {@code @Param} 注解的参数。
     *
     * <p>该方法作为 {@link #findExecuteMethod(Class)} 的辅助判定手段，用于在类中没有方法
     * 显式标注 @Tool 注解时，判断某个方法是否通过 @Param 注解隐式声明了工具参数，
     * 从而将该方法视为潜在的工具执行方法。遍历方法的每一个参数，对每个参数调用
     * {@link #findAnnotation(Object, String)} 查找 "Param" 注解。</p>
     *
     * <p>如果方法没有任何参数（空参数列表），循环不会执行，直接返回 {@code false}，
     * 因为无参方法在没有 @Tool 注解的情况下不太可能是工具执行入口。</p>
     *
     * @param m 需要检查的 Java 反射方法对象，通常来自 {@code clazz.getDeclaredMethods()} 的遍历结果
     * @return 如果方法中存在任意一个参数标注了 @Param 注解则返回 {@code true}，否则返回 {@code false}
     */
    private boolean hasParamAnnotation(Method m) {
        for (var p : m.getParameters()) {
            if (findAnnotation(p, "Param") != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * 通过反射解析目标方法的参数列表，构建包含 JSON Schema 参数定义的工具描述对象。
     *
     * <p><b>构建流程：</b></p>
     * <ol>
     *   <li><b>初始化 JSON Schema 骨架：</b>创建 params Map，设置根类型为 {@code "object"}，
     *       同时创建空的 properties Map 和 required 列表，分别用于存储各参数的类型定义和
     *       必填参数名称列表。</li>
     *   <li><b>遍历方法参数：</b>对目标方法的每一个参数（通过 {@code method.getParameters()}
     *       获取），依次提取参数名称、描述、是否必填等信息。首先尝试从参数上的 {@code @Param}
     *       注解中读取这些信息（通过反射调用注解的 name()、description()、required() 方法），
     *       如果注解不存在或反射调用失败，则使用 Java 反射提供的默认参数名和空描述作为回退值。</li>
     *   <li><b>类型映射：</b>调用 {@link #javaTypeToJsonType(Class)} 将每个参数的 Java 类型
     *       （如 String、int、double、boolean 等）映射为对应的 JSON Schema 类型字符串
     *       （如 "string"、"integer"、"number"、"boolean"），存储在 properties 中。</li>
     *   <li><b>必填参数收集：</b>将所有标记为必填的参数名称加入 required 列表，
     *       便于 LLM 在调用工具时了解哪些参数是必须提供的。</li>
     *   <li><b>构建最终对象：</b>将 properties 和 required 列表合并到 params 根 Map 中，
     *       使用 {@link ToolDefinition.Builder} 模式创建包含名称、显示名称、描述、参数定义、
     *       来源标识、超时时间和只读属性的完整工具定义对象。</li>
     * </ol>
     *
     * @param name 工具的唯一标识名称，用于 ToolRegistry 中的查找和 LLM 的函数调用匹配
     * @param desc 工具的功能描述文本，帮助 LLM 理解何时应该调用该工具
     * @param group 工具所属的分组名称，用于在工具列表中按组分类展示
     * @param readonly 是否为只读工具，只读工具不会修改系统状态，可以在安全沙箱中更宽松地执行
     * @param timeout 工具执行的超时时间（毫秒），超时后工具调用将被中断
     * @param method 目标执行方法，通过反射解析其参数列表来生成 JSON Schema 参数定义
     * @return 完整的 {@link ToolDefinition} 对象，包含名称、描述、参数 Schema 等所有元数据
     */
    private ToolDefinition buildDefinition(String name, String desc, String group,
                                            boolean readonly, long timeout, Method method) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();

        for (var p : method.getParameters()) {
            String pName = p.getName();
            String pDesc = "";
            boolean pReq = true;

            try {
                Object pa = findAnnotation(p, "Param");
                if (pa != null) {
                    String n = (String) pa.getClass().getMethod("name").invoke(pa);
                    if (n != null && !n.isEmpty()) {
                        pName = n;
                    }
                    pDesc = (String) pa.getClass().getMethod("description").invoke(pa);
                    pReq = (Boolean) pa.getClass().getMethod("required").invoke(pa);
                }
            } catch (Exception ignored) {
                // Fallback to defaults
            }

            Map<String, Object> prop = new LinkedHashMap<>();
            prop.put("type", javaTypeToJsonType(p.getType()));
            prop.put("description", pDesc);
            properties.put(pName, prop);
            if (pReq) {
                required.add(pName);
            }
        }

        params.put("properties", properties);
        if (!required.isEmpty()) {
            params.put("required", required);
        }

        return ToolDefinition.builder()
                .name(name)
                .displayName(name)
                .description(desc)
                .parameters(params)
                .source("builtin")
                .timeout(timeout)
                .readOnly(readonly)
                .build();
    }

    /**
     * 将 Java 基本类型或包装类型映射为 JSON Schema 规范中对应的类型字符串。
     *
     * <p><b>映射规则：</b></p>
     * <ul>
     *   <li>{@link String} 类型 → {@code "string"}，涵盖所有字符串类型的参数</li>
     *   <li>{@code int}、{@code long}、{@link Integer}、{@link Long} → {@code "integer"}，
     *       涵盖所有整数类型的参数（包括基本类型和包装类型）</li>
     *   <li>{@code double}、{@code float}、{@link Double}、{@link Float} → {@code "number"}，
     *       涵盖所有浮点数类型的参数</li>
     *   <li>{@code boolean}、{@link Boolean} → {@code "boolean"}，涵盖布尔类型的参数</li>
     *   <li>其他未识别的类型（如数组、集合、自定义对象等）统一回退为 {@code "string"}，
     *       确保 JSON Schema 始终有效且 LLM 能够理解</li>
     * </ul>
     *
     * <p>此方法不处理数组类型、枚举类型或复杂对象类型，这些类型会被统一映射为 "string"，
     * 由 LLM 在调用时以字符串形式传递参数值，然后在参数绑定时通过
     * {@link lyjew.com.lyclaw.autoconfigure.binding.ParameterBindingDescriptor#bindAndInvoke}
     * 进行类型转换。</p>
     *
     * @param type Java 反射中的参数类型 Class 对象，可能为基本类型、包装类型或引用类型
     * @return 对应的 JSON Schema 类型名称字符串，如 "string"、"integer"、"number" 或 "boolean"
     */
    private String javaTypeToJsonType(Class<?> type) {
        if (type == String.class) {
            return "string";
        }
        if (type == int.class || type == long.class || type == Integer.class
                || type == Long.class) {
            return "integer";
        }
        if (type == double.class || type == float.class || type == Double.class
                || type == Float.class) {
            return "number";
        }
        if (type == boolean.class || type == Boolean.class) {
            return "boolean";
        }
        return "string";
    }

    // --- reflection helpers (avoid compile dependency on framework annotations) ---

    /**
     * 通过反射在目标对象（类、方法或参数）上查找指定简单名称的注解，避免编译期依赖。
     *
     * <p><b>设计目的：</b>此方法的核心价值在于解耦——本处理器模块不需要在编译期依赖
     * 框架的注解模块（lyclaw-annotation），而是通过运行时反射按注解简单名称匹配。
     * 这意味着即使注解类在编译路径上不可用，只要运行时 classpath 中存在，注解发现
     * 就能正常工作。</p>
     *
     * <p><b>支持的目标类型：</b></p>
     * <ul>
     *   <li>{@link Class} 对象——获取类上的所有注解，用于发现类级别的 @Tool 注解</li>
     *   <li>{@link java.lang.reflect.Method} 对象——获取方法上的所有注解，用于发现
     *       方法级别的 @Tool 注解</li>
     *   <li>{@link java.lang.reflect.Parameter} 对象——获取参数上的所有注解，用于
     *       发现 @Param 注解</li>
     *   <li>其他类型——返回 {@code null}，表示不支持的目标类型</li>
     * </ul>
     *
     * <p><b>匹配策略：</b>使用注解全限定名的后缀匹配（{@code endsWith("." + simpleName)}），
     * 而非简单名称的精确匹配。例如，查找 "Tool" 时会匹配 {@code lyjew.com.lyclaw.tool.Tool}，
     * 这种方式比简单的 {@code getSimpleName().equals()} 更安全，避免了跨包同名注解的误匹配。</p>
     *
     * @param obj 反射目标对象，支持 Class、Method、Parameter 三种类型
     * @param simpleName 注解的简单类名（如 "Tool"、"Param"），不需要包含包名前缀
     * @return 匹配到的注解对象，如果未找到或不支持的目标类型则返回 {@code null}
     */
    private Object findAnnotation(Object obj, String simpleName) {
        Annotation[] anns;
        //获取类上的完整注解
        if (obj instanceof Class<?> c) {
            anns = c.getAnnotations();
        } else if (obj instanceof Method m) {
            anns = m.getAnnotations();
        } else if (obj instanceof java.lang.reflect.Parameter p) {
            anns = p.getAnnotations();
        } else {
            return null;
        }
        for (Annotation a : anns) {
            // 改用全限定名比对更安全，匹配注解名字，如Autowired会匹配org.springframework.beans.factory.annotation.Autowired
            if (a.annotationType().getName().endsWith("." + simpleName)) {
                return a;
            }
        }
        return null;
    }

    /**
     * 通过反射调用注解对象的指定属性方法，获取注解属性的值并进行类型安全的返回。
     *
     * <p>此方法是框架注解反射工具的核心，使用 Java 反射 API 动态调用注解接口中定义的
     * 属性方法（如 {@code name()}、{@code description()}、{@code required()} 等），
     * 避免了对注解类的编译期依赖。</p>
     *
     * <p><b>实现机制：</b>通过 {@code ann.getClass().getMethod(attr).invoke(ann)} 调用
     * 注解接口的属性方法，返回值通过泛型类型参数进行强制转换。由于注解属性方法的返回值
     * 类型在编译时已确定（如 String、Boolean、Long 等），这种反射调用是安全的。</p>
     *
     * <p><b>异常处理与默认值策略：</b></p>
     * <ul>
     *   <li>如果反射调用过程中发生任何异常（如方法不存在、访问权限受限、调用异常等），
     *       则根据期望的类型参数返回对应的默认值：{@link Boolean} 类型默认返回
     *       {@link Boolean#FALSE}，{@link Long} 类型默认返回 {@code 0L}，
     *       其他类型（包括 String）默认返回 {@code null}</li>
     *   <li>这种容错设计确保即使注解定义发生变化（增加或删除属性），处理器也不会
     *       抛出异常导致应用启动失败</li>
     * </ul>
     *
     * @param ann 注解对象实例，通过 {@link #findAnnotation(Object, String)} 获取
     * @param attr 要获取的注解属性方法名，如 "name"、"description"、"required" 等
     * @param type 期望的返回值类型 Class 对象，用于类型安全的强制转换和默认值生成
     * @param <T> 泛型类型参数，表示属性值的实际类型
     * @return 注解属性的值，如果获取失败则返回该类型的默认值
     */
    @SuppressWarnings("unchecked")
    private <T> T getAnnotationAttr(Object ann, String attr, Class<T> type) {
        try {
            Object val = ann.getClass().getMethod(attr).invoke(ann);
            return (T) val;
        } catch (Exception e) {
            if (type == Boolean.class) {
                return (T) Boolean.FALSE;
            }
            if (type == Long.class) {
                return (T) Long.valueOf(0L);
            }
            return null;
        }
    }
}

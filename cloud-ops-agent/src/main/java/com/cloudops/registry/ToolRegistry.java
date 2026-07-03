package com.cloudops.registry;

import dev.langchain4j.agent.tool.Tool;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Tool 注册器 — 配置驱动动态收集启用的 Tool
 *
 * 工作原理：
 *   1. 扫描 Spring 容器中所有带 @Tool 注解方法的对象
 *   2. 按 tools-config.yml 的 enabled 列表过滤
 *   3. 返回启用的 Tool 对象列表给 AgentConfig
 *
 * 好处：
 *   - 增减 Tool 只改 YAML，不改 AgentConfig 代码
 *   - 新增 Tool 类 + 改配置即可上线，符合开闭原则
 *   - 面试讲法："配置驱动 Tool 注册，对标联通 product_config 动态加载产品能力"
 *
 * 为什么扫描 @Tool 注解而不是手动维护 Bean 列表：
 *   - 自动发现：新增 Tool 类不用记着改注册表
 *   - 防遗漏：只要标了 @Tool 就能被发现
 *   - 可过滤：发现了再按配置过滤，灵活控制
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolRegistry {

    private final ApplicationContext applicationContext;
    private final ToolRegistryConfig toolRegistryConfig;

    /** 容器中所有 Tool 对象（启动时扫描一次） */
    private Map<String, Object> allToolBeans;

    @PostConstruct
    public void init() {
        allToolBeans = scanToolBeans();
        log.info("[ToolRegistry] 扫描到 {} 个 Tool Bean: {}",
                allToolBeans.size(), allToolBeans.keySet());
    }

    /**
     * 获取启用的 Tool 列表（给 AgentConfig 用）
     *
     * @return 启用的 Tool 对象列表
     */
    public List<Object> getEnabledTools() {
        // 配置驱动模式关闭 → 返回全部
        if (!toolRegistryConfig.isConfigDriven()) {
            log.info("[ToolRegistry] 配置驱动关闭，注册全部 {} 个 Tool", allToolBeans.size());
            return new ArrayList<>(allToolBeans.values());
        }

        // 按配置过滤
        List<String> enabledNames = toolRegistryConfig.getEnabled();
        List<Object> enabledTools = new ArrayList<>();
        List<String> missingTools = new ArrayList<>();

        for (String toolName : enabledNames) {
            Object tool = allToolBeans.get(toolName);
            if (tool != null) {
                enabledTools.add(tool);
            } else {
                missingTools.add(toolName);
            }
        }

        // 警告：配置了但找不到 Bean
        if (!missingTools.isEmpty()) {
            log.warn("[ToolRegistry] 配置启用的 Tool 未找到 Bean: {}，请检查类名或 Bean 名称", missingTools);
        }

        log.info("[ToolRegistry] 配置启用 {} 个 Tool: {}",
                enabledTools.size(),
                enabledTools.stream().map(t -> t.getClass().getSimpleName()).collect(Collectors.toList()));

        return enabledTools;
    }

    /**
     * 扫描 Spring 容器中所有带 @Tool 注解方法的 Bean
     *
     * 判断标准：Bean 的任一方法上有 @Tool 注解 → 这是一个 Tool 对象
     */
    private Map<String, Object> scanToolBeans() {
        Map<String, Object> tools = new LinkedHashMap<>();

        // 拿到所有 Bean
        String[] beanNames = applicationContext.getBeanDefinitionNames();
        for (String beanName : beanNames) {
            try {
                Class<?> beanType = applicationContext.getType(beanName);
                if (beanType == null) continue;

                // 检查类的任一方法是否有 @Tool 注解
                if (hasToolAnnotation(beanType)) {
                    Object bean = applicationContext.getBean(beanName);
                    tools.put(beanName, bean);
                }
            } catch (Exception e) {
                // 跳过无法获取类型的 Bean（如 scope=prototype 等）
            }
        }

        return tools;
    }

    /**
     * 检查类的任一方法是否有 @Tool 注解
     */
    private boolean hasToolAnnotation(Class<?> clazz) {
        for (var method : clazz.getDeclaredMethods()) {
            if (method.isAnnotationPresent(Tool.class)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 获取所有已发现的 Tool Bean（调试用）
     */
    public Map<String, Object> getAllToolBeans() {
        return Collections.unmodifiableMap(allToolBeans);
    }
}

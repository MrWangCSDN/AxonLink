package com.axonlink.config;

import com.axonlink.ai.opencode.OpencodeProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;

/**
 * SPA 路由配置
 * 所有非 /api 开头的请求，若找不到静态文件，则返回 index.html
 * 让 Vue Router 处理前端路由
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final AiAnalysisConfig aiAnalysisConfig;
    private final OpencodeProperties opencodeProperties;

    public WebMvcConfig(AiAnalysisConfig aiAnalysisConfig, OpencodeProperties opencodeProperties) {
        this.aiAnalysisConfig = aiAnalysisConfig;
        this.opencodeProperties = opencodeProperties;
    }

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        long timeoutMillis = Math.max(60_000L, (long) aiAnalysisConfig.getRequestTimeoutSeconds() * 1000L + 15_000L);
        // 深度分析（opencode 多轮探索）整体超时更长：全局异步上限要罩得住它，否则 Spring 先掐断流。
        // 权衡：全局上限抬高意味着其他挂死的异步流也要等这么久才被容器兜底掐断，但各流自身
        // 都有更短的业务超时（aiAnalysis 120s / opencode 300s）先触发，全局值只是最后一道防线。
        timeoutMillis = Math.max(timeoutMillis, (long) opencodeProperties.getTimeoutSeconds() * 1000L + 15_000L);
        configurer.setDefaultTimeout(timeoutMillis);
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        Resource resource = location.createRelative(resourcePath);
                        if (resource.exists() && resource.isReadable()) {
                            return resource;
                        }
                        return new ClassPathResource("/static/index.html");
                    }
                });
    }
}

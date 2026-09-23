package com.mall.search.config;

import com.mall.search.service.ProductIndexService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 应用启动时确保 ES 商品索引存在（幂等）。
 *
 * <p>失败仅告警不阻断启动（与「ES 不可达不影响应用启动」的装配策略一致）；
 * 商品索引写入前也会再次 ensureIndex，实现自愈。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductIndexInitializer implements ApplicationRunner {

    private final ProductIndexService productIndexService;

    @Override
    public void run(ApplicationArguments args) {
        try {
            productIndexService.ensureIndex();
        } catch (Exception e) {
            log.warn("ES 商品索引初始化失败（不影响应用启动，写入索引时会重试）: {}", e.getMessage());
        }
    }
}

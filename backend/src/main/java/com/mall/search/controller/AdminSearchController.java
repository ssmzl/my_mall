package com.mall.search.controller;

import com.mall.common.Result;
import com.mall.search.service.ProductIndexService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端搜索运维接口（/admin/** 由登录拦截器要求管理员角色）。
 */
@Tag(name = "搜索-管理端", description = "ES 商品索引运维")
@RestController
@RequestMapping("/admin/search")
@RequiredArgsConstructor
public class AdminSearchController {

    private final ProductIndexService productIndexService;

    @Operation(summary = "存量商品全量同步 / 索引重建",
            description = "查库全量商品（含下架）分批 bulk 写入 ES（upsert 幂等），返回成功写入的文档数；用于首次灌数据或索引损坏后重建")
    @PostMapping("/product/syncAll")
    public Result<Integer> syncAll() {
        return Result.ok(productIndexService.syncAllProducts());
    }
}

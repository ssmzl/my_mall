package com.mall.search.controller;

import com.mall.common.PageResult;
import com.mall.common.Result;
import com.mall.search.dto.SearchProductVO;
import com.mall.search.dto.SearchQueryDTO;
import com.mall.search.service.ProductSearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商品搜索接口（GET 在登录拦截器白名单内，游客可访问）。
 */
@Tag(name = "搜索", description = "ES 商品搜索")
@RestController
@RequestMapping("/search")
@RequiredArgsConstructor
public class SearchController {

    private final ProductSearchService productSearchService;

    @Operation(summary = "商品搜索",
            description = "关键词高亮（name/subTitle 含 <em> 标签）+ 分类/价格筛选 + 综合/销量/价格排序 + 分页；keyword 可空（按筛选条件浏览）")
    @GetMapping
    public Result<PageResult<SearchProductVO>> search(SearchQueryDTO query) {
        return Result.ok(productSearchService.search(query));
    }
}

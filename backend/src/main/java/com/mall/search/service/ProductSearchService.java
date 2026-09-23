package com.mall.search.service;

import com.mall.common.PageResult;
import com.mall.search.dto.SearchProductVO;
import com.mall.search.dto.SearchQueryDTO;

/**
 * ES 商品搜索。
 */
public interface ProductSearchService {

    /**
     * 关键词搜索：name/subTitle 高亮（&lt;em&gt; 标签）+ 分类/价格筛选 + 综合/销量/价格排序 + 分页。
     */
    PageResult<SearchProductVO> search(SearchQueryDTO query);
}

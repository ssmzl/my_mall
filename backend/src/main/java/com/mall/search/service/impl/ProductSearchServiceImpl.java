package com.mall.search.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import com.mall.common.PageResult;
import com.mall.product.service.ProductService;
import com.mall.search.dto.SearchProductVO;
import com.mall.search.dto.SearchQueryDTO;
import com.mall.search.entity.EsProduct;
import com.mall.search.service.ProductIndexService;
import com.mall.search.service.ProductSearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * ES 商品搜索实现。
 *
 * <p>查询结构：bool { must: multiMatch(name^2, subTitle)；filter: status=1 + categoryId(展开子树) + 价格区间 }，
 * 筛选条件走 filter 上下文不参与评分，命中相关性只由关键词决定。
 *
 * <p>排序约定（与前端契约一致）：
 * default=综合（相关性降序，销量降序兜底）；sales=销量；priceAsc/priceDesc=价格；均以 id 降序保证分页稳定。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductSearchServiceImpl implements ProductSearchService {

    private static final int STATUS_ON = 1;
    private static final String FIELD_NAME = "name";
    private static final String FIELD_SUB_TITLE = "subTitle";
    private static final String HIGHLIGHT_PRE_TAG = "<em>";
    private static final String HIGHLIGHT_POST_TAG = "</em>";
    /** 分页大小上限，防止恶意大页拖垮 ES */
    private static final long MAX_PAGE_SIZE = 100;

    private final ElasticsearchClient elasticsearchClient;
    private final ProductIndexService productIndexService;
    private final ProductService productService;

    @Override
    public PageResult<SearchProductVO> search(SearchQueryDTO query) {
        long pageNum = Math.max(1, query.getPageNum());
        long pageSize = Math.min(MAX_PAGE_SIZE, Math.max(1, query.getPageSize()));
        String keyword = query.getKeyword() == null ? "" : query.getKeyword().trim();

        BoolQuery.Builder bool = new BoolQuery.Builder();
        if (!keyword.isEmpty()) {
            bool.must(m -> m.multiMatch(mm -> mm.query(keyword).fields("name^2", "subTitle")));
        }
        bool.filter(f -> f.term(t -> t.field("status").value(FieldValue.of(STATUS_ON))));
        // 一级/二级分类均展开为「自身 + 后代」子树过滤，与商品列表页行为一致
        if (query.getCategoryId() != null) {
            List<Long> categoryIds = productService.expandCategoryIds(query.getCategoryId());
            bool.filter(f -> f.terms(t -> t.field("categoryId")
                    .terms(v -> v.value(categoryIds.stream().map(FieldValue::of).toList()))));
        }
        if (query.getMinPrice() != null || query.getMaxPrice() != null) {
            bool.filter(f -> f.range(r -> r.number(n -> {
                n.field("price");
                if (query.getMinPrice() != null) {
                    n.gte(query.getMinPrice().doubleValue());
                }
                if (query.getMaxPrice() != null) {
                    n.lte(query.getMaxPrice().doubleValue());
                }
                return n;
            })));
        }

        SearchResponse<EsProduct> response;
        try {
            response = elasticsearchClient.search(s -> s
                            .index(productIndexService.getIndexName())
                            .from((int) ((pageNum - 1) * pageSize))
                            .size((int) pageSize)
                            .query(q -> q.bool(bool.build()))
                            .sort(buildSorts(query.getSort()))
                            .highlight(hl -> hl
                                    .preTags(HIGHLIGHT_PRE_TAG)
                                    .postTags(HIGHLIGHT_POST_TAG)
                                    .fields(FIELD_NAME, hf -> hf.numberOfFragments(0))
                                    .fields(FIELD_SUB_TITLE, hf -> hf.numberOfFragments(0))),
                    EsProduct.class);
        } catch (IOException e) {
            log.error("ES 搜索失败: keyword={}, categoryId={}, minPrice={}, maxPrice={}, sort={}",
                    keyword, query.getCategoryId(), query.getMinPrice(), query.getMaxPrice(), query.getSort(), e);
            throw new IllegalStateException("商品搜索失败，请稍后重试", e);
        }

        PageResult<SearchProductVO> result = new PageResult<>();
        result.setTotal(response.hits().total() == null ? 0L : response.hits().total().value());
        result.setCurrent(pageNum);
        result.setSize(pageSize);
        result.setRecords(response.hits().hits().stream().map(this::toVO).toList());
        return result;
    }

    private List<SortOptions> buildSorts(String sort) {
        // 非法排序值按综合排序兜底（前端下拉只会传约定值，此处防御）
        return switch (sort == null ? "default" : sort) {
            case "sales" -> List.of(
                    fieldSort("sales", SortOrder.Desc), scoreDesc(), fieldSort("id", SortOrder.Desc));
            case "priceAsc" -> List.of(
                    fieldSort("price", SortOrder.Asc), scoreDesc(), fieldSort("id", SortOrder.Desc));
            case "priceDesc" -> List.of(
                    fieldSort("price", SortOrder.Desc), scoreDesc(), fieldSort("id", SortOrder.Desc));
            default -> List.of(
                    scoreDesc(), fieldSort("sales", SortOrder.Desc), fieldSort("id", SortOrder.Desc));
        };
    }

    private SortOptions fieldSort(String field, SortOrder order) {
        return SortOptions.of(s -> s.field(f -> f.field(field).order(order)));
    }

    private SortOptions scoreDesc() {
        return SortOptions.of(s -> s.score(sc -> sc.order(SortOrder.Desc)));
    }

    /** 高亮片段优先；关键词未命中该字段时回退原始值（如只命中名称，副标题原样返回） */
    private SearchProductVO toVO(Hit<EsProduct> hit) {
        EsProduct source = hit.source();
        SearchProductVO vo = new SearchProductVO();
        vo.setId(source.getId());
        vo.setCategoryId(source.getCategoryId());
        vo.setMainPic(source.getMainPic());
        vo.setPrice(source.getPrice());
        vo.setSales(source.getSales());
        Map<String, List<String>> highlights = hit.highlight();
        vo.setName(applyHighlight(highlights, FIELD_NAME, source.getName()));
        vo.setSubTitle(applyHighlight(highlights, FIELD_SUB_TITLE, source.getSubTitle()));
        return vo;
    }

    private String applyHighlight(Map<String, List<String>> highlights, String field, String fallback) {
        List<String> fragments = highlights.get(field);
        return fragments != null && !fragments.isEmpty() ? fragments.get(0) : fallback;
    }
}

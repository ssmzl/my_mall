package com.mall.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.mall.product.entity.PmsProduct;
import com.mall.product.mapper.ProductMapper;
import com.mall.search.dto.SearchProductVO;
import com.mall.search.dto.SearchQueryDTO;
import com.mall.search.mq.ProductSyncProducer;
import com.mall.search.service.ProductIndexService;
import com.mall.search.service.ProductSearchService;
import com.mall.search.support.MiddlewareConditions;
import com.mall.common.PageResult;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 搜索同步与查询集成测试（连接 docker ES / RocketMQ / MySQL）。
 *
 * <p>使用独立的测试索引与 Topic（*_it 后缀）隔离，避免污染开发数据；
 * 中间件未启动时整体跳过（见 MiddlewareConditions），不阻塞 mvn test。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "mall.search.product-index=mall_product_it",
        "mall.search.sync-topic=mall-product-sync-it",
        "mall.search.sync-consumer-group=mall-search-sync-consumer-it"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIf(value = "com.mall.search.support.MiddlewareConditions#searchEnvAvailable",
        disabledReason = "ES/RocketMQ 未启动，跳过搜索集成测试")
class SearchSyncIntegrationTest {

    private static final String INDEX = "mall_product_it";

    /** 合成测试商品：分类 9001（两个）/ 9002 / 9003，900004 为下架状态 */
    private static final long ID_PHONE_1 = 900001L;
    private static final long ID_PHONE_2 = 900002L;
    private static final long ID_IPHONE = 900003L;
    private static final long ID_OFF_SHELF = 900004L;
    private static final long CATEGORY_PHONES = 9001L;

    @Autowired
    private ProductIndexService productIndexService;
    @Autowired
    private ProductSearchService productSearchService;
    @Autowired
    private ProductSyncProducer producer;
    @Autowired
    private ElasticsearchClient client;
    @Autowired
    private ProductMapper productMapper;

    @BeforeAll
    void cleanTestIndex() throws IOException {
        if (client.indices().exists(e -> e.index(INDEX)).value()) {
            client.indices().delete(d -> d.index(INDEX));
        }
    }

    @Test
    @Order(1)
    void ensureIndexShouldCreateIndexWithExpectedMapping() throws IOException {
        productIndexService.ensureIndex();
        productIndexService.ensureIndex(); // 幂等：重复调用不报错

        assertThat(client.indices().exists(e -> e.index(INDEX)).value()).isTrue();
        var nameProp = client.indices().getMapping(g -> g.index(INDEX))
                .result().get(INDEX).mappings().properties().get("name");
        // IK 已安装时为 ik_max_word，未安装降级 standard
        assertThat(nameProp.text().analyzer()).isIn("ik_max_word", "standard");
        var priceProp = client.indices().getMapping(g -> g.index(INDEX))
                .result().get(INDEX).mappings().properties().get("price");
        assertThat(priceProp.scaledFloat().scalingFactor()).isEqualTo(100.0);
    }

    @Test
    @Order(2)
    void indexProductShouldUpsertDocs() throws IOException {
        productIndexService.indexProduct(phone1());
        productIndexService.indexProduct(phone2());
        productIndexService.indexProduct(iphone());
        productIndexService.indexProduct(offShelf());
        refresh();

        for (Long id : List.of(ID_PHONE_1, ID_PHONE_2, ID_IPHONE, ID_OFF_SHELF)) {
            assertThat(client.get(g -> g.index(INDEX).id(String.valueOf(id)), com.mall.search.entity.EsProduct.class)
                    .found()).isTrue();
        }
    }

    @Test
    @Order(3)
    void keywordSearchShouldFilterOffShelfAndHighlight() {
        PageResult<SearchProductVO> result = search(query("手机", CATEGORY_PHONES, null, null, "default", 1, 10));

        assertThat(result.getTotal()).isEqualTo(2L);
        assertThat(ids(result)).containsExactlyInAnyOrder(ID_PHONE_1, ID_PHONE_2);
    }

    @Test
    @Order(4)
    void priceRangeFilterShouldWork() {
        PageResult<SearchProductVO> above = search(query("手机", CATEGORY_PHONES, new BigDecimal("6000"), null, "default", 1, 10));
        assertThat(ids(above)).containsExactly(ID_PHONE_1);

        PageResult<SearchProductVO> below = search(query("手机", CATEGORY_PHONES, null, new BigDecimal("6000"), "default", 1, 10));
        assertThat(ids(below)).containsExactly(ID_PHONE_2);
    }

    @Test
    @Order(5)
    void sortShouldSupportSalesAndPrice() {
        PageResult<SearchProductVO> bySales = search(query("手机", CATEGORY_PHONES, null, null, "sales", 1, 10));
        assertThat(ids(bySales)).containsExactly(ID_PHONE_1, ID_PHONE_2); // 3200 > 1500

        PageResult<SearchProductVO> byPriceAsc = search(query("手机", CATEGORY_PHONES, null, null, "priceAsc", 1, 10));
        assertThat(ids(byPriceAsc)).containsExactly(ID_PHONE_2, ID_PHONE_1); // 5999.50 < 6999.00

        PageResult<SearchProductVO> byPriceDesc = search(query("手机", CATEGORY_PHONES, null, null, "priceDesc", 1, 10));
        assertThat(ids(byPriceDesc)).containsExactly(ID_PHONE_1, ID_PHONE_2);
    }

    @Test
    @Order(6)
    void paginationShouldSplitResults() {
        PageResult<SearchProductVO> page1 = search(query("手机", CATEGORY_PHONES, null, null, "sales", 1, 1));
        assertThat(page1.getTotal()).isEqualTo(2L);
        assertThat(page1.getCurrent()).isEqualTo(1L);
        assertThat(page1.getSize()).isEqualTo(1L);
        assertThat(ids(page1)).containsExactly(ID_PHONE_1);

        PageResult<SearchProductVO> page2 = search(query("手机", CATEGORY_PHONES, null, null, "sales", 2, 1));
        assertThat(ids(page2)).containsExactly(ID_PHONE_2);
    }

    @Test
    @Order(7)
    void subTitleShouldBeHighlightedWhenMatched() {
        PageResult<SearchProductVO> result = search(query("旗舰", CATEGORY_PHONES, null, null, "default", 1, 10));

        assertThat(result.getTotal()).isEqualTo(1L);
        SearchProductVO vo = result.getRecords().get(0);
        assertThat(vo.getId()).isEqualTo(ID_PHONE_1);
        assertThat(vo.getName()).contains("<em>旗舰</em>");
        assertThat(vo.getSubTitle()).contains("<em>旗舰</em>");
    }

    @Test
    @Order(8)
    void blankKeywordShouldBrowseByFilters() {
        PageResult<SearchProductVO> result = search(query("", CATEGORY_PHONES, null, null, "default", 1, 10));
        assertThat(result.getTotal()).isEqualTo(2L);
    }

    @Test
    @Order(9)
    void mqUpsertShouldSyncProductToIndex() throws IOException {
        // 以种子商品 1（Apple iPhone 15 Pro）走真实 MQ 链路：生产者 → 消费者回查 DB → 写 ES
        PmsProduct dbProduct = productMapper.selectById(1L);
        assertThat(dbProduct).isNotNull();
        producer.sendUpsert(1L);

        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            try {
                var resp = client.get(g -> g.index(INDEX).id("1"), com.mall.search.entity.EsProduct.class);
                assertThat(resp.found()).isTrue();
                assertThat(resp.source().getName()).isEqualTo(dbProduct.getName());
                assertThat(resp.source().getPrice()).isEqualByComparingTo(dbProduct.getPrice());
                assertThat(resp.source().getSales()).isEqualTo(dbProduct.getSales());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        refresh();
    }

    @Test
    @Order(10)
    void mqDeleteShouldRemoveDocFromIndex() {
        producer.sendDelete(1L);

        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            try {
                assertThat(client.get(g -> g.index(INDEX).id("1"), com.mall.search.entity.EsProduct.class).found())
                        .isFalse();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    // ------------------------------------------------------------------
    // 测试数据与工具
    // ------------------------------------------------------------------

    private PmsProduct phone1() {
        return product(ID_PHONE_1, CATEGORY_PHONES, "华为Mate60 Pro 旗舰智能手机", "麒麟芯片 智能旗舰",
                new BigDecimal("6999.00"), 3200, 1);
    }

    private PmsProduct phone2() {
        return product(ID_PHONE_2, CATEGORY_PHONES, "小米14 Ultra 智能拍照手机", "徕卡光学 骁龙芯片",
                new BigDecimal("5999.50"), 1500, 1);
    }

    private PmsProduct iphone() {
        return product(ID_IPHONE, 9002L, "苹果 iPhone 15 Pro Max 手机", null,
                new BigDecimal("9999.00"), 8000, 1);
    }

    private PmsProduct offShelf() {
        return product(ID_OFF_SHELF, 9003L, "呆萌小熊玩偶", "毛绒玩具",
                new BigDecimal("59.90"), 10, 0);
    }

    private PmsProduct product(long id, long categoryId, String name, String subTitle,
                               BigDecimal price, int sales, int status) {
        PmsProduct p = new PmsProduct();
        p.setId(id);
        p.setCategoryId(categoryId);
        p.setName(name);
        p.setSubTitle(subTitle);
        p.setMainPic("https://picsum.photos/seed/it" + id + "/600/600");
        p.setPrice(price);
        p.setSales(sales);
        p.setStatus(status);
        return p;
    }

    private SearchQueryDTO query(String keyword, Long categoryId, BigDecimal minPrice,
                                 BigDecimal maxPrice, String sort, long pageNum, long pageSize) {
        SearchQueryDTO q = new SearchQueryDTO();
        q.setKeyword(keyword);
        q.setCategoryId(categoryId);
        q.setMinPrice(minPrice);
        q.setMaxPrice(maxPrice);
        q.setSort(sort);
        q.setPageNum(pageNum);
        q.setPageSize(pageSize);
        return q;
    }

    private PageResult<SearchProductVO> search(SearchQueryDTO q) {
        return productSearchService.search(q);
    }

    private List<Long> ids(PageResult<SearchProductVO> result) {
        // 保持 ES 返回顺序，排序断言依赖它
        return result.getRecords().stream().map(SearchProductVO::getId).toList();
    }

    private void refresh() throws IOException {
        client.indices().refresh(r -> r.index(INDEX));
    }
}

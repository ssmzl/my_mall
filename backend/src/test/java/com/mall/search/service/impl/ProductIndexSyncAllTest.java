package com.mall.search.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.json.JsonpMapper;
import co.elastic.clients.transport.endpoints.BooleanResponse;
import co.elastic.clients.util.ObjectBuilder;
import com.mall.product.entity.PmsProduct;
import com.mall.product.service.ProductService;
import jakarta.json.stream.JsonParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 存量商品全量同步 syncAllProducts 单元测试（mock ElasticsearchClient）。
 *
 * <p>BulkResponse 通过 JSON 反序列化构造（withJson），避免手写嵌套 items 结构。
 */
@ExtendWith(MockitoExtension.class)
class ProductIndexSyncAllTest {

    private static final String INDEX = "mall_product_test";

    private static final String BULK_OK_JSON = """
            {"took":1,"errors":false,"items":[
              {"index":{"_index":"%s","_id":"1","status":201}}
            ]}
            """.formatted(INDEX);

    private static final String BULK_ERROR_JSON = """
            {"took":1,"errors":true,"items":[
              {"index":{"_index":"%s","_id":"1","status":400,
                "error":{"type":"illegal_argument_exception","reason":"bad doc"}}}
            ]}
            """.formatted(INDEX);

    @Mock
    private ElasticsearchClient client;

    @Mock
    private ProductService productService;

    private ProductIndexServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ProductIndexServiceImpl(client, productService, INDEX);
    }

    @Test
    void syncAllShouldReturnZeroWhenNoProducts() throws IOException {
        mockIndexExists();
        when(productService.listAllForIndex()).thenReturn(List.of());

        int count = service.syncAllProducts();

        assertThat(count).isZero();
        verify(client, never()).bulk(anyBulkFn());
    }

    @Test
    void syncAllShouldBulkUpsertAllProducts() throws IOException {
        mockIndexExists();
        when(productService.listAllForIndex()).thenReturn(products(3));
        List<BulkRequest> captured = mockBulkOk();

        int count = service.syncAllProducts();

        assertThat(count).isEqualTo(3);
        assertThat(captured).hasSize(1);
        List<BulkOperation> operations = captured.get(0).operations();
        assertThat(operations).hasSize(3);
        // 逐条 upsert：文档 ID 与商品 ID 一致
        assertThat(operations.stream().map(op -> op.index().id())).containsExactly("1", "2", "3");
        assertThat(operations.stream().map(op -> op.index().index())).containsOnly(INDEX);
    }

    @Test
    void syncAllShouldSplitBatchesOverLimit() throws IOException {
        mockIndexExists();
        when(productService.listAllForIndex()).thenReturn(products(501));
        List<BulkRequest> captured = mockBulkOk();

        int count = service.syncAllProducts();

        assertThat(count).isEqualTo(501);
        // 单批上限 500，应拆成 500 + 1 两批
        assertThat(captured).hasSize(2);
        assertThat(captured.get(0).operations()).hasSize(500);
        assertThat(captured.get(1).operations()).hasSize(1);
    }

    @Test
    void syncAllShouldThrowWhenBulkHasErrors() throws IOException {
        mockIndexExists();
        when(productService.listAllForIndex()).thenReturn(products(1));
        when(client.bulk(anyBulkFn())).thenAnswer(inv -> bulkResponse(BULK_ERROR_JSON));

        assertThatThrownBy(() -> service.syncAllProducts())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("失败条目");
    }

    /** mock indices().exists 返回 true，跳过建索引与 IK 探测 */
    private void mockIndexExists() throws IOException {
        var indicesClient = mock(co.elastic.clients.elasticsearch.indices.ElasticsearchIndicesClient.class);
        when(client.indices()).thenReturn(indicesClient);
        when(indicesClient.exists(anyExistsFn())).thenReturn(new BooleanResponse(true));
    }

    /** 消除 exists(ExistsRequest) 与 exists(Function) 两个重载的歧义，只匹配 lambda 重载 */
    private static Function<co.elastic.clients.elasticsearch.indices.ExistsRequest.Builder,
            ObjectBuilder<co.elastic.clients.elasticsearch.indices.ExistsRequest>> anyExistsFn() {
        return any();
    }

    /** mock bulk 成功响应，并捕获每次请求用于断言 */
    private List<BulkRequest> mockBulkOk() throws IOException {
        List<BulkRequest> captured = new ArrayList<>();
        when(client.bulk(anyBulkFn())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Function<BulkRequest.Builder, ObjectBuilder<BulkRequest>> fn = inv.getArgument(0);
            captured.add(fn.apply(new BulkRequest.Builder()).build());
            return bulkResponse(BULK_OK_JSON);
        });
        return captured;
    }

    /** 消除 bulk(BulkRequest) 与 bulk(Function) 两个重载的歧义，只匹配 lambda 重载 */
    private static Function<BulkRequest.Builder, ObjectBuilder<BulkRequest>> anyBulkFn() {
        return any();
    }

    private static BulkResponse bulkResponse(String json) {
        JsonpMapper mapper = new JacksonJsonpMapper();
        JsonParser parser = mapper.jsonProvider().createParser(new StringReader(json));
        return BulkResponse.of(b -> b.withJson(parser, mapper));
    }

    private static List<PmsProduct> products(int n) {
        List<PmsProduct> list = new ArrayList<>(n);
        for (long i = 1; i <= n; i++) {
            PmsProduct p = new PmsProduct();
            p.setId(i);
            p.setCategoryId(11L);
            p.setName("商品" + i);
            p.setSubTitle("副标题" + i);
            p.setMainPic("https://picsum.photos/seed/" + i + "/600/600");
            p.setPrice(new BigDecimal("99.90"));
            p.setSales(10);
            p.setStatus(1);
            list.add(p);
        }
        return list;
    }
}

package com.mall.search.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 搜索结果条目（与前端 SearchProduct 契约对齐）。
 *
 * <p>name / subTitle 命中关键词时已包含 &lt;em&gt; 高亮标签，由前端转义后 v-html 渲染。
 */
@Data
public class SearchProductVO {

    private Long id;

    private Long categoryId;

    private String name;

    private String subTitle;

    private String mainPic;

    private BigDecimal price;

    private Integer sales;
}

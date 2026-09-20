package com.mall.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.product.entity.PmsSku;
import org.apache.ibatis.annotations.Mapper;

/**
 * 商品SKU Mapper。
 */
@Mapper
public interface SkuMapper extends BaseMapper<PmsSku> {
}

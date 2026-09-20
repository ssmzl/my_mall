package com.mall.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.product.entity.PmsProduct;
import org.apache.ibatis.annotations.Mapper;

/**
 * 商品 Mapper。
 */
@Mapper
public interface ProductMapper extends BaseMapper<PmsProduct> {
}

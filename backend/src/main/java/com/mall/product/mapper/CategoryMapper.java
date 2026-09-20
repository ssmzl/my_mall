package com.mall.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.product.entity.PmsCategory;
import org.apache.ibatis.annotations.Mapper;

/**
 * 商品分类 Mapper。
 */
@Mapper
public interface CategoryMapper extends BaseMapper<PmsCategory> {
}

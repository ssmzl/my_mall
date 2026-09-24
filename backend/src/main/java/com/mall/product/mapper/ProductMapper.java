package com.mall.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.product.entity.PmsProduct;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 商品 Mapper。
 */
@Mapper
public interface ProductMapper extends BaseMapper<PmsProduct> {

    /** 支付成功后按购买数量累加商品销量 */
    @Update("UPDATE pms_product SET sales = sales + #{quantity} WHERE id = #{id}")
    int increaseSales(@Param("id") Long id, @Param("quantity") Integer quantity);
}

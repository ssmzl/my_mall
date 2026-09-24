package com.mall.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.product.entity.PmsSku;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 商品SKU Mapper。
 */
@Mapper
public interface SkuMapper extends BaseMapper<PmsSku> {

    /**
     * 条件扣减库存：仅当剩余库存充足时扣减，返回影响行数（0 表示库存不足），
     * 由数据库原子性保证并发下不超卖。
     */
    @Update("UPDATE pms_sku SET stock = stock - #{quantity} WHERE id = #{id} AND stock >= #{quantity}")
    int deductStock(@Param("id") Long id, @Param("quantity") Integer quantity);

    /** 回补库存（取消/超时关单） */
    @Update("UPDATE pms_sku SET stock = stock + #{quantity} WHERE id = #{id}")
    int restockStock(@Param("id") Long id, @Param("quantity") Integer quantity);
}

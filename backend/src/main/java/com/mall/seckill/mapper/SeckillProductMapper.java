package com.mall.seckill.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.seckill.entity.SmsSeckillProduct;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 秒杀商品 Mapper。
 */
@Mapper
public interface SeckillProductMapper extends BaseMapper<SmsSeckillProduct> {

    /**
     * 条件扣减秒杀库存（异步建单时以 DB 为最终防线）：仅当剩余库存充足时扣减，
     * 返回影响行数（0 表示库存不足），由数据库原子性保证并发下不超卖。
     */
    @Update("UPDATE sms_seckill_product SET seckill_stock = seckill_stock - #{quantity} "
            + "WHERE id = #{id} AND seckill_stock >= #{quantity}")
    int deductSeckillStock(@Param("id") Long id, @Param("quantity") Integer quantity);

    /** 回补秒杀库存（异步建单失败时兜底归还） */
    @Update("UPDATE sms_seckill_product SET seckill_stock = seckill_stock + #{quantity} WHERE id = #{id}")
    int restockSeckillStock(@Param("id") Long id, @Param("quantity") Integer quantity);
}

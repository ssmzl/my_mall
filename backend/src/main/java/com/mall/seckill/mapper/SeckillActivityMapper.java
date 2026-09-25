package com.mall.seckill.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.seckill.entity.SmsSeckillActivity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 秒杀活动 Mapper。
 */
@Mapper
public interface SeckillActivityMapper extends BaseMapper<SmsSeckillActivity> {
}

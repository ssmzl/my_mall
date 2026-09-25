package com.mall.admin.service;

import com.mall.admin.dto.AdminSeckillActivityAddDTO;
import com.mall.admin.dto.AdminSeckillActivityUpdateDTO;
import com.mall.admin.dto.AdminSeckillProductAddDTO;

/**
 * 管理端秒杀活动配置服务。
 */
public interface AdminSeckillService {

    /** 新增秒杀活动（结束时间必须晚于开始时间；status=1 上线时预热库存到 Redis） */
    void addActivity(AdminSeckillActivityAddDTO dto);

    /** 修改秒杀活动（仅更新非空字段；置为上线时预热秒杀库存到 Redis） */
    void updateActivity(Long id, AdminSeckillActivityUpdateDTO dto);

    /** 新增活动商品（校验活动/SKU 归属；活动上线中则同步写 Redis 预热库存） */
    void addSeckillProduct(AdminSeckillProductAddDTO dto);

    /** 删除活动商品（同时清理 Redis 预热库存 key） */
    void deleteSeckillProduct(Long id);
}

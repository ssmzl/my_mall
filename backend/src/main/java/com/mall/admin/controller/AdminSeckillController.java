package com.mall.admin.controller;

import com.mall.admin.dto.AdminSeckillActivityAddDTO;
import com.mall.admin.dto.AdminSeckillActivityUpdateDTO;
import com.mall.admin.dto.AdminSeckillProductAddDTO;
import com.mall.admin.service.AdminSeckillService;
import com.mall.common.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端秒杀活动配置接口（/admin/** 由登录拦截器要求管理员角色）。
 */
@Tag(name = "管理端-秒杀", description = "秒杀活动增改、活动商品维护；上线时预热秒杀库存到 Redis")
@RestController
@RequestMapping("/admin/seckill")
@RequiredArgsConstructor
public class AdminSeckillController {

    private final AdminSeckillService adminSeckillService;

    @Operation(summary = "新增秒杀活动", description = "结束时间必须晚于开始时间；status=1 上线时预热秒杀库存到 Redis")
    @PostMapping("/activity")
    public Result<Void> addActivity(@Valid @RequestBody AdminSeckillActivityAddDTO dto) {
        adminSeckillService.addActivity(dto);
        return Result.ok(null);
    }

    @Operation(summary = "修改秒杀活动", description = "仅更新非空字段；置为上线(1)时预热秒杀库存到 Redis")
    @PutMapping("/activity/{id}")
    public Result<Void> updateActivity(@PathVariable Long id,
                                       @RequestBody AdminSeckillActivityUpdateDTO dto) {
        adminSeckillService.updateActivity(id, dto);
        return Result.ok(null);
    }

    @Operation(summary = "新增活动商品", description = "校验活动/SKU 归属；活动上线中则同步写 Redis 预热库存")
    @PostMapping("/product")
    public Result<Void> addSeckillProduct(@Valid @RequestBody AdminSeckillProductAddDTO dto) {
        adminSeckillService.addSeckillProduct(dto);
        return Result.ok(null);
    }

    @Operation(summary = "删除活动商品", description = "同时清理 Redis 预热库存 key")
    @DeleteMapping("/product/{id}")
    public Result<Void> deleteSeckillProduct(@PathVariable Long id) {
        adminSeckillService.deleteSeckillProduct(id);
        return Result.ok(null);
    }
}

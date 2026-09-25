package com.mall.seckill.controller;

import com.mall.common.Result;
import com.mall.seckill.dto.SeckillActivityVO;
import com.mall.seckill.dto.SeckillOrderVO;
import com.mall.seckill.dto.SeckillResultVO;
import com.mall.seckill.service.SeckillService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 秒杀接口（与前端 seckill.ts 契约一致）：
 * GET /api/seckill/activities 活动列表（免登录）、
 * POST /api/seckill/{seckillProductId} 秒杀下单（需登录，异步返回 taskId）、
 * GET /api/seckill/result?taskId= 结果查询（需登录，前端轮询）。
 */
@Tag(name = "秒杀")
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/seckill")
public class SeckillController {

    private final SeckillService seckillService;

    @Operation(summary = "秒杀活动列表", description = "含活动商品与实时剩余库存")
    @GetMapping("/activities")
    public Result<List<SeckillActivityVO>> activities() {
        return Result.ok(seckillService.listActivities());
    }

    @Operation(summary = "秒杀下单", description = "异步，返回 taskId 供轮询")
    @PostMapping("/{seckillProductId}")
    public Result<SeckillOrderVO> seckillOrder(@PathVariable Long seckillProductId) {
        return Result.ok(seckillService.seckillOrder(seckillProductId));
    }

    @Operation(summary = "查询秒杀下单结果", description = "QUEUED 继续轮询")
    @GetMapping("/result")
    public Result<SeckillResultVO> result(@RequestParam @NotBlank String taskId) {
        return Result.ok(seckillService.getResult(taskId));
    }
}

package com.mall.cart.controller;

import com.mall.cart.dto.CartItemDTO;
import com.mall.cart.dto.CartSelectedDTO;
import com.mall.cart.dto.CartSelectAllDTO;
import com.mall.cart.dto.CartVO;
import com.mall.cart.service.CartService;
import com.mall.common.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 购物车接口（均需登录，用户ID取自登录上下文；数据存 Redis Hash）。
 */
@Tag(name = "购物车", description = "购物车增删改查、勾选/全选、合计金额")
@RestController
@RequestMapping("/cart")
@RequiredArgsConstructor
public class CartController {

    private final CartService cartService;

    @Operation(summary = "查询购物车", description = "按加购时间升序，含总件数/勾选件数/勾选合计金额")
    @GetMapping
    public Result<CartVO> getCart() {
        return Result.ok(cartService.getCart());
    }

    @Operation(summary = "加入购物车", description = "已在购物车则累加数量；校验 SKU 存在、商品上架、库存充足")
    @PostMapping
    public Result<Void> add(@Valid @RequestBody CartItemDTO dto) {
        cartService.add(dto);
        return Result.ok();
    }

    @Operation(summary = "修改条目数量", description = "校验条目存在与库存充足")
    @PutMapping("/quantity")
    public Result<Void> updateQuantity(@Valid @RequestBody CartItemDTO dto) {
        cartService.updateQuantity(dto);
        return Result.ok();
    }

    @Operation(summary = "勾选/取消勾选单个条目")
    @PutMapping("/selected")
    public Result<Void> updateSelected(@Valid @RequestBody CartSelectedDTO dto) {
        cartService.updateSelected(dto);
        return Result.ok();
    }

    @Operation(summary = "全选/取消全选")
    @PutMapping("/selectedAll")
    public Result<Void> updateSelectedAll(@Valid @RequestBody CartSelectAllDTO dto) {
        cartService.updateSelectedAll(dto.getSelected());
        return Result.ok();
    }

    @Operation(summary = "删除条目", description = "幂等删除")
    @DeleteMapping("/{skuId}")
    public Result<Void> remove(@PathVariable Long skuId) {
        cartService.remove(skuId);
        return Result.ok();
    }
}

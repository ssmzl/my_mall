package com.mall.user.controller;

import com.mall.common.Result;
import com.mall.security.LoginUser;
import com.mall.user.dto.AddressDTO;
import com.mall.user.entity.UmsAddress;
import com.mall.user.service.AddressService;
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

import java.util.List;

/**
 * 收货地址接口（均需登录，用户ID取自登录上下文）。
 */
@Tag(name = "收货地址", description = "地址增删改查、默认地址")
@RestController
@RequestMapping("/address")
@RequiredArgsConstructor
public class AddressController {

    private final AddressService addressService;

    @Operation(summary = "地址列表", description = "默认地址置顶")
    @GetMapping("/list")
    public Result<List<UmsAddress>> list() {
        return Result.ok(addressService.listByUserId(LoginUser.getCurrentUserId()));
    }

    @Operation(summary = "新增地址", description = "首个地址自动设为默认")
    @PostMapping
    public Result<Void> add(@Valid @RequestBody AddressDTO dto) {
        addressService.add(LoginUser.getCurrentUserId(), dto);
        return Result.ok();
    }

    @Operation(summary = "修改地址")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody AddressDTO dto) {
        addressService.update(LoginUser.getCurrentUserId(), id, dto);
        return Result.ok();
    }

    @Operation(summary = "删除地址")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        addressService.delete(LoginUser.getCurrentUserId(), id);
        return Result.ok();
    }

    @Operation(summary = "设为默认地址")
    @PutMapping("/default/{id}")
    public Result<Void> setDefault(@PathVariable Long id) {
        addressService.setDefault(LoginUser.getCurrentUserId(), id);
        return Result.ok();
    }
}

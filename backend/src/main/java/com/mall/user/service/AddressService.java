package com.mall.user.service;

import com.mall.user.dto.AddressDTO;
import com.mall.user.entity.UmsAddress;

import java.util.List;

/**
 * 收货地址服务。
 */
public interface AddressService {

    /** 查询当前用户全部地址（默认地址置顶） */
    List<UmsAddress> listByUserId(Long userId);

    /** 新增地址；首个地址自动设为默认 */
    void add(Long userId, AddressDTO dto);

    /** 修改地址（校验归属） */
    void update(Long userId, Long addressId, AddressDTO dto);

    /** 删除地址（校验归属） */
    void delete(Long userId, Long addressId);

    /** 设为默认地址（同一用户仅一个默认） */
    void setDefault(Long userId, Long addressId);
}

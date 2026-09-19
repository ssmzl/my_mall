package com.mall.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.mall.common.BizException;
import com.mall.user.dto.AddressDTO;
import com.mall.user.entity.UmsAddress;
import com.mall.user.mapper.AddressMapper;
import com.mall.user.service.AddressService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 收货地址服务实现。默认地址唯一性在应用层保证：设默认前先清空该用户原有默认标记。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AddressServiceImpl implements AddressService {

    private static final int DEFAULT_TRUE = 1;
    private static final int DEFAULT_FALSE = 0;

    private final AddressMapper addressMapper;

    @Override
    public List<UmsAddress> listByUserId(Long userId) {
        return addressMapper.selectList(new LambdaQueryWrapper<UmsAddress>()
                .eq(UmsAddress::getUserId, userId)
                .orderByDesc(UmsAddress::getIsDefault)
                .orderByAsc(UmsAddress::getId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void add(Long userId, AddressDTO dto) {
        boolean shouldBeDefault = Boolean.TRUE.equals(dto.getIsDefault());
        if (!shouldBeDefault) {
            // 用户还没有任何地址时，首个地址强制设为默认
            Long count = addressMapper.selectCount(new LambdaQueryWrapper<UmsAddress>()
                    .eq(UmsAddress::getUserId, userId));
            shouldBeDefault = count == null || count == 0;
        }
        if (shouldBeDefault) {
            clearDefault(userId);
        }

        UmsAddress address = new UmsAddress();
        copyDto(dto, address);
        address.setUserId(userId);
        address.setIsDefault(shouldBeDefault ? DEFAULT_TRUE : DEFAULT_FALSE);
        addressMapper.insert(address);
        log.info("新增地址: userId={}, addressId={}, isDefault={}", userId, address.getId(), address.getIsDefault());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long userId, Long addressId, AddressDTO dto) {
        UmsAddress address = getOwnedAddress(userId, addressId);

        if (Boolean.TRUE.equals(dto.getIsDefault()) && address.getIsDefault() != DEFAULT_TRUE) {
            clearDefault(userId);
        }

        copyDto(dto, address);
        address.setIsDefault(Boolean.TRUE.equals(dto.getIsDefault()) ? DEFAULT_TRUE : DEFAULT_FALSE);
        addressMapper.updateById(address);
        log.info("修改地址: userId={}, addressId={}", userId, addressId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long userId, Long addressId) {
        getOwnedAddress(userId, addressId);
        addressMapper.deleteById(addressId);
        log.info("删除地址: userId={}, addressId={}", userId, addressId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setDefault(Long userId, Long addressId) {
        getOwnedAddress(userId, addressId);
        clearDefault(userId);
        addressMapper.update(null, new LambdaUpdateWrapper<UmsAddress>()
                .eq(UmsAddress::getId, addressId)
                .set(UmsAddress::getIsDefault, DEFAULT_TRUE));
        log.info("设置默认地址: userId={}, addressId={}", userId, addressId);
    }

    /** 查询并校验地址归属当前用户，防止越权操作他人地址 */
    private UmsAddress getOwnedAddress(Long userId, Long addressId) {
        UmsAddress address = addressMapper.selectById(addressId);
        if (address == null || !address.getUserId().equals(userId)) {
            throw new BizException("地址不存在");
        }
        return address;
    }

    /** 清空该用户所有默认地址标记，保证默认地址唯一 */
    private void clearDefault(Long userId) {
        addressMapper.update(null, new LambdaUpdateWrapper<UmsAddress>()
                .eq(UmsAddress::getUserId, userId)
                .eq(UmsAddress::getIsDefault, DEFAULT_TRUE)
                .set(UmsAddress::getIsDefault, DEFAULT_FALSE));
    }

    private void copyDto(AddressDTO dto, UmsAddress address) {
        address.setReceiverName(dto.getReceiverName());
        address.setReceiverPhone(dto.getReceiverPhone());
        address.setProvince(dto.getProvince());
        address.setCity(dto.getCity());
        address.setDistrict(dto.getDistrict());
        address.setDetailAddress(dto.getDetailAddress());
    }
}

package com.mall.user.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.mall.common.BizException;
import com.mall.user.dto.AddressDTO;
import com.mall.user.entity.UmsAddress;
import com.mall.user.mapper.AddressMapper;
import com.mall.user.service.impl.AddressServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 地址服务单元测试：默认地址唯一、归属校验（防越权）、首个地址自动默认。
 */
@ExtendWith(MockitoExtension.class)
class AddressServiceImplTest {

    private static final Long USER_ID = 1001L;

    @Mock
    private AddressMapper addressMapper;

    private AddressServiceImpl addressService;

    @BeforeAll
    static void initTableInfo() {
        // LambdaUpdateWrapper.set() 需要 MP 实体元数据缓存，mock mapper 的纯单测环境需手动初始化
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), UmsAddress.class);
    }

    @BeforeEach
    void setUp() {
        addressService = new AddressServiceImpl(addressMapper);
    }

    private AddressDTO buildDTO(boolean isDefault) {
        AddressDTO dto = new AddressDTO();
        dto.setReceiverName("张三");
        dto.setReceiverPhone("13800138000");
        dto.setProvince("广东省");
        dto.setCity("深圳市");
        dto.setDistrict("南山区");
        dto.setDetailAddress("科技园路1号");
        dto.setIsDefault(isDefault);
        return dto;
    }

    private UmsAddress buildEntity(Long id, Long userId, int isDefault) {
        UmsAddress address = new UmsAddress();
        address.setId(id);
        address.setUserId(userId);
        address.setIsDefault(isDefault);
        return address;
    }

    @Test
    void addFirstAddressShouldForceDefault() {
        when(addressMapper.selectCount(any())).thenReturn(0L);

        addressService.add(USER_ID, buildDTO(false));

        ArgumentCaptor<UmsAddress> captor = ArgumentCaptor.forClass(UmsAddress.class);
        verify(addressMapper).insert(captor.capture());
        // 用户还没有地址时，首个地址强制设为默认
        assertThat(captor.getValue().getUserId()).isEqualTo(USER_ID);
        assertThat(captor.getValue().getIsDefault()).isEqualTo(1);
    }

    @Test
    void addDefaultAddressShouldClearExistingDefault() {
        addressService.add(USER_ID, buildDTO(true));

        // 先清空旧默认（update），再插入新默认
        verify(addressMapper).update(any(), any());
        ArgumentCaptor<UmsAddress> captor = ArgumentCaptor.forClass(UmsAddress.class);
        verify(addressMapper).insert(captor.capture());
        assertThat(captor.getValue().getIsDefault()).isEqualTo(1);
    }

    @Test
    void addNonDefaultShouldNotTouchExistingDefault() {
        when(addressMapper.selectCount(any())).thenReturn(1L);

        addressService.add(USER_ID, buildDTO(false));

        verify(addressMapper, never()).update(any(), any());
        ArgumentCaptor<UmsAddress> captor = ArgumentCaptor.forClass(UmsAddress.class);
        verify(addressMapper).insert(captor.capture());
        assertThat(captor.getValue().getIsDefault()).isEqualTo(0);
    }

    @Test
    void updateOtherUsersAddressShouldThrow() {
        when(addressMapper.selectById(9L)).thenReturn(buildEntity(9L, 2002L, 0));

        assertThatThrownBy(() -> addressService.update(USER_ID, 9L, buildDTO(false)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("地址不存在");
        verify(addressMapper, never()).updateById(any(UmsAddress.class));
    }

    @Test
    void deleteOwnedAddressShouldSucceed() {
        when(addressMapper.selectById(5L)).thenReturn(buildEntity(5L, USER_ID, 1));

        addressService.delete(USER_ID, 5L);

        verify(addressMapper).deleteById(5L);
    }

    @Test
    void deleteOtherUsersAddressShouldThrow() {
        when(addressMapper.selectById(5L)).thenReturn(buildEntity(5L, 2002L, 1));

        assertThatThrownBy(() -> addressService.delete(USER_ID, 5L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("地址不存在");
        verify(addressMapper, never()).deleteById(anyLong());
    }

    @Test
    void setDefaultShouldClearOldThenSetNew() {
        when(addressMapper.selectById(5L)).thenReturn(buildEntity(5L, USER_ID, 0));

        addressService.setDefault(USER_ID, 5L);

        // 清空旧默认 + 设置新默认，两次 update
        verify(addressMapper, times(2)).update(any(), any());
    }

    @Test
    void listShouldReturnList() {
        when(addressMapper.selectList(any())).thenReturn(
                List.of(buildEntity(1L, USER_ID, 1), buildEntity(2L, USER_ID, 0)));

        List<UmsAddress> list = addressService.listByUserId(USER_ID);

        assertThat(list).hasSize(2);
    }
}

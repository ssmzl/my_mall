package com.mall.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新增/修改收货地址请求参数。
 */
@Data
public class AddressDTO {

    @NotBlank(message = "收货人姓名不能为空")
    @Size(max = 64, message = "收货人姓名最长64位")
    private String receiverName;

    @NotBlank(message = "收货人手机号不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String receiverPhone;

    @NotBlank(message = "省份不能为空")
    @Size(max = 32, message = "省份最长32位")
    private String province;

    @NotBlank(message = "城市不能为空")
    @Size(max = 32, message = "城市最长32位")
    private String city;

    @NotBlank(message = "区/县不能为空")
    @Size(max = 32, message = "区/县最长32位")
    private String district;

    @NotBlank(message = "详细地址不能为空")
    @Size(max = 255, message = "详细地址最长255位")
    private String detailAddress;

    /** 是否设为默认地址 */
    private Boolean isDefault;
}

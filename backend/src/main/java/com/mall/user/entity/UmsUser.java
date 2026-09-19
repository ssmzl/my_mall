package com.mall.user.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户表 ums_user 实体。
 */
@Data
@TableName("ums_user")
public class UmsUser implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    /** BCrypt 加密存储；禁止序列化输出 */
    @JsonIgnore
    private String password;

    private String nickname;

    private String phone;

    private String avatar;

    /** 角色：0普通用户 1管理员 */
    private Integer role;

    /** 状态：0禁用 1启用 */
    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}

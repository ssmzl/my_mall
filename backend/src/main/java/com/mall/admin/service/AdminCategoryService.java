package com.mall.admin.service;

import com.mall.admin.dto.AdminCategoryAddDTO;
import com.mall.admin.dto.AdminCategoryUpdateDTO;
import com.mall.product.entity.PmsCategory;

import java.util.List;

/**
 * 管理端分类维护服务。
 */
public interface AdminCategoryService {

    /** 新增分类（parentId=0 为一级分类，否则挂到指定一级分类下；层级固定两级） */
    void add(AdminCategoryAddDTO dto);

    /** 修改分类（仅更新非空字段：名称/排序/状态） */
    void update(Long id, AdminCategoryUpdateDTO dto);

    /** 删除分类（存在子分类或分类下有商品时拒绝） */
    void delete(Long id);

    /** 管理端分类树（含禁用分类，两级结构） */
    List<PmsCategory> tree();
}

package com.mall.cart.service;

import com.mall.cart.dto.CartItemDTO;
import com.mall.cart.dto.CartSelectedDTO;
import com.mall.cart.dto.CartVO;

/**
 * 购物车服务（Redis Hash 存储，key: mall:cart:{userId}）。
 */
public interface CartService {

    /** 查询当前用户购物车（按加购时间升序，含合计信息） */
    CartVO getCart();

    /** 加入购物车：已在购物车则累加数量；校验 SKU 存在、商品上架、库存充足 */
    void add(CartItemDTO dto);

    /** 修改条目数量：校验条目存在、库存充足 */
    void updateQuantity(CartItemDTO dto);

    /** 勾选/取消勾选单个条目 */
    void updateSelected(CartSelectedDTO dto);

    /** 全选/取消全选 */
    void updateSelectedAll(boolean selected);

    /** 删除条目（幂等） */
    void remove(Long skuId);
}

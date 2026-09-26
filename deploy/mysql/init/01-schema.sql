-- =====================================================================
-- my_mall 数据库初始化脚本（01-schema.sql）
-- 由 MySQL 容器首次启动时自动执行（/docker-entrypoint-initdb.d）
-- 所有表：InnoDB + utf8mb4，主键统一 BIGINT AUTO_INCREMENT
-- =====================================================================
SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS my_mall
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

USE my_mall;

-- ---------------------------------------------------------------------
-- 用户模块
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ums_user (
  id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  username    VARCHAR(64)  NOT NULL COMMENT '登录用户名',
  password    VARCHAR(100) NOT NULL COMMENT '密码（BCrypt 加密存储）',
  nickname    VARCHAR(64)  DEFAULT NULL COMMENT '昵称',
  phone       VARCHAR(20)  DEFAULT NULL COMMENT '手机号',
  avatar      VARCHAR(255) DEFAULT NULL COMMENT '头像URL',
  role        TINYINT      NOT NULL DEFAULT 0 COMMENT '角色：0普通用户 1管理员',
  status      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：0禁用 1启用',
  create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_username (username)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='用户表';

CREATE TABLE IF NOT EXISTS ums_address (
  id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  user_id        BIGINT       NOT NULL COMMENT '用户ID',
  receiver_name  VARCHAR(64)  NOT NULL COMMENT '收货人姓名',
  receiver_phone VARCHAR(20)  NOT NULL COMMENT '收货人手机号',
  province       VARCHAR(32)  NOT NULL COMMENT '省份',
  city           VARCHAR(32)  NOT NULL COMMENT '城市',
  district       VARCHAR(32)  NOT NULL COMMENT '区/县',
  detail_address VARCHAR(255) NOT NULL COMMENT '详细地址',
  is_default     TINYINT      NOT NULL DEFAULT 0 COMMENT '是否默认地址：0否 1是',
  create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  KEY idx_user_id (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='收货地址表';

-- ---------------------------------------------------------------------
-- 商品模块
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS pms_category (
  id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
  parent_id   BIGINT      NOT NULL DEFAULT 0 COMMENT '父分类ID，0表示一级分类',
  name        VARCHAR(64) NOT NULL COMMENT '分类名称',
  level       TINYINT     NOT NULL DEFAULT 1 COMMENT '层级：1一级分类 2二级分类',
  sort        INT         NOT NULL DEFAULT 0 COMMENT '排序值，越小越靠前',
  status      TINYINT     NOT NULL DEFAULT 1 COMMENT '状态：0禁用 1启用',
  create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  KEY idx_parent_id (parent_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='商品分类表';

CREATE TABLE IF NOT EXISTS pms_product (
  id          BIGINT         NOT NULL AUTO_INCREMENT COMMENT '主键',
  category_id BIGINT         NOT NULL COMMENT '所属二级分类ID',
  name        VARCHAR(128)   NOT NULL COMMENT '商品名称',
  sub_title   VARCHAR(255)   DEFAULT NULL COMMENT '副标题',
  main_pic    VARCHAR(512)   DEFAULT NULL COMMENT '主图URL',
  detail      TEXT           DEFAULT NULL COMMENT '商品详情（富文本/HTML）',
  price       DECIMAL(10, 2) NOT NULL DEFAULT 0.00 COMMENT '展示价格（元）',
  stock       INT UNSIGNED   NOT NULL DEFAULT 0 COMMENT '总库存（非负）',
  sales       INT UNSIGNED   NOT NULL DEFAULT 0 COMMENT '销量',
  status      TINYINT        NOT NULL DEFAULT 1 COMMENT '状态：0下架 1上架',
  create_time DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  KEY idx_category_id (category_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='商品表';

CREATE TABLE IF NOT EXISTS pms_sku (
  id          BIGINT         NOT NULL AUTO_INCREMENT COMMENT '主键',
  product_id  BIGINT         NOT NULL COMMENT '商品ID',
  sku_code    VARCHAR(64)    NOT NULL COMMENT 'SKU编码',
  spec_json   VARCHAR(1024)  DEFAULT NULL COMMENT '规格JSON，如 {"颜色":"黑色","存储":"256GB"}',
  price       DECIMAL(10, 2) NOT NULL DEFAULT 0.00 COMMENT 'SKU价格（元）',
  stock       INT UNSIGNED   NOT NULL DEFAULT 0 COMMENT 'SKU库存（非负）',
  create_time DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_sku_code (sku_code),
  KEY idx_product_id (product_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='商品SKU表';

-- ---------------------------------------------------------------------
-- 订单模块
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS oms_order (
  id               BIGINT         NOT NULL AUTO_INCREMENT COMMENT '主键',
  order_no         VARCHAR(32)    NOT NULL COMMENT '订单编号（全局唯一）',
  user_id          BIGINT         NOT NULL COMMENT '下单用户ID',
  total_amount     DECIMAL(10, 2) NOT NULL DEFAULT 0.00 COMMENT '订单总金额（元）',
  status           TINYINT        NOT NULL DEFAULT 0 COMMENT '状态：0待支付 1已支付 2已发货 3已完成 4已取消',
  address_snapshot VARCHAR(512)   DEFAULT NULL COMMENT '收货地址快照（JSON）',
  pay_time         DATETIME       DEFAULT NULL COMMENT '支付时间',
  create_time      DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time      DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_order_no (order_no),
  KEY idx_user_id (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='订单表';

CREATE TABLE IF NOT EXISTS oms_order_item (
  id           BIGINT         NOT NULL AUTO_INCREMENT COMMENT '主键',
  order_id     BIGINT         NOT NULL COMMENT '订单ID',
  product_id   BIGINT         NOT NULL COMMENT '商品ID',
  sku_id       BIGINT         NOT NULL COMMENT 'SKU ID',
  product_name VARCHAR(128)   NOT NULL COMMENT '下单时商品名称快照',
  sku_spec     VARCHAR(255)   DEFAULT NULL COMMENT '下单时SKU规格快照',
  price        DECIMAL(10, 2) NOT NULL DEFAULT 0.00 COMMENT '下单单价（元）',
  quantity     INT UNSIGNED   NOT NULL DEFAULT 1 COMMENT '购买数量',
  create_time  DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (id),
  KEY idx_order_id (order_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='订单明细表';

-- ---------------------------------------------------------------------
-- 秒杀模块
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sms_seckill_activity (
  id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  name        VARCHAR(128) NOT NULL COMMENT '活动名称',
  start_time  DATETIME     NOT NULL COMMENT '活动开始时间',
  end_time    DATETIME     NOT NULL COMMENT '活动结束时间',
  status      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：0下线 1上线',
  create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='秒杀活动表';

CREATE TABLE IF NOT EXISTS sms_seckill_product (
  id             BIGINT         NOT NULL AUTO_INCREMENT COMMENT '主键',
  activity_id    BIGINT         NOT NULL COMMENT '秒杀活动ID',
  product_id     BIGINT         NOT NULL COMMENT '商品ID',
  sku_id         BIGINT         NOT NULL COMMENT '参与秒杀的SKU ID',
  seckill_price  DECIMAL(10, 2) NOT NULL DEFAULT 0.00 COMMENT '秒杀价（元）',
  seckill_stock  INT UNSIGNED   NOT NULL DEFAULT 0 COMMENT '秒杀库存（非负）',
  limit_per_user INT UNSIGNED   NOT NULL DEFAULT 1 COMMENT '每人限购件数',
  create_time    DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (id),
  KEY idx_activity_id (activity_id),
  KEY idx_product_id (product_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='秒杀商品表';

-- ---------------------------------------------------------------------
-- AI 智能客服模块
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ai_conversation (
  id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  user_id     BIGINT       NOT NULL COMMENT '用户ID',
  title       VARCHAR(64)  DEFAULT NULL COMMENT '会话标题（首条消息前20字）',
  create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  KEY idx_user_id (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='AI客服会话表';

CREATE TABLE IF NOT EXISTS ai_message (
  id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  conversation_id BIGINT       NOT NULL COMMENT '所属会话ID',
  role            VARCHAR(16)  NOT NULL COMMENT '角色：system/user/assistant/tool',
  content         TEXT         DEFAULT NULL COMMENT '消息内容',
  tool_calls      TEXT         DEFAULT NULL COMMENT 'LLM 触发的 tool_calls JSON',
  tool_result     TEXT         DEFAULT NULL COMMENT '工具执行结果 JSON',
  create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (id),
  KEY idx_conversation_id (conversation_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='AI客服消息表';

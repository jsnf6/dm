-- ============================================================
-- 练习目标1: Lua + Redis 原子扣库存 -> Kafka 异步建单
-- 库存库/表设计(建库建表由引导方负责,业务代码自行练习)
--
-- 1) 为什么库存独立库 + 单表不分片:
--    t_order 按 userId 基因分片(2库x4表), 路由维度是"用户";
--    扣库存按 program_id 定位, 若库存表也按用户基因分片,
--    同一节目的库存会散落到多张表, 扣减要跨表, 破坏原子性且放大热点, 属错误建模。
--    故库存独立成库单表, 只承担: 预热源 / 最终一致性回补 / 对账。
-- 2) Redis 是扣减的真正执行点(Lua 原子扣), DB 表是底账:
--    CHECK 约束是数据库侧最后一道防线(可给面试官当"双保险"讲)。
-- 3) 幂等: 重复 Kafka 消息 -> 重复 INSERT t_order -> order_number 主键冲突,
--    MySQL 拒绝 -> 天然防重复建单(消费端捕获 DuplicateKeyException 即可)。
-- ============================================================

CREATE DATABASE IF NOT EXISTS `stock_db`
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;

USE `stock_db`;

CREATE TABLE IF NOT EXISTS `t_stock` (
  `program_id`  BIGINT   NOT NULL                COMMENT '节目ID(业务主键, 对应 t_order.program_id)',
  `total_stock` INT      NOT NULL                COMMENT '总库存(常量, 预热 Redis 的初始值)',
  `stock`       INT      NOT NULL                COMMENT '当前可售库存(初始=total; 回补/对账时原子扣减)',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `edit_time`   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`program_id`),
  CONSTRAINT `chk_stock_range` CHECK (`stock` >= 0 AND `stock` <= `total_stock`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='节目库存表(独立库单表, Redis 预热来源 + 最终台账)';

INSERT INTO t_stock (program_id, total_stock, stock) VALUES
  (1, 100, 100),   -- 压测主用: 100 库存, 可用 200 并发试超卖
  (2,  50,  50),
  (3, 200, 200);

-- 库存扣减流水: 幂等依据(order_number 主键), 与 t_stock 同库 -> 同事务
CREATE TABLE IF NOT EXISTS t_stock_deduct_log (
  order_number BIGINT NOT NULL COMMENT '订单号(主键,幂等依据)',
  program_id BIGINT NOT NULL COMMENT '节目ID',
  buy_count INT NOT NULL COMMENT '购买数量',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (order_number)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='库存扣减流水(与 t_stock 同库, 幂等+原子)';
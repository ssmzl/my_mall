#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""秒杀全链路压测 + 防超卖验证脚本（Python 标准库，无第三方依赖）。

流程（全部走真实 HTTP，与前端同一条链路：Tomcat → JWT 认证 → 限流 → Redis Lua 预扣
→ MQ 异步建单）：
  1. 前置检查：后端存活、管理员登录；
  2. 准备数据：注册/登录 N 个压测用户；管理员新建秒杀活动（status=1 自动预热库存）+ 活动商品；
     校验 Redis 预热库存 key 已写入；
  3. 瞬时压测：N 个用户同栅栏并发抢 1 件，记录每个请求结果与耗时；
  4. 防超卖验证：
     - HTTP 层：预扣成功数 == 库存数，失败数 == 人数 - 库存，成功用户互不重复；
     - 任务层：轮询全部 taskId 直到 SUCCESS/FAIL，订单号非空；
     - Redis 层：预扣库存归零（docker exec redis-cli）；
     - DB 层：订单数 == 成功数、DB 秒杀库存 == 0（docker exec mysql）；
  5. 清理（默认开启，--keep-data 跳过）：等待延迟关单消息消费后删除订单/活动/用户，
     删除活动商品（API，同时清 Redis 预热 key），清理 Redis 残留 key。

输出 QPS 与 P50/P95/P99 延迟、各检查项 PASS/FAIL，可直接作为面试/文档截图素材。

用法示例：
  python scripts/seckill_benchmark.py                     # 200 用户抢 100 件
  python scripts/seckill_benchmark.py --users 500 --stock 100
  python scripts/seckill_benchmark.py --keep-data         # 保留数据便于人工核查

前置条件：docker compose 中间件已启动；后端已启动（默认 http://localhost:8080/api）；
MySQL 容器 my-mall-mysql、Redis 容器 my-mall-redis、种子商品 productId=1/skuId=1 存在。
"""

import argparse
import http.client
import json
import subprocess
import sys
import threading
import time
from datetime import datetime, timedelta

# ---------------- HTTP 基础封装 ----------------


def request_once(host, port, method, path, body=None, token=None, timeout=15):
    """单次 HTTP 请求。返回 (http_status, 解析后的 dict 或 None, 错误信息)。

    后端约定：业务失败为 HTTP 200 + Result{code!=200}；限流为 HTTP 429。
    """
    conn = http.client.HTTPConnection(host, port, timeout=timeout)
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    payload = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
    try:
        conn.request(method, path, body=payload, headers=headers)
        resp = conn.getresponse()
        raw = resp.read().decode("utf-8", errors="replace")
        status = resp.status
    except (http.client.HTTPException, OSError) as e:
        return None, None, "%s: %s" % (type(e).__name__, e)
    finally:
        conn.close()
    try:
        return status, json.loads(raw), None
    except json.JSONDecodeError:
        return status, None, "响应非 JSON: %s" % raw[:200]


def call_api(args, method, path, body=None, token=None, what=""):
    """请求并严格校验 HTTP 200 + Result.code==200，返回 data。失败立即终止（不静默吞错）。"""
    status, result, err = request_once(args.host, args.port, method, args.context_path + path,
                                       body=body, token=token)
    ok = status == 200 and result is not None and result.get("code") == 200
    if not ok:
        raise RuntimeError("%s 失败: HTTP=%s err=%s result=%s"
                           % (what or path, status, err, json.dumps(result, ensure_ascii=False) if result else None))
    return result.get("data")


def percentile(sorted_values, p):
    if not sorted_values:
        return 0.0
    idx = min(len(sorted_values) - 1, max(0, int(round(p / 100.0 * len(sorted_values))) - 1))
    return sorted_values[idx]


# ---------------- docker exec（Redis / MySQL 校验与清理） ----------------


def docker_exec(cmd, what):
    proc = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
    if proc.returncode != 0:
        raise RuntimeError("%s 失败: %s%s" % (what, proc.stdout, proc.stderr))
    return (proc.stdout or "").strip()


def redis_get(args, key):
    return docker_exec(["docker", "exec", args.redis_container, "redis-cli", "GET", key],
                       "Redis GET %s" % key)


def redis_del(args, keys):
    for i in range(0, len(keys), 100):
        docker_exec(["docker", "exec", args.redis_container, "redis-cli", "DEL"] + keys[i:i + 100],
                    "Redis 批量 DEL")


def mysql_exec(args, sql, what):
    return docker_exec(["docker", "exec", args.mysql_container, "mysql", "-uroot",
                        "-p" + args.mysql_password, args.mysql_db, "-N", "-B", "-e", sql], what)


def sql_in(values):
    return ",".join("'%s'" % v for v in values)


# ---------------- 主流程 ----------------


def main():
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")

    parser = argparse.ArgumentParser(description="秒杀全链路压测 + 防超卖验证")
    parser.add_argument("--base-url", default="http://localhost:8080/api")
    parser.add_argument("--users", type=int, default=200, help="压测用户数（默认 200，每人限购 1 件）")
    parser.add_argument("--stock", type=int, default=100, help="秒杀库存（默认 100）")
    parser.add_argument("--admin", default="admin")
    parser.add_argument("--admin-password", default="123456")
    parser.add_argument("--password", default="123456", help="压测用户密码")
    parser.add_argument("--product-id", type=int, default=1, help="参与秒杀的种子商品 ID")
    parser.add_argument("--sku-id", type=int, default=1, help="参与秒杀的种子 SKU ID")
    parser.add_argument("--seckill-price", default="1.00")
    parser.add_argument("--mysql-container", default="my-mall-mysql")
    parser.add_argument("--redis-container", default="my-mall-redis")
    parser.add_argument("--mysql-password", default="root123")
    parser.add_argument("--mysql-db", default="my_mall")
    parser.add_argument("--result-timeout", type=int, default=120, help="轮询异步建单结果超时（秒）")
    parser.add_argument("--keep-data", action="store_true", help="不清理压测产生的数据（便于人工核查）")
    args = parser.parse_args()

    # 解析 base-url → host / port / context-path（如 http://localhost:8080/api）
    from urllib.parse import urlparse
    parsed = urlparse(args.base_url)
    args.host, args.port = parsed.hostname, parsed.port or 80
    args.context_path = parsed.path.rstrip("/")

    ts = datetime.now().strftime("%Y%m%d%H%M%S")
    user_prefix = "loadtest_%s" % ts
    activity_name = "压测活动_%s" % ts

    checks = []  # (名称, 是否通过, 明细)

    def check(name, ok, detail=""):
        checks.append(ok)
        print("  [%s] %s%s" % ("PASS" if ok else "FAIL", name, ("（%s）" % detail) if detail else ""))

    # ---------- 1. 前置检查 ----------
    print("=" * 62)
    print("[1/5] 前置检查")
    call_api(args, "GET", "/seckill/activities", what="后端存活检查 GET /seckill/activities")
    admin_token = call_api(args, "POST", "/user/login",
                           {"username": args.admin, "password": args.admin_password},
                           what="管理员登录")["token"]
    print("  后端存活，管理员 %s 登录成功" % args.admin)

    # ---------- 2. 准备数据：压测用户 ----------
    print("[2/5] 注册并登录 %d 个压测用户（前缀 %s）..." % (args.users, user_prefix))
    tokens = [None] * args.users
    user_ids = [None] * args.users
    reg_errors = []

    def setup_user(i):
        username = "%s_%04d" % (user_prefix, i)
        try:
            call_api(args, "POST", "/user/register",
                     {"username": username, "password": args.password, "nickname": "压测用户%04d" % i},
                     what="注册用户 %s" % username)
            data = call_api(args, "POST", "/user/login",
                            {"username": username, "password": args.password},
                            what="登录用户 %s" % username)
            tokens[i] = data["token"]
            user_ids[i] = data["userId"]
        except RuntimeError as e:
            reg_errors.append(str(e))

    from concurrent.futures import ThreadPoolExecutor
    with ThreadPoolExecutor(max_workers=20) as ex:
        list(ex.map(setup_user, range(args.users)))
    if reg_errors:
        raise RuntimeError("压测用户准备失败（前 %d 条）: %s" % (min(3, len(reg_errors)), reg_errors[:3]))
    print("  %d 个用户就绪" % args.users)

    # ---------- 3. 创建秒杀活动 + 活动商品（status=1 自动预热 Redis） ----------
    print("[3/5] 创建秒杀活动与活动商品（上线自动预热库存到 Redis）")
    now = datetime.now()
    call_api(args, "POST", "/admin/seckill/activity",
             {"name": activity_name,
              "startTime": (now - timedelta(minutes=1)).strftime("%Y-%m-%d %H:%M:%S"),
              "endTime": (now + timedelta(minutes=30)).strftime("%Y-%m-%d %H:%M:%S"),
              "status": 1},
             token=admin_token, what="新增秒杀活动")
    activities = call_api(args, "GET", "/admin/seckill/activity/list", token=admin_token,
                          what="查询活动列表")
    activity = next((a for a in activities if a["name"] == activity_name), None)
    if activity is None:
        raise RuntimeError("未找到刚创建的活动 %s" % activity_name)
    activity_id = activity["id"]

    call_api(args, "POST", "/admin/seckill/product",
             {"activityId": activity_id, "productId": args.product_id, "skuId": args.sku_id,
              "seckillPrice": args.seckill_price, "seckillStock": args.stock, "limitPerUser": 1},
             token=admin_token, what="新增活动商品")
    activities = call_api(args, "GET", "/admin/seckill/activity/list", token=admin_token,
                          what="重新查询活动列表")
    sp = next((p for a in activities if a["id"] == activity_id for p in a["products"]
               if p["productId"] == args.product_id), None)
    if sp is None:
        raise RuntimeError("未找到活动 %d 下商品 %d 的秒杀记录" % (activity_id, args.product_id))
    sp_id = sp["id"]

    stock_key = "mall:seckill:stock:%d" % sp_id
    warmed = redis_get(args, stock_key)
    if warmed != str(args.stock):
        raise RuntimeError("Redis 预热库存异常: key=%s 期望 %d 实际 %r" % (stock_key, args.stock, warmed))
    print("  活动 %d / 秒杀商品 %d，Redis 预热库存 = %s 校验通过" % (activity_id, sp_id, warmed))

    # ---------- 4. 瞬时并发抢购 ----------
    print("[4/5] %d 用户同栅栏并发抢购（库存 %d）..." % (args.users, args.stock))
    barrier = threading.Barrier(args.users)
    results = [None] * args.users  # (latency_ms, ok, taskId, http_status, code, message)

    def burst(idx):
        conn = http.client.HTTPConnection(args.host, args.port, timeout=15)
        headers = {"Content-Type": "application/json", "Authorization": "Bearer " + (tokens[idx] or "")}
        try:
            barrier.wait(timeout=60)
        except threading.BrokenBarrierError:
            results[idx] = (0.0, False, None, 0, 0, "栅栏超时")
            return
        begin = time.perf_counter()
        try:
            conn.request("POST", "%s/seckill/%d" % (args.context_path, sp_id),
                         body=b"{}", headers=headers)
            resp = conn.getresponse()
            raw = resp.read().decode("utf-8", errors="replace")
            latency = (time.perf_counter() - begin) * 1000
            result = json.loads(raw)
            data = result.get("data") or {}
            task_id = data.get("taskId") if result.get("code") == 200 else None
            results[idx] = (latency, task_id is not None, task_id, resp.status,
                            result.get("code"), result.get("message"))
        except (http.client.HTTPException, OSError, json.JSONDecodeError, ValueError) as e:
            results[idx] = ((time.perf_counter() - begin) * 1000, False, None, 0, 0,
                            "%s: %s" % (type(e).__name__, e))
        finally:
            conn.close()

    threads = [threading.Thread(target=burst, args=(i,)) for i in range(args.users)]
    burst_begin = time.perf_counter()
    for t in threads:
        t.start()
    for t in threads:
        t.join(timeout=120)
    burst_elapsed = time.perf_counter() - burst_begin
    if any(r is None for r in results):
        raise RuntimeError("存在未返回的请求（线程超时），压测结果不完整，请检查后端日志")

    latencies = sorted(r[0] for r in results)
    ok_list = [r for r in results if r[1]]
    task_ids = [r[2] for r in ok_list]
    fail_msgs = {}
    rate_limited = 0
    for r in results:
        if not r[1]:
            if r[3] == 429:
                rate_limited += 1
            fail_msgs[r[5]] = fail_msgs.get(r[5], 0) + 1

    print()
    print("=" * 62)
    print("压测结果：%d 用户瞬时并发，窗口 %.3f s" % (args.users, burst_elapsed))
    print("  预扣成功: %d   失败: %d（其中限流 429: %d）" % (len(ok_list), args.users - len(ok_list), rate_limited))
    print("  QPS: %.1f" % (args.users / burst_elapsed))
    print("  RT(ms): avg=%.1f  P50=%.1f  P95=%.1f  P99=%.1f  max=%.1f"
          % (sum(latencies) / len(latencies), percentile(latencies, 50), percentile(latencies, 95),
             percentile(latencies, 99), latencies[-1] if latencies else 0))
    for msg, cnt in sorted(fail_msgs.items(), key=lambda kv: -kv[1]):
        print("  失败分布: %r x%d" % (msg, cnt))
    print("=" * 62)

    # ---------- 5. 防超卖四层验证 ----------
    print("[5/5] 防超卖验证")

    # 5.1 HTTP 层
    check("HTTP 预扣成功数 == 库存", len(ok_list) == args.stock,
          "%d == %d" % (len(ok_list), args.stock))
    check("其余请求全部失败（售罄/限购）", len(results) - len(ok_list) == args.users - args.stock,
          "%d == %d" % (len(results) - len(ok_list), args.users - args.stock))
    check("成功 taskId 互不重复（限购未被并发击穿）", len(set(task_ids)) == len(task_ids),
          "%d == %d" % (len(set(task_ids)), len(task_ids)))
    if rate_limited:
        print("  [WARN] 有 %d 个请求触发限流（每用户 3 QPS），不影响防超卖结论" % rate_limited)

    # 5.2 任务层：轮询异步建单结果（需任务归属用户的 token）
    terminal = {}
    pending = {r[2]: idx for idx, r in enumerate(results) if r[1]}
    deadline = time.time() + args.result_timeout
    while pending and time.time() < deadline:
        time.sleep(0.5)
        for task_id, idx in list(pending.items()):
            status, result, err = request_once(args.host, args.port, "GET",
                                               "%s/seckill/result?taskId=%s" % (args.context_path, task_id),
                                               token=tokens[idx])
            data = (result or {}).get("data") or {}
            if status == 200 and data.get("status") in ("SUCCESS", "FAIL"):
                terminal[task_id] = data
                del pending[task_id]
    check("异步建单全部终态（无超时未决）", not pending,
          "剩余 %d 个: %s" % (len(pending), list(pending)[:5]) if pending else "%d 个任务" % len(terminal))
    all_success = all(d["status"] == "SUCCESS" and d.get("orderNo") for d in terminal.values())
    check("全部任务 SUCCESS 且订单号非空", all_success,
          "%d/%d 成功" % (sum(1 for d in terminal.values() if d["status"] == "SUCCESS"), len(terminal)))
    order_nos = [d["orderNo"] for d in terminal.values() if d.get("orderNo")]

    # 5.3 Redis 层：预扣库存归零
    redis_stock = redis_get(args, stock_key)
    check("Redis 预扣库存归零", redis_stock == "0", "实际 %r" % redis_stock)

    # 5.4 DB 层：订单数与库存
    db_orders = mysql_exec(args,
                           "SELECT COUNT(*) FROM oms_order WHERE order_no IN (%s)" % sql_in(order_nos),
                           "查询压测订单数")
    check("DB 订单数 == 预扣成功数", db_orders == str(args.stock),
          "%s == %d" % (db_orders, args.stock))
    db_stock = mysql_exec(args,
                          "SELECT seckill_stock FROM sms_seckill_product WHERE id=%d" % sp_id,
                          "查询 DB 秒杀库存")
    check("DB 秒杀库存扣减为 0（无负数、无超卖）", db_stock == "0", "实际 %r" % db_stock)

    print("-" * 62)
    print("防超卖验证：%s（%d/%d 项通过）"
          % ("全部通过" if all(checks) else "存在失败项！", sum(checks), len(checks)))

    # ---------- 清理 ----------
    if args.keep_data:
        print("--keep-data：已保留压测数据（活动=%s，用户前缀=%s）" % (activity_name, user_prefix))
        sys.exit(0 if all(checks) else 1)

    # 等待延迟关单消息（订单创建后约 30s 自动关单），先删订单会让消费者找不到订单打 warn
    wait = max(0.0, 35.0 - (time.time() - burst_begin))
    if wait > 0:
        print("清理：等待 %.0f s 让延迟关单消息消费完..." % wait)
        time.sleep(wait)

    print("清理压测数据...")
    call_api(args, "DELETE", "/admin/seckill/product/%d" % sp_id, token=admin_token,
             what="删除活动商品（同时清理 Redis 预热 key）")
    mysql_exec(args,
               "DELETE FROM oms_order_item WHERE order_id IN "
               "(SELECT id FROM oms_order WHERE order_no IN (%s))" % sql_in(order_nos),
               "删除压测订单项")
    mysql_exec(args, "DELETE FROM oms_order WHERE order_no IN (%s)" % sql_in(order_nos), "删除压测订单")
    mysql_exec(args, "DELETE FROM sms_seckill_product WHERE id=%d" % sp_id, "删除秒杀商品行")
    mysql_exec(args, "DELETE FROM sms_seckill_activity WHERE id=%d" % activity_id, "删除压测活动")
    mysql_exec(args, "DELETE FROM ums_user WHERE username LIKE '%s%%'" % user_prefix, "删除压测用户")

    keys = ["mall:seckill:purchased:%d:%s" % (sp_id, uid) for uid in user_ids if uid]
    keys += ["mall:seckill:task:%s" % tid for tid in task_ids]
    keys.append(stock_key)  # API 删除已清理，DEL 兜底
    redis_del(args, keys)
    print("清理完成")

    sys.exit(0 if all(checks) else 1)


if __name__ == "__main__":
    main()

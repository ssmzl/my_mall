package com.mall.search.support;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * 集成测试可用性条件：ES 与 RocketMQ 均可达时才运行（本地 docker compose 中间件），
 * 未启动中间件时相关集成测试自动跳过，保证 mvn test 在任意环境可执行。
 */
public class MiddlewareConditions {

    public static boolean searchEnvAvailable() {
        return reachable("localhost", 9200) && reachable("localhost", 9876);
    }

    private static boolean reachable(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 1000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}

package com.mall;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.mall.**.mapper")
public class MallBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(MallBackendApplication.class, args);
    }
}

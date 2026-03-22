package com.axonlink;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.axonlink.mapper")
public class AxonLinkApplication {
    public static void main(String[] args) {
        SpringApplication.run(AxonLinkApplication.class, args);
    }
}

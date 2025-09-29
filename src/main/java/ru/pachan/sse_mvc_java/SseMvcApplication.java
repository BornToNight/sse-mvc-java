package ru.pachan.sse_mvc_java;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class SseMvcApplication {
    public static void main(String[] args) {
        SpringApplication.run(SseMvcApplication.class, args);
    }

}
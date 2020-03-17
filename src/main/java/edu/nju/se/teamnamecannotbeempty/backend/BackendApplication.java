package edu.nju.se.teamnamecannotbeempty.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@EnableCaching
public class BackendApplication {
    @Bean
    public Boolean useCSVDataSource() {
        return true;
    }

    public static void main(String[] args) {
        SpringApplication.run(BackendApplication.class, args);
    }

}

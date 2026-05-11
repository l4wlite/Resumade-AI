package com.resumade;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;

@OpenAPIDefinition(
    info = @Info(
        title = "Resumade API",
        version = "1.0",
        description = "Consolidated Resumade Monolithic Backend - All Services Combined",
        contact = @Contact(name = "Resumade Team")
    )
)
@SpringBootApplication
@EnableJpaRepositories(basePackages = "com.resumade")
public class ResumadeApplication {
    public static void main(String[] args) {
        SpringApplication.run(ResumadeApplication.class, args);
    }
}

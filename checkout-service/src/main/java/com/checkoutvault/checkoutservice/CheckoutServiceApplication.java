package com.checkoutvault.checkoutservice;


import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import com.checkoutvault.checkoutservice.config.CheckoutVaultProperties;

@SpringBootApplication
@EnableConfigurationProperties(CheckoutVaultProperties.class)
public class CheckoutServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(CheckoutServiceApplication.class, args);
	}

}

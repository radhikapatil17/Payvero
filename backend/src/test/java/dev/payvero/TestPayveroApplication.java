package dev.payvero;

import org.springframework.boot.SpringApplication;

public class TestPayveroApplication {

	public static void main(String[] args) {
		SpringApplication.from(PayveroApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}

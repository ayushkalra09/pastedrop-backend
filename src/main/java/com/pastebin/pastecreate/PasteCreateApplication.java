package com.pastebin.pastecreate;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.function.Function;

@SpringBootApplication
public class PasteCreateApplication {

	public static void main(String[] args) {
		SpringApplication.run(PasteCreateApplication.class, args);
	}
}

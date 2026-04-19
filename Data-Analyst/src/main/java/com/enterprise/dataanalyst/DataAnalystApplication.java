package com.enterprise.dataanalyst;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

@SpringBootApplication
@Slf4j
public class DataAnalystApplication {

	public static void main(String[] args) {
		SpringApplication.run(DataAnalystApplication.class, args);
	}

	@EventListener(ApplicationReadyEvent.class)
	public void onStartup() {
		log.info("=========================================================");
		log.info("  Offline Data Analyst — READY");
		log.info("  UI: http://localhost:8080");
		log.info("  All data remains on this machine. Zero network calls.");
		log.info("=========================================================");
	}
}

package dev.sonar.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Errors every controller shares, as plain-text reasons the UI shows as-is. */
@RestControllerAdvice
public class ApiErrors {

	/** Bad input (unknown channel, duplicate name, missing field, OBS refusing a request): the client's fault. */
	@ExceptionHandler(IllegalArgumentException.class)
	public ResponseEntity<String> badRequest(IllegalArgumentException e) {
		return ResponseEntity.badRequest().body(e.getMessage());
	}

	/** Something we depend on isn't there right now (OBS not running). */
	@ExceptionHandler(IllegalStateException.class)
	public ResponseEntity<String> unavailable(IllegalStateException e) {
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(e.getMessage());
	}
}

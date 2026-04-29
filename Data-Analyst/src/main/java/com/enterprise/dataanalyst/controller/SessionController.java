package com.enterprise.dataanalyst.controller;

import com.enterprise.dataanalyst.dto.SessionDTO;
import com.enterprise.dataanalyst.service.session.SessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/sessions")
@RequiredArgsConstructor
@Slf4j
public class SessionController {

    private final SessionService sessionService;

    @PostMapping
    public ResponseEntity<SessionDTO> createSession() {
        try {
            return ResponseEntity.ok(
                sessionService.createSession());
        } catch (SQLException e) {
            log.error("Create session failed: {}", e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping
    public ResponseEntity<List<SessionDTO>> getAllSessions() {
        try {
            return ResponseEntity.ok(
                sessionService.getAllSessions());
        } catch (SQLException e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/{sessionId}")
    public ResponseEntity<SessionDTO> getSession(
            @PathVariable String sessionId) {
        try {
            SessionDTO session =
                sessionService.getSessionDetail(sessionId);
            if (session == null)
                return ResponseEntity.notFound().build();
            return ResponseEntity.ok(session);
        } catch (SQLException e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Map<String, String>> deleteSession(
            @PathVariable String sessionId) {
        try {
            sessionService.deleteSession(sessionId);
            return ResponseEntity.ok(
                Map.of("message", "Session deleted."));
        } catch (SQLException e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage()));
        }
    }
    }

package com.project.fitness.controller;

import com.project.fitness.security.AccountGuard;
import com.project.fitness.security.CurrentUser;
import com.project.fitness.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * The notification feed (PLAN P2-2)  →  /api/notifications
 *
 * <p>No userId segment anywhere: a feed is whatever the token says it is.
 */
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;
    private final AccountGuard accountGuard;

    @GetMapping
    public ResponseEntity<Page<Map<String, Object>>> feed(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(notificationService.getFeed(CurrentUser.id(),
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100))));
    }

    /**
     * Just the badge number.
     *
     * <p>Separate from the feed because the badge is polled and the feed is not — making
     * the header fetch a page of rows every time to count them would be the expensive
     * half of this feature running constantly.
     */
    @GetMapping("/unread-count")
    public ResponseEntity<Map<String, Object>> unreadCount() {
        return ResponseEntity.ok(Map.of("unread", notificationService.unreadCount(accountGuard.requireCurrent())));
    }

    @PostMapping("/{notificationId}/read")
    public ResponseEntity<Map<String, Object>> markRead(@PathVariable String notificationId) {
        return ResponseEntity.ok(notificationService.markRead(CurrentUser.id(), notificationId));
    }

    @PostMapping("/read-all")
    public ResponseEntity<Map<String, Object>> markAllRead() {
        return ResponseEntity.ok(Map.of("marked", notificationService.markAllRead(CurrentUser.id())));
    }
}

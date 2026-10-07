package com.project.fitness.controller;

import com.project.fitness.dto.ActivityRequest;
import com.project.fitness.dto.ActivityResponse;
import com.project.fitness.security.CurrentUser;
import com.project.fitness.service.ActivityService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Set;

@RestController
@RequestMapping("/api/activities")
@RequiredArgsConstructor
public class ActivityController {

    private final ActivityService activityService;

    /** Columns a client is allowed to sort by — anything else is rejected. */
    private static final Set<String> SORTABLE = Set.of(
            "createdAt", "updatedAt", "startTime", "duration", "caloriesBurned", "difficultyRating");

    /**
     * The activity is always logged against the authenticated user. Any userId in
     * the request body is ignored — otherwise a client could write activities into
     * someone else's account (AGENT.md §1).
     */
    @PostMapping
    public ResponseEntity<ActivityResponse> trackActivity(@RequestBody ActivityRequest request) {
        request.setUserId(CurrentUser.id());
        return ResponseEntity.ok(activityService.trackActivity(request));
    }

    /**
     * GET /api/activities?page=0&size=20&sort=createdAt,desc
     * Paginated — default page=0, size=20, sorted newest first.
     *
     * <p>Returns ActivityResponse, not the Activity entity. Handing back the entity
     * meant the list was a different shape from every other activity response, and the
     * frontend's personal-best badge read a field that only exists on the DTO.
     */
    @GetMapping
    public ResponseEntity<Page<ActivityResponse>> getUserActivities(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt,desc") String sort) {

        String[] sortParts = sort.split(",");
        String property = SORTABLE.contains(sortParts[0]) ? sortParts[0] : "createdAt";
        Sort.Direction direction = sortParts.length > 1 && sortParts[1].equalsIgnoreCase("asc")
                ? Sort.Direction.ASC : Sort.Direction.DESC;
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100),
                Sort.by(direction, property));

        return ResponseEntity.ok(activityService.getUserActivitiesPage(CurrentUser.id(), pageable));
    }

    /** Edit a logged activity. Omitted fields keep their current value. */
    @PutMapping("/{activityId}")
    public ResponseEntity<ActivityResponse> updateActivity(
            @PathVariable String activityId,
            @RequestBody ActivityRequest request) {
        return ResponseEntity.ok(
                activityService.updateActivity(CurrentUser.id(), activityId, request));
    }

    /** Delete a logged activity, rebuilding streaks, records and recovery afterwards. */
    @DeleteMapping("/{activityId}")
    public ResponseEntity<Void> deleteActivity(@PathVariable String activityId) {
        activityService.deleteActivity(CurrentUser.id(), activityId);
        return ResponseEntity.noContent().build();
    }

    // Feature 13: rate difficulty 1–5
    @PostMapping("/{activityId}/rate")
    public ResponseEntity<ActivityResponse> rateActivity(
            @PathVariable String activityId,
            @RequestBody RatingRequest request) {
        return ResponseEntity.ok(
                activityService.rateActivity(CurrentUser.id(), activityId, request.getRating()));
    }

    // Activity notes
    @PutMapping("/{activityId}/notes")
    public ResponseEntity<ActivityResponse> addNotes(
            @PathVariable String activityId,
            @RequestBody NotesRequest request) {
        return ResponseEntity.ok(
                activityService.addNotes(CurrentUser.id(), activityId, request.getNotes()));
    }

    @Data static class RatingRequest { private int rating; }
    @Data static class NotesRequest  { private String notes; }
}

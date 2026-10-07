package com.project.fitness.controller;

import com.project.fitness.model.PersonalBest;
import com.project.fitness.repository.PersonalBestRepository;
import com.project.fitness.security.AccountGuard;
import com.project.fitness.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/personal-bests")
@RequiredArgsConstructor
public class PersonalBestController {

    private final PersonalBestRepository personalBestRepository;
    private final AccountGuard accountGuard;

    @GetMapping("/{userId}")
    public ResponseEntity<List<PersonalBest>> getPersonalBests(@PathVariable String userId) {
        return ResponseEntity.ok(personalBestRepository.findByUserId(accountGuard.requireSelf(userId)));
    }
}

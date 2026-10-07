package com.project.fitness.model;

public enum ChallengeStatus {
    PENDING,    // invited, waiting for opponent to accept
    ACTIVE,     // both accepted, competition running
    COMPLETED,  // week ended, winner declared
    DECLINED,   // opponent declined
    CANCELLED   // creator cancelled before it started
}

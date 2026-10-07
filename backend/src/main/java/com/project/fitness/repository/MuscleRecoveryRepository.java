package com.project.fitness.repository;

import com.project.fitness.model.MuscleGroup;
import com.project.fitness.model.MuscleRecovery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MuscleRecoveryRepository extends JpaRepository<MuscleRecovery, String> {

    Optional<MuscleRecovery> findByUserIdAndMuscleGroup(String userId, MuscleGroup muscleGroup);

    List<MuscleRecovery> findByUserId(String userId);
}

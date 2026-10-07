package com.project.fitness.repository;

import com.project.fitness.model.UserPlanEnrollment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserPlanEnrollmentRepository extends JpaRepository<UserPlanEnrollment, String> {
    Optional<UserPlanEnrollment> findByUserIdAndIsActiveTrue(String userId);
    List<UserPlanEnrollment> findByUserId(String userId);
}

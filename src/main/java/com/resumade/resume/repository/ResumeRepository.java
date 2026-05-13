package com.resumade.resume.repository;

import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.resumade.resume.entity.Resume;

@Repository
public interface ResumeRepository extends JpaRepository<Resume, Integer> {
    @EntityGraph(attributePaths = "sections")
    java.util.Optional<Resume> findById(Integer resumeId);

    @EntityGraph(attributePaths = "sections")
    List<Resume> findByUserId(Integer userId);

    @EntityGraph(attributePaths = "sections")
    List<Resume> findByUserIdOrderByUpdatedAtDesc(Integer userId);

    @EntityGraph(attributePaths = "sections")
    List<Resume> findByIsPublicTrueOrderByViewCountDesc();

    @EntityGraph(attributePaths = "sections")
    @org.springframework.data.jpa.repository.Query("SELECT r FROM Resume r WHERE r.isPublic = true AND (" +
           "LOWER(r.title) LIKE LOWER(CONCAT('%', :q, '%')) OR " +
           "LOWER(r.targetJobTitle) LIKE LOWER(CONCAT('%', :q, '%')) OR " +
           "LOWER(r.ownerName) LIKE LOWER(CONCAT('%', :q, '%')))")
    List<Resume> searchPublicResumes(@org.springframework.data.repository.query.Param("q") String q);

    long countByUserId(Integer userId);
}

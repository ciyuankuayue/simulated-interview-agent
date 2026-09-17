package com.bupt.mockinterview.domain.repository;

import com.bupt.mockinterview.domain.Note;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NoteRepository extends JpaRepository<Note, Long> {
}

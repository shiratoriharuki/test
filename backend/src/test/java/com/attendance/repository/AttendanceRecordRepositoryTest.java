package com.attendance.repository;

import com.attendance.entity.AttendanceRecord;
import org.springframework.dao.DataIntegrityViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 勤怠レコードリポジトリテスト
 * 関連要件: REQ-UC002 - カレンダー操作, REQ-UC003 - 勤務時間入力, REQ-UC004 - 休暇登録
 */
@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AttendanceRecordRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired
    private AttendanceRecordRepository attendanceRecordRepository;

    @Test
    void findsRecordsByEmployeeId() {
        attendanceRecordRepository.saveAndFlush(record("EMP001", "2025-01-15", "SICK_LEAVE"));
        attendanceRecordRepository.saveAndFlush(record("EMP002", "2025-01-15", "PERSONAL_LEAVE"));

        var records = attendanceRecordRepository.findByEmployeeId("EMP001");

        assertEquals(1, records.size());
        assertEquals("SICK_LEAVE", records.get(0).getStatus());
    }

    @Test
    void findsLeaveByEmployeeAndDateAndReturnsEmptyWhenMissing() {
        attendanceRecordRepository.saveAndFlush(record("EMP001", "2025-01-15", "BUSINESS_TRIP"));

        Optional<AttendanceRecord> found = attendanceRecordRepository
                .findByEmployeeIdAndAttendanceDate("EMP001", LocalDate.parse("2025-01-15"));
        Optional<AttendanceRecord> missing = attendanceRecordRepository
                .findByEmployeeIdAndAttendanceDate("EMP001", LocalDate.parse("2025-01-16"));

        assertTrue(found.isPresent());
        assertEquals("BUSINESS_TRIP", found.orElseThrow().getStatus());
        assertFalse(missing.isPresent());
    }

    @Test
    void deletesTheLeaveRecordForTheSelectedDate() {
        AttendanceRecord leave = attendanceRecordRepository.saveAndFlush(
                record("EMP001", "2025-01-15", "PERSONAL_LEAVE"));

        attendanceRecordRepository.delete(leave);
        attendanceRecordRepository.flush();

        assertTrue(attendanceRecordRepository
                .findByEmployeeIdAndAttendanceDate("EMP001", LocalDate.parse("2025-01-15"))
                .isEmpty());
    }

    @Test
    void preventsMoreThanOneRecordForTheSameEmployeeAndDate() {
        attendanceRecordRepository.saveAndFlush(record("EMP001", "2025-01-15", "SICK_LEAVE"));

        assertThrows(DataIntegrityViolationException.class, () ->
                attendanceRecordRepository.saveAndFlush(record("EMP001", "2025-01-15", "BUSINESS_TRIP")));
    }

    private AttendanceRecord record(String employeeId, String date, String status) {
        AttendanceRecord record = new AttendanceRecord();
        record.setEmployeeId(employeeId);
        record.setAttendanceDate(LocalDate.parse(date));
        record.setStatus(status);
        return record;
    }
}

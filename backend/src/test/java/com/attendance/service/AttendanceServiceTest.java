package com.attendance.service;

import com.attendance.dto.AttendanceRequest;
import com.attendance.dto.AttendanceResponse;
import com.attendance.entity.AttendanceRecord;
import com.attendance.exception.AttendanceApiException;
import com.attendance.repository.AttendanceRecordRepository;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 勤怠サービステスト
 * 関連要件: REQ-UC002 - カレンダー操作, REQ-UC003 - 勤務時間入力, REQ-UC004 - 休暇登録
 */
@ExtendWith(MockitoExtension.class)
class AttendanceServiceTest {

    @Mock
    private AttendanceRecordRepository attendanceRecordRepository;

    @InjectMocks
    private AttendanceService attendanceService;

    @Test
    void savesNewAttendanceAndDefaultsStatusToWork() {
        AttendanceRequest request = request("09:00", "18:00");
        when(attendanceRecordRepository.findByEmployeeIdAndAttendanceDate("EMP001", LocalDate.parse("2025-01-15")))
                .thenReturn(Optional.empty());
        when(attendanceRecordRepository.save(any(AttendanceRecord.class))).thenAnswer(invocation -> {
            AttendanceRecord record = invocation.getArgument(0);
            record.setId(1L);
            return record;
        });

        AttendanceResponse response = attendanceService.saveAttendance(request);

        assertEquals("EMP001", response.getEmployeeId());
        assertEquals("09:00:00", response.getClockInTime());
        assertEquals("18:00:00", response.getClockOutTime());
        assertEquals("WORK", response.getStatus());
        verify(attendanceRecordRepository).save(any(AttendanceRecord.class));
    }

    @Test
    void updatesExistingAttendanceInsteadOfCreatingAnotherRecord() {
        AttendanceRecord existing = new AttendanceRecord();
        existing.setId(4L);
        existing.setEmployeeId("EMP001");
        existing.setAttendanceDate(LocalDate.parse("2025-01-15"));
        when(attendanceRecordRepository.findByEmployeeIdAndAttendanceDate("EMP001", LocalDate.parse("2025-01-15")))
                .thenReturn(Optional.of(existing));
        when(attendanceRecordRepository.save(any(AttendanceRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AttendanceResponse response = attendanceService.saveAttendance(request("08:30", "17:30"));

        assertEquals(4L, response.getId());
        assertEquals("08:30:00", response.getClockInTime());
        verify(attendanceRecordRepository).save(existing);
    }

    @Test
    void rejectsMalformedTimeBeforeMissingTime() {
        AttendanceRequest request = request("25:00", null);

        AttendanceApiException exception = assertThrows(
                AttendanceApiException.class, () -> attendanceService.saveAttendance(request));

        assertEquals("INVALID_TIME_FORMAT", exception.getError());
        assertEquals("clockInTime", exception.getDetails().get("field"));
    }

    @Test
    void rejectsMissingTime() {
        AttendanceApiException exception = assertThrows(
                AttendanceApiException.class, () -> attendanceService.saveAttendance(request("09:00", " ")));

        assertEquals("REQUIRED_TIME", exception.getError());
    }

    @Test
    void rejectsClockInAtOrAfterClockOut() {
        AttendanceApiException exception = assertThrows(
                AttendanceApiException.class, () -> attendanceService.saveAttendance(request("18:00", "09:00")));

        assertEquals("INVALID_TIME_ORDER", exception.getError());
    }

    @Test
    void rejectsEqualTimesWithoutWritingToRepository() {
        AttendanceApiException exception = assertThrows(
                AttendanceApiException.class, () -> attendanceService.saveAttendance(request("09:00", "09:00")));

        assertEquals("INVALID_TIME_ORDER", exception.getError());
        verifyNoInteractions(attendanceRecordRepository);
    }

    @Test
    void acceptsValidTimeBoundaries() {
        when(attendanceRecordRepository.findByEmployeeIdAndAttendanceDate("EMP001", LocalDate.parse("2025-01-15")))
                .thenReturn(Optional.empty());
        when(attendanceRecordRepository.save(any(AttendanceRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AttendanceResponse response = attendanceService.saveAttendance(request("00:00", "23:59"));

        assertEquals("00:00:00", response.getClockInTime());
        assertEquals("23:59:00", response.getClockOutTime());
    }

    @Test
    void rejectsInvalidTimeWithoutWritingToRepository() {
        AttendanceApiException exception = assertThrows(
                AttendanceApiException.class, () -> attendanceService.saveAttendance(request("09:60", "18:00")));

        assertEquals("INVALID_TIME_FORMAT", exception.getError());
        verifyNoInteractions(attendanceRecordRepository);
    }

    @Test
    void acceptsFutureDates() {
        AttendanceRequest request = request("09:00", "18:00");
        request.setAttendanceDate("2099-01-15");
        when(attendanceRecordRepository.findByEmployeeIdAndAttendanceDate("EMP001", LocalDate.parse("2099-01-15")))
                .thenReturn(Optional.empty());
        when(attendanceRecordRepository.save(any(AttendanceRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AttendanceResponse response = attendanceService.saveAttendance(request);

        assertEquals("2099-01-15", response.getAttendanceDate());
    }

    @Test
    void getAttendanceByDateReturnsSavedRecord() {
        AttendanceRecord record = new AttendanceRecord();
        record.setEmployeeId("EMP001");
        record.setAttendanceDate(LocalDate.parse("2025-01-15"));
        record.setClockInTime(LocalTime.parse("09:00"));
        record.setClockOutTime(LocalTime.parse("18:00"));
        when(attendanceRecordRepository.findByEmployeeIdAndAttendanceDate(eq("EMP001"), any(LocalDate.class)))
                .thenReturn(Optional.of(record));

        Optional<AttendanceResponse> response = attendanceService.getAttendanceByDate("EMP001", "2025-01-15");

        Assertions.assertTrue(response.isPresent());
        assertEquals("09:00:00", response.orElseThrow().getClockInTime());
        assertEquals("18:00:00", response.orElseThrow().getClockOutTime());
    }

    @Test
    void rejectsInvalidDateWhenFetchingAttendance() {
        AttendanceApiException exception = assertThrows(
                AttendanceApiException.class, () -> attendanceService.getAttendanceByDate("EMP001", "2025-02-30"));

        assertEquals("INVALID_DATE_FORMAT", exception.getError());
    }

    @Test
    void getsAttendanceListWithinOptionalDateRange() {
        AttendanceRecord earlier = record("2025-01-14", "08:00", "17:00");
        AttendanceRecord included = record("2025-01-15", "09:00", "18:00");
        AttendanceRecord later = record("2025-01-16", "10:00", "19:00");
        when(attendanceRecordRepository.findByEmployeeId("EMP001"))
                .thenReturn(List.of(later, included, earlier));

        List<AttendanceResponse> response = attendanceService.getAttendancesByEmployeeId(
                "EMP001", "2025-01-15", "2025-01-15");

        assertEquals(1, response.size());
        assertEquals("2025-01-15", response.get(0).getAttendanceDate());
    }

    private AttendanceRequest request(String clockInTime, String clockOutTime) {
        AttendanceRequest request = new AttendanceRequest();
        request.setEmployeeId("EMP001");
        request.setAttendanceDate("2025-01-15");
        request.setClockInTime(clockInTime);
        request.setClockOutTime(clockOutTime);
        return request;
    }

    private AttendanceRecord record(String date, String clockInTime, String clockOutTime) {
        AttendanceRecord record = new AttendanceRecord();
        record.setEmployeeId("EMP001");
        record.setAttendanceDate(LocalDate.parse(date));
        record.setClockInTime(LocalTime.parse(clockInTime));
        record.setClockOutTime(LocalTime.parse(clockOutTime));
        record.setStatus("WORK");
        return record;
    }
}

package com.attendance.service;

import com.attendance.dto.AttendanceRequest;
import com.attendance.dto.AttendanceResponse;
import com.attendance.entity.AttendanceRecord;
import com.attendance.exception.AttendanceApiException;
import com.attendance.repository.AttendanceRecordRepository;
import org.springframework.http.HttpStatus;
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
import static org.mockito.Mockito.never;

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
    void savesSupportedLeaveStatusesWithNullTimesAndOptionalNotes() {
        when(attendanceRecordRepository.findByEmployeeIdAndAttendanceDate("EMP001", LocalDate.parse("2025-01-15")))
                .thenReturn(Optional.empty());
        when(attendanceRecordRepository.save(any(AttendanceRecord.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        for (String status : List.of("SICK_LEAVE", "PERSONAL_LEAVE", "BUSINESS_TRIP")) {
            AttendanceRequest request = request(null, null);
            request.setStatus(status);
            request.setNotes("   ");

            AttendanceResponse response = attendanceService.saveAttendance(request);

            assertEquals(status, response.getStatus());
            Assertions.assertNull(response.getClockInTime());
            Assertions.assertNull(response.getClockOutTime());
            Assertions.assertNull(response.getNotes());
        }
    }

    @Test
    void savesLeaveWithNotesOnAFutureDate() {
        when(attendanceRecordRepository.findByEmployeeIdAndAttendanceDate(
                "EMP001", LocalDate.parse("2099-01-15")))
                .thenReturn(Optional.empty());
        when(attendanceRecordRepository.save(any(AttendanceRecord.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        AttendanceRequest request = request(null, null);
        request.setAttendanceDate("2099-01-15");
        request.setStatus("SICK_LEAVE");
        request.setNotes("風邪のため休暇");

        AttendanceResponse response = attendanceService.saveAttendance(request);

        assertEquals("2099-01-15", response.getAttendanceDate());
        assertEquals("SICK_LEAVE", response.getStatus());
        assertEquals("風邪のため休暇", response.getNotes());
        Assertions.assertNull(response.getClockInTime());
        Assertions.assertNull(response.getClockOutTime());
    }

    @Test
    void updatesAnExistingLeaveRecord() {
        AttendanceRecord existing = new AttendanceRecord();
        existing.setId(8L);
        existing.setEmployeeId("EMP001");
        existing.setAttendanceDate(LocalDate.parse("2025-01-15"));
        existing.setStatus("SICK_LEAVE");
        existing.setNotes("風邪");
        when(attendanceRecordRepository.findByEmployeeIdAndAttendanceDate("EMP001", LocalDate.parse("2025-01-15")))
                .thenReturn(Optional.of(existing));
        when(attendanceRecordRepository.save(any(AttendanceRecord.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        AttendanceRequest request = request(null, null);
        request.setStatus("PERSONAL_LEAVE");
        request.setNotes("私用に変更");

        AttendanceResponse response = attendanceService.saveAttendance(request);

        assertEquals(8L, response.getId());
        assertEquals("PERSONAL_LEAVE", response.getStatus());
        assertEquals("私用に変更", response.getNotes());
        verify(attendanceRecordRepository).save(existing);
    }

    @Test
    void rejectsLeaveRegistrationWhenWorkRecordExists() {
        AttendanceRecord existing = record("2025-01-15", "09:00", "18:00");
        when(attendanceRecordRepository.findByEmployeeIdAndAttendanceDate("EMP001", LocalDate.parse("2025-01-15")))
                .thenReturn(Optional.of(existing));
        AttendanceRequest request = request(null, null);
        request.setStatus("SICK_LEAVE");

        AttendanceApiException exception = assertThrows(
                AttendanceApiException.class, () -> attendanceService.saveAttendance(request));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        assertEquals("WORK_RECORD_CONFLICT", exception.getError());
        verify(attendanceRecordRepository, never()).save(any(AttendanceRecord.class));
    }

    @Test
    void rejectsUnsupportedLeaveStatusAndSpecifiedLeaveTimes() {
        AttendanceRequest unknownStatus = request(null, null);
        unknownStatus.setStatus("OTHER");
        AttendanceApiException statusException = assertThrows(
                AttendanceApiException.class, () -> attendanceService.saveAttendance(unknownStatus));
        assertEquals("INVALID_ATTENDANCE_STATUS", statusException.getError());

        for (AttendanceRequest leaveWithTime : List.of(request("09:00", null), request(null, "18:00"))) {
            leaveWithTime.setStatus("BUSINESS_TRIP");
            AttendanceApiException timeException = assertThrows(
                    AttendanceApiException.class, () -> attendanceService.saveAttendance(leaveWithTime));
            assertEquals("INVALID_LEAVE_TIMES", timeException.getError());
        }
        verifyNoInteractions(attendanceRecordRepository);
    }

    @Test
    void deletesExistingUc004LeaveRecord() {
        AttendanceRecord existing = new AttendanceRecord();
        existing.setEmployeeId("EMP001");
        existing.setAttendanceDate(LocalDate.parse("2025-01-15"));
        existing.setStatus("BUSINESS_TRIP");
        when(attendanceRecordRepository.findByEmployeeIdAndAttendanceDate("EMP001", LocalDate.parse("2025-01-15")))
                .thenReturn(Optional.of(existing));

        attendanceService.deleteLeave("EMP001", "2025-01-15");

        verify(attendanceRecordRepository).delete(existing);
    }

    @Test
    void rejectsDeletingMissingOrNonLeaveRecords() {
        when(attendanceRecordRepository.findByEmployeeIdAndAttendanceDate("EMP001", LocalDate.parse("2025-01-15")))
                .thenReturn(Optional.empty());
        AttendanceApiException missing = assertThrows(
                AttendanceApiException.class, () -> attendanceService.deleteLeave("EMP001", "2025-01-15"));
        assertEquals(HttpStatus.NOT_FOUND, missing.getStatus());
        assertEquals("NOT_FOUND", missing.getError());

        AttendanceRecord work = record("2025-01-15", "09:00", "18:00");
        when(attendanceRecordRepository.findByEmployeeIdAndAttendanceDate("EMP001", LocalDate.parse("2025-01-15")))
                .thenReturn(Optional.of(work));
        AttendanceApiException conflict = assertThrows(
                AttendanceApiException.class, () -> attendanceService.deleteLeave("EMP001", "2025-01-15"));
        assertEquals(HttpStatus.CONFLICT, conflict.getStatus());
        assertEquals("ATTENDANCE_RECORD_CONFLICT", conflict.getError());
        verify(attendanceRecordRepository, never()).delete(any(AttendanceRecord.class));
    }

    @Test
    void deletesOnlyUc004LeaveTypes() {
        for (String status : List.of("SICK_LEAVE", "PERSONAL_LEAVE", "BUSINESS_TRIP")) {
            AttendanceRecord leave = new AttendanceRecord();
            leave.setEmployeeId("EMP001");
            leave.setAttendanceDate(LocalDate.parse("2025-01-15"));
            leave.setStatus(status);
            when(attendanceRecordRepository.findByEmployeeIdAndAttendanceDate(
                    "EMP001", LocalDate.parse("2025-01-15")))
                    .thenReturn(Optional.of(leave));

            attendanceService.deleteLeave("EMP001", "2025-01-15");

            verify(attendanceRecordRepository).delete(leave);
        }
    }

    @Test
    void rejectsDeletingOtherLeaveTypesAndInvalidDates() {
        AttendanceRecord paidLeave = new AttendanceRecord();
        paidLeave.setEmployeeId("EMP001");
        paidLeave.setAttendanceDate(LocalDate.parse("2025-01-15"));
        paidLeave.setStatus("PAID_LEAVE");
        when(attendanceRecordRepository.findByEmployeeIdAndAttendanceDate(
                "EMP001", LocalDate.parse("2025-01-15")))
                .thenReturn(Optional.of(paidLeave));

        AttendanceApiException conflict = assertThrows(
                AttendanceApiException.class, () -> attendanceService.deleteLeave("EMP001", "2025-01-15"));
        assertEquals(HttpStatus.CONFLICT, conflict.getStatus());
        assertEquals("ATTENDANCE_RECORD_CONFLICT", conflict.getError());

        AttendanceApiException invalidDate = assertThrows(
                AttendanceApiException.class, () -> attendanceService.deleteLeave("EMP001", "2025-02-30"));
        assertEquals(HttpStatus.BAD_REQUEST, invalidDate.getStatus());
        assertEquals("INVALID_DATE_FORMAT", invalidDate.getError());
        verify(attendanceRecordRepository, never()).delete(any(AttendanceRecord.class));
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
        record.setId(5L);
        record.setEmployeeId("EMP001");
        record.setAttendanceDate(LocalDate.parse("2025-01-15"));
        record.setStatus("PERSONAL_LEAVE");
        record.setNotes("私用のため");
        when(attendanceRecordRepository.findByEmployeeIdAndAttendanceDate(eq("EMP001"), any(LocalDate.class)))
                .thenReturn(Optional.of(record));

        Optional<AttendanceResponse> response = attendanceService.getAttendanceByDate("EMP001", "2025-01-15");

        Assertions.assertTrue(response.isPresent());
        assertEquals("PERSONAL_LEAVE", response.orElseThrow().getStatus());
        assertEquals("私用のため", response.orElseThrow().getNotes());
        Assertions.assertNull(response.orElseThrow().getClockInTime());
        Assertions.assertNull(response.orElseThrow().getClockOutTime());
        assertEquals("2025-01-15", response.orElseThrow().getAttendanceDate());
    }

    @Test
    void getAttendanceByDateReturnsEmptyWhenRecordDoesNotExist() {
        when(attendanceRecordRepository.findByEmployeeIdAndAttendanceDate(
                "EMP001", LocalDate.parse("2025-01-15")))
                .thenReturn(Optional.empty());

        Optional<AttendanceResponse> response = attendanceService.getAttendanceByDate("EMP001", "2025-01-15");

        Assertions.assertTrue(response.isEmpty());
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

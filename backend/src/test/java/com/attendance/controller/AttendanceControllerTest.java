package com.attendance.controller;

import com.attendance.dto.AttendanceResponse;
import com.attendance.exception.AttendanceApiException;
import com.attendance.service.AttendanceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 勤怠コントローラーテスト
 * 関連要件: REQ-UC002 - カレンダー操作, REQ-UC003 - 勤務時間入力, REQ-UC004 - 休暇登録
 */
@WebMvcTest(AttendanceController.class)
class AttendanceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AttendanceService attendanceService;

    @Test
    void savesAttendanceAndReturnsTimeWithSeconds() throws Exception {
        AttendanceResponse response = new AttendanceResponse(
            1L, "EMP001", "2025-01-15", "09:00:00", "18:00:00", "WORK", null,
            "2025-01-15T09:00:00", "2025-01-15T09:00:00");
        when(attendanceService.saveAttendance(any())).thenReturn(response);

        mockMvc.perform(post("/api/attendance")
                .contentType("application/json")
                .content("""
                    {"employeeId":"EMP001","attendanceDate":"2025-01-15","clockInTime":"09:00","clockOutTime":"18:00","status":"WORK"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.clockInTime").value("09:00:00"))
            .andExpect(jsonPath("$.clockOutTime").value("18:00:00"));
    }

    @Test
    void getsAttendanceForDate() throws Exception {
        AttendanceResponse response = new AttendanceResponse(
            1L, "EMP001", "2025-01-15", "09:00:00", "18:00:00", "WORK", null,
            "2025-01-15T09:00:00", "2025-01-15T09:00:00");
        when(attendanceService.getAttendanceByDate("EMP001", "2025-01-15"))
            .thenReturn(Optional.of(response));

        mockMvc.perform(get("/api/attendance/EMP001/2025-01-15"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.employeeId").value("EMP001"))
            .andExpect(jsonPath("$.clockInTime").value("09:00:00"));
    }

    @Test
    void deletesLeaveAndReturnsNoContent() throws Exception {
        doNothing().when(attendanceService).deleteLeave("EMP001", "2025-01-15");

        mockMvc.perform(delete("/api/attendance/EMP001/2025-01-15"))
            .andExpect(status().isNoContent())
            .andExpect(jsonPath("$").doesNotExist());
    }

    @Test
    void savesLeaveAndReturnsNullTimes() throws Exception {
        when(attendanceService.saveAttendance(any())).thenReturn(new AttendanceResponse(
                2L, "EMP001", "2025-01-15", null, null, "PERSONAL_LEAVE", null,
                "2025-01-15T09:00:00", "2025-01-15T09:00:00"));

        mockMvc.perform(post("/api/attendance")
                        .contentType("application/json")
                        .content("""
                            {"employeeId":"EMP001","attendanceDate":"2025-01-15","clockInTime":null,"clockOutTime":null,"status":"PERSONAL_LEAVE","notes":null}
                            """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PERSONAL_LEAVE"))
                .andExpect(jsonPath("$.clockInTime").value(nullValue()))
                .andExpect(jsonPath("$.clockOutTime").value(nullValue()))
                .andExpect(jsonPath("$.notes").value(nullValue()));
    }

    @Test
    void returnsConflictWhenLeaveWouldReplaceWorkRecord() throws Exception {
        when(attendanceService.saveAttendance(any())).thenThrow(new AttendanceApiException(
                HttpStatus.CONFLICT,
                "WORK_RECORD_CONFLICT",
                "対象日に勤務記録が存在するため、休暇として登録できません",
                Map.of("employeeId", "EMP001", "attendanceDate", "2025-01-15")));

        mockMvc.perform(post("/api/attendance")
                        .contentType("application/json")
                        .content("""
                            {"employeeId":"EMP001","attendanceDate":"2025-01-15","status":"SICK_LEAVE","clockInTime":null,"clockOutTime":null}
                            """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("WORK_RECORD_CONFLICT"))
                .andExpect(jsonPath("$.details.employeeId").value("EMP001"));
    }

    @Test
    void returnsNotFoundWhenDeletingMissingLeave() throws Exception {
        doThrow(new AttendanceApiException(
                HttpStatus.NOT_FOUND,
                "NOT_FOUND",
                "休暇レコードが見つかりません。最新の状態を確認してください",
                Map.of("employeeId", "EMP001", "date", "2025-01-15")))
                .when(attendanceService).deleteLeave("EMP001", "2025-01-15");

        mockMvc.perform(delete("/api/attendance/EMP001/2025-01-15"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void returnsConflictWhenDeletingNonLeaveRecord() throws Exception {
        doThrow(new AttendanceApiException(
                HttpStatus.CONFLICT,
                "ATTENDANCE_RECORD_CONFLICT",
                "対象レコードは休暇ではないため削除できません",
                Map.of("employeeId", "EMP001", "date", "2025-01-15", "status", "WORK")))
                .when(attendanceService).deleteLeave("EMP001", "2025-01-15");

        mockMvc.perform(delete("/api/attendance/EMP001/2025-01-15"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("ATTENDANCE_RECORD_CONFLICT"))
                .andExpect(jsonPath("$.details.status").value("WORK"));
    }

    @Test
    void returnsStructuredNotFoundForMissingAttendance() throws Exception {
        when(attendanceService.getAttendanceByDate("EMP001", "2025-01-15"))
            .thenReturn(Optional.empty());

        mockMvc.perform(get("/api/attendance/EMP001/2025-01-15"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("NOT_FOUND"))
            .andExpect(jsonPath("$.message").value("勤怠レコードが見つかりません"));
    }

    @Test
    void returnsStructuredValidationError() throws Exception {
        when(attendanceService.saveAttendance(any())).thenThrow(new AttendanceApiException(
            HttpStatus.BAD_REQUEST, "INVALID_TIME_ORDER", "出勤時刻は退勤時刻より前である必要があります",
            Map.of("field", "clockOutTime", "rejectedValue", "09:00", "reason", "invalid order")));

        mockMvc.perform(post("/api/attendance")
                .contentType("application/json")
                .content("""
                    {"employeeId":"EMP001","attendanceDate":"2025-01-15","clockInTime":"18:00","clockOutTime":"09:00","status":"WORK"}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("INVALID_TIME_ORDER"))
            .andExpect(jsonPath("$.details.field").value("clockOutTime"));
    }

    @Test
    void getsEmployeeAttendanceList() throws Exception {
        AttendanceResponse response = new AttendanceResponse(
                1L, "EMP001", "2025-01-15", "09:00:00", "18:00:00", "WORK", null,
                "2025-01-15T09:00:00", "2025-01-15T09:00:00");
        when(attendanceService.getAttendancesByEmployeeId("EMP001", "2025-01-01", "2025-01-31"))
                .thenReturn(List.of(response));

        mockMvc.perform(get("/api/attendance/EMP001")
                        .param("startDate", "2025-01-01")
                        .param("endDate", "2025-01-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].attendanceDate").value("2025-01-15"));
    }
}

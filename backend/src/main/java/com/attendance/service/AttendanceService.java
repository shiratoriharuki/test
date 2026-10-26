package com.attendance.service;

import com.attendance.dto.AttendanceRequest;
import com.attendance.dto.AttendanceResponse;
import com.attendance.entity.AttendanceRecord;
import com.attendance.exception.AttendanceApiException;
import com.attendance.repository.AttendanceRecordRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 勤怠サービス
 * 関連要件: REQ-UC002 - カレンダー操作, REQ-UC003 - 勤務時間入力, REQ-UC004 - 休暇登録
 */
@Service
@Transactional
public class AttendanceService {

    private static final Pattern TIME_PATTERN = Pattern.compile("^([01][0-9]|2[0-3]):[0-5][0-9]$");
    private static final DateTimeFormatter RESPONSE_TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter RESPONSE_TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final AttendanceRecordRepository attendanceRecordRepository;

    public AttendanceService(AttendanceRecordRepository attendanceRecordRepository) {
        this.attendanceRecordRepository = attendanceRecordRepository;
    }

    /** 従業員IDと日付をキーに勤怠レコードを新規作成または更新する */
    public AttendanceResponse saveAttendance(AttendanceRequest request) {
        if (request == null || isBlank(request.getEmployeeId())) {
            throw validationError("INVALID_REQUEST", "従業員IDを入力してください", "employeeId", null,
                    "必須項目です");
        }

        LocalDate attendanceDate = parseDate(request.getAttendanceDate());
        String status = isBlank(request.getStatus()) ? "WORK" : request.getStatus();
        LocalTime clockInTime = null;
        LocalTime clockOutTime = null;

        if ("WORK".equals(status)) {
            validateTimes(request.getClockInTime(), request.getClockOutTime());
            clockInTime = LocalTime.parse(request.getClockInTime());
            clockOutTime = LocalTime.parse(request.getClockOutTime());
        }

        AttendanceRecord record = attendanceRecordRepository
                .findByEmployeeIdAndAttendanceDate(request.getEmployeeId(), attendanceDate)
                .orElseGet(AttendanceRecord::new);
        record.setEmployeeId(request.getEmployeeId());
        record.setAttendanceDate(attendanceDate);
        record.setClockInTime(clockInTime);
        record.setClockOutTime(clockOutTime);
        record.setStatus(status);
        record.setNotes(request.getNotes());

        return toResponse(attendanceRecordRepository.save(record));
    }

    /** 従業員IDと日付で勤怠レコードを取得する */
    public Optional<AttendanceResponse> getAttendanceByDate(String employeeId, String date) {
        LocalDate attendanceDate = parseDate(date);
        return attendanceRecordRepository.findByEmployeeIdAndAttendanceDate(employeeId, attendanceDate)
                .map(this::toResponse);
    }

    /** 従業員IDで勤怠レコード一覧を取得する */
    public List<AttendanceResponse> getAttendancesByEmployeeId(String employeeId, String startDate, String endDate) {
        LocalDate start = isBlank(startDate) ? null : parseDate(startDate);
        LocalDate end = isBlank(endDate) ? null : parseDate(endDate);
        if (start != null && end != null && start.isAfter(end)) {
            throw validationError("INVALID_DATE_RANGE", "取得期間の開始日は終了日以前である必要があります",
                    "startDate", startDate, "endDate より後の日付です");
        }

        return attendanceRecordRepository.findByEmployeeId(employeeId).stream()
                .filter(record -> start == null || !record.getAttendanceDate().isBefore(start))
                .filter(record -> end == null || !record.getAttendanceDate().isAfter(end))
                .sorted((left, right) -> left.getAttendanceDate().compareTo(right.getAttendanceDate()))
                .map(this::toResponse)
                .toList();
    }

    private void validateTimes(String clockInTime, String clockOutTime) {
        if (isInvalidTime(clockInTime)) {
            throw validationError("INVALID_TIME_FORMAT", "有効な時刻形式で入力してください",
                    "clockInTime", clockInTime, "HH:MM 形式ではありません");
        }
        if (isInvalidTime(clockOutTime)) {
            throw validationError("INVALID_TIME_FORMAT", "有効な時刻形式で入力してください",
                    "clockOutTime", clockOutTime, "HH:MM 形式ではありません");
        }
        if (isBlank(clockInTime) || isBlank(clockOutTime)) {
            String field = isBlank(clockInTime) ? "clockInTime" : "clockOutTime";
            String value = isBlank(clockInTime) ? clockInTime : clockOutTime;
            throw validationError("REQUIRED_TIME", "出勤時刻と退勤時刻を入力してください",
                    field, value, "必須項目です");
        }
        if (!LocalTime.parse(clockInTime).isBefore(LocalTime.parse(clockOutTime))) {
            throw validationError("INVALID_TIME_ORDER", "出勤時刻は退勤時刻より前である必要があります",
                    "clockOutTime", clockOutTime, "clockInTime 以前の時刻です");
        }
    }

    private boolean isInvalidTime(String value) {
        return !isBlank(value) && !TIME_PATTERN.matcher(value).matches();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException | NullPointerException exception) {
            throw validationError("INVALID_DATE_FORMAT", "日付の形式が不正です",
                    "attendanceDate", value, "YYYY-MM-DD 形式ではありません");
        }
    }

    private AttendanceApiException validationError(
            String code, String message, String field, Object rejectedValue, String reason) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("field", field);
        details.put("rejectedValue", rejectedValue);
        details.put("reason", reason);
        return new AttendanceApiException(HttpStatus.BAD_REQUEST, code, message, details);
    }

    private AttendanceResponse toResponse(AttendanceRecord record) {
        return new AttendanceResponse(
                record.getId(),
                record.getEmployeeId(),
                record.getAttendanceDate().toString(),
                formatTime(record.getClockInTime()),
                formatTime(record.getClockOutTime()),
                record.getStatus(),
                record.getNotes(),
                formatTimestamp(record.getCreatedAt()),
                formatTimestamp(record.getUpdatedAt()));
    }

    private String formatTime(LocalTime time) {
        return time == null ? null : RESPONSE_TIME_FORMAT.format(time);
    }

    private String formatTimestamp(LocalDateTime timestamp) {
        return timestamp == null ? null : RESPONSE_TIMESTAMP_FORMAT.format(timestamp);
    }
}

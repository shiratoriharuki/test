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
    private static final List<String> UC004_LEAVE_STATUSES = List.of(
            "SICK_LEAVE", "PERSONAL_LEAVE", "BUSINESS_TRIP");
    private static final List<String> ATTENDANCE_STATUSES = List.of(
            "WORK", "PAID_LEAVE", "SICK_LEAVE", "UNPAID_LEAVE", "PERSONAL_LEAVE", "BUSINESS_TRIP");
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
        if (!ATTENDANCE_STATUSES.contains(status)) {
            throw validationError("INVALID_ATTENDANCE_STATUS", "選択された休暇種別が正しくありません",
                    "status", status, "定義済みの勤怠ステータスではありません");
        }

        LocalTime clockInTime = null;
        LocalTime clockOutTime = null;

        if ("WORK".equals(status)) {
            validateTimes(request.getClockInTime(), request.getClockOutTime());
            clockInTime = LocalTime.parse(request.getClockInTime());
            clockOutTime = LocalTime.parse(request.getClockOutTime());
        } else if (request.getClockInTime() != null || request.getClockOutTime() != null) {
            throw validationError("INVALID_LEAVE_TIMES", "休暇登録では出勤時刻と退勤時刻を指定できません",
                    request.getClockInTime() != null ? "clockInTime" : "clockOutTime",
                    request.getClockInTime() != null ? request.getClockInTime() : request.getClockOutTime(),
                    "休暇時刻は両方nullである必要があります");
        }

        Optional<AttendanceRecord> existingRecord = attendanceRecordRepository
                .findByEmployeeIdAndAttendanceDate(request.getEmployeeId(), attendanceDate);
        if (!"WORK".equals(status) && existingRecord
                .filter(record -> "WORK".equals(record.getStatus()))
                .isPresent()) {
            throw new AttendanceApiException(
                    HttpStatus.CONFLICT,
                    "WORK_RECORD_CONFLICT",
                    "対象日に勤務記録が存在するため、休暇として登録できません",
                    Map.of("employeeId", request.getEmployeeId(), "attendanceDate", attendanceDate.toString()));
        }

        AttendanceRecord record = existingRecord.orElseGet(AttendanceRecord::new);
        record.setEmployeeId(request.getEmployeeId());
        record.setAttendanceDate(attendanceDate);
        record.setClockInTime(clockInTime);
        record.setClockOutTime(clockOutTime);
        record.setStatus(status);
        String notes = request.getNotes();
        if (!"WORK".equals(status) && isBlank(notes)) {
            notes = null;
        }
        record.setNotes(notes);

        return toResponse(attendanceRecordRepository.save(record));
    }

    /** UC004で扱う休暇レコードを削除する */
    public void deleteLeave(String employeeId, String date) {
        LocalDate attendanceDate = parseDate(date);
        AttendanceRecord record = attendanceRecordRepository
                .findByEmployeeIdAndAttendanceDate(employeeId, attendanceDate)
                .orElseThrow(() -> new AttendanceApiException(
                        HttpStatus.NOT_FOUND,
                        "NOT_FOUND",
                        "休暇レコードが見つかりません。最新の状態を確認してください",
                        Map.of("employeeId", employeeId, "date", date)));

        if (!UC004_LEAVE_STATUSES.contains(record.getStatus())) {
            throw new AttendanceApiException(
                    HttpStatus.CONFLICT,
                    "ATTENDANCE_RECORD_CONFLICT",
                    "対象レコードは休暇ではないため削除できません",
                    Map.of("employeeId", employeeId, "date", date, "status",
                            record.getStatus() == null ? "" : record.getStatus()));
        }

        attendanceRecordRepository.delete(record);
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

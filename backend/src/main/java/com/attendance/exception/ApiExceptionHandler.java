package com.attendance.exception;

import com.attendance.dto.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.OffsetDateTime;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(AttendanceApiException.class)
    public ResponseEntity<ErrorResponse> handleAttendanceApiException(AttendanceApiException exception) {
        ErrorResponse response = new ErrorResponse(
                exception.getError(),
                exception.getMessage(),
                OffsetDateTime.now(),
                exception.getDetails());
        return ResponseEntity.status(exception.getStatus()).body(response);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpectedException(Exception exception) {
        ErrorResponse response = new ErrorResponse(
                "INTERNAL_SERVER_ERROR",
                "予期しないエラーが発生しました。システム管理者に連絡してください。",
                OffsetDateTime.now(),
                null);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }
}
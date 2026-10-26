package com.attendance.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ErrorResponse {

    private String error;
    private String message;
    private OffsetDateTime timestamp;
    private Map<String, Object> details;
}
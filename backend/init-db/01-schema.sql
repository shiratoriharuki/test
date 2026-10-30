-- 勤怠レコードテーブル

CREATE TABLE IF NOT EXISTS attendance_records (
    id BIGSERIAL PRIMARY KEY,
    employee_id VARCHAR(50) NOT NULL,
    attendance_date DATE NOT NULL,
    clock_in_time TIME,
    clock_out_time TIME,
    status VARCHAR(50) NOT NULL DEFAULT 'WORK',
    notes TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_attendance_employee_date UNIQUE(employee_id, attendance_date),
    CONSTRAINT chk_attendance_status CHECK (
        status IN ('WORK', 'PAID_LEAVE', 'SICK_LEAVE', 'UNPAID_LEAVE', 'PERSONAL_LEAVE', 'BUSINESS_TRIP')
    ),
    CONSTRAINT chk_attendance_clock_times CHECK (
        (status = 'WORK' AND clock_in_time IS NOT NULL AND clock_out_time IS NOT NULL
            AND clock_out_time > clock_in_time)
        OR
        (status IN ('PAID_LEAVE', 'SICK_LEAVE', 'UNPAID_LEAVE', 'PERSONAL_LEAVE', 'BUSINESS_TRIP')
            AND clock_in_time IS NULL AND clock_out_time IS NULL)
    )
);

-- 既存DBにも同じ制約を適用する。既存のNULLステータスは通常の勤務レコードとして補正する。
UPDATE attendance_records SET status = 'WORK' WHERE status IS NULL;

ALTER TABLE attendance_records
    ALTER COLUMN status SET DEFAULT 'WORK',
    ALTER COLUMN status SET NOT NULL;

DO $$
DECLARE
    existing_unique_name TEXT;
BEGIN
    SELECT conname INTO existing_unique_name
    FROM pg_constraint
    WHERE conrelid = 'attendance_records'::regclass
      AND contype = 'u'
      AND pg_get_constraintdef(oid) = 'UNIQUE (employee_id, attendance_date)'
    LIMIT 1;

    IF existing_unique_name IS NULL THEN
        ALTER TABLE attendance_records
            ADD CONSTRAINT uk_attendance_employee_date UNIQUE (employee_id, attendance_date);
    ELSIF existing_unique_name <> 'uk_attendance_employee_date' THEN
        EXECUTE format(
            'ALTER TABLE attendance_records RENAME CONSTRAINT %I TO uk_attendance_employee_date',
            existing_unique_name
        );
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'attendance_records'::regclass
          AND conname = 'chk_attendance_status'
    ) THEN
        ALTER TABLE attendance_records
            ADD CONSTRAINT chk_attendance_status CHECK (
                status IN ('WORK', 'PAID_LEAVE', 'SICK_LEAVE', 'UNPAID_LEAVE', 'PERSONAL_LEAVE', 'BUSINESS_TRIP')
            );
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'attendance_records'::regclass
          AND conname = 'chk_attendance_clock_times'
    ) THEN
        ALTER TABLE attendance_records
            ADD CONSTRAINT chk_attendance_clock_times CHECK (
                (status = 'WORK' AND clock_in_time IS NOT NULL AND clock_out_time IS NOT NULL
                    AND clock_out_time > clock_in_time)
                OR
                (status IN ('PAID_LEAVE', 'SICK_LEAVE', 'UNPAID_LEAVE', 'PERSONAL_LEAVE', 'BUSINESS_TRIP')
                    AND clock_in_time IS NULL AND clock_out_time IS NULL)
            );
    END IF;
END $$;

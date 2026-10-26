import React, { FormEvent, useEffect, useState } from 'react'
import { AttendanceApiError, getAttendanceByDate, saveAttendance } from '../services/attendanceService'
import { validateWorkTime } from './attendanceValidation'

interface WorkTimeFormProps {
  selectedDate: Date
  onCancel: () => void
}

const EMPLOYEE_ID = 'EMP001'
const VALIDATION_MESSAGES = {
  format: '有効な時刻形式で入力してください',
  required: '出勤時刻と退勤時刻を入力してください',
  order: '出勤時刻は退勤時刻より前である必要があります',
  unknown: '予期しないエラーが発生しました。システム管理者に連絡してください。',
}

const formatDateKey = (date: Date): string => {
  const year = date.getFullYear()
  const month = `${date.getMonth() + 1}`.padStart(2, '0')
  const day = `${date.getDate()}`.padStart(2, '0')
  return `${year}-${month}-${day}`
}

const formatDateLabel = (date: Date): string => {
  return `${date.getFullYear()}年${date.getMonth() + 1}月${date.getDate()}日`
}

const getErrorMessage = (error: unknown): string => {
  if (error instanceof AttendanceApiError) {
    return error.message
  }
  return VALIDATION_MESSAGES.unknown
}

/**
 * 勤務時間入力フォームコンポーネント
 * 関連要件: REQ-UC003 - 勤務時間入力
 */
const WorkTimeForm: React.FC<WorkTimeFormProps> = ({ selectedDate, onCancel }) => {
  const attendanceDate = formatDateKey(selectedDate)
  const [clockInTime, setClockInTime] = useState('')
  const [clockOutTime, setClockOutTime] = useState('')
  const [errorMessage, setErrorMessage] = useState('')
  const [successMessage, setSuccessMessage] = useState('')
  const [isLoadingRecord, setIsLoadingRecord] = useState(true)
  const [isSaving, setIsSaving] = useState(false)

  useEffect(() => {
    let isActive = true
    setClockInTime('')
    setClockOutTime('')
    setErrorMessage('')
    setSuccessMessage('')
    setIsLoadingRecord(true)

    getAttendanceByDate(EMPLOYEE_ID, attendanceDate)
      .then((record) => {
        if (!isActive || !record) {
          return
        }
        setClockInTime(record.clockInTime?.slice(0, 5) ?? '')
        setClockOutTime(record.clockOutTime?.slice(0, 5) ?? '')
      })
      .catch((error: unknown) => {
        if (isActive) {
          setErrorMessage(getErrorMessage(error))
        }
      })
      .finally(() => {
        if (isActive) {
          setIsLoadingRecord(false)
        }
      })

    return () => {
      isActive = false
    }
  }, [attendanceDate])

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    setErrorMessage('')
    setSuccessMessage('')

    const validationError = validateWorkTime(clockInTime, clockOutTime)
    if (validationError === 'INVALID_TIME_FORMAT') {
      setErrorMessage(VALIDATION_MESSAGES.format)
      return
    }
    if (validationError === 'REQUIRED_TIME') {
      setErrorMessage(VALIDATION_MESSAGES.required)
      return
    }
    if (validationError === 'INVALID_TIME_ORDER') {
      setErrorMessage(VALIDATION_MESSAGES.order)
      return
    }

    setIsSaving(true)
    try {
      await saveAttendance({
        employeeId: EMPLOYEE_ID,
        attendanceDate,
        clockInTime,
        clockOutTime,
        status: 'WORK',
      })
      setSuccessMessage('勤怠情報が正常に保存されました')
    } catch (error) {
      setErrorMessage(getErrorMessage(error))
    } finally {
      setIsSaving(false)
    }
  }

  const clearMessages = () => {
    setErrorMessage('')
    setSuccessMessage('')
  }

  return (
    <div className="dialog-backdrop" onMouseDown={(event) => {
      if (event.target === event.currentTarget) onCancel()
    }}>
      <section className="work-time-dialog" role="dialog" aria-modal="true" aria-labelledby="work-time-title">
        <header className="dialog-header">
          <div>
            <p className="dialog-eyebrow">勤務記録</p>
            <h2 id="work-time-title">勤務時間入力</h2>
          </div>
          <button className="dialog-close" type="button" aria-label="閉じる" onClick={onCancel}>×</button>
        </header>
        <p className="dialog-date">対象日 <strong>{formatDateLabel(selectedDate)}</strong></p>
        <form onSubmit={handleSubmit} noValidate>
          <div className="time-fields">
            <label className="time-field" htmlFor="clock-in-input">
              <span>出勤時刻</span>
              <input
                id="clock-in-input"
                data-testid="clock-in-input"
                type="text"
                inputMode="numeric"
                placeholder="09:00"
                autoComplete="off"
                value={clockInTime}
                disabled={isLoadingRecord || isSaving}
                onChange={(event) => {
                  setClockInTime(event.target.value)
                  clearMessages()
                }}
              />
            </label>
            <label className="time-field" htmlFor="clock-out-input">
              <span>退勤時刻</span>
              <input
                id="clock-out-input"
                data-testid="clock-out-input"
                type="text"
                inputMode="numeric"
                placeholder="18:00"
                autoComplete="off"
                value={clockOutTime}
                disabled={isLoadingRecord || isSaving}
                onChange={(event) => {
                  setClockOutTime(event.target.value)
                  clearMessages()
                }}
              />
            </label>
          </div>
          {isLoadingRecord && <p className="dialog-loading" role="status">勤怠情報を確認しています...</p>}
          <div className="form-message error-message" data-testid="error-message" role="alert" aria-live="polite">
            {errorMessage}
          </div>
          <div className="form-message success-message" data-testid="success-message" role="status" aria-live="polite">
            {successMessage}
          </div>
          <footer className="dialog-actions">
            <button className="btn-cancel" data-testid="btn-cancel" type="button" onClick={onCancel}>
              キャンセル
            </button>
            <button className="btn-save" data-testid="btn-save" type="submit" disabled={isLoadingRecord || isSaving}>
              {isSaving ? '保存中...' : '保存'}
            </button>
          </footer>
        </form>
      </section>
    </div>
  )
}

export default WorkTimeForm

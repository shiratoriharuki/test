import React, { FormEvent, useEffect, useState } from 'react'
import { AttendanceApiError, deleteAttendance, getAttendanceByDate, saveAttendance } from '../services/attendanceService'
import { AttendanceRecord } from '../types/attendance'

interface LeaveFormProps {
  selectedDate: Date
  onCancel: () => void
  onSaved: () => void
  onDeleted: () => void
}

const EMPLOYEE_ID = 'EMP001'
const LEAVE_OPTIONS = [
  { value: 'SICK_LEAVE', label: 'Sick Leave' },
  { value: 'PERSONAL_LEAVE', label: 'Personal Leave' },
  { value: 'BUSINESS_TRIP', label: 'Business Trip' },
] as const
const isLeaveStatus = (
  status: string | undefined
): status is (typeof LEAVE_OPTIONS)[number]['value'] => {
  return LEAVE_OPTIONS.some((option) => option.value === status)
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
  return '予期しないエラーが発生しました。システム管理者に連絡してください。'
}

const LeaveForm: React.FC<LeaveFormProps> = ({ selectedDate, onCancel, onSaved, onDeleted }) => {
  const attendanceDate = formatDateKey(selectedDate)
  const [status, setStatus] = useState('')
  const [notes, setNotes] = useState('')
  const [existingRecord, setExistingRecord] = useState<AttendanceRecord | null>(null)
  const [isLoadingRecord, setIsLoadingRecord] = useState(true)
  const [errorMessage, setErrorMessage] = useState('')
  const [isSaving, setIsSaving] = useState(false)
  const [isDeleting, setIsDeleting] = useState(false)

  useEffect(() => {
    let isActive = true
    setStatus('')
    setNotes('')
    setExistingRecord(null)
    setErrorMessage('')
    setIsLoadingRecord(true)

    getAttendanceByDate(EMPLOYEE_ID, attendanceDate)
      .then((record) => {
        if (!isActive || !record) return
        setExistingRecord(record)
        if (isLeaveStatus(record.status)) {
          setStatus(record.status ?? '')
          setNotes(record.notes ?? '')
        }
      })
      .catch((error: unknown) => {
        if (isActive) setErrorMessage(getErrorMessage(error))
      })
      .finally(() => {
        if (isActive) setIsLoadingRecord(false)
      })

    return () => {
      isActive = false
    }
  }, [attendanceDate])

  const isExistingLeave = existingRecord !== null
    && isLeaveStatus(existingRecord.status)
  const isBlockedByExistingRecord = existingRecord !== null && !isExistingLeave
  const isReady = !isLoadingRecord && !errorMessage && !isBlockedByExistingRecord
  const isBusy = isSaving || isDeleting

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    setErrorMessage('')
    if (!status) {
      setErrorMessage('休暇種別を選択してください')
      return
    }

    setIsSaving(true)
    try {
      await saveAttendance({
        employeeId: EMPLOYEE_ID,
        attendanceDate,
        clockInTime: null,
        clockOutTime: null,
        status,
        notes: notes.trim() ? notes : null,
      })
      onSaved()
    } catch (error) {
      setErrorMessage(getErrorMessage(error))
    } finally {
      setIsSaving(false)
    }
  }

  const handleDelete = async () => {
    if (!window.confirm('この休暇レコードを削除します。よろしいですか？')) return

    setErrorMessage('')
    setIsDeleting(true)
    try {
      await deleteAttendance(EMPLOYEE_ID, attendanceDate)
      onDeleted()
    } catch (error) {
      setErrorMessage(getErrorMessage(error))
    } finally {
      setIsDeleting(false)
    }
  }

  const clearError = () => setErrorMessage('')

  return (
    <div className="dialog-backdrop" onMouseDown={(event) => {
      if (!isBusy && event.target === event.currentTarget) onCancel()
    }}>
      <section className="leave-dialog" role="dialog" aria-modal="true" aria-labelledby="leave-title">
        <header className="dialog-header">
          <div>
            <p className="dialog-eyebrow">休暇記録</p>
            <h2 id="leave-title">休暇登録</h2>
          </div>
          <button
            className="dialog-close"
            type="button"
            aria-label="閉じる"
            onClick={onCancel}
            disabled={isBusy}
          >
            ×
          </button>
        </header>
        <p className="dialog-date">対象日 <strong>{formatDateLabel(selectedDate)}</strong></p>

        {isLoadingRecord && <p className="dialog-loading" role="status">勤怠情報を確認しています...</p>}
        {isBlockedByExistingRecord && (
          <p className="leave-conflict" role="status">
            対象日に既存の勤怠レコード（{existingRecord.status}）があるため、休暇として登録・削除できません。
          </p>
        )}

        <form onSubmit={handleSubmit} noValidate>
          <label className="leave-field" htmlFor="leave-status">
            <span>休暇種別</span>
            <select
              id="leave-status"
              data-testid="leave-status"
              value={status}
              disabled={!isReady || isBusy}
              onChange={(event) => {
                setStatus(event.target.value)
                clearError()
              }}
            >
              <option value="">選択してください</option>
              {LEAVE_OPTIONS.map((option) => (
                <option key={option.value} value={option.value}>{option.label}</option>
              ))}
            </select>
          </label>

          <label className="leave-field" htmlFor="leave-notes">
            <span>メモ</span>
            <textarea
              id="leave-notes"
              data-testid="leave-notes"
              value={notes}
              rows={4}
              disabled={!isReady || isBusy}
              onChange={(event) => {
                setNotes(event.target.value)
                clearError()
              }}
            />
          </label>

          <div className="form-message error-message" data-testid="error-message" role="alert" aria-live="polite">
            {errorMessage}
          </div>
          <footer className="dialog-actions">
            {isExistingLeave && (
              <button
                className="btn-delete"
                data-testid="btn-delete-leave"
                type="button"
                onClick={handleDelete}
                disabled={!isReady || isBusy}
              >
                {isDeleting ? '削除中...' : '削除'}
              </button>
            )}
            <button className="btn-cancel" data-testid="btn-cancel-leave" type="button" onClick={onCancel} disabled={isBusy}>
              キャンセル
            </button>
            <button className="btn-save" data-testid="btn-save-leave" type="submit" disabled={!isReady || isBusy}>
              {isSaving ? '保存中...' : '保存'}
            </button>
          </footer>
        </form>
      </section>
    </div>
  )
}

export default LeaveForm

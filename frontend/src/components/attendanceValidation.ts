export type WorkTimeValidationError =
  | 'INVALID_TIME_FORMAT'
  | 'REQUIRED_TIME'
  | 'INVALID_TIME_ORDER'

const TIME_PATTERN = /^([01][0-9]|2[0-3]):[0-5][0-9]$/

const toMinutes = (time: string): number => {
  const [hours, minutes] = time.split(':').map(Number)
  return hours * 60 + minutes
}

export const validateWorkTime = (
  clockInTime: string,
  clockOutTime: string
): WorkTimeValidationError | null => {
  const invalidTime = [clockInTime, clockOutTime].some(
    (time) => time.trim() !== '' && !TIME_PATTERN.test(time)
  )
  if (invalidTime) return 'INVALID_TIME_FORMAT'
  if (!clockInTime.trim() || !clockOutTime.trim()) return 'REQUIRED_TIME'
  if (toMinutes(clockInTime) >= toMinutes(clockOutTime)) return 'INVALID_TIME_ORDER'
  return null
}
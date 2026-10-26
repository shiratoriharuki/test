import { AttendanceRecord, AttendanceRequest } from '../types/attendance'

const API_BASE_URL = '/api/attendance'

const NETWORK_ERROR_MESSAGE = 'ネットワークエラーが発生しました。接続を確認してください。'
const SERVER_ERROR_MESSAGE = '予期しないエラーが発生しました。システム管理者に連絡してください。'

interface ApiErrorResponse {
  error?: string
  message?: string
}

export class AttendanceApiError extends Error {
  constructor(
    message: string,
    public readonly status?: number,
    public readonly code?: string
  ) {
    super(message)
    this.name = 'AttendanceApiError'
  }
}

const request = async (url: string, init?: RequestInit): Promise<Response> => {
  try {
    return await fetch(url, init)
  } catch {
    throw new AttendanceApiError(NETWORK_ERROR_MESSAGE, undefined, 'ERR99002')
  }
}

const readError = async (response: Response): Promise<AttendanceApiError> => {
  let body: ApiErrorResponse = {}
  try {
    body = await response.json() as ApiErrorResponse
  } catch {
    // The server may return an empty or non-JSON error response.
  }

  if (response.status >= 500) {
    return new AttendanceApiError(SERVER_ERROR_MESSAGE, response.status, 'ERR99001')
  }
  return new AttendanceApiError(
    body.message || '勤怠情報を処理できませんでした。入力内容を確認してください。',
    response.status,
    body.error
  )
}

const readJson = async <T>(response: Response): Promise<T> => {
  if (!response.ok) {
    throw await readError(response)
  }
  return response.json() as Promise<T>
}

/**
 * 勤怠APIサービス
 * 関連要件: REQ-UC002 - カレンダー操作, REQ-UC003 - 勤務時間入力, REQ-UC004 - 休暇登録
 */

/**
 * 勤怠レコードを保存する
 */
export const saveAttendance = async (request: AttendanceRequest): Promise<AttendanceRecord> => {
  const response = await requestApi(API_BASE_URL, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(request),
  })
  return readJson<AttendanceRecord>(response)
}

/**
 * 特定日の勤怠レコードを取得する
 */
export const getAttendanceByDate = async (
  employeeId: string,
  date: string
): Promise<AttendanceRecord | null> => {
  const response = await requestApi(`${API_BASE_URL}/${employeeId}/${date}`)
  if (response.status === 404) {
    return null
  }
  return readJson<AttendanceRecord>(response)
}

const requestApi = (url: string, init?: RequestInit): Promise<Response> => request(url, init)

/**
 * 従業員の勤怠レコード一覧を取得する
 * TODO: REQ-UC002 - API連携実装予定
 */
export const getAttendancesByEmployeeId = async (employeeId: string): Promise<AttendanceRecord[]> => {
  const response = await requestApi(`${API_BASE_URL}/${employeeId}`)
  return readJson<AttendanceRecord[]>(response)
}

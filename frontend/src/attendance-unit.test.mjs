import assert from 'node:assert/strict'
import { after, afterEach, before, describe, it } from 'node:test'
import { resolve } from 'node:path'
import { createServer } from 'vite'

let vite
let validateWorkTime
let getAttendanceByDate
let saveAttendance
let deleteAttendance
let AttendanceApiError
let originalFetch

before(async () => {
  vite = await createServer({
    root: process.cwd(),
    configFile: resolve(process.cwd(), 'vite.config.ts'),
    appType: 'custom',
    logLevel: 'error',
    server: { middlewareMode: true },
  })
  ;({ validateWorkTime } = await vite.ssrLoadModule('/src/components/attendanceValidation.ts'))
  ;({ getAttendanceByDate, saveAttendance, deleteAttendance, AttendanceApiError } = await vite.ssrLoadModule(
    '/src/services/attendanceService.ts'
  ))
})

after(async () => {
  await vite?.close()
})

afterEach(() => {
  if (originalFetch) {
    globalThis.fetch = originalFetch
    originalFetch = undefined
  }
})

const mockFetch = (implementation) => {
  originalFetch = globalThis.fetch
  globalThis.fetch = implementation
}

const jsonResponse = (status, body) => ({
  status,
  ok: status >= 200 && status < 300,
  json: async () => body,
})

describe('validateWorkTime', () => {
  it('形式エラーを未入力エラーより優先する', () => {
    assert.equal(validateWorkTime('25:00', ''), 'INVALID_TIME_FORMAT')
    assert.equal(validateWorkTime('', '9:00'), 'INVALID_TIME_FORMAT')
  })

  it('出勤または退勤が未入力なら必須エラーを返す', () => {
    assert.equal(validateWorkTime('', '18:00'), 'REQUIRED_TIME')
    assert.equal(validateWorkTime('09:00', '  '), 'REQUIRED_TIME')
  })

  it('同時刻または出勤が遅い時刻を拒否する', () => {
    assert.equal(validateWorkTime('09:00', '09:00'), 'INVALID_TIME_ORDER')
    assert.equal(validateWorkTime('18:00', '09:00'), 'INVALID_TIME_ORDER')
  })

  it('有効な境界時刻を受け入れる', () => {
    assert.equal(validateWorkTime('00:00', '23:59'), null)
    assert.equal(validateWorkTime('09:00', '18:00'), null)
  })

  it('24時台、分の範囲外、桁不足を拒否する', () => {
    assert.equal(validateWorkTime('24:00', '18:00'), 'INVALID_TIME_FORMAT')
    assert.equal(validateWorkTime('09:60', '18:00'), 'INVALID_TIME_FORMAT')
    assert.equal(validateWorkTime('9:00', '18:00'), 'INVALID_TIME_FORMAT')
  })
})

describe('attendanceService', () => {
  it('POSTで勤怠情報を送り保存結果を返す', async () => {
    const record = { id: 7, employeeId: 'EMP001', clockInTime: '09:00:00' }
    mockFetch(async (url, init) => {
      assert.equal(url, '/api/attendance')
      assert.equal(init.method, 'POST')
      assert.deepEqual(JSON.parse(init.body), {
        employeeId: 'EMP001',
        attendanceDate: '2025-01-15',
        clockInTime: '09:00',
        clockOutTime: '18:00',
      })
      return jsonResponse(200, record)
    })

    assert.deepEqual(await saveAttendance({
      employeeId: 'EMP001',
      attendanceDate: '2025-01-15',
      clockInTime: '09:00',
      clockOutTime: '18:00',
    }), record)
  })

  it('単日取得の404を未登録としてnullにする', async () => {
    mockFetch(async (url) => {
      assert.equal(url, '/api/attendance/EMP001/2025-01-15')
      return jsonResponse(404, { error: 'NOT_FOUND' })
    })

    assert.equal(await getAttendanceByDate('EMP001', '2025-01-15'), null)
  })

  it('204の休暇削除応答を正常に処理する', async () => {
    mockFetch(async (url, init) => {
      assert.equal(url, '/api/attendance/EMP001/2025-01-15')
      assert.equal(init.method, 'DELETE')
      return { status: 204, ok: true }
    })

    await assert.doesNotReject(deleteAttendance('EMP001', '2025-01-15'))
  })

  it('休暇削除のAPIエラーを呼び出し元へ返す', async () => {
    mockFetch(async () => jsonResponse(409, {
      error: 'ATTENDANCE_RECORD_CONFLICT',
      message: '対象レコードは休暇ではないため削除できません',
    }))

    await assert.rejects(
      deleteAttendance('EMP001', '2025-01-15'),
      (error) => error instanceof AttendanceApiError
        && error.status === 409
        && error.code === 'ATTENDANCE_RECORD_CONFLICT'
    )
  })

  it('400のサーバーメッセージとエラーコードを保持する', async () => {
    mockFetch(async () => jsonResponse(400, {
      error: 'INVALID_TIME_ORDER',
      message: '出勤時刻は退勤時刻より前である必要があります',
    }))

    await assert.rejects(
      saveAttendance({ employeeId: 'EMP001', attendanceDate: '2025-01-15' }),
      (error) => error instanceof AttendanceApiError
        && error.code === 'INVALID_TIME_ORDER'
        && error.message === '出勤時刻は退勤時刻より前である必要があります'
    )
  })

  it('5xxを共通予期しないエラーへ変換する', async () => {
    mockFetch(async () => jsonResponse(500, { message: 'internal details' }))

    await assert.rejects(
      getAttendanceByDate('EMP001', '2025-01-15'),
      (error) => error instanceof AttendanceApiError
        && error.code === 'ERR99001'
        && error.message === '予期しないエラーが発生しました。システム管理者に連絡してください。'
    )
  })

  it('fetch失敗をネットワークエラーへ変換する', async () => {
    mockFetch(async () => { throw new TypeError('connection refused') })

    await assert.rejects(
      getAttendanceByDate('EMP001', '2025-01-15'),
      (error) => error instanceof AttendanceApiError
        && error.code === 'ERR99002'
        && error.message === 'ネットワークエラーが発生しました。接続を確認してください。'
    )
  })
})
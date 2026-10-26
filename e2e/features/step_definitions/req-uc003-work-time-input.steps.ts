import { Given, When, Then } from '@cucumber/cucumber';
import { expect } from '@playwright/test';
import { World } from './world';

const API_BASE_URL = 'http://localhost:8080/api/attendance';
const EMPLOYEE_ID = 'EMP001';

interface AttendanceRecord {
  id: number;
  employeeId: string;
  attendanceDate: string;
  clockInTime: string;
  clockOutTime: string;
  status: string;
  updatedAt: string;
}

const formatDateKey = (date: Date): string => {
  const year = date.getFullYear();
  const month = `${date.getMonth() + 1}`.padStart(2, '0');
  const day = `${date.getDate()}`.padStart(2, '0');
  return `${year}-${month}-${day}`;
};

const getDisplayedMonth = (title: string | null): { year: number; month: number } | null => {
  const match = title?.match(/(\d{4})年\s*(\d{1,2})月/);
  if (!match) return null;
  return { year: Number(match[1]), month: Number(match[2]) };
};

const selectDate = async (world: World, date: string) => {
  const { page } = world;
  const target = new Date(`${date}T12:00:00`);
  const displayed = getDisplayedMonth(await page.locator('.calendar-title').textContent());
  if (!displayed) throw new Error('カレンダーの年月を読み取れません');

  const monthDifference = (target.getFullYear() - displayed.year) * 12
    + (target.getMonth() + 1 - displayed.month);
  const navigationButton = monthDifference < 0
    ? page.locator('.calendar-nav-button').first()
    : page.locator('.calendar-nav-button').last();

  for (let month = 0; month < Math.abs(monthDifference); month += 1) {
    await navigationButton.click();
  }

  const dateCell = page.locator(`.calendar-day[data-date="${date}"]`);
  await expect(dateCell).toBeVisible();
  await dateCell.click();
  await expect(dateCell).toHaveClass(/selected/);
  world.context.selectedDate = date;
  await page.getByTestId('btn-work-time').click();
  await expect(page.getByRole('dialog')).toBeVisible();
};

const getAttendance = async (world: World, date: string) => {
  return world.page.request.get(`${API_BASE_URL}/${EMPLOYEE_ID}/${date}`);
};

const getSelectedDate = (world: World): string => {
  const date = world.context.selectedDate;
  if (!date) throw new Error('対象日が選択されていません');
  return date;
};

const waitForAttendanceTimes = async (
  world: World,
  date: string,
  clockInTime: string,
  clockOutTime: string
) => {
  await expect.poll(async () => {
    const response = await getAttendance(world, date);
    if (response.status() !== 200) return null;
    const record = await response.json() as AttendanceRecord;
    return `${record.clockInTime}/${record.clockOutTime}`;
  }, { timeout: 15000 }).toBe(`${clockInTime}:00/${clockOutTime}:00`);
};

const registerAttendance = async (
  world: World,
  date: string,
  clockInTime = '09:00',
  clockOutTime = '18:00'
) => {
  const response = await world.page.request.post(API_BASE_URL, {
    data: {
      employeeId: EMPLOYEE_ID,
      attendanceDate: date,
      clockInTime,
      clockOutTime,
      status: 'WORK',
    },
  });
  expect(response.status()).toBe(200);
  return await response.json() as AttendanceRecord;
};

Given('従業員が日付 {string} を選択している', async function (this: World, date: string) {
  await selectDate(this, date);
});

Given('従業員が未来の日付を選択している', async function (this: World) {
  const futureDate = new Date();
  futureDate.setFullYear(futureDate.getFullYear() + 1);
  await selectDate(this, formatDateKey(futureDate));
});

Given('従業員 {string} が日付 {string} に勤怠レコードを登録済みである',
  async function (this: World, employeeId: string, date: string) {
    expect(employeeId).toBe(EMPLOYEE_ID);
    this.context.registeredAttendance = await registerAttendance(this, date);
  });

Given('従業員 {string} が日付 {string} に勤怠レコードを登録している',
  async function (this: World, employeeId: string, date: string) {
    expect(employeeId).toBe(EMPLOYEE_ID);
    this.context.registeredAttendance = await registerAttendance(this, date);
  });

When('従業員が出勤時刻 {string} を入力する', async function (this: World, time: string) {
  await this.page.getByTestId('clock-in-input').fill(time);
});

When('従業員が退勤時刻 {string} を入力する', async function (this: World, time: string) {
  await this.page.getByTestId('clock-out-input').fill(time);
});

When('従業員が保存ボタンをクリックする', async function (this: World) {
  const before = await getAttendance(this, getSelectedDate(this));
  this.context.attendanceBeforeSave = before.status() === 200 ? await before.json() : null;
  await this.page.getByTestId('btn-save').click();
});

When('システムが従業員 {string} の日付 {string} の勤怠レコードを取得する',
  async function (this: World, employeeId: string, date: string) {
    this.context.attendanceLookupResponse = await this.page.request.get(
      `${API_BASE_URL}/${employeeId}/${date}`
    );
  });

Then('勤怠情報が正常に保存される', async function (this: World) {
  const date = getSelectedDate(this);
  const clockInTime = await this.page.getByTestId('clock-in-input').inputValue();
  const clockOutTime = await this.page.getByTestId('clock-out-input').inputValue();
  await waitForAttendanceTimes(this, date, clockInTime, clockOutTime);
  const response = await getAttendance(this, date);
  const record = await response.json() as AttendanceRecord;
  expect(record.status).toBe('WORK');
});

Then('保存成功メッセージが表示される', async function (this: World) {
  await expect(this.page.getByTestId('success-message')).toContainText('勤怠情報が正常に保存されました');
});

Then('バリデーションエラーが表示される', async function (this: World) {
  await expect(this.page.getByTestId('error-message')).not.toBeEmpty();
});

Then('エラーメッセージに {string} が含まれる', async function (this: World, message: string) {
  await expect(this.page.getByTestId('error-message')).toContainText(message);
});

Then('勤怠情報が保存されない', async function (this: World) {
  const date = getSelectedDate(this);
  const response = await getAttendance(this, date);
  const before = this.context.attendanceBeforeSave as AttendanceRecord | null;
  if (before === null) {
    expect(response.status()).toBe(404);
    return;
  }

  expect(response.status()).toBe(200);
  const after = await response.json() as AttendanceRecord;
  expect(after.id).toBe(before.id);
  expect(after.clockInTime).toBe(before.clockInTime);
  expect(after.clockOutTime).toBe(before.clockOutTime);
  expect(after.updatedAt).toBe(before.updatedAt);
});

Then('既存の勤怠レコードが更新される', async function (this: World) {
  const date = getSelectedDate(this);
  await waitForAttendanceTimes(this, date, '08:30', '17:30');
  const response = await getAttendance(this, date);
  const record = await response.json() as AttendanceRecord;
  const registered = this.context.registeredAttendance as AttendanceRecord;
  expect(record.id).toBe(registered.id);
  expect(record.clockInTime).toBe('08:30:00');
  expect(record.clockOutTime).toBe('17:30:00');
});

Then('勤怠レコードが正常に取得される', async function (this: World) {
  expect(this.context.attendanceLookupResponse.status()).toBe(200);
});

Then('レコードに出勤時刻と退勤時刻が含まれる', async function (this: World) {
  const record = await this.context.attendanceLookupResponse.json() as AttendanceRecord;
  expect(record.clockInTime).toBeTruthy();
  expect(record.clockOutTime).toBeTruthy();
});
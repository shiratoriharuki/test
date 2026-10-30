import { Given, When, Then } from '@cucumber/cucumber';
import { expect } from '@playwright/test';
import { World } from './world';

const API_BASE_URL = 'http://localhost:8080/api/attendance';
const EMPLOYEE_ID = 'EMP001';

const LEAVE_STATUSES: Record<string, string> = {
  'Sick Leave': 'SICK_LEAVE',
  'Personal Leave': 'PERSONAL_LEAVE',
  'Business Trip': 'BUSINESS_TRIP',
};

interface AttendanceRecord {
  id: number;
  employeeId: string;
  attendanceDate: string;
  clockInTime: string | null;
  clockOutTime: string | null;
  status: string;
  notes: string | null;
}

const getSelectedDate = (world: World): string => {
  const date = world.context.selectedDate;
  if (!date) throw new Error('対象日が選択されていません');
  return date;
};

const getAttendance = async (world: World, date: string) => {
  return world.page.request.get(`${API_BASE_URL}/${EMPLOYEE_ID}/${date}`);
};

const ensureLeaveFormOpen = async (world: World) => {
  const { page } = world;
  const dialog = page.getByRole('dialog');
  if (await dialog.isVisible().catch(() => false)) {
    const leaveStatus = page.getByTestId('leave-status');
    if (await leaveStatus.isVisible().catch(() => false)) return;
    await page.getByTestId('btn-cancel').click();
  }

  await page.getByTestId('btn-leave').click();
  await expect(page.getByRole('dialog')).toBeVisible();
  await expect(page.getByTestId('leave-status')).toBeVisible();
  await expect(page.getByTestId('leave-status')).toBeEnabled();
};

const registerLeave = async (
  world: World,
  employeeId: string,
  date: string,
  displayStatus: string
): Promise<AttendanceRecord> => {
  expect(employeeId).toBe(EMPLOYEE_ID);
  const status = LEAVE_STATUSES[displayStatus];
  if (!status) throw new Error(`未対応の休暇種別です: ${displayStatus}`);

  const response = await world.page.request.post(API_BASE_URL, {
    data: {
      employeeId,
      attendanceDate: date,
      clockInTime: null,
      clockOutTime: null,
      status,
      notes: null,
    },
  });
  expect(response.status()).toBe(200);
  return await response.json() as AttendanceRecord;
};

Given('従業員 {string} が日付 {string} に {string} を登録済みである',
  async function (this: World, employeeId: string, date: string, leaveType: string) {
    this.context.registeredLeave = await registerLeave(this, employeeId, date, leaveType);
  });

Given('従業員 {string} が日付 {string} に休暇を登録済みである',
  async function (this: World, employeeId: string, date: string) {
    this.context.registeredLeave = await registerLeave(this, employeeId, date, 'Sick Leave');
  });

When('従業員が休暇種別 {string} を選択する', async function (this: World, leaveType: string) {
  const status = LEAVE_STATUSES[leaveType];
  if (!status) throw new Error(`未対応の休暇種別です: ${leaveType}`);
  this.context.isLeaveFlow = true;
  await ensureLeaveFormOpen(this);
  await this.page.getByTestId('leave-status').selectOption(status);
});

When('従業員がメモ {string} を入力する', async function (this: World, notes: string) {
  this.context.isLeaveFlow = true;
  await ensureLeaveFormOpen(this);
  await this.page.getByTestId('leave-notes').fill(notes);
});

When('従業員がメモを入力しない', async function (this: World) {
  this.context.isLeaveFlow = true;
  await ensureLeaveFormOpen(this);
  await this.page.getByTestId('leave-notes').fill('');
});

When('従業員が削除ボタンをクリックする', async function (this: World) {
  this.context.isLeaveFlow = true;
  await ensureLeaveFormOpen(this);
  this.context.leaveDeleteDialog = new Promise<void>((resolve, reject) => {
    this.page.once('dialog', async (dialog) => {
      try {
        this.context.leaveDeleteDialogMessage = dialog.message();
        await dialog.accept();
        resolve();
      } catch (error) {
        reject(error);
      }
    });
  });
  await this.page.getByTestId('btn-delete-leave').click();
});

When('従業員が削除確認ダイアログで確認する', async function (this: World) {
  await this.context.leaveDeleteDialog;
});

Then('休暇情報が正常に保存される', async function (this: World) {
  const date = getSelectedDate(this);
  const response = await getAttendance(this, date);
  expect(response.status()).toBe(200);
  const record = await response.json() as AttendanceRecord;
  expect(record.employeeId).toBe(EMPLOYEE_ID);
  expect(record.attendanceDate).toBe(date);
  expect(Object.values(LEAVE_STATUSES)).toContain(record.status);
  expect(record.clockInTime).toBeNull();
  expect(record.clockOutTime).toBeNull();
  this.context.savedLeave = record;
});

Then('勤怠ステータスが {string} に設定される', async function (this: World, leaveType: string) {
  const expectedStatus = LEAVE_STATUSES[leaveType];
  if (!expectedStatus) throw new Error(`未対応の休暇種別です: ${leaveType}`);

  const response = await getAttendance(this, getSelectedDate(this));
  expect(response.status()).toBe(200);
  const record = await response.json() as AttendanceRecord;
  expect(record.status).toBe(expectedStatus);
});

Then('既存の休暇レコードが更新される', async function (this: World) {
  const response = await getAttendance(this, getSelectedDate(this));
  expect(response.status()).toBe(200);
  const updated = await response.json() as AttendanceRecord;
  const registered = this.context.registeredLeave as AttendanceRecord;
  expect(updated.id).toBe(registered.id);
  expect(updated.status).toBe('PERSONAL_LEAVE');
  expect(updated.notes).toBe('私用に変更');
});

Then('休暇レコードが削除される', async function (this: World) {
  await this.context.leaveDeleteDialog;
  await expect(this.page.getByTestId('page-success-message')).toContainText('休暇情報が削除されました');
  expect(this.context.leaveDeleteDialogMessage).toContain('削除');
  const response = await getAttendance(this, getSelectedDate(this));
  expect(response.status()).toBe(404);
});

Then('削除成功メッセージが表示される', async function (this: World) {
  await expect(this.page.getByTestId('page-success-message')).toContainText('休暇情報が削除されました');
});

Then('メモが空の状態で保存される', async function (this: World) {
  const response = await getAttendance(this, getSelectedDate(this));
  expect(response.status()).toBe(200);
  const record = await response.json() as AttendanceRecord;
  expect(record.notes).toBeNull();
});

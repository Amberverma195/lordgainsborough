import { test, expect } from '@playwright/test';
import { Client } from 'pg';
import { hashSync } from 'bcryptjs';
import { randomUUID } from 'node:crypto';
import { readFile } from 'node:fs/promises';

test('profile deletion confirms password, preserves failures, and clears every open tab', async ({ page, context }, info) => {
  const state = JSON.parse(await readFile('.e2e/state.json', 'utf8'));
  if (!/^lgb_e2e_[a-f0-9]{32}$/.test(state.database)) throw Error('Refusing non-E2E database');
  const db = new Client({ host: '127.0.0.1', port: state.port, database: state.database, user: 'postgres', password: 'postgres' });
  await db.connect();
  const id = randomUUID(), otherId = randomUUID(), orderId = randomUUID();
  const address = `delete-${id}@example.test`, password = 'DeleteFixture1!';
  const errors: string[] = [];
  page.on('pageerror', error => errors.push(error.message));
  try {
    for (const [userId, email] of [[id, address], [otherId, `other-${otherId}@example.test`]]) {
      await db.query("insert into users(id,name,email,password,role) values ($1,'Deletion Test',$2,$3,'MEMBER')", [userId, email, hashSync(password, 10)]);
    }
    await db.query("insert into customer_orders(id,customer_name,customer_email,customer_phone,pickup_date,status) values ($1,'Deletion Test',$2,'5195551111','2099-01-01','NEW')", [orderId, address]);
    await db.query("insert into reservations(id,name,email,phone,date,time,party_size,special_requests) values ($1,'Deletion Test',$2,'5195551111','2099-01-01','12:00',2,'Private booking note')", [id, address]);
    await db.query("insert into reservations(id,name,email,phone,date,time,party_size) values ($1,'Deletion Test',$2,'5195551111','2099-01-01','12:00',2)", [otherId, `other-${otherId}@example.test`]);
    await db.query("insert into order_items(id,order_id,name,quantity,unit_price,total) values ($1,$2,'Test Dish',1,10,10)", [randomUUID(), orderId]);
    const cartId = randomUUID();
    await db.query('insert into carts(id,user_id) values ($1,$2)', [cartId, id]);
    await db.query("insert into cart_items(id,cart_id,menu_item_id,name,price,quantity) values ($1,$2,'deletion-fixture','Test Dish',10,1)", [randomUUID(), cartId]);

    await page.goto('/login');
    await page.getByPlaceholder('Email or phone number', { exact: true }).fill(address);
    await page.locator('input[type=password]').fill(password);
    await page.getByRole('button', { name: 'Sign In', exact: true }).click();
    await expect(page.getByRole('heading', { name: 'My Account', exact: true })).toBeVisible();
    expect(await page.evaluate(() => localStorage.getItem('authToken'))).toBeNull();
    // Test automation can inspect HttpOnly cookies; page JavaScript cannot.
    const savedToken = (await context.cookies()).find(cookie => cookie.name.endsWith('lgb_session'))?.value;
    expect(savedToken).toBeTruthy();
    const otherTab = await context.newPage();
    await otherTab.goto('/profile');
    await expect(otherTab.getByRole('heading', { name: 'My Account', exact: true })).toBeVisible();
    for (const tab of [page, otherTab]) {
      await tab.evaluate(order => {
        sessionStorage.setItem(`orderTrackingToken:${order}`, 'fixture-token');
        sessionStorage.setItem('pickupDate', '2099-01-01');
        sessionStorage.setItem('checkoutIdempotencyKey', 'fixture-key');
      }, orderId);
    }

    const open = page.getByRole('button', { name: 'Delete account', exact: true });
    await open.click();
    const dialog = page.getByRole('dialog', { name: 'Delete your account?' });
    await expect(dialog).toBeVisible();
    await expect(dialog).toContainText('including upcoming reservations');
    await expect(dialog.getByRole('button', { name: 'Cancel', exact: true })).toBeFocused();
    await dialog.getByRole('button', { name: 'Cancel', exact: true }).click();
    await expect(dialog).not.toBeVisible();
    await expect(open).toBeFocused();
    expect((await db.query('select id from users where id=$1', [id])).rowCount).toBe(1);

    await open.click();
    await dialog.getByLabel('Confirm your current password').fill('incorrect');
    await dialog.getByRole('button', { name: 'Permanently delete account' }).click();
    await expect(dialog.getByRole('alert')).toHaveText('Your current password is incorrect.');
    expect((await db.query('select id from customer_orders where id=$1', [orderId])).rowCount).toBe(1);

    await dialog.getByLabel('Confirm your current password').fill(password);
    await dialog.getByRole('button', { name: 'Permanently delete account' }).click();
    await expect(dialog.getByRole('alert')).toHaveText('Your account cannot be deleted while you have active orders. Please wait until all orders are completed.');
    expect((await db.query('select id from users where id=$1', [id])).rowCount).toBe(1);
    expect((await db.query('select id from carts where user_id=$1', [id])).rowCount).toBe(1);
    expect((await db.query('select id from reservations where id=$1', [id])).rowCount).toBe(1);
    expect((await context.cookies()).find(cookie => cookie.name.endsWith('lgb_session'))?.value).toBe(savedToken);
    await expect(otherTab).toHaveURL(/\/profile$/);
    // After the order completes, the normal deletion and failure-recovery flow remains available.
    await db.query("update customer_orders set status='PICKED_UP' where id=$1", [orderId]);

    await page.route('**/api/auth/me', route => route.request().method() === 'DELETE' ? route.abort() : route.continue());
    await dialog.getByLabel('Confirm your current password').fill(password);
    await dialog.getByRole('button', { name: 'Permanently delete account' }).click();
    await expect(dialog.getByRole('alert')).toHaveText('Unable to connect to the server. Please try again.');
    expect((await db.query('select id from users where id=$1', [id])).rowCount).toBe(1);
    await page.unroute('**/api/auth/me');

    let release!: () => void;
    const pending = new Promise<void>(resolve => { release = resolve; });
    await page.route('**/api/auth/me', async route => {
      if (route.request().method() === 'DELETE') await pending;
      await route.continue();
    });
    await dialog.getByRole('button', { name: 'Permanently delete account' }).click();
    await expect(dialog.getByRole('button', { name: 'Deleting...' })).toBeDisabled();
    await expect(dialog.getByRole('button', { name: 'Cancel', exact: true })).toBeDisabled();
    await page.screenshot({ path: info.outputPath('delete-confirmation.png') });
    release();
    for (const tab of [page, otherTab]) {
      await expect(tab).toHaveURL(/\/login\?accountDeleted=1$/);
      await expect(tab.getByRole('status')).toHaveText('Your account, orders, cart, and reservations have been deleted.');
      const remaining = await tab.evaluate(() => ({ token: localStorage.getItem('authToken'),
        cart: JSON.parse(localStorage.getItem('lord_gainsborough_cart') || '[]'),
        privateKeys: Object.keys(sessionStorage).filter(key => key.startsWith('orderTrackingToken:') || ['pickupDate', 'checkoutIdempotencyKey'].includes(key)) }));
      expect(remaining).toEqual({ token: null, cart: [], privateKeys: [] });
    }
    for (const table of ['users', 'carts', 'customer_orders', 'reservations']) {
      const column = table === 'carts' ? 'user_id' : 'id';
      expect((await db.query(`select * from ${table} where ${column}=$1`, [table === 'customer_orders' ? orderId : id])).rowCount).toBe(0);
    }
    expect((await db.query('select id from users where id=$1', [otherId])).rowCount).toBe(1);
    expect((await db.query('select id from reservations where id=$1', [otherId])).rowCount).toBe(1);
    const stale = await page.request.get('http://127.0.0.1:8082/api/auth/me', { headers: { Authorization: `Bearer ${savedToken}` } });
    expect(stale.status()).toBe(401);
    expect(errors).toEqual([]);
  } finally { await db.end(); }
});

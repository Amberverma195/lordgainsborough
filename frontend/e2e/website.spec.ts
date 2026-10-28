import { test, expect, type Page, type BrowserContext } from '@playwright/test';
import { Client } from 'pg';
import { readFile, writeFile } from 'node:fs/promises';
import { randomBytes, randomUUID, randomInt } from 'node:crypto';
import { io } from 'socket.io-client';

let state: { database: string; port: number; adminEmail: string; adminPassword: string };
let customer: Page, admin: Page, kitchen: Page;
let customerContext: BrowserContext, adminContext: BrowserContext, kitchenContext: BrowserContext;
let orderId = '';
const diagnostics:{role:string;kind:string;value:string}[]=[];
function watch(page:Page,role:string) {
  page.on('pageerror',error=>diagnostics.push({role,kind:'pageerror',value:error.message.replace(/eyJ[\w.-]+/g,'[redacted token]')}));
  page.on('requestfailed',request=>diagnostics.push({role,kind:'network',value:new URL(request.url()).pathname+': '+request.failure()?.errorText}));
  page.on('websocket',socket=>socket.on('framereceived',frame=>{const payload=String(frame.payload);if(payload.startsWith('42')){try{diagnostics.push({role,kind:'socket-event',value:JSON.parse(payload.slice(2))[0]});}catch{}}}));
}

const account = { email: `customer-${randomUUID()}@example.test`, phone: `519555${randomInt(2000, 9999)}`,
  name: `E2E Browser ${randomUUID().slice(0,8)}`, password: `Aa1!${randomBytes(16).toString('hex')}` };
const today = () => new Intl.DateTimeFormat('en-CA', { timeZone: 'America/Toronto' }).format(new Date());
async function db(sql: string, values: unknown[] = []) {
  state ||= JSON.parse(await readFile('.e2e/state.json', 'utf8'));
  if (!/^lgb_e2e_[a-f0-9]{32}$/.test(state.database)) throw Error('Refusing non-E2E database');
  const client = new Client({ host: '127.0.0.1', port: state.port, user: 'postgres', password: 'postgres', database: state.database });
  await client.connect();try { return (await client.query(sql, values)).rows; } finally { await client.end(); }
}
async function inbox(address: string, offset = 0) {
  let code: string | undefined;
  await expect.poll(async () => {
    const mails = await (await fetch('http://127.0.0.1:8025/messages')).json();
    code = mails.slice(offset).findLast((mail: { to: string[] }) => mail.to.includes(address))?.code;
    return code;
  }).toMatch(/^\d{6}$/);return code!;
}
async function login(page: Page, address: string, password: string) {
  await page.goto('/login');await page.getByPlaceholder('Email or phone number', { exact: true }).fill(address);
  await page.locator('input[type=password]').fill(password);await page.getByRole('button', { name: 'Sign In', exact: true }).click();
  await expect(page).toHaveURL(/\/profile$/);await expect(page.getByRole('heading', { name: 'My Account', exact: true })).toBeVisible();
}
async function adminTab(label: string) { await admin.getByRole('navigation', { name: 'Admin sections' }).getByRole('button', { name: new RegExp(label) }).click(); }
async function signup(page: Page, user = account, wrongCode = false) {
  await page.goto('/signup');await page.getByPlaceholder('Enter your full name', { exact: true }).fill(user.name);
  await page.getByPlaceholder('+1 (123)-456-7890', { exact: true }).fill(user.phone);
  await page.getByPlaceholder('Enter email address', { exact: true }).fill(user.email);
  await page.getByPlaceholder('Enter Password', { exact: true }).fill(user.password);
  await page.getByPlaceholder('Re-enter password', { exact: true }).fill(user.password);
  await page.getByRole('checkbox').check();await page.getByRole('button', { name: 'Create Account', exact: true }).click();
  await expect(page).toHaveURL(/\/signup\/verify/);const code=await inbox(user.email);
  if (wrongCode) {
    const wrong=code==='000000'?'111111':'000000';
    for(let i=0;i<6;i++)await page.getByRole('textbox',{name:`Digit ${i+1}`,exact:true}).fill(wrong[i]);
    await page.getByRole('button',{name:/Verify Email & Create Account|Create Account/i,exact:true}).click();
    await expect.poll(async()=>Number((await db('SELECT count(*) FROM users WHERE email=$1',[user.email]))[0].count)).toBe(0);
    await expect(page.getByText(/incorrect|invalid|does not match/i)).toBeVisible();
  }
  for(let i=0;i<6;i++)await page.getByRole('textbox',{name:`Digit ${i+1}`,exact:true}).fill(code[i]);
  await page.getByRole('button',{name:/Verify Email & Create Account|Create Account/i,exact:true}).click();await expect(page).toHaveURL(/\/profile$/);
  await expect(page.getByRole('heading',{name:user.name,exact:true})).toBeVisible();
}
async function menuItem(page: Page, name = 'Cheesy Garlic Bread') {
  await page.goto('/menu');await page.getByRole('heading',{name:'Culinary Selection'}).waitFor();
  await page.locator('input[type=date]').fill(today());await page.getByRole('combobox').selectOption({label:'9:00 PM'});
  const card=page.getByRole('heading',{name,exact:true}).locator('../..');
  await card.getByRole('button',{name:'Add to Cart',exact:true}).click();
}
async function checkout(page: Page) {
  await page.getByRole('button',{name:'Proceed to Checkout',exact:true}).click();await expect(page).toHaveURL(/\/checkout$/);
  await page.getByRole('button',{name:/Confirm Pick-up Order/}).click();await expect(page).toHaveURL(/\/confirmation$/);
  await expect(page.getByRole('heading',{name:'Sent to the kitchen'})).toBeVisible();
  const id=page.url().split('/').at(-2)!;
  await page.getByRole('link',{name:'Check Order',exact:true}).click();await expect(page.getByRole('heading',{level:1})).toContainText('ORD-');
  return id;
}
test.beforeAll(async ({ browser }) => {
  state=JSON.parse(await readFile('.e2e/state.json','utf8'));
  customerContext=await browser.newContext({viewport:{width:1440,height:900}});adminContext=await browser.newContext({viewport:{width:1440,height:900}});kitchenContext=await browser.newContext({viewport:{width:1440,height:900}});
  customer=await customerContext.newPage();admin=await adminContext.newPage();kitchen=await kitchenContext.newPage();
  watch(customer,'customer');watch(admin,'admin');watch(kitchen,'kitchen');
});
test.afterEach(async ({},info) => {
  for(const [name,page] of [['customer',customer],['admin',admin],['kitchen',kitchen]] as const) {
    if(page&&!page.isClosed())await page.screenshot({path:info.outputPath(`${name}.png`),fullPage:true}).catch(()=>{});
  }
});
test.afterAll(async()=>{await writeFile('.e2e/browser-diagnostics.json',JSON.stringify(diagnostics,null,2));for(const [label,ctx] of [['customer',customerContext],['admin',adminContext],['kitchen',kitchenContext]] as const){if(ctx){await ctx.close();}}});

test('01 administrator login and real kitchen access controls',async()=>{
  await login(admin,state.adminEmail,state.adminPassword);await admin.getByRole('link',{name:'Admin Panel',exact:true}).click();
  await expect(admin.getByRole('navigation',{name:'Admin sections'})).toBeVisible();await adminTab('Store Settings');
  const current=String((await db("SELECT kds_allowed_ip FROM store_settings WHERE id='default'"))[0].kds_allowed_ip);
  if(!current.includes('127.0.0.1')) { await admin.getByLabel('Add IP address',{exact:true}).fill('127.0.0.1');await admin.getByRole('button',{name:'Add IP',exact:true}).click();await admin.getByRole('button',{name:'Save settings',exact:true}).click(); }
  await admin.getByLabel('Default ready time',{exact:true}).selectOption('30');await admin.getByRole('button',{name:'Save settings',exact:true}).click();
  await expect.poll(async()=>Number((await db("SELECT default_ready_minutes FROM store_settings WHERE id='default'"))[0].default_ready_minutes)).toBe(30);
  await admin.getByLabel('Default ready time',{exact:true}).selectOption('25');await admin.getByRole('button',{name:'Save settings',exact:true}).click();
  await expect.poll(async()=>String((await db("SELECT kds_allowed_ip FROM store_settings WHERE id='default'"))[0].kds_allowed_ip)).toContain('127.0.0.1');
  await kitchen.goto('/kds');await expect(kitchen.getByRole('heading',{name:'New',exact:true})).toBeVisible();
  await expect(kitchen.getByText('Live', {exact:true})).toBeVisible();
});
test('02 customer signup including incorrect OTP, cart edits, validation and persisted order',async()=>{
  await signup(customer,account,true);
  await customer.reload();await expect(customer.getByRole('heading',{name:account.name,exact:true})).toBeVisible();
  await menuItem(customer);
  await customer.getByRole('button',{name:'Increase quantity of Cheesy Garlic Bread',exact:true}).click();
  await expect(customer.getByRole('heading',{name:'Your Order'}).locator('../..')).toContainText('$27.98');
  await customer.getByRole('button',{name:'Decrease quantity of Cheesy Garlic Bread',exact:true}).click();
  await customer.getByRole('button',{name:'Remove Cheesy Garlic Bread',exact:true}).click();
  await expect(customer.getByText('Your cart is empty.',{exact:true})).toBeVisible();
  await customer.getByRole('button',{name:'Close cart',exact:true}).click();
  await customer.getByRole('heading',{name:'Cheesy Garlic Bread',exact:true}).locator('../..').getByRole('button',{name:'Add to Cart',exact:true}).click();
  orderId=await checkout(customer);
  const stored=(await db('SELECT customer_email,status,subtotal,tax,total FROM customer_orders WHERE id=$1',[orderId]))[0];
  expect(stored).toMatchObject({customer_email:account.email,status:'NEW',subtotal:'13.99',tax:'1.82',total:'15.81'});
  await expect(kitchen.locator('article').filter({hasText:account.name})).toHaveCount(1);
});
test('03 customer and kitchen messages, statuses and reconnect persist over real sockets',async()=>{
  const card=kitchen.locator('article').filter({hasText:account.name});
  await customer.getByText('Messages (0)',{exact:true}).click();await customer.getByLabel('Message kitchen',{exact:true}).fill('E2E no onions please');
  await customer.getByRole('button',{name:'Send message',exact:true}).click();
  await card.getByText(/Messages/).click();await expect(card.getByText('E2E no onions please',{exact:false})).toBeVisible();
  await card.getByLabel('Message customer',{exact:true}).fill('E2E kitchen confirmed');await card.getByRole('button',{name:'Send message',exact:true}).click();
  await expect(customer.getByText('E2E kitchen confirmed',{exact:false})).toBeVisible();
  await card.getByRole('button',{name:'Increase ready time by 5 minutes',exact:true}).click();
  await expect.poll(async()=>Number((await db('SELECT average_ready_minutes FROM customer_orders WHERE id=$1',[orderId]))[0].average_ready_minutes)).toBe(30);
  await card.getByRole('button',{name:'Accept',exact:true}).click();await expect(customer.getByText('In progress',{exact:true})).toBeVisible();
  await card.getByRole('button',{name:'Mark ready',exact:true}).click();await expect(customer.getByText('Ready for pickup',{exact:true})).toBeVisible();
  await customerContext.setOffline(true);await customerContext.setOffline(false);await customer.reload();
  await expect(customer.getByText('Ready for pickup',{exact:true})).toBeVisible();
  await card.getByRole('button',{name:'Picked up',exact:true}).click();await expect(customer.getByText(/Picked up/i).first()).toBeVisible();
  expect((await db('SELECT count(*) FROM order_messages WHERE order_id=$1',[orderId]))[0].count).toBe('2');
  expect(diagnostics.some(d=>d.role==='kitchen'&&d.kind==='socket-event'&&d.value==='order.created')).toBe(true);
  expect(diagnostics.some(d=>d.role==='customer'&&d.kind==='socket-event'&&d.value==='order.updated')).toBe(true);
});
test('04 review submission, duplicate prevention and administrator moderation',async()=>{
  await customer.getByRole('button',{name:'Review items',exact:true}).click();await customer.getByRole('button',{name:'5 stars',exact:true}).click();
  await customer.getByPlaceholder('Optional description',{exact:true}).fill(`Review ${orderId}`);await customer.getByRole('button',{name:'Publish review',exact:true}).click();
  await expect(customer.getByText('Review submitted. It will appear after approval.')).toBeVisible();
  await customer.reload();await expect(customer.getByRole('button',{name:'Review items',exact:true})).toHaveCount(0);
  await adminTab('Reviews');const review=admin.locator('div[class*=reviewRow]').filter({hasText:`Review ${orderId}`});
  await expect(review).toHaveCount(1);await review.getByRole('button',{name:'Publish',exact:true}).click();
  await expect.poll(async()=>(await db('SELECT status FROM order_reviews WHERE order_id=$1',[orderId]))[0].status).toBe('PUBLISHED');
  await review.getByRole('button',{name:'Hide',exact:true}).click();
  await expect.poll(async()=>(await db('SELECT status FROM order_reviews WHERE order_id=$1',[orderId]))[0].status).toBe('HIDDEN');
});
test('05 reservation creation, administrator approval and deletion',async()=>{
  await customer.goto('/reserve');await customer.locator('input[type=date]').fill(today());
  await customer.locator('select[name=time]').selectOption({label:'7:00 PM'});
  await customer.getByPlaceholder('Enter your full name',{exact:true}).fill(`${account.name} reservation`);
  await customer.getByPlaceholder('(555) 555-5555',{exact:true}).fill(account.phone);
  await customer.getByPlaceholder('Enter email address',{exact:true}).fill(account.email);
  await customer.getByRole('button',{name:'Confirm Reservation',exact:true}).click();
  await expect(customer.getByText(/confirmed|received|Thank you/i).first()).toBeVisible();
  await adminTab('Reservations');const card=admin.locator('div[class*=reservationRow]').filter({hasText:`${account.name} reservation`});
  await card.getByRole('button',{name:'Approve',exact:true}).click();
  await expect.poll(async()=>(await db('SELECT status FROM reservations WHERE name=$1',[`${account.name} reservation`]))[0]?.status).toBe('APPROVED');
  await card.getByRole('button',{name:'Delete',exact:true}).click();await admin.getByRole('button',{name:'Confirm Delete',exact:true}).click();
  await expect.poll(async()=>(await db('SELECT count(*) FROM reservations WHERE name=$1',[`${account.name} reservation`]))[0].count).toBe('0');
});
test('06 administrator customer search by name, email and phone',async()=>{
  await adminTab('Search User');const search=admin.getByPlaceholder('Search by name, email, or phone',{exact:true});
  for(const query of [account.name,account.email,account.phone]){
    await search.fill(query);await admin.getByRole('button',{name:'Search',exact:true}).click();
    await expect(admin.locator('p').filter({hasText:account.email})).toBeVisible();
  }
});
test('07 ordinary customer cannot access administrator UI or private order',async({browser})=>{
  const strangerContext=await browser.newContext();const stranger=await strangerContext.newPage();await stranger.goto('/admin');
  await expect(stranger.getByText(/404|not found/i).first()).toBeVisible();
  await stranger.goto(`/orders/${orderId}`);await expect(stranger.getByRole('heading',{name:/ORD-/})).toHaveCount(0);
  const responses=await customer.evaluate(async()=>{
    const token=localStorage.getItem('authToken');return Promise.all(['/api/admin/customers/search?q=E2E','/api/kds/orders/all'].map(async path=>{
      const response=await fetch('http://localhost:8082'+path,{headers:{Authorization:'Bearer '+token}});return response.status;
    }));
  });expect(responses).toEqual([404,404]);await strangerContext.close();
});
test('08 profile update, logout, wrong password, email and phone login',async()=>{
  await customer.goto('/profile');await customer.getByRole('button',{name:'Edit Profile',exact:true}).click();
  await customer.locator('input').filter({visible:true}).fill('E2E Updated Customer');await customer.getByRole('button',{name:'Save',exact:true}).click();
  await expect(customer.getByRole('heading',{name:'E2E Updated Customer',exact:true})).toBeVisible();
  await customer.getByRole('button',{name:'Logout',exact:true}).click();await customer.getByRole('button',{name:'Log Out',exact:true}).click();
  await customer.goto('/login');await customer.getByPlaceholder('Email or phone number',{exact:true}).fill(account.email);
  await customer.locator('input[type=password]').fill('WrongPassword1!');await customer.getByRole('button',{name:'Sign In',exact:true}).click();
  await expect(customer.getByText('The email, phone number, or password is incorrect.',{exact:true})).toBeVisible();
  await login(customer,account.email,account.password);await login(customer,account.phone,account.password);
});
test('09 forgot password through local inbox and prior JWT rejection',async()=>{
  const previous=await customer.evaluate(()=>localStorage.getItem('authToken'));
  const offset=(await(await fetch('http://127.0.0.1:8025/messages')).json()).length;
  await customer.goto('/forgot-password');await customer.getByPlaceholder('Enter email address',{exact:true}).fill(account.email);
  await customer.getByRole('button',{name:/Send.*Code/i}).click();
  await customer.getByPlaceholder('6-digit code',{exact:true}).fill(await inbox(account.email,offset));
  const replacement=`NewAa2!${randomBytes(16).toString('hex')}`;
  await customer.getByPlaceholder('Enter new password',{exact:true}).fill(replacement);
  await customer.getByPlaceholder('Re-enter new password',{exact:true}).fill(replacement);
  await customer.getByRole('button',{name:'Reset Password',exact:true}).click();await expect(customer.getByRole('heading',{name:'Password Updated',exact:true})).toBeVisible();
  await expect.poll(async()=>Number((await db('SELECT token_version FROM users WHERE email=$1',[account.email]))[0].token_version)).toBe(1);
  const status=await customer.evaluate(async old=>(await fetch('http://localhost:8082/api/auth/me',{cache:'no-store',headers:{Authorization:'Bearer '+old}})).status,previous);
  expect(status).toBe(401);await login(customer,account.email,replacement);account.password=replacement;
});
test('10 duplicate registration is rejected in the actual signup form',async({page})=>{
  await page.goto('/signup');await page.getByPlaceholder('Enter your full name',{exact:true}).fill(account.name);
  await page.getByPlaceholder('+1 (123)-456-7890',{exact:true}).fill(account.phone);
  await page.getByPlaceholder('Enter email address',{exact:true}).fill(account.email);
  await page.getByPlaceholder('Enter Password',{exact:true}).fill(account.password);
  await page.getByPlaceholder('Re-enter password',{exact:true}).fill(account.password);await page.getByRole('checkbox').check();
  await page.getByRole('button',{name:'Create Account',exact:true}).click();
  await expect(page.getByText('Unable to complete signup with the provided details.',{exact:true})).toBeVisible();
  expect((await db('SELECT count(*) FROM users WHERE email=$1',[account.email]))[0].count).toBe('1');
});
test('11 unrelated customer JWT, wrong tracking token and unauthorized sockets cannot receive private data',async({browser})=>{
  const otherContext=await browser.newContext();const other=await otherContext.newPage();
  const stranger={email:`other-${randomUUID()}@example.test`,name:'Other E2E Customer',phone:`519555${randomInt(2000,9999)}`,password:`Aa1!${randomBytes(16).toString('hex')}`};
  await signup(other,stranger);const strangerJwt=await other.evaluate(()=>localStorage.getItem('authToken'));
  const denied=await other.evaluate(async ({id,wrong})=>{
    const response=await fetch(`http://localhost:8082/api/orders/${id}`,{headers:{Authorization:'Bearer '+localStorage.getItem('authToken'),'X-Order-Tracking-Token':wrong}});return response.status;
  },{id:orderId,wrong:randomBytes(32).toString('base64url')});expect(denied).toBe(404);
  await other.goto(`/orders/${orderId}`);await expect(other.getByText(/not found|404/i).first()).toBeVisible();
  const unexpected:string[]=[];
  const wrong=io('http://localhost:9092',{transports:['websocket'],reconnection:false,query:{orderId},auth:{token:strangerJwt}});
  wrong.on('chat.message',()=>unexpected.push('private message'));wrong.on('order.updated',()=>unexpected.push('private update'));
  const rejection=await new Promise<string>(resolve=>{wrong.on('connect_error',()=>resolve('denied'));setTimeout(()=>resolve('timeout'),5000);});expect(rejection).toBe('denied');
  // Model an ordinary remote customer behind the configured trusted loopback proxy.
  const falseKitchen=io('http://localhost:9092',{transports:['websocket'],reconnection:false,query:{role:'KDS'},extraHeaders:{'X-Forwarded-For':'198.51.100.29'}});
  const gate=await new Promise<string>(resolve=>{falseKitchen.on('connect_error',()=>resolve('denied'));setTimeout(()=>resolve('timeout'),5000);});expect(gate).toBe('denied');
  await customer.goto(`/orders/${orderId}`);await customer.locator('details summary').click();
  await customer.getByLabel('Message kitchen',{exact:true}).fill('Private security verification');await customer.getByRole('button',{name:'Send message',exact:true}).click();
  await expect(customer.getByText('Private security verification',{exact:false})).toBeVisible();
  const hash=(await db('SELECT tracking_token_hash FROM customer_orders WHERE id=$1',[orderId]))[0].tracking_token_hash;
  const visible=await customer.locator('body').innerText();
  expect(visible.includes(hash)).toBe(false);expect(visible.includes(strangerJwt!)).toBe(false);
  expect((await readFile('.e2e/backend.log','utf8')).includes(hash)).toBe(false);
  await new Promise(resolve=>setTimeout(resolve,400));expect(unexpected).toEqual([]);wrong.disconnect();falseKitchen.disconnect();
  await otherContext.close();
});
test('12 no-show workflow updates the customer and persisted counters',async()=>{
  await menuItem(customer);const noShowId=await checkout(customer);
  const card=kitchen.locator('article').filter({hasText:`ORD-${noShowId.slice(-6).toUpperCase()}`});
  await card.getByRole('button',{name:'Accept',exact:true}).click();await card.getByRole('button',{name:'Mark ready',exact:true}).click();
  await card.getByRole('button',{name:'No show',exact:true}).click();await expect(customer.getByText('No show',{exact:true})).toBeVisible();
  expect((await db('SELECT status FROM customer_orders WHERE id=$1',[noShowId]))[0].status).toBe('NO_SHOW');
  expect((await db('SELECT no_show_count FROM users WHERE email=$1',[account.email]))[0].no_show_count).toBe(1);
});
test('13 administrator menu/category CRUD and real customer image display',async()=>{
  const category=`E2E category ${randomUUID().slice(0,8)}`, dish=`E2E dish ${randomUUID().slice(0,8)}`;
  await adminTab('Categories');await admin.getByRole('button',{name:'+ Add Category',exact:true}).click();
  await admin.getByPlaceholder('e.g. Starters',{exact:true}).fill(category);await admin.getByPlaceholder('1',{exact:true}).fill('99');
  await admin.getByPlaceholder('Brief description',{exact:true}).fill('E2E category description');await admin.getByRole('button',{name:'Create Category',exact:true}).click();
  await expect(admin.getByRole('heading',{name:category,exact:true})).toBeVisible();
  await adminTab('Menu Items');await admin.getByRole('button',{name:'+ Add Item',exact:true}).click();
  await admin.getByPlaceholder('Dish name',{exact:true}).fill(dish);await admin.getByPlaceholder('0.00',{exact:true}).fill('12.34');
  await admin.getByRole('combobox').selectOption({label:category});await admin.getByPlaceholder('Describe the dish...',{exact:true}).fill('E2E menu description');
  await admin.getByPlaceholder('Paste image URL',{exact:true}).fill('/NavLOGO.png');await admin.getByRole('button',{name:'Create Item',exact:true}).click();
  await expect.poll(async()=>(await db('SELECT price FROM menu_items WHERE name=$1',[dish]))[0]?.price).toBe('12.34');
  await customer.goto('/menu');await customer.getByRole('button',{name:category,exact:true}).click();
  const display=customer.getByRole('heading',{name:dish,exact:true}).locator('../../..');
  await expect(display).toContainText('$12.34');await expect(display.getByRole('img')).toBeVisible();
  expect(await display.getByRole('img').evaluate((image:HTMLImageElement)=>image.complete&&image.naturalWidth>0)).toBe(true);
  const row=admin.locator('div[class*=tableRow]').filter({hasText:dish});await row.getByRole('button',{name:'Edit',exact:true}).click();
  await admin.getByPlaceholder('0.00',{exact:true}).fill('13.34');await admin.getByRole('button',{name:'Update Item',exact:true}).click();
  await expect.poll(async()=>(await db('SELECT price FROM menu_items WHERE name=$1',[dish]))[0]?.price).toBe('13.34');
  await row.getByRole('button',{name:'Delete',exact:true}).click();await admin.getByRole('button',{name:'Confirm Delete',exact:true}).click();
  await expect.poll(async()=>(await db('SELECT count(*) FROM menu_items WHERE name=$1',[dish]))[0].count).toBe('0');
  await adminTab('Categories');const categoryCard=admin.getByRole('heading',{name:category,exact:true}).locator('../..');
  await categoryCard.getByRole('button',{name:'Delete',exact:true}).click();await admin.getByRole('button',{name:'Confirm Delete',exact:true}).click();
  await expect.poll(async()=>(await db('SELECT count(*) FROM categories WHERE name=$1',[category]))[0].count).toBe('0');
});
test('14 main navigation and denied kitchen page work in Chromium',async({browser,page})=>{
  await page.goto('/');await page.getByRole('navigation').getByRole('link',{name:'Menu',exact:true}).click();await expect(page.getByRole('heading',{name:'Culinary Selection'})).toBeVisible();
  await page.getByRole('navigation').getByRole('link',{name:'About',exact:true}).click();await expect(page.getByRole('heading',{name:'About Lord Gainsborough'})).toBeVisible();
  await page.getByRole('link',{name:'Contact us',exact:true}).click();await expect(page.getByRole('heading',{name:'Contact Lord Gainsborough'})).toBeVisible();
  await page.getByRole('navigation').getByRole('link',{name:'Book a Table',exact:true}).click();await expect(page.getByRole('button',{name:'Confirm Reservation',exact:true})).toBeVisible();
  await page.getByRole('navigation').getByRole('link',{name:'Home',exact:true}).click();await expect(page.getByRole('heading',{name:'Lord Gainsborough',exact:true,level:1})).toBeVisible();
  const denied=await browser.newContext({extraHTTPHeaders:{'X-Forwarded-For':'198.51.100.29'}});const deniedPage=await denied.newPage();
  await deniedPage.goto('/kds');await expect(deniedPage.getByText(/404|not found/i).first()).toBeVisible();
  await expect(deniedPage.getByText('Verifying access...',{exact:true})).toHaveCount(0);await denied.close();
});
for(const [label,width,height] of [['desktop',1440,900],['tablet',768,1024],['mobile',390,844]] as const){
  test(`responsive ${label}: real navigation, menu, cart and checkout`,async({page})=>{
    await page.setViewportSize({width,height});await page.goto('/');
    await expect(page.getByRole('link',{name:'Login',exact:true})).toBeVisible();
    if(width<=768){await page.getByRole('button',{name:'Open navigation',exact:true}).click();await expect(page.locator('#mobile-navigation').getByRole('link',{name:'Menu',exact:true})).toBeVisible();await page.getByRole('button',{name:'Close navigation',exact:true}).click();await expect(page.locator('#mobile-navigation')).toHaveCount(0);}
    await page.getByRole('link',{name:'Explore Menu',exact:true}).click();await page.waitForURL('**/menu');
    await page.locator('input[type=date]').fill(today());await page.getByRole('combobox').selectOption({label:'9:00 PM'});
    await page.getByRole('button',{name:'Sides',exact:true}).click();
    await page.getByRole('heading',{name:'Side Mayo',exact:true}).locator('../..').getByRole('button',{name:'Add to Cart',exact:true}).click();
    await expect(page.getByRole('heading',{name:'Your Order',exact:true})).toBeVisible();
    expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
    await page.getByRole('button',{name:'Proceed to Checkout',exact:true}).click();await expect(page.getByRole('heading',{name:'Confirm Your Order'})).toBeVisible();
    await page.getByRole('button',{name:/Confirm Pick-up Order/}).click();await expect(page.getByRole('dialog',{name:'Sign in to place your order'})).toBeVisible();
    await page.screenshot({path:`.e2e/responsive-dialog-${label}.png`,fullPage:true});
    const dialog=page.getByRole('dialog',{name:'Sign in to place your order'});
    await dialog.getByPlaceholder('Email or phone number',{exact:true}).fill(account.email);
    await dialog.locator('input[type=password]').fill(account.password);await dialog.getByRole('button',{name:'Sign In',exact:true}).click();
    await expect(dialog).toHaveCount(0);
    await expect(page.getByRole('heading',{name:'Sent to the kitchen',exact:true})).toBeVisible();
    const id=page.url().split('/').at(-2)!;expect((await db('SELECT total FROM customer_orders WHERE id=$1',[id]))[0].total).toBe('1.70');
    await page.screenshot({path:`.e2e/responsive-${label}.png`,fullPage:true});
  });
}

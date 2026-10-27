import { Client } from 'pg';
import { SMTPServer } from 'smtp-server';
import { hashSync } from 'bcryptjs';
import { randomBytes, randomUUID } from 'node:crypto';
import { mkdir, writeFile } from 'node:fs/promises';
import { createWriteStream } from 'node:fs';
import { createServer } from 'node:http';
import { spawn } from 'node:child_process';
import { createConnection } from 'node:net';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../..');
const artifacts=path.join(root,'frontend/.e2e');await mkdir(artifacts,{recursive:true});
for(const servicePort of [3000,8082,9092,2525,8025]) {
    const occupied=await new Promise(resolve=>{
        const socket=createConnection({host:'127.0.0.1',port:servicePort});
        socket.once('connect',()=>{socket.destroy();resolve(true);});
        socket.once('error',()=>resolve(false));
    });
    if(occupied)throw Error(`Port ${servicePort} is occupied; stop the previous local E2E services first`);
}
const port=Number(process.env.E2E_POSTGRES_PORT||54353);
const connection={host:'127.0.0.1',port,user:'postgres',password:'postgres'};
const admin=new Client({...connection,database:'postgres'});await admin.connect();
const version=await admin.query('SHOW server_version_num');
const directory=(await admin.query('SHOW data_directory')).rows[0].data_directory;
if(Number(version.rows[0].server_version_num)<170000||Number(version.rows[0].server_version_num)>=180000
    ||!/[\\/]lgb_(pg017|test)_[a-f0-9]{32}[\\/]data$/.test(directory))throw Error('Refusing E2E mutations outside verified disposable PostgreSQL 17 cluster');
const database='lgb_e2e_'+randomUUID().replaceAll('-','');await admin.query(`CREATE DATABASE ${database}`);await admin.end();
const password=randomBytes(24).toString('base64url');
const state={database,port,adminEmail:'administrator@example.test',adminPassword:password};
await writeFile(path.join(artifacts,'state.json'),JSON.stringify(state));
const messages=[];
const smtp=new SMTPServer({authOptional:true,disabledCommands:['AUTH','STARTTLS'],logger:false,
    onRcptTo(address,session,done){done(address.address.endsWith('@example.test')?null:new Error('E2E recipients only'));},
    onData(stream,session,done){let chunks=[];stream.on('data',chunk=>chunks.push(chunk));stream.on('end',()=>{
        const raw=Buffer.concat(chunks).toString('utf8');const code=raw.match(/\b\d{6}\b/)?.[0];
        messages.push({to:session.envelope.rcptTo.map(r=>r.address),code});done();});}});
await new Promise(resolve=>smtp.listen(2525,'127.0.0.1',resolve));
const inbox=createServer((request,response)=>{response.setHeader('Content-Type','application/json');response.end(JSON.stringify(messages));});
await new Promise(resolve=>inbox.listen(8025,'127.0.0.1',resolve));
const children=[];
function start(command,args,cwd,env,log){const output=createWriteStream(path.join(artifacts,log));const child=spawn(command,args,{cwd,env:{...process.env,...env},windowsHide:true,stdio:['ignore','pipe','pipe']});child.stdout.pipe(output);child.stderr.pipe(output);children.push(child);return child;}
const backendEnv={DB_HOST:'127.0.0.1',DB_PORT:String(port),DB_NAME:database,DB_USER:'postgres',DB_PASSWORD:'postgres',DB_SSL_MODE:'disable',
    SPRING_DATASOURCE_URL:`jdbc:postgresql://127.0.0.1:${port}/${database}?sslmode=disable`,SPRING_DATASOURCE_USERNAME:'postgres',SPRING_DATASOURCE_PASSWORD:'postgres',
    JWT_SECRET:randomBytes(48).toString('base64url'),ABUSE_IP_HASH_SECRET:randomBytes(48).toString('base64url'),
    MAIL_USERNAME:'local',MAIL_PASSWORD:'local',MAIL_FROM:'restaurant@example.test',BREVO_API_KEY:'',
    SPRING_MAIL_HOST:'127.0.0.1',SPRING_MAIL_PORT:'2525',SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH:'false',SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE:'false',
    SERVER_ADDRESS:'127.0.0.1',SOCKETIO_HOST:'127.0.0.1',PORT:'8082',SOCKETIO_PORT:'9092',MANAGEMENT_SERVER_PORT:'8083',
    APP_ALLOWED_ORIGINS:'http://localhost:3000',SOCKETIO_ORIGIN:'http://localhost:3000'};
start('java',['-jar','target/restaurant-api-0.0.1-SNAPSHOT.jar'],path.join(root,'backend'),backendEnv,'backend.log');
async function reachable(url){for(let attempt=0;attempt<120;attempt++){try{if((await fetch(url)).ok)return;}catch{}await new Promise(resolve=>setTimeout(resolve,500));}throw Error(`Service unavailable: ${url}`);}
await reachable('http://127.0.0.1:8082/api/menu');
const db=new Client({...connection,database});await db.connect();
// HTTP starts before CommandLineRunner catalog/default initialization completes.
for(let attempt=0;attempt<120;attempt++) {
    if((await db.query("SELECT count(*) FROM store_settings WHERE id='default'")).rows[0].count==='1')break;
    if(attempt===119)throw Error('Default database initialization did not finish');
    await new Promise(resolve=>setTimeout(resolve,500));
}
await db.query("INSERT INTO users(id,name,email,phone,phone_normalized,password,role,token_version,no_show_count) VALUES($1,$2,$3,$4,$5,$6,'ADMIN',0,0)",
    [randomUUID(),'E2E Administrator',state.adminEmail,'+1 (519)-555-0001','5195550001',hashSync(password,10)]);
await db.query("UPDATE store_settings SET kds_allowed_ip='127.0.0.1,::1' WHERE id='default'");await db.end();
start(process.execPath,['node_modules/next/dist/bin/next',process.env.E2E_FRONTEND_MODE==='production'?'start':'dev','--port','3000'],path.join(root,'frontend'),
    {NEXT_PUBLIC_BACKEND_ORIGIN:'http://localhost:8082',NEXT_PUBLIC_SOCKET_URL:'http://localhost:9092'},'frontend.log');
await reachable('http://localhost:3000');console.log(`E2E ready: http://localhost:3000; isolated database ${database}; local inbox :8025`);
let stopping=false;async function stop(){if(stopping)return;stopping=true;children.forEach(child=>child.kill());smtp.close();inbox.close();process.exit();}
process.on('SIGINT',stop);process.on('SIGTERM',stop);

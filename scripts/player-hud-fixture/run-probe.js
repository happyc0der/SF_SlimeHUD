'use strict';
const fs=require('fs'),path=require('path'),crypto=require('crypto'),cp=require('child_process');
const mc=require('minecraft-protocol'),nbt=require('prismarine-nbt');
const args={};for(let i=2;i<process.argv.length;i+=2){if(!process.argv[i].startsWith('--')||!process.argv[i+1])throw Error('Expected --option value');args[process.argv[i].slice(2)]=process.argv[i+1];}
for(const key of ['work-dir','label','addon','java-home'])if(!args[key])throw Error('Missing --'+key);
const root=path.resolve(args['work-dir']),label=args.label,addonArg=args.addon,mode=args.mode||'native';
if(!/^[a-zA-Z0-9_-]+$/.test(label))throw Error('Unsafe label');
if(!['native','wit','restart'].includes(mode))throw Error('Mode must be native, wit, or restart');
const javaBin=path.join(path.resolve(args['java-home']),'bin','java');
const addon=path.resolve(addonArg),work=path.join(root,label),wit=mode==='wit',reuse=mode==='restart';
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
const digest=p=>crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
const record={schema:1,label,mode,runner_sha256:digest(__filename),inputs:JSON.parse(fs.readFileSync(path.join(root,'inputs.json'),'utf8')),addon_sha256:digest(addon),core_sha256:digest(path.join(root,'template/plugins/Slimefun.jar')),server_sha256:digest(path.join(root,'template/server.jar')),fixture_sha256:digest(path.join(root,'downloads/SlimeHudClientProbe.jar')),connected_client:false,cases:[],packets:[]};
const evidenceDir=reuse?path.join(work,'restart-evidence'):work;
if(!reuse&&fs.existsSync(work))throw Error('Refusing to reuse output directory');
if(reuse&&!fs.existsSync(work))throw Error('Missing saved world for restart');
if(!reuse){
fs.cpSync(path.join(root,'template'),work,{recursive:true,filter:src=>!src.endsWith('/session.lock')&&!src.includes('/logs')&&!src.includes('/plugins/.paper-remapped')});
fs.copyFileSync(addon,path.join(work,'plugins/SlimeHUD.jar'));
fs.copyFileSync(path.join(root,'downloads/SlimeHudClientProbe.jar'),path.join(work,'plugins/SlimeHudClientProbe.jar'));
if(wit){fs.copyFileSync(path.join(root,'downloads/WIT-1.9.5-mc.1.21.11.jar'),path.join(work,'plugins/WIT.jar'));fs.mkdirSync(path.join(work,'plugins/WIT'),{recursive:true});fs.writeFileSync(path.join(work,'plugins/WIT/config.yml'),fs.readFileSync(path.join(root,'downloads/wit-config.yml'),'utf8').replace('mode: \"normal\"','mode: \"sneaking\"').replace('bstats: true','bstats: false'));record.wit_sha256=digest(path.join(work,'plugins/WIT.jar'));}
fs.mkdirSync(path.join(work,'plugins/bStats'),{recursive:true});fs.writeFileSync(path.join(work,'plugins/bStats/config.yml'),'enabled: false\n');
let properties=fs.readFileSync(path.join(work,'server.properties'),'utf8').replace(/^server-port=\d+/m,'server-port=25592');fs.writeFileSync(path.join(work,'server.properties'),properties);
}
fs.mkdirSync(evidenceDir,{recursive:true});
if(reuse){if(digest(path.join(work,'plugins/SlimeHUD.jar'))!==record.addon_sha256)throw Error('Restart addon mismatch');fs.copyFileSync(path.join(work,'logs/latest.log'),path.join(work,'boot-one-server.log'));record.preferences_before={sha256:digest(path.join(work,'plugins/SlimeHUD/player.yml')),mtime:fs.statSync(path.join(work,'plugins/SlimeHUD/player.yml')).mtimeMs};}
let consoleText='',error=null,serverExit=null,client=null,heartbeat=null,stage='boot',bars=new Map(),actionbar='',chat=[];
const javaArgs=['-Xms256M','-Xmx1G','-Dterminal.jline=false','-Dterminal.ansi=false'];
if(fs.existsSync('/etc/ssl/certs/java/cacerts'))javaArgs.push('-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts');
if(process.env.HTTPS_PROXY){let proxy=new URL(process.env.HTTPS_PROXY);for(let scheme of ['http','https'])javaArgs.push(`-D${scheme}.proxyHost=${proxy.hostname}`,`-D${scheme}.proxyPort=${proxy.port||80}`);}
javaArgs.push('-jar','server.jar','--nogui');
const output=fs.openSync(path.join(evidenceDir,'console.log'),'w');
const server=cp.spawn(javaBin,javaArgs,{cwd:work,stdio:['pipe','pipe','pipe']});
server.stdout.on('data',v=>{consoleText+=v;fs.writeSync(output,v);});server.stderr.on('data',v=>{consoleText+=v;fs.writeSync(output,v);});server.on('exit',code=>{serverExit=code;});
const command=cmd=>{server.stdin.write(cmd+'\n');record.packets.push({at:Date.now(),stage,event:'console-command',command:cmd});};
const waitFor=async(fn,label,timeout=15000)=>{let end=Date.now()+timeout;while(!fn()){if(Date.now()>end||serverExit!==null)throw Error('Timed out '+label+' exit '+serverExit);await sleep(100);}};
function plain(value){if(value==null)return '';if(typeof value==='string'){try{return plain(JSON.parse(value));}catch{return value;}}if(value.type)value=nbt.simplify(value);if(typeof value==='string')return value;if(Array.isArray(value))return value.map(plain).join('');return(value.text||value.translate||value['']||'')+(value.with?value.with.map(plain).join(''):'')+(value.extra?value.extra.map(plain).join(''):'');}
const packet=(event,data)=>record.packets.push({at:Date.now(),stage,event,...data});
async function snapshot(name){stage=name;command('hudverify state '+name);await sleep(200);const snap={name,at:Date.now(),bars:[...bars].map(([id,text])=>({id,text})),actionbar,chat:chat.slice(-5)};record.cases.push(snap);console.log(JSON.stringify(snap));return snap;}
async function target(value){command('hudverify target '+value);await sleep(900);}
async function sneak(value){client.write('player_input',{inputs:{shift:value}});await sleep(250);command('hudverify aim');await sleep(750);}
async function playerCommand(value){client.chat('/'+value);await sleep(700);}
(async()=>{try{
 await waitFor(()=>consoleText.includes('Done ('),'server startup',180000);stage='connect';command('op HudProbe');
 client=mc.createClient({host:'127.0.0.1',port:25592,username:'HudProbe',auth:'offline',version:'1.21.11'});
 client.on('error',e=>{error=e;packet('client-error',{message:String(e)});});client.on('end',reason=>packet('client-end',{reason}));
 client.on('login',()=>{record.connected_client=true;packet('login',{});});
 client.on('position',p=>{packet('position',{x:p.x,y:p.y,z:p.z,yaw:p.yaw,pitch:p.pitch,flags:p.flags});client.write('teleport_confirm',{teleportId:p.teleportId});client.write('position_look',{x:p.x,y:p.y,z:p.z,yaw:p.yaw,pitch:p.pitch,flags:{onGround:true}});client.write('player_loaded',{});});
 client.on('boss_bar',p=>{if(p.action===0||p.action===3)bars.set(p.entityUUID,plain(p.title));else if(p.action===1)bars.delete(p.entityUUID);packet('boss_bar',{id:p.entityUUID,action:p.action,text:p.title?plain(p.title):undefined,rawTitle:p.title});});
 client.on('action_bar',p=>{actionbar=plain(p.text);packet('action_bar',{text:actionbar});});
 client.on('system_chat',p=>{const text=plain(p.content);if(p.isActionBar){actionbar=text;packet('action_bar',{text});}else{chat.push(text);packet('chat',{text});}});
 client.on('kick_disconnect',p=>{error=Error(plain(p.reason));packet('kick',{text:String(error)});});
 await waitFor(()=>record.connected_client,'client login');await sleep(1500);heartbeat=setInterval(()=>{if(client.state==='play')client.write('flying',{flags:{onGround:true}});},100);
 command('gamemode creative HudProbe');command('time set noon');await sleep(250);
 if(reuse){
  await target('STONE');await snapshot('restart-restores-actionbar');
  await sneak(true);await snapshot('restart-crouching-stone');
  command('hudverify away');await sleep(900);await snapshot('restart-clear-on-look-away');
 }else if(!wit){
  await target('STONE');await snapshot('standing-stone');
  await sneak(true);await snapshot('crouching-stone');
  await target('WHEAT 1');await snapshot('crouching-wheat-young');
  await target('WHEAT 7');await snapshot('crouching-wheat-mature');
  await target('REDSTONE_WIRE');await snapshot('crouching-redstone');
  await target('TORCH');await snapshot('crouching-torch');
  await target('SF:ELECTRIC_FURNACE');await snapshot('crouching-electric-furnace');
  await target('SF:SOLAR_GENERATOR');await snapshot('crouching-solar-generator');
  await target('DROP');await snapshot('crouching-dropped-diamonds');
  await target('STONE');await playerCommand('slimehud display actionbar');await snapshot('actionbar-stone');
  command('hudverify away');await sleep(900);await snapshot('actionbar-clear-on-look-away');
  await playerCommand('slimehud display bossbar');await target('STONE');
  command('hudverify rate 1');await sleep(1200);await snapshot('tick-rate-one');
  command('hudverify rate 2');await sleep(1200);await snapshot('tick-rate-two');
  command('hudverify rate 5');await sleep(700);await snapshot('tick-rate-five');
  await playerCommand('slimehud toggle');await snapshot('native-toggle-off');
  await playerCommand('slimehud toggle');await snapshot('native-toggle-on');
  await playerCommand('slimehud display actionbar');await snapshot('saved-actionbar-preference');
 }else{
  await target('STONE');await snapshot('wit-standing-native');
  await sneak(true);await snapshot('wit-crouching-single-bar');
  await sneak(false);await snapshot('wit-stand-handoff');
  await sneak(true);await playerCommand('wit type bogus');await snapshot('wit-invalid-display-fallback');
  await playerCommand('wit type bossbar');await snapshot('wit-valid-display-recovery');
  await target('SF:ELECTRIC_FURNACE');await snapshot('wit-electric-furnace');
  await target('SF:SOLAR_GENERATOR');await snapshot('wit-solar-generator');
  await target('WHEAT 1');await snapshot('wit-crouching-wheat-young');
  await playerCommand('wit toggle');await target('STONE');await snapshot('wit-disabled-native-fallback');
  await playerCommand('wit toggle');await snapshot('wit-enabled-recovery');
  await sneak(false);await snapshot('wit-final-stand-handoff');
  await sneak(true);await playerCommand('wit type actionbar');await snapshot('wit-actionbar-crouching');
  await sneak(false);await snapshot('wit-actionbar-stand-handoff');
  await sneak(true);await snapshot('wit-actionbar-resume');
  await playerCommand('wit type bossbar');await snapshot('wit-bossbar-after-actionbar');
  await playerCommand('wit reload');await snapshot('wit-reload-single-display');
 }
 if(error)throw error;
 record.status='RECORDED';
 }catch(e){record.status='FAIL';record.failure=String(e.stack||e);console.error(e);}
 finally{if(heartbeat)clearInterval(heartbeat);if(client)client.end();await sleep(300);command('stop');await waitFor(()=>serverExit!==null,'normal shutdown',60000).catch(e=>{record.shutdown_failure=String(e);server.kill('SIGTERM');});record.server_exit=serverExit;record.native_log_sha256=fs.existsSync(path.join(work,'logs/latest.log'))?digest(path.join(work,'logs/latest.log')):null;record.fixture_errors=consoleText.split('\n').filter(x=>x.includes('HUDVERIFY_FAIL'));record.hud_errors=consoleText.split('\n').filter(x=>/SlimeHUD.*failed|Error occurred while enabling SlimeHUD|WIT bridge failed|generated an exception/.test(x));record.environment_errors=consoleText.split('\n').filter(x=>x.includes('ERROR'));if(reuse){record.preferences_after={sha256:digest(path.join(work,'plugins/SlimeHUD/player.yml')),mtime:fs.statSync(path.join(work,'plugins/SlimeHUD/player.yml')).mtimeMs};record.preferences_unchanged=JSON.stringify(record.preferences_before)===JSON.stringify(record.preferences_after);}
fs.writeFileSync(path.join(evidenceDir,'verification.json'),JSON.stringify(record,null,2));fs.closeSync(output);console.log('EVIDENCE '+path.join(evidenceDir,'verification.json')); process.exit(record.status==='FAIL'||record.fixture_errors.length?1:0);}
})();

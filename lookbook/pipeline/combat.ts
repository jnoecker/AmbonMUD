// Combat capture: typed combat commands are refused in the browser ("use the
// on-screen controls"), and mob sprite positions shift per session, so this
// grid-clicks the canvas to find a mob whose field manual has an "Attack" action
// (the practice dummy in the Proving Yard), attacks, then screenshots the fight
// (damage toasts + victory/loot) and the battle-journal combat log.
//   bun combat.ts   # writes /tmp/C-combat*.jpg and /tmp/C-combatlog.jpg
import { chromium } from "playwright-core";
const EXEC=`${process.env.HOME}/Library/Caches/ms-playwright/chromium-1208/chrome-mac-x64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing`;
const URL="https://mud.ambon.dev"; const INGAME=".canvas-vitals-skin, .canvas-vitals-hud";
const b=await chromium.launch({executablePath:EXEC,headless:true,args:["--enable-unsafe-swiftshader","--use-angle=swiftshader","--mute-audio","--disable-features=Vulkan,WebGPU"]});
const p=await (await b.newContext({viewport:{width:1600,height:1000}})).newPage();
const cmd=async(c)=>p.evaluate((t)=>{const i=document.querySelector(".canvas-command-input") as HTMLInputElement;const s=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,"value")!.set!;s.call(i,t);i.dispatchEvent(new Event("input",{bubbles:true}));(i.closest("form") as HTMLFormElement).requestSubmit();},c);
const mmOpen=async()=>p.evaluate(()=>!!document.querySelector('.mm-card'));
const hasAttack=async()=>p.evaluate(()=>[...document.querySelectorAll('.mm-card button')].some(x=>(x.textContent||'').trim().startsWith('Attack')));
const clickAttack=async()=>p.evaluate(()=>{const t=[...document.querySelectorAll('.mm-card button')].find(x=>(x.textContent||'').trim().startsWith('Attack'));if(t){(t as HTMLButtonElement).click();return true}return false});
const closeMM=async()=>p.evaluate(()=>{const t=[...document.querySelectorAll('.mm-card button')].find(x=>(x.textContent||'').trim()==='✕');if(t)(t as HTMLButtonElement).click();});
await p.goto(URL,{waitUntil:"domcontentloaded",timeout:45000}); await p.waitForTimeout(6000);
{let i=p.locator("input.login-art-input").first();await i.fill("Claude");await i.press("Enter");} await p.waitForTimeout(3000);
{let i=p.locator("input.login-art-input").first();await i.fill("ClaudeFable5");await i.press("Enter");}
await p.waitForSelector(INGAME,{timeout:60000}); await p.waitForTimeout(3000);
await cmd("goto proving_yard"); await p.waitForTimeout(3500);
// Find a mob whose manual has an Attack action (the dummy).
let attacked=false;
const ys=[330,381,300,413]; const xs=[920,1000,840,1080,760,1160,680,1240];
outer: for(const y of ys){ for(const x of xs){
  await p.mouse.click(x,y); await p.waitForTimeout(350);
  if(await mmOpen()){
    if(await hasAttack()){ await clickAttack(); attacked=true; console.log("attacked dummy at",x,y); break outer; }
    else { await closeMM(); await p.waitForTimeout(200); }
  }
}}
console.log("attacked:",attacked);
await p.waitForTimeout(3000);
await p.screenshot({path:"/tmp/C-combat1.jpg"});
await p.keyboard.press("1"); await p.waitForTimeout(1200); await p.keyboard.press("2"); await p.waitForTimeout(1800);
await p.screenshot({path:"/tmp/C-combat2.jpg"});
// Combat log
await p.evaluate(()=>{const t=[...document.querySelectorAll('button')].find(x=>((x.getAttribute('aria-label')||x.textContent||'').trim()).startsWith('Combat Log'));(t as HTMLButtonElement)?.click();});
await p.waitForTimeout(2000); await p.screenshot({path:"/tmp/C-combatlog.jpg"});
const entries=await p.evaluate(()=>document.body.innerText.match(/Battle Journal[\s\S]{0,200}/)?.[0]||'?');
console.log("log:",entries.replace(/\n+/g,' '));
await b.close(); console.log("DONE");

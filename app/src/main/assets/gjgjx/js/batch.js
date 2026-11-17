/*
SPDX-FileCopyrightText: 2026 晨钟酱Official
SPDX-License-Identifier: GPL-3.0-only
See LICENSE for the full license text.
*/
import {GJ} from "./core.js";
const GJBatch = (() => {
  function arithmetic(expression,variables){const source=expression.replace(/\b[A-Za-z_]\w*\b/g,name=>String(Number(variables.get(name.toLowerCase())||0)));if(!/^[\d\s()+\-*/%]+$/.test(source))throw new Error('set /a 只支持数字、变量与加减乘除余数');const tokens=source.match(/\d+|[()+\-*/%]/g)||[];let i=0;const atom=()=>{const t=tokens[i++];if(t==='('){const n=sum();if(tokens[i++]!==')')throw new Error('算式括号不匹配');return n;}if(t==='-')return-atom();if(t==='+')return atom();if(!/^\d+$/.test(t||''))throw new Error('无效算式');return Number(t);};const product=()=>{let n=atom();while(['*','/','%'].includes(tokens[i])){const op=tokens[i++],r=atom();if(r===0&&op!=='*')throw new Error('除数不能为零');n=op==='*'?n*r:op==='/'?Math.trunc(n/r):n%r;}return n;};const sum=()=>{let n=product();while(['+','-'].includes(tokens[i])){const op=tokens[i++],r=product();n=op==='+'?n+r:n-r;}return n;};const result=sum();if(i!==tokens.length||!Number.isFinite(result))throw new Error('无效算式');return result;}
  class Runner {
    constructor({execute,output=()=>{},ask=async()=>'',progress=()=>{},hasFile=()=>false}={}){this.execute=execute;this.output=output;this.ask=ask;this.progress=progress;this.hasFile=hasFile;this.running=false;this.variables=new Map();this.controller=null;}
    stop(){this.controller?.abort(GJ.abortError());}
    expand(text){return text.replace(/%([^%]+)%/g,(all,name)=>this.variables.has(name.toLowerCase())?this.variables.get(name.toLowerCase()):all).replace(/%%([A-Za-z])/g,(all,name)=>this.variables.has(name.toLowerCase())?this.variables.get(name.toLowerCase()):all).replace(/!([A-Za-z_]\w*)!/g,(all,name)=>this.variables.get(name.toLowerCase())??all);}
    condition(raw){let inverse=false,text=raw.trim();if(/^not\s+/i.test(text)){inverse=true;text=text.replace(/^not\s+/i,'');}let result;const defined=text.match(/^defined\s+(\w+)\s*$/i),exists=text.match(/^exist\s+(.+)$/i),code=text.match(/^errorlevel\s+(\d+)$/i);if(defined)result=this.variables.has(defined[1].toLowerCase());else if(exists)result=this.hasFile(exists[1].replace(/^"|"$/g,''));else if(code)result=Number(this.variables.get('errorlevel')||0)>=Number(code[1]);else{const m=text.match(/^(.*?)\s*(==|\bEQU\b|\bNEQ\b|\bLSS\b|\bLEQ\b|\bGTR\b|\bGEQ\b)\s*(.*?)$/i);if(!m)throw new Error(`不支持的 IF 条件：${text}`);const a=m[1].replace(/^"|"$/g,''),b=m[3].replace(/^"|"$/g,''),op=m[2].toUpperCase();result=op==='=='?a===b:op==='EQU'?Number(a)===Number(b):op==='NEQ'?Number(a)!==Number(b):op==='LSS'?Number(a)<Number(b):op==='LEQ'?Number(a)<=Number(b):op==='GTR'?Number(a)>Number(b):Number(a)>=Number(b);}return inverse?!result:result;}
    async run(source,{signal}={}){if(this.running)throw new Error('脚本正在运行');GJ.checkAbort(signal);const lines=String(source).replace(/^\uFEFF/,'').replace(/\^\r?\n/g,'').split(/\r?\n/),labels=new Map();lines.forEach((line,index)=>{const m=line.trim().match(/^:([^:][^\s]*)\s*$/);if(m){if(labels.has(m[1].toLowerCase()))throw new Error(`标签重复：${m[1]}`);labels.set(m[1].toLowerCase(),index);}});let pc=0,steps=0;const loops=new Map(),blocks=new Map();
      // Match parenthesized FOR /L and IF blocks before executing anything.
      const stack=[];for(let i=0;i<lines.length;i++){const l=lines[i].trim();if(/^(?:@?for\s+\/l\b.+\bdo|@?if\b.+)\s*\(\s*$/i.test(l))stack.push(i);else if(l===')'){const open=stack.pop();if(open===undefined)throw new Error(`第 ${i+1} 行：多余的右括号`);blocks.set(open,i);}}if(stack.length)throw new Error(`第 ${stack.at(-1)+1} 行：缺少右括号`);
      this.running=true;this.controller=new AbortController();const controller=this.controller;const externalStop=()=>controller.abort(signal.reason||GJ.abortError());signal?.addEventListener('abort',externalStop,{once:true});this.variables=new Map([['errorlevel','0']]);
      try{while(pc<lines.length){GJ.checkAbort(controller.signal);if(++steps>100000)throw new Error('脚本超过 100000 步，请检查循环条件');if(steps%50===0)await GJ.sleep(0,controller.signal);this.progress(pc+1,lines.length);let raw=lines[pc].trim().replace(/^@/,'');const lineNumber=pc+1;pc++;if(!raw||/^rem(?:\s|$)/i.test(raw)||raw.startsWith(':')||/^echo\s+off\s*$/i.test(raw)||/^chcp\b/i.test(raw)||/^setlocal\b|^endlocal\b/i.test(raw))continue;
        try{
          let line=this.expand(raw);
          if(line===')'){const active=[...loops.values()].find(x=>x.end===pc-1);if(active){active.current+=active.step;if(active.step>0?active.current<=active.limit:active.current>=active.limit){this.variables.set(active.variable,String(active.current));pc=active.start+1;}else loops.delete(active.start);}continue;}
          const forLoop=line.match(/^for\s+\/l\s+%%([A-Za-z])\s+in\s*\(\s*(-?\d+)\s*,\s*(-?\d+)\s*,\s*(-?\d+)\s*\)\s+do\s*\(\s*$/i)||raw.match(/^for\s+\/l\s+%%([A-Za-z])\s+in\s*\(\s*(-?\d+)\s*,\s*(-?\d+)\s*,\s*(-?\d+)\s*\)\s+do\s*\(\s*$/i);
          if(forLoop){const start=pc-1,end=blocks.get(start),first=Number(forLoop[2]),step=Number(forLoop[3]),limit=Number(forLoop[4]);if(!step)throw new Error('FOR 步长不能为 0');if(end===undefined)throw new Error('FOR /L 必须使用多行括号块');if(step>0?first>limit:first<limit){pc=end+1;continue;}const variable=forLoop[1].toLowerCase();loops.set(start,{start,end,current:first,step,limit,variable});this.variables.set(variable,String(first));continue;}
          const ifBlock=line.match(/^if\s+(.+)\s+\(\s*$/i);if(ifBlock){if(!this.condition(ifBlock[1]))pc=blocks.get(pc-1)+1;continue;}
          const conditional=line.match(/^if\s+(.+?)\s+((?:goto|echo|adb(?:\.exe)?|set|exit|call)\b.*)$/i);if(conditional){if(!this.condition(conditional[1]))continue;line=conditional[2];}
          const jump=line.match(/^goto\s+:?(\S+)\s*$/i);if(jump){const label=jump[1].toLowerCase();if(label==='eof')break;if(!labels.has(label))throw new Error(`找不到标签 ${jump[1]}`);pc=labels.get(label)+1;continue;}
          if(/^exit(?:\s+\/b)?(?:\s+\d+)?\s*$/i.test(line))break;
          const setAsk=line.match(/^set\s+\/p\s+(\w+)=(.*)$/i);if(setAsk){const value=await this.ask(setAsk[2]||setAsk[1],controller.signal);GJ.checkAbort(controller.signal);this.variables.set(setAsk[1].toLowerCase(),value);continue;}
          const setMath=line.match(/^set\s+\/a\s+"?([A-Za-z_]\w*)\s*(\+=|-=|\*=|\/=|%=|=)\s*(.*?)"?\s*$/i);if(setMath){const key=setMath[1].toLowerCase(),op=setMath[2];let n=arithmetic(setMath[3].replace(/"$/,''),this.variables);if(op!=='=')n=arithmetic(`${Number(this.variables.get(key)||0)}${op[0]}(${n})`,this.variables);this.variables.set(key,String(n));continue;}
          const setValue=line.match(/^set\s+"?([A-Za-z_]\w*)=(.*?)"?\s*$/i);if(setValue){this.variables.set(setValue[1].toLowerCase(),setValue[2]);continue;}
          const echo=line.match(/^echo(?:\s+|\.)(.*)$/i);if(echo){if(!/>\s*nul\s*$/i.test(echo[1]))this.output('result',echo[1].replace(/\^(.)/g,'$1'));continue;}
          if(/^echo\s*$/i.test(line)){this.output('result','');continue;}
          const delay=line.match(/^(?:timeout|choice)\b.*?(?:\/t\s+|\/t:)([\d.]+)/i);if(delay){await GJ.sleep(GJ.number(delay[1],0,86400,'延时')*1000,controller.signal);continue;}
          if(/^pause(?:\s|$)/i.test(line)){await this.ask('脚本已暂停，点击继续',controller.signal);continue;}
          if(/^cls\s*$/i.test(line))continue;
          line=line.replace(/\s+(?:[12]?>{1,2})\s*(?:nul|"?[^\s"]+[\\/]Log[\\/][^\s"]+"?)\s*$/i,'').replace(/\^(?=[&|<>])/g,'');
          if(/^adb(?:\.exe)?\b|^(?:"[^"\r\n]+[\\/]adb(?:\.exe)?"|\S+[\\/]adb(?:\.exe)?)\s/i.test(line)){try{await this.execute(line,{signal:controller.signal});this.variables.set('errorlevel','0');}catch(error){this.variables.set('errorlevel',String(error.exitCode||1));if(error.name==='AbortError')throw error;const next=lines.slice(pc).find(x=>x.trim()&&!/^\s*(?:rem\b|::)/i.test(x))||'';if(/^\s*@?if\s+(?:(?:not|\/i)\s+)*(?:errorlevel\b|.*%errorlevel%|.*!errorlevel!)/i.test(next))continue;throw error;}continue;}
          if(/^call\s+/i.test(line)){line=line.replace(/^call\s+/i,'');if(/^adb(?:\.exe)?\b/i.test(line)){await this.execute(line,{signal:controller.signal});continue;}throw new Error('CALL 仅支持 ADB 命令；请将子脚本内容合并后执行');}
          if(/^for\b|^if\b/i.test(line))throw new Error('此 Windows 批处理语法尚不能在浏览器解释，请使用脚本插入器生成等价流程');
          throw new Error(`不支持的电脑端命令：${line.split(/\s/)[0]}。Android 命令请以 adb shell 开头`);
        }catch(error){if(error.name==='AbortError')throw error;throw new Error(`第 ${lineNumber} 行：${error.message}`);}
      }return {steps,lines:lines.length};
      }finally{signal?.removeEventListener('abort',externalStop);if(this.controller===controller){this.running=false;this.controller=null;}}
    }
  }
  return {Runner,arithmetic};
})();

export {GJBatch};

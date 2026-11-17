/*
SPDX-FileCopyrightText: 2026 晨钟酱Official
SPDX-License-Identifier: GPL-3.0-only
See LICENSE for the full license text.
*/
const GJTerminal = (() => {
  class Controller {
    constructor({element,onState=()=>{},onError=()=>{}}) {
      this.element=element;this.onState=onState;this.onError=onError;this.generation=0;this.pending=[];this.starting=false;
    }
    get active(){return !!this.session;}
    ensure(){
      if(this.term)return;
      this.term=new globalThis.Terminal({cursorBlink:true,fontSize:14,fontFamily:'ui-monospace, "Cascadia Mono", Consolas, monospace',lineHeight:1.2,scrollback:5000,allowProposedApi:false,disableStdin:true,theme:{background:'#111e27',foreground:'#deebf0',cursor:'#deebf0',selectionBackground:'#4657be88'}});
      this.fitAddon=new globalThis.FitAddon.FitAddon();this.term.loadAddon(this.fitAddon);this.term.open(this.element);
      this.term.textarea.setAttribute('aria-label','Android Shell 终端');
      this.term.onData(data=>this.send(data));
      this.term.onBinary(data=>this.send(Uint8Array.from(data,c=>c.charCodeAt(0))));
      this.term.onResize(({rows,cols})=>{clearTimeout(this.resizeTimer);const s=this.session;this.resizeTimer=setTimeout(()=>{if(s&&this.session===s&&s.pty.resize)Promise.resolve(s.pty.resize(rows,cols)).catch(error=>this.fail(s,error));},80);});
      this.term.attachCustomKeyEventHandler(event=>{
        if(event.type==='keydown'&&event.ctrlKey&&(event.code==='KeyC'||event.key?.toLowerCase()==='c')){event.preventDefault();if(event.shiftKey)navigator.clipboard.writeText(this.text()).catch(this.onError);else this.interrupt();return false;}return true;
      });
      this.observer=new ResizeObserver(()=>this.fit());this.observer.observe(this.element);
      for(const [kind,text]of this.pending)this.activity(kind,text);this.pending=[];
    }
    fit(){if(!this.term||!this.element.clientWidth||!this.element.clientHeight)return;this.fitAddon.fit();}
    focus(){this.term?.focus();}
    activity(kind,text){
      if(this.active||this.starting)return;
      if(!this.term){this.pending.push([kind,text]);if(this.pending.length>1500)this.pending.shift();return;}
      const color=kind==='command'?'\x1b[36m':kind==='stderr'?'\x1b[31m':'\x1b[0m';
      this.term.write(color+String(text).replace(/\r?\n/g,'\r\n')+'\x1b[0m\r\n');
    }
    clear(){this.pending=[];this.term?.clear();}
    text(){
      if(!this.term)return this.pending.map(([,text])=>text).join('\n');
      if(this.term.hasSelection())return this.term.getSelection();
      const lines=[],buffer=this.term.buffer.active;for(let i=0;i<buffer.length;i++)lines.push(buffer.getLine(i)?.translateToString(true)||'');return lines.join('\n').trimEnd();
    }
    async open(driver){
      this.ensure();this.fit();
      if(this.driver===driver&&this.active){this.focus();return;}
      if(this.driver===driver&&this.starting)return this.opening;
      this.close();this.driver=driver;this.starting=true;const generation=this.generation;this.onState('正在连接终端…');
      this.opening=(async()=>{
        let pty;
        try{
          pty=await driver.openTerminal();
          if(generation!==this.generation||!driver.connected){await pty.kill();return;}
          const s={pty,writer:pty.input.getWriter(),reader:pty.output.getReader(),queue:Promise.resolve(),closed:false};this.session=s;
          const exited=Promise.resolve(pty.exited).then(code=>({code}),error=>({error}));
          s.pump=(async()=>{
            let failure;
            try{while(true){const {value,done}=await s.reader.read();if(done)break;await new Promise(resolve=>this.term.write(value,resolve));}const result=await exited;if(result.error)throw result.error;s.exitCode=result.code;}catch(error){failure=error;}
            finally{
              s.reader.releaseLock();try{s.writer.releaseLock();}catch{}
              if(this.session===s){this.session=null;this.term.options.disableStdin=true;this.onState(failure?'终端连接已关闭':`会话已结束${s.exitCode===undefined?'':'（退出码 '+s.exitCode+'）'}，可重开会话`);if(failure&&!s.closed&&driver.connected)this.onError(failure);}
            }
          })();
          if(pty.resize)await pty.resize(this.term.rows,this.term.cols);
          if(this.session===s){this.term.options.disableStdin=false;this.onState('Android shell');this.focus();}
        }catch(error){if(generation===this.generation){this.close();this.onState('终端连接失败，可重开会话');throw error;}else if(pty)await Promise.resolve(pty.kill()).catch(()=>{});}
        finally{if(generation===this.generation){this.starting=false;this.onState();}}
      })();
      return this.opening;
    }
    send(data){
      const s=this.session;if(!s||s.closed)return;
      const bytes=typeof data==='string'?new TextEncoder().encode(data):data;
      s.queue=s.queue.then(()=>{if(this.session===s&&!s.closed)return s.writer.write(bytes);}).catch(error=>this.fail(s,error));
    }
    interrupt(){this.send('\x03');this.focus();}
    fail(s,error){if(this.session!==s||s.closed)return;this.close();this.onState('终端连接已关闭，可重开会话');this.onError(error);}
    close(){
      this.generation++;clearTimeout(this.resizeTimer);this.starting=false;this.driver=null;
      const s=this.session;this.session=null;if(s){s.closed=true;Promise.resolve(s.pty.kill()).catch(()=>{});}
      if(this.term)this.term.options.disableStdin=true;this.onState('终端未连接');
    }
    dispose(){this.close();this.observer?.disconnect();this.term?.dispose();}
  }
  return {Controller};
})();

export {GJTerminal};

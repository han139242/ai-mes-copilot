import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import * as vue from 'vue';

const script=readFileSync(new URL('../src/components/auth/FaceCaptureDialog.vue',import.meta.url),'utf8')
  .match(/<script setup>([\s\S]*?)<\/script>/)[1].replace(/^import[^\n]*\n/gm,'');
function harness({ready=[],delayCamera=false,delayProbe=false,rateLimited=false,probeLatency=0}={}) {
  const props=vue.reactive({modelValue:true,purpose:'login',username:'synthetic-user',password:''});
  const state={now:10000,token:'owner',calls:[],events:[],timeouts:new Map(),intervals:new Map(),listeners:new Map(),
    sequence:0,stopped:0,ready:[...ready],image:0,cameraRequested:false,delays:[]};
  const stream={getTracks:()=>[{stop:()=>state.stopped++}]};
  const doc={hidden:false,addEventListener:(k,fn)=>state.listeners.set(k,fn),removeEventListener:k=>state.listeners.delete(k),
    createElement:()=>({getContext:()=>({drawImage(){}}),toDataURL:()=> 'synthetic-frame-'+(++state.image)})};
  const request=async(action,payload)=>{
    state.calls.push({action,payload});
    if(action==='identify-challenge')return {data:{challengeId:'a'.repeat(32),sequence:['front','right','left'],expiresIn:45}};
    if(action==='identify-frame') {
      state.now+=probeLatency;
      if(rateLimited)throw {response:{status:429}};
      if(delayProbe)return new Promise(resolve=>{state.resolveProbe=resolve});
      return {data:{ready:state.ready.length?state.ready.shift():true,message:'synthetic-pose-result'}};
    }
    if(action==='identify-login')return {token:'synthetic-jwt'};
    throw Error('Unexpected action '+action);
  };
  const scope=vue.effectScope();
  const c=scope.run(()=>new Function('vue','injectedProps','state','doc','injectedStream','request','delayCamera',`
    const {computed,nextTick,onBeforeUnmount:unused,ref,watch}=vue;
    const onBeforeUnmount=fn=>state.cleanup=fn;
    const defineProps=()=>injectedProps,defineEmits=()=> (...args)=>state.events.push(args);
    const getToken=()=>state.token,faceAction=request;
    const window={isSecureContext:true},document=doc,Date={now:()=>state.now};
    const navigator={mediaDevices:{getUserMedia:()=>{state.cameraRequested=true;
      return delayCamera?new Promise(resolve=>state.resolveCamera=resolve):Promise.resolve(injectedStream)}}};
    const setTimeout=(fn,delay)=>{state.delays.push(delay);const id=++state.sequence;state.timeouts.set(id,fn);return id};
    const clearTimeout=id=>state.timeouts.delete(id);
    const setInterval=fn=>{const id=++state.sequence;state.intervals.set(id,fn);return id};
    const clearInterval=id=>state.intervals.delete(id);
    ${script}
    return {start,close,stage,frames,stable,video,pauseWhenHidden,consent};
  `)(vue,props,state,doc,stream,request,delayCamera));
  c.video.value={videoWidth:960,videoHeight:720,play:async()=>{},srcObject:null};
  return {c,props,state,stream,doc,dispose(){state.cleanup();scope.stop()},async tick(){
    state.now+=800;
    const [id,fn]=state.timeouts.entries().next().value || [];
    assert.ok(fn,'capture loop should have exactly one scheduled probe');
    state.timeouts.delete(id); return fn();
  }};
}
test('automatic guidance collects stable poses in server order and submits once without clicks',async()=>{
  const h=harness();try {
    await h.c.start();
    for(let i=0;i<6;i++)await h.tick();
    const probes=h.state.calls.filter(r=>r.action==='identify-frame');
    assert.deepEqual(probes.map(r=>r.payload.step),[0,0,1,1,2,2]);
    assert.ok(probes.slice(2).every(r=>r.payload.referenceFrame===probes[1].payload.frame));
    assert.ok(h.state.calls.every(r=>!('username' in r.payload)),'account discovery never sends the typed account');
    const submits=h.state.calls.filter(r=>r.action==='identify-login');
    assert.equal(submits.length,1);assert.equal(submits[0].payload.frames.length,3);
    assert.equal(new Set(submits[0].payload.frames).size,3);
    assert.equal(h.state.events.filter(e=>e[0]==='verified').length,1);
    assert.equal(h.state.timeouts.size,0);assert.equal(h.state.intervals.size,0);assert.equal(h.state.stopped,1);
  } finally {h.dispose()}
});

test('probe cadence includes processing time instead of adding another full wait',async()=>{
  const h=harness({probeLatency:500});try{
    await h.c.start();assert.equal(h.state.delays[0],200);
    await h.tick();assert.equal(h.state.delays[1],300);
    assert.equal(h.c.frames.value.length,0,'one frame is never enough to advance');
  }finally{h.dispose()}
});
test('a failed pose resets stability and cannot advance on isolated ready frames',async()=>{
  const h=harness({ready:[true,false,true,true]});try{
    await h.c.start();await h.tick();await h.tick();await h.tick();
    assert.equal(h.c.frames.value.length,0);
    await h.tick();assert.equal(h.c.frames.value.length,1);
    assert.equal(h.state.calls.filter(r=>r.action==='identify-login').length,0);
  }finally{h.dispose()}
});
test('closing while a probe is in flight discards its response and stops the camera',async()=>{
  const h=harness({delayProbe:true});try{
    await h.c.start();const pending=h.tick();h.c.close();
    h.state.resolveProbe({data:{ready:true,message:'late response'}});await pending;
    assert.equal(h.c.frames.value.length,0);assert.equal(h.state.timeouts.size,0);assert.equal(h.state.stopped,1);
    assert.equal(h.state.calls.filter(r=>r.action==='identify-login').length,0);
  }finally{h.dispose()}
});
test('late camera permission cannot restart a closed capture session',async()=>{
  const h=harness({delayCamera:true});try{
    const pending=h.c.start();for(let i=0;i<5;i++)await Promise.resolve();
    assert.ok(h.state.cameraRequested);h.c.close();h.state.resolveCamera(h.stream);await pending;
    assert.equal(h.c.stage.value,'ready');assert.equal(h.state.stopped,1);assert.equal(h.state.timeouts.size,0);
  }finally{h.dispose()}
});
test('hidden, expired and replaced-owner sessions stop before sending more frames',async()=>{
  for(const condition of ['hidden','expired','owner']){
    const h=harness();try{
      await h.c.start();
      if(condition==='hidden')h.doc.hidden=true;
      if(condition==='expired')h.state.now+=45001;
      if(condition==='owner')h.state.token='another-owner';
      await h.tick();
      assert.equal(h.c.stage.value,'expired');assert.equal(h.state.stopped,1);
      assert.equal(h.state.calls.filter(r=>r.action==='identify-frame'||r.action==='identify-login').length,0);
    }finally{h.dispose()}
  }
});
test('shared-service rate limits back off instead of aborting after three responses',async()=>{
  const h=harness({rateLimited:true});try {
    await h.c.start();for(let i=0;i<5;i++)await h.tick();
    assert.equal(h.c.stage.value,'capture');assert.equal(h.c.frames.value.length,0);
    assert.equal(h.state.timeouts.size,1);assert.equal(h.state.stopped,0);
  }finally{h.dispose()}
});

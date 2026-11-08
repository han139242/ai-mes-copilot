import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {knowledgePreferencesSnapshot} from '../src/utils/private-knowledge.js';

const source=readFileSync(new URL('../src/pages/ai/private-knowledge.vue',import.meta.url),'utf8');
const functions=source.slice(source.indexOf('async function savePreferences()'),source.indexOf('async function loadGraph('));

test('successful save becomes the revert snapshot even when the following refresh fails',async()=>{
  const old={preferences:{role:'通用岗位',level:'入门',length:'简洁',product:''},model_consent:false,graph_enabled:false};
  const service={value:old}, prefs={...knowledgePreferencesSnapshot(old),modelConsent:true};
  const prefsDirty={value:true},prefsBusy={value:false},serviceError={value:''},actionError={value:''};
  let posts=0;
  const api=async()=>{posts++;return {preferences:{...old.preferences,product:'L100'},modelConsent:true,graphEnabled:false}};
  const refresh=async()=>{serviceError.value='synthetic refresh unavailable'};
  const f=new Function('prefs','service','prefsDirty','prefsBusy','serviceError','actionError','privateAction','refresh','knowledgePreferencesSnapshot',`
    const preview=false;let syncPrefs=false;const ElMessage={success(){}};
    ${functions}
    return {savePreferences,resetPreferences};
  `)(prefs,service,prefsDirty,prefsBusy,serviceError,actionError,api,refresh,knowledgePreferencesSnapshot);
  await f.savePreferences();
  assert.equal(posts,1);assert.equal(prefsBusy.value,false);assert.equal(prefsDirty.value,false);
  prefs.product='unsaved-draft';prefsDirty.value=true;
  f.resetPreferences();
  assert.equal(prefs.product,'L100');assert.equal(prefs.modelConsent,true);assert.equal(prefsDirty.value,false);
  await f.savePreferences();assert.equal(posts,1,'unchanged/offline save cannot post again');
});

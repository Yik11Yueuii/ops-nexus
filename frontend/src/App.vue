<script setup lang="ts">
import {ref,onMounted} from 'vue';
import KnowledgeCenter from './KnowledgeCenter.vue';
import AssistantView from './AssistantView.vue';
import DiagnosisView from './DiagnosisView.vue';
import VersionCompareView from './VersionCompareView.vue';
import KnowledgeGapView from './KnowledgeGapView.vue';
import DashboardView from './DashboardView.vue';
import BusinessQueryView from './BusinessQueryView.vue';
import AnalyticsView from './AnalyticsView.vue';
const view=ref('dashboard');
type User={displayName:string;role:string;username:string};
const user=ref<User|null>(null),username=ref(''),password=ref(''),error=ref(''),busy=ref(false),status=ref<Record<string,unknown>|null>(null),observability=ref<Record<string,number>|null>(null);
let token=sessionStorage.getItem('ops-token')||'';
async function api(path:string, options:RequestInit={}) {
 const res=await fetch('/api'+path,{...options,headers:{'Content-Type':'application/json',...(token?{Authorization:'Bearer '+token}:{}),...options.headers}});
 const body=await res.json(); if(!res.ok)throw new Error(body.message||'请求失败'); return body.data;
}
async function load(){user.value=await api('/auth/me'); if(user.value?.role==='ADMIN'){status.value=await api('/admin/status');observability.value=await api('/admin/observability/summary');}}
async function login(){busy.value=true;error.value='';try{const data=await api('/auth/login',{method:'POST',body:JSON.stringify({username:username.value,password:password.value})});token=data.token;sessionStorage.setItem('ops-token',token);password.value='';await load();}catch(e){error.value=e instanceof Error?e.message:'无法连接服务';}finally{busy.value=false;}}
function logout(){token='';sessionStorage.removeItem('ops-token');user.value=null;status.value=null;observability.value=null;}
onMounted(async()=>{if(token)try{await load();}catch{logout();}});
</script>
<template>
 <main v-if="!user" class="login">
  <section class="intro"><span class="eyebrow">星云科技 · 技术知识空间</span><h1>让知识成为<br>每一次判断的依据。</h1><p>OpsNexus 企业知识运营助手</p><small>可追溯问答 / 故障辅助诊断 / 知识运营</small></section>
  <form @submit.prevent="login"><div class="brand">◈ OpsNexus</div><h2>登录工作空间</h2><p class="muted">使用企业演示账号继续</p><label>用户名<input v-model="username" required maxlength="50" autocomplete="username"></label><label>密码<input v-model="password" required type="password" maxlength="100" autocomplete="current-password"></label><p v-if="error" role="alert" class="error">{{error}}</p><button :disabled="busy">{{busy?'正在登录…':'登录'}}</button><small class="muted">本地开发版 · 星云科技为虚构演示企业</small></form>
 </main>
 <div v-else class="workspace">
  <aside><div class="brand">◈ OpsNexus</div><p>星云科技 / 技术知识空间</p><button class="nav-item" :class="{active:view==='dashboard'}" @click="view='dashboard'">⌂　工作台</button><button class="nav-item" :class="{active:view==='assistant'}" @click="view='assistant'">✦　知识助手</button><button class="nav-item" :class="{active:view==='business'}" @click="view='business'">⌘　业务查询</button><button class="nav-item" :class="{active:view==='diagnosis'}" @click="view='diagnosis'">⌁　故障诊断</button><button class="nav-item" :class="{active:view==='knowledge'}" @click="view='knowledge'">▤　知识文档</button><button v-if="user.role==='ADMIN'" class="nav-item" :class="{active:view==='compare'}" @click="view='compare'">⇄　版本对比</button><button v-if="user.role==='ADMIN'" class="nav-item" :class="{active:view==='gaps'}" @click="view='gaps'">⌁　缺口雷达</button><button v-if="user.role==='ADMIN'" class="nav-item" :class="{active:view==='analytics'}" @click="view='analytics'">⌗　数据分析</button><button v-if="user.role==='ADMIN'" class="nav-item" :class="{active:view==='status'}" @click="view='status'">◉　系统状态</button><small>知识沉淀，从每一份资料开始。</small><button class="secondary" @click="logout">退出登录</button></aside>
  <section class="content"><header><span>工作空间 / {{view==='dashboard'?'工作台':view==='knowledge'?'知识文档':view==='assistant'?'知识助手':view==='business'?'业务查询':view==='diagnosis'?'故障诊断':view==='compare'?'版本对比':view==='gaps'?'缺口雷达':view==='analytics'?'数据分析':'系统状态'}}</span><span>{{user.displayName}} · {{user.role==='ADMIN'?'管理员':'普通用户'}}</span></header>
   <DashboardView v-if="view==='dashboard'" :admin="user.role==='ADMIN'" @navigate="view=$event" />
   <AssistantView v-else-if="view==='assistant'" />
   <BusinessQueryView v-else-if="view==='business'" />
   <DiagnosisView v-else-if="view==='diagnosis'" />
   <VersionCompareView v-else-if="view==='compare'" />
   <KnowledgeGapView v-else-if="view==='gaps'" />
   <AnalyticsView v-else-if="view==='analytics'" />
   <KnowledgeCenter v-else-if="view==='knowledge'" :admin="user.role==='ADMIN'" />
   <div v-else><h1>系统状态</h1><div class="cards"><article v-if="status"><h3>依赖连接</h3><p>数据库：{{status.database}}</p><p>Redis：{{status.redis}}</p><p>AI 限流后端：{{status.aiLimiter}}</p><small>模型密钥：{{status.modelConfigured?'已配置':'未配置'}}（不代表调用已验证）</small></article><article v-if="observability"><h3>运行概览</h3><p>AI 调用：{{observability.aiCalls}}</p><p>RAG 检索：{{observability.ragRequests}}</p><p>工具 / SQL：{{observability.toolCalls}} / {{observability.sqlQueries}}</p><p>语义对比 / 入库：{{observability.semanticComparisons}} / {{observability.ingestionEvents}}</p></article></div></div>
  </section>
 </div>
</template>
